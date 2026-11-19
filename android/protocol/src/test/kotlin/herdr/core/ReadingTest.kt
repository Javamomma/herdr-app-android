package herdr.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ReadingTest {
    private fun part(last: Int, lines: Int, text: String = "x\n".repeat(last), first: Int = 1) = FilePart(text, first, last, lines)

    @Test fun cutAtTheCapSaysSoAndNamesTheNumbers() {
        val p = part(20_000, 55_000)
        assertTrue(Reading.isCut(p))
        assertEquals("Showing the first 20,000 of 55,000 lines (39 KB). The rest is not loaded.", Reading.cutNotice(p))
        assertEquals(20_001, Reading.nextOffset(p))
    }

    @Test fun aWholeFileHasNoNoticeAndNoMore() {
        val p = part(3, 3)
        assertFalse(Reading.isCut(p))
        assertNull(Reading.cutNotice(p))
        assertNull(Reading.nextOffset(p))
        assertNull(Reading.cutNotice(FilePart("", 1, 0, 0)), "an empty file")
    }

    @Test fun loadMoreJoinsOnlyWhenItFollowsOn() {
        val a = part(2, 5, "a\nb\n")
        val b = FilePart("c\nd\n", 3, 4, 5)
        assertEquals(FilePart("a\nb\nc\nd\n", 1, 4, 5), Reading.append(a, b))
        assertNull(Reading.append(a, FilePart("x\n", 4, 4, 5)), "a gap or an overlap means the file changed")
    }

    @Test fun readsTheCoresAnswer() {
        val j = json.parseToJsonElement("""{"text":"a\nb\n","first":1,"last":2,"lines":9}""")
        assertEquals(FilePart("a\nb\n", 1, 2, 9), FilePart.of(j))
    }

    @Test fun binaryContentIsRecognised() {
        assertTrue(Reading.looksBinary("PK\u0003\u0004\u0000\u0000data"))
        assertTrue(Reading.looksBinary("�".repeat(50) + "abc"))
        assertTrue(Reading.looksBinary("\u0001\u0002\u0003\u0004abcd"))
        assertFalse(Reading.looksBinary("fun main() {\n\tprintln(\"héllo ✓\")\r\n}\n"))
        assertFalse(Reading.looksBinary("\u001B[31mred\u001B[0m log"), "colour codes in a log are text")
        assertFalse(Reading.looksBinary(""))
        assertEquals(Reading.BINARY_NOTICE, Reading.errorText("not_text", "x is not UTF-8 text"))
        assertEquals("no file x", Reading.errorText("not_found", "no file x"))
    }

    @Test fun largeTextIsLaidOutLazily() {
        assertFalse(Reading.lazy("a".repeat(60_000)))
        assertTrue(Reading.lazy("a".repeat(60_001)))
    }

    @Test fun chunksNeverSplitALineAndJoinBackExactly() {
        val text = (1..1000).joinToString("\n") { "line $it" }
        val chunks = Chunks.split(text, maxLines = 200)
        assertEquals(5, chunks.size)
        assertEquals(listOf(1, 201, 401, 601, 801), chunks.map { it.firstLine })
        assertEquals(text, Chunks.join(chunks))
        assertEquals("line 1", chunks[0].text.lines().first())
        assertEquals("line 1000", chunks.last().text.lines().last())
    }

    @Test fun chunksKeepBlankLinesAndDropOnlyTheFinalBreak() {
        val text = "a\n\n\nb\n"
        assertEquals("a\n\n\nb", Chunks.join(Chunks.split(text)))
        assertEquals(emptyList(), Chunks.split(""))
        assertEquals("", Chunks.join(Chunks.split("\n")), "one empty line")
    }

    @Test fun characterBudgetStartsANewBlockAtALineBreak() {
        val text = listOf("a".repeat(50), "b".repeat(50), "c".repeat(50)).joinToString("\n")
        val chunks = Chunks.split(text, maxLines = 100, maxChars = 110)
        assertEquals(listOf(1, 3), chunks.map { it.firstLine })
        assertEquals(text, Chunks.join(chunks))
        assertTrue(chunks.none { it.continues })
    }

    @Test fun aSingleHugeLineIsCutWithoutSplittingASurrogatePair() {
        val pair = "😀"
        val line = "ab" + pair.repeat(10) + "cd"
        val chunks = Chunks.split("x\n$line\ny", maxLines = 100, maxChars = 5)
        for (c in chunks) {
            assertFalse(c.text.isNotEmpty() && Character.isHighSurrogate(c.text.last()), "ends inside a pair: ${c.text}")
            assertFalse(c.text.isNotEmpty() && Character.isLowSurrogate(c.text.first()), "starts inside a pair: ${c.text}")
            assertTrue(c.text.length <= 5)
        }
        assertEquals("x\n$line\ny", Chunks.join(chunks))
        assertTrue(chunks.any { it.continues })
    }

    @Test fun crlfIsKeptAsIs() {
        val text = "a\r\nb\r\n"
        assertEquals("a\r\nb\r", Chunks.join(Chunks.split(text)))
    }

    // ---- last reply --------------------------------------------------------

    @Test fun replyIsPlainText() {
        assertEquals("a\nb", Reply.clean("a\r\nb\r"))
        assertEquals("hi", Reply.clean("\u001B[31mhi\u0007".replace("[31m", "")))
        assertEquals("abc", Reply.clean("a‮b⁦c"), "direction overrides removed")
        assertEquals("", Reply.clean(null))
        assertEquals("[click](http://x)", Reply.clean("[click](http://x)"), "links stay text")
    }

    @Test fun excerptKeepsTheFirstLinesAndShowsAnEllipsis() {
        val e = Reply.excerpt("one\n\ntwo\nthree\nfour\nfive", 4)
        assertEquals("one\ntwo\nthree\nfour…", e.text)
        assertTrue(e.more)
    }

    @Test fun shortReplyNeedsNoShowAll() {
        val e = Reply.excerpt("Done. Tests pass.", 4)
        assertEquals("Done. Tests pass.", e.text)
        assertFalse(e.more)
        assertEquals(Reply.Excerpt("", false), Reply.excerpt("  \n \n", 3))
    }

    @Test fun longLineIsCutAtASpaceWithinTheCharacterBudget() {
        val e = Reply.excerpt("word ".repeat(100), 3, maxChars = 50)
        assertEquals("word word word word word word word word word word…", e.text)
        assertTrue(e.more)
        val emoji = Reply.excerpt("😀".repeat(40), 3, maxChars = 11)
        assertTrue(emoji.text.removeSuffix("…").codePoints().allMatch { it == 0x1F600 }, "no half emoji: ${emoji.text}")
    }

    @Test fun aReplyAtTheHelpersLimitOffersShowAllAndSaysItWasCut() {
        val full = "x".repeat(600)
        assertTrue(Reply.cutByHelper(full))
        assertFalse(Reply.cutByHelper("x".repeat(599)))
        assertFalse(Reply.excerpt("short but", 4).more)
        val e = Reply.excerpt("a".repeat(30) + " " + "b".repeat(569), 4, maxChars = 1000)
        assertTrue(e.more, "even when it all fits the card")
        assertEquals("Cut at 600 characters by the Mac. The rest of this reply is not available here.", Reply.cutNotice(full))
        assertNull(Reply.cutNotice("x".repeat(599)))
        assertNotNull(Reply.cutNotice("😀".repeat(600)), "counted in characters, not UTF-16 units")
        assertNull(Reply.cutNotice("😀".repeat(400)))
    }
}
