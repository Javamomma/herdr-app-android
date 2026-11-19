package herdr.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.assertIs

class ReadingTest {
    private fun part(last: Int, lines: Int, text: String = "x\n".repeat(last), first: Int = 1) = FilePart(text, first, last, lines)
    private fun doc(last: Int, lines: Int, text: String = "x\n".repeat(last)) = ReadDoc.of(part(last, lines, text))

    // ---- the core's answer ---------------------------------------------------

    @Test fun readsTheCoresAnswerAndFillsMissingFieldsFromFirst() {
        val j = json.parseToJsonElement("""{"text":"a\nb\n","first":1,"last":2,"lines":9}""")
        assertEquals(FilePart("a\nb\n", 1, 2, 9), FilePart.of(j))
        val bare = FilePart.of(json.parseToJsonElement("""{"text":"a\nb\n"}"""))
        assertEquals(1, bare.first); assertEquals(2, bare.last); assertEquals(2, bare.lines)
        val from5 = FilePart.of(json.parseToJsonElement("""{"text":"a\nb","first":5}"""))
        assertEquals(6, from5.last, "last follows first, not 1")
        assertEquals(5, FilePart.of(json.parseToJsonElement("""{"text":"héé"}""")).bytes, "bytes counted once, in UTF-8")
        assertEquals(4, Reading.utf8Size("😀"))
    }

    // ---- cut notice, load more -------------------------------------------------

    @Test fun cutAtTheCapSaysSoAndNamesTheNumbers() {
        val d = doc(20_000, 55_000)
        assertEquals("Showing the first 20,000 of 55,000 lines (39 KB). The rest is not loaded.", Reading.notice(d))
        assertEquals(20_001, Reading.nextOffset(d))
    }

    @Test fun aWholeFileHasNoNoticeAndNoMore() {
        val d = doc(3, 3)
        assertNull(Reading.notice(d)); assertNull(Reading.nextOffset(d))
        assertNull(Reading.notice(ReadDoc.of(FilePart("", 1, 0, 0))), "an empty file")
    }

    @Test fun nothingShownOfAFileWithLinesIsNotCalledEmpty() {
        val d = ReadDoc.of(FilePart("", 1, 0, 1))
        assertEquals("Nothing could be shown: line 1 is too long to load.", Reading.notice(d))
        assertNull(Reading.nextOffset(d), "no point asking again")
    }

    @Test fun loadMoreJoinsOnlyTheNewPartAndKeepsTheBreak() {
        val a = ReadDoc.of(part(2, 5, "a\nb\n"))
        val j = assertIs<ReadDoc.More.Joined>(a.more(FilePart("c\nd\n", 3, 4, 5)))
        assertEquals("a\nb\nc\nd", Chunks.join(j.doc.chunks), "the old last block gains its line break")
        assertEquals(4, j.doc.last); assertEquals(a.bytes + 4, j.doc.bytes)
        assertEquals(Reading.display("a\nb\nc\nd").text, Chunks.join(Chunks.split("a\nb\nc\nd\n")))
    }

    @Test fun loadMoreWithoutProgressOrOutOfOrderIsSaid() {
        val a = ReadDoc.of(part(2, 5, "a\nb\n"))
        assertIs<ReadDoc.More.NoProgress>(a.more(FilePart("", 3, 2, 5)), "zero lines")
        assertIs<ReadDoc.More.NoProgress>(a.more(FilePart("", 3, 3, 5)))
        assertIs<ReadDoc.More.Changed>(a.more(FilePart("x\n", 4, 4, 5)), "a gap means the file changed")
        assertIs<ReadDoc.More.Changed>(a.more(FilePart("x\n", 2, 2, 5)))
    }

    @Test fun loadMoreStopsAtTheCeiling() {
        val big = ReadDoc(listOf(Chunk("x", 1)), 1, Reading.CEILING_BYTES, 100, 500, false)
        assertNull(Reading.nextOffset(big))
        assertTrue(Reading.notice(big)!!.endsWith("Loading stops at 4.0 MB."), Reading.notice(big))
    }

    // ---- binary and hidden characters -------------------------------------------

    @Test fun binaryContentIsRecognisedButALogWithOneNulIsNot() {
        assertTrue(Reading.looksBinary("PK\u0003\u0004\u0000\u0000data"))
        assertTrue(Reading.looksBinary("�".repeat(50) + "abc"))
        assertTrue(Reading.looksBinary("\u0000".repeat(500) + "text".repeat(100)))
        assertFalse(Reading.looksBinary("line\n".repeat(1000) + "\u0000" + "more\n".repeat(1000)), "one NUL in a log")
        assertFalse(Reading.looksBinary("﻿fun main() {\n\tprintln(\"héllo ✓ 😀\")\r\n}\n"), "BOM, emoji, CRLF")
        assertFalse(Reading.looksBinary("\u001B[31mred\u001B[0m log"))
        assertFalse(Reading.looksBinary(""))
        assertEquals(Reading.BINARY_NOTICE, Reading.errorText("not_text", "x is not UTF-8 text"))
        assertEquals("no file x", Reading.errorText("not_found", "no file x"))
    }

    @Test fun displayedTextHasNoHiddenCharactersAndSaysWhenItReplacedAny() {
        val d = Reading.display("a\u001B[31mb\u0007‮c⁦d\u0085e f\tg\nh")
        assertEquals("a�[31mb��c�d�e�f\tg\nh", d.text)
        assertTrue(d.replaced)
        val crlf = Reading.display("a\r\nb\r\n")
        assertEquals("a\nb\n", crlf.text); assertFalse(crlf.replaced, "CRLF is not hidden content")
        assertEquals("a�b", Reading.display("a\rb").text)
        assertFalse(Reading.display("plain\ttext\nüñí ✓").replaced)
        assertTrue(ReadDoc.of(part(1, 1, "a‮b\n")).replaced)
        assertEquals("Hidden control characters were replaced.", Reading.REPLACED_NOTICE)
    }

    // ---- blocks --------------------------------------------------------------------

    @Test fun displayedBlocksPutEndToEndAreTheTextSoACopyAcrossABorderKeepsItsLines() {
        val text = (1..1000).joinToString("\n") { "line $it" }
        val chunks = Chunks.split(text + "\n", maxLines = 200)
        assertEquals(5, chunks.size)
        assertEquals(listOf(1, 201, 401, 601, 801), chunks.map { it.firstLine })
        assertEquals(text, chunks.joinToString("") { it.text }, "plain concatenation of what is drawn")
        assertTrue(chunks[0].text.endsWith("line 200\n") && chunks[1].text.startsWith("line 201"))
        assertFalse(chunks.last().text.endsWith("\n"))
    }

    @Test fun blankLinesAreRealAndNeverAPlaceholder() {
        val text = "a\n\n\nb"
        assertEquals(text, Chunks.join(Chunks.split(text, maxLines = 1)), "one line per block, blank ones included")
        val c = Chunks.split(text, maxLines = 1)
        assertEquals(listOf("a\n", "\n", "\n", "b"), c.map { it.text })
        assertEquals(emptyList(), Chunks.split(""))
        assertEquals("", Chunks.join(Chunks.split("\n")), "one empty line")
        assertTrue(Chunks.split("a\n\n").let { it.size == 1 && it[0].text == "a\n" })
    }

    @Test fun characterBudgetStartsANewBlockAtALineBreak() {
        val text = listOf("a".repeat(50), "b".repeat(50), "c".repeat(50)).joinToString("\n")
        val chunks = Chunks.split(text, maxLines = 100, maxChars = 110)
        assertEquals(listOf(1, 3), chunks.map { it.firstLine })
        assertEquals(text, Chunks.join(chunks))
        assertTrue(chunks.none { it.continues })
    }

    @Test fun smallTextIsOneBlockBigTextIsMany() {
        assertEquals(1, Chunks.forText("x\n".repeat(29_000)).size)
        assertTrue(Chunks.forText("x\n".repeat(31_000)).size > 1)
        assertFalse(Reading.lazy(60_000)); assertTrue(Reading.lazy(60_001))
    }

    @Test fun appendingSplitsOnlyTheNewTextAndNumbersItOn() {
        val a = ReadDoc.of(part(300, 900, (1..300).joinToString("") { "l$it\n" }))
        val more = FilePart((301..900).joinToString("") { "l$it\n" }, 301, 900, 900)
        val j = (a.more(more) as ReadDoc.More.Joined).doc
        assertEquals((1..900).joinToString("\n") { "l$it" }, Chunks.join(j.chunks))
        assertEquals(301, j.chunks.first { it.firstLine >= 301 }.firstLine)
    }

    @Test fun aSingleHugeLineIsCutWithoutSplittingAPairAndPiecesKeepTheLinesColour() {
        val line = "+ab" + "😀".repeat(10) + "cd"
        val chunks = Chunks.split("x\n$line\ny", maxLines = 100, maxChars = 5)
        for (c in chunks) {
            assertFalse(c.text.trimEnd('\n').let { it.isNotEmpty() && Character.isHighSurrogate(it.last()) }, "ends inside a pair: ${c.text}")
            assertFalse(c.text.isNotEmpty() && Character.isLowSurrogate(c.text.first()), "starts inside a pair")
        }
        assertEquals("x\n$line\ny", Chunks.join(chunks))
        val pieces = chunks.filter { it.continues }
        assertTrue(pieces.isNotEmpty())
        assertTrue(pieces.all { Chunks.segments(it).first().lead == "+ab😀".take(4) }, "every piece leads with the line's own start")
    }

    @Test fun combiningMarksAndJoinersStayWithTheirBase() {
        val line = "aaaa" + "é" + "bb"
        val c = Chunks.split(line, maxLines = 10, maxChars = 5)
        assertEquals(line, Chunks.join(c))
        assertFalse(c.any { it.text.startsWith("́") }, "mark alone at a piece start: ${c.map { it.text }}")
        val fam = "abc" + "👨‍👩‍👧" + "cd"
        val f = Chunks.split(fam, maxLines = 10, maxChars = 10)
        assertEquals(fam, Chunks.join(f))
        assertTrue(f.none { it.text.startsWith("‍") || it.text.startsWith("👩") || it.text.startsWith("👧") }, "${f.map { it.text }}")
    }

    @Test fun segmentsAreTheLinesOfABlock() {
        val c = Chunks.split("+a\n-b\n c\n", maxLines = 100)[0]
        assertEquals(listOf("+a\n", "-b\n", " c"), Chunks.segments(c).map { it.text })
        assertEquals("+a\n-b\n c", Chunks.segments(c).joinToString("") { it.text })
        assertEquals(listOf("+a", "-b", " c"), Chunks.segments(c).map { it.lead })
    }

    // ---- copy all -------------------------------------------------------------------

    @Test fun copyAllKeepsTheStartAndSaysHowMuch() {
        val d = ReadDoc.of(part(3, 3, "abcdef\nghij\nk\n"))
        val whole = Reading.copyAll(d, 1000)
        assertEquals("abcdef\nghij\nk", whole.text); assertFalse(whole.cut)
        assertEquals("Copied.", Reading.copyNotice(whole))
        val cut = Reading.copyAll(d, 8)
        assertEquals("abcdef\ng", cut.text); assertTrue(cut.cut)
        assertEquals("Copied the first 8 of 13 characters: the rest was too much for the clipboard.", Reading.copyNotice(cut))
        assertEquals("a", Reading.copyAll(ReadDoc.of(part(1, 1, "a😀\n")), 2).text, "no half pair")
    }

    @Test fun diffOfAnUntrackedFileShowsAsAddedLines() {
        assertEquals("+a\n+b", Reading.addedDiff(part(2, 2, "a\nb\n")))
        assertEquals("", Reading.addedDiff(FilePart("", 1, 0, 0)))
        assertEquals("Binary file, not shown.", Reading.addedDiff(part(1, 1, "\u0000\u0000b\n")))
        assertTrue(Reading.addedDiff(part(1, 5, "a\n")).startsWith("+a\nShowing the first 1 of 5 lines"))
        assertEquals(2, ReadDoc.whole("+a\n+b").lines)
    }

    // ---- last reply ----------------------------------------------------------------

    @Test fun replyIsPlainText() {
        assertEquals("a\nb", Reply.clean("a\r\nb\r"))
        assertEquals("hi there", Reply.clean("\u001B[31;1mhi\u001B[0m\u0007 there"), "a real colour sequence goes away whole")
        assertEquals("title", Reply.clean("\u001B]0;window\u0007title"), "an OSC sequence too")
        assertEquals("abc", Reply.clean("a‮b⁦c"), "direction overrides removed")
        assertEquals("", Reply.clean(null))
        assertEquals("[click](http://x)", Reply.clean("[click](http://x)"), "links stay text")
    }

    @Test fun excerptKeepsTheFirstLinesAndShowsAnEllipsis() {
        val e = Reply.excerpt("one\n\ntwo\nthree\nfour\nfive", 4)
        assertEquals("one\ntwo\nthree\nfour…", e.text); assertTrue(e.more)
    }

    @Test fun shortReplyNeedsNoShowAll() {
        val e = Reply.excerpt("Done. Tests pass.", 4)
        assertEquals(Reply.Excerpt("Done. Tests pass.", false), e)
        assertEquals(Reply.Excerpt("", false), Reply.excerpt("  \n \n", 3))
    }

    @Test fun longLineIsCutAtASpaceWithinTheCharacterBudget() {
        val e = Reply.excerpt("word ".repeat(100), 3, maxChars = 50)
        assertEquals("word word word word word word word word word word…", e.text); assertTrue(e.more)
        val emoji = Reply.excerpt("😀".repeat(40), 3, maxChars = 11)
        assertTrue(emoji.text.removeSuffix("…").codePoints().allMatch { it == 0x1F600 }, "no half emoji: ${emoji.text}")
    }

    @Test fun onlyExactlyTheHelpersLimitIsTakenAsCutAndItIsDecidedOnTheRawText() {
        val exact = "x".repeat(599) + "x"
        assertTrue(Reply.maybeCut(exact))
        val endsInSpace = "x".repeat(590) + " ".repeat(10)
        assertTrue(Reply.maybeCut(endsInSpace), "the 600th character is white space: clean() would have trimmed it")
        assertTrue(Reply.clean(endsInSpace).length < 600)
        assertFalse(Reply.maybeCut("x".repeat(599)))
        assertFalse(Reply.maybeCut("x".repeat(2000)), "a long reply is complete, not cut")
        assertFalse(Reply.maybeCut("x".repeat(601)))
        assertTrue(Reply.maybeCut("😀".repeat(600)), "counted in characters, not UTF-16 units")
        assertFalse(Reply.maybeCut("😀".repeat(400)))
        assertFalse(Reply.maybeCut(null))
        assertEquals("This may be cut short by the Mac at 600 characters. The rest is not available here.", Reply.cutNotice(true))
        assertNull(Reply.cutNotice(false))
        val e = Reply.excerpt("a".repeat(30) + " " + "b".repeat(100), 4, maxChars = 1000, maybeCut = true)
        assertTrue(e.more, "Show all appears even when it all fits the card")
        assertFalse(Reply.excerpt("short", 4, maybeCut = false).more)
    }

    @Test fun theCardShowsTheReplyOnlyOnceTheAgentHasStopped() {
        assertTrue(Reply.showsOnCard("done")); assertTrue(Reply.showsOnCard("idle"))
        assertFalse(Reply.showsOnCard("blocked")); assertFalse(Reply.showsOnCard("working")); assertFalse(Reply.showsOnCard("error"))
    }
}
