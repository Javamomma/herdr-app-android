package herdr.core

import kotlinx.serialization.json.JsonElement
import java.util.Locale

/** One answer of the core's `file.read`: whole lines `first..last` of a file with `lines` lines, `bytes` of UTF-8 text. */
data class FilePart(val text: String, val first: Int, val last: Int, val lines: Int, val bytes: Int = Reading.utf8Size(text)) {
    companion object {
        /** Missing fields follow from the others: `first` is 1 (the core counts from 1), `last` from the lines in the text. */
        fun of(r: JsonElement?): FilePart {
            val text = r["text"].str ?: ""
            val first = (r["first"].long ?: 1).toInt()
            val last = (r["last"].long ?: (first - 1 + Reading.lineCount(text)).toLong()).toInt()
            return FilePart(text, first, last, (r["lines"].long ?: last.toLong()).toInt())
        }
    }
}

/** What was loaded of a file (or a diff), ready to draw: the blocks, how much, and what is still missing. Immutable. */
class ReadDoc(
    val chunks: List<Chunk>,
    /** Characters shown (for the lazy/eager choice). */
    val chars: Int,
    /** UTF-8 bytes read from the core (kept as a running total, never recounted). */
    val bytes: Int,
    /** Lines loaded so far (the file's lines 1..last). */
    val last: Int,
    /** Lines the file has. */
    val lines: Int,
    /** Something hidden (a control or direction character) was replaced in what is shown. */
    val replaced: Boolean,
) {
    sealed interface More {
        data class Joined(val doc: ReadDoc) : More
        /** The core gave no new line: stop offering more. */
        data object NoProgress : More
        /** The answer does not follow on: the file changed. Read again from the top. */
        data object Changed : More
    }

    /** The next read ([next] was asked for at `last + 1`) joined on; only the new text is cleaned and split. */
    fun more(next: FilePart): More {
        if (next.first != last + 1) return More.Changed
        if (next.last < next.first || next.text.isEmpty()) return More.NoProgress
        val d = Reading.display(next.text)
        val added = Chunks.split(d.text, startLine = last + 1)
        val old = if (chunks.isEmpty()) chunks else chunks.dropLast(1) + chunks.last().let { it.copy(text = it.text + "\n") }
        return More.Joined(ReadDoc(old + added, chars + d.text.length, bytes + next.bytes, next.last, next.lines, replaced || d.replaced))
    }

    companion object {
        /** The first read of a file. */
        fun of(p: FilePart): ReadDoc {
            val d = Reading.display(p.text)
            return ReadDoc(Chunks.forText(d.text), d.text.length, p.bytes, p.last, p.lines, d.replaced)
        }

        /** A text that is all there is (a diff). */
        fun whole(text: String): ReadDoc {
            val d = Reading.display(text)
            val n = Reading.lineCount(d.text)
            return ReadDoc(Chunks.forText(d.text), d.text.length, Reading.utf8Size(text), n, n, d.replaced)
        }
    }
}

/**
 * Reading a file: what the core's `file.read` gives (whole lines from `offset`, counted from 1, `limit` lines,
 * at most 8 MB of them, UTF-8 text only, else the error kind `not_text`), what the viewer says about it,
 * and which files it will not draw.
 */
object Reading {
    /** Lines asked for in one read (the core stops sooner if the lines are over 8 MB together). */
    const val LINE_LIMIT = 20_000

    /** The viewer holds at most this much of a file; past it "Load more" is not offered. */
    const val CEILING_BYTES = 4 shl 20

    /** The core said the file is not UTF-8 text (`hc exec cat` is the way to get bytes). */
    const val NOT_TEXT = "not_text"

    const val BINARY_NOTICE = "This file is not text (binary or not UTF-8), so it is not shown."
    const val REPLACED_NOTICE = "Hidden control characters were replaced."
    const val LAZY_HINT = "Large file: selection covers what is on screen. Use Copy all."
    const val NO_MORE = "Nothing more could be loaded."

    /** The error to show for a failed read. */
    fun errorText(kind: String?, message: String?): String = if (kind == NOT_TEXT) BINARY_NOTICE else message ?: "cannot read"

    /**
     * Text that is really bytes: more than 1 in 20 of the first 8,000 characters is a NUL, a control or a replacement
     * character. (A file with high bytes that are not UTF-8 never gets here: the core refuses it as `not_text`.)
     */
    fun looksBinary(text: String): Boolean {
        val n = minOf(text.length, 8000)
        if (n == 0) return false
        var bad = 0
        for (i in 0 until n) {
            val c = text[i]
            if (c == '�' || (c < ' ' && c != '\n' && c != '\r' && c != '\t' && c != '\u000C' && c != '\u001B')) bad++
        }
        return bad * 20 > n
    }

    /** A character that is never drawn from a file or a reply: controls (but not tab and newline), and text-direction or line-break overrides. */
    fun hidden(c: Char): Boolean =
        (c < ' ' && c != '\n' && c != '\t') || c == '\u007F' || c in '\u0080'..'\u009F' ||
            c in '‪'..'‮' || c in '⁦'..'⁩' || c == '‎' || c == '‏' || c == '؜' || c == ' ' || c == ' '

    class Display(val text: String, val replaced: Boolean)

    /** [raw] as drawn and copied: CRLF becomes LF, every other hidden character one U+FFFD. */
    fun display(raw: String): Display {
        var needs = false
        for (c in raw) if (hidden(c)) { needs = true; break }
        if (!needs) return Display(raw, false)
        val sb = StringBuilder(raw.length)
        var replaced = false
        var i = 0
        while (i < raw.length) {
            val c = raw[i]
            if (c == '\r' && i + 1 < raw.length && raw[i + 1] == '\n') { i++; continue }
            if (hidden(c)) { sb.append('�'); replaced = true } else sb.append(c)
            i++
        }
        return Display(sb.toString(), replaced)
    }

    /** What the viewer says under a cut file, or null when the whole file is loaded. */
    fun notice(d: ReadDoc): String? {
        if (d.last >= d.lines) return null
        if (d.chunks.isEmpty()) return "Nothing could be shown: line ${grouped(d.last + 1)} is too long to load."
        val base = "Showing the first ${grouped(d.last)} of ${grouped(d.lines)} lines (${size(d.bytes)}). The rest is not loaded."
        return if (d.bytes >= CEILING_BYTES) "$base Loading stops at ${size(CEILING_BYTES)}." else base
    }

    /** The next read when the file was cut and there is room: the line after the last one loaded. */
    fun nextOffset(d: ReadDoc): Int? = if (d.last < d.lines && d.chunks.isNotEmpty() && d.bytes < CEILING_BYTES) d.last + 1 else null

    /** Past this the viewer lays the text out lazily (only what scrolls into view), and selection only reaches what is composed. */
    const val EAGER_CHARS = 60_000

    fun lazy(chars: Int): Boolean = chars > EAGER_CHARS

    class Copied(val text: String, val total: Int) {
        val cut: Boolean get() = text.length < total
    }

    /** The text of all blocks for the clipboard, held to [max] characters: the START is kept (never half a surrogate pair). */
    fun copyAll(d: ReadDoc, max: Int): Copied {
        val sb = StringBuilder(minOf(d.chars, max) + 2)
        var total = 0
        for (c in d.chunks) {
            total += c.text.length
            if (sb.length < max) sb.append(c.text)
        }
        var end = minOf(sb.length, max)
        if (end < sb.length && end > 0 && Character.isHighSurrogate(sb[end - 1])) end--
        return Copied(sb.substring(0, end), total)
    }

    fun copyNotice(c: Copied): String =
        if (c.cut) "Copied the first ${grouped(c.text.length)} of ${grouped(c.total)} characters: the rest was too much for the clipboard." else "Copied."

    fun size(bytes: Int): String = when {
        bytes < 1024 -> "$bytes B"
        bytes < 1024 * 1024 -> "${bytes / 1024} KB"
        else -> String.format(Locale.US, "%.1f MB", bytes / 1048576.0)
    }

    fun grouped(n: Int): String = String.format(Locale.US, "%,d", n)

    fun utf8Size(s: String): Int {
        var n = 0
        var i = 0
        while (i < s.length) {
            val c = s[i]
            n += when {
                c.code < 0x80 -> 1
                c.code < 0x800 -> 2
                Character.isHighSurrogate(c) && i + 1 < s.length && Character.isLowSurrogate(s[i + 1]) -> { i++; 4 }
                else -> 3
            }
            i++
        }
        return n
    }

    /** Lines in [text]: a last line without a break counts, a final break does not start another. */
    fun lineCount(text: String): Int {
        if (text.isEmpty()) return 0
        var n = 0
        for (c in text) if (c == '\n') n++
        return if (text.endsWith('\n')) n else n + 1
    }

    /** The Changes view of a file git does not track yet: every line added, or a note when it is not text or was cut. */
    fun addedDiff(p: FilePart): String {
        if (looksBinary(p.text)) return "Binary file, not shown."
        val body = if (p.text.isEmpty()) "" else p.text.removeSuffix("\n").lines().joinToString("\n") { "+$it" }
        if (p.last >= p.lines) return body
        val note = "Showing the first ${grouped(p.last)} of ${grouped(p.lines)} lines (${size(p.bytes)}). The rest is not loaded."
        return if (body.isEmpty()) note else "$body\n$note"
    }
}

/** A run of lines of a file, drawn as one block of text. */
data class Chunk(
    /**
     * The lines, each followed by its `\n`, except the last of the whole text: so the blocks' texts put end to end
     * are the text (and a selection copied across a border keeps the line break). A line cut in two has no break between the pieces.
     */
    val text: String,
    /** The file's line this block starts at (1-based). */
    val firstLine: Int,
    /** A very long line was cut here: the block continues the line before it. */
    val continues: Boolean = false,
    /** The first characters of the line this block starts in (a diff colours a cut line's pieces alike). */
    val lead: String = "",
)

/** One line of a block (with its break) and the start of the line it belongs to. */
data class Segment(val text: String, val lead: String)

object Chunks {
    /** Small texts are one block (nothing to join, all selectable); big ones are blocks of 200 lines. */
    fun forText(text: String): List<Chunk> =
        if (text.length <= Reading.EAGER_CHARS) split(text, Int.MAX_VALUE, Reading.EAGER_CHARS.coerceAtLeast(text.length)) else split(text)

    /**
     * Splits [text] (a final line break is dropped) into blocks: never inside a line unless one line alone is over
     * [maxChars], then never inside a surrogate pair, a combining mark or an emoji sequence.
     */
    fun split(text: String, maxLines: Int = 200, maxChars: Int = 8000, startLine: Int = 1): List<Chunk> {
        if (text.isEmpty()) return emptyList()
        val body = text.removeSuffix("\n")
        val maxL = maxLines.coerceAtLeast(1)
        val maxC = maxChars.coerceAtLeast(2)
        val out = ArrayList<Chunk>()
        val cur = StringBuilder()
        var curLines = 0
        var curFirst = startLine
        var curLead = ""
        fun flush() {
            if (curLines > 0) out.add(Chunk(cur.toString(), curFirst, lead = curLead))
            cur.setLength(0)
            curLines = 0
        }
        var lineNo = startLine - 1
        var pos = 0
        while (pos <= body.length) {
            val nl = body.indexOf('\n', pos)
            val end = if (nl < 0) body.length else nl
            lineNo++
            val line = body.substring(pos, end)
            pos = if (nl < 0) body.length + 1 else nl + 1
            if (line.length > maxC) {
                flush()
                var a = 0
                var first = true
                val lead = line.take(4)
                while (a < line.length) {
                    val b = pieceEnd(line, a, maxC)
                    out.add(Chunk(line.substring(a, b), lineNo, continues = !first, lead = lead))
                    first = false
                    a = b
                }
                continue
            }
            if (curLines >= maxL || (curLines > 0 && cur.length + 1 + line.length > maxC)) flush()
            if (curLines == 0) { curFirst = lineNo; curLead = line.take(4) } else cur.append('\n')
            cur.append(line)
            curLines++
        }
        flush()
        for (i in 0 until out.size - 1) if (!out[i + 1].continues) out[i] = out[i].copy(text = out[i].text + "\n")
        return out
    }

    private fun extends(cp: Int): Boolean {
        if (cp == 0x200D || cp in 0xFE00..0xFE0F || cp in 0x1F3FB..0x1F3FF) return true
        val t = Character.getType(cp)
        return t == Character.NON_SPACING_MARK.toInt() || t == Character.ENCLOSING_MARK.toInt() || t == Character.COMBINING_SPACING_MARK.toInt()
    }

    /** Where a piece of [line] starting at [a] ends: at most [maxC] on, not inside a character that belongs together. */
    private fun pieceEnd(line: String, a: Int, maxC: Int): Int {
        var b = minOf(a + maxC, line.length)
        if (b >= line.length) return line.length
        val forced = if (Character.isHighSurrogate(line[b - 1]) && Character.isLowSurrogate(line[b])) b - 1 else b
        while (b > a + 1) {
            if (Character.isHighSurrogate(line[b - 1]) && Character.isLowSurrogate(line[b])) { b--; continue }
            if (extends(line.codePointAt(b)) || line.codePointBefore(b) == 0x200D) { b = line.offsetByCodePoints(b, -1); continue }
            return b
        }
        return forced.coerceAtLeast(a + 1)
    }

    /** The text back from its blocks. */
    fun join(chunks: List<Chunk>): String = chunks.joinToString("") { it.text }

    /** The lines of a block, each with its break, as they are drawn (so colours follow lines, also in a cut line's pieces). */
    fun segments(c: Chunk): List<Segment> {
        if (c.text.isEmpty()) return emptyList()
        val out = ArrayList<Segment>()
        var pos = 0
        var firstSeg = true
        while (pos < c.text.length) {
            val nl = c.text.indexOf('\n', pos)
            val end = if (nl < 0) c.text.length else nl + 1
            val s = c.text.substring(pos, end)
            out.add(Segment(s, if (firstSeg && c.continues) c.lead else s.trimEnd('\n').take(4)))
            firstSeg = false
            pos = end
        }
        return out
    }
}

/**
 * An agent's last reply, as the card shows it. It is the status message the agent's program reported (`status.msg`
 * of the terminal record, from OSC 7501); the Mac's helper that reports it is said to cut it at [HELPER_CAP]
 * characters. It is the agent's own output, so it is only ever drawn as plain text.
 */
object Reply {
    const val HELPER_CAP = 600
    const val COVER_LINES = 4
    const val LIST_LINES = 3

    private val ansi = Regex("\u001B\\[[0-9;?]*[ -/]*[@-~]|\u001B\\][^\u0007\u001B]*(\u0007|\u001B\\\\)")

    /** Plain text: escape sequences and control characters dropped, line ends as `\n`, no text-direction overrides. */
    fun clean(raw: String?): String {
        if (raw.isNullOrEmpty()) return ""
        val s = if (raw.indexOf('\u001B') >= 0) ansi.replace(raw, "") else raw
        val sb = StringBuilder(s.length)
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (c == '\r') { sb.append('\n'); if (i + 1 < s.length && s[i + 1] == '\n') i++ }
            else if (!Reading.hidden(c)) sb.append(c)
            i++
        }
        return sb.toString().trim()
    }

    /** Decided on the raw reply as reported: exactly the helper's limit in characters is the sign it was cut (a longer or shorter one was not). */
    fun maybeCut(raw: String?): Boolean = raw != null && raw.codePointCount(0, raw.length) == HELPER_CAP

    /** What the reader sheet says when the Mac may have cut the reply, else null. */
    fun cutNotice(maybeCut: Boolean): String? =
        if (maybeCut) "This may be cut short by the Mac at $HELPER_CAP characters. The rest is not available here." else null

    /** The card shows the reply only for an agent that has stopped; a blocked or working one keeps its terminal tail (what is being asked or done). */
    fun showsOnCard(state: String): Boolean = state == "done" || state == "idle"

    /** The card's text and whether "Show all" belongs under it. */
    data class Excerpt(val text: String, val more: Boolean)

    /**
     * The start of [text] for a card: at most [maxLines] non-blank lines (each trimmed) and [maxChars]
     * characters, cut at a space where it can and ending with an ellipsis when something was left out.
     * [more] is true when text was left out or the Mac may have cut the reply ([maybeCut]).
     */
    fun excerpt(text: String, maxLines: Int, maxChars: Int = maxLines * 100, maybeCut: Boolean = false): Excerpt {
        val lines = text.lines().map { it.trim() }.filter { it.isNotEmpty() }
        if (lines.isEmpty()) return Excerpt("", false)
        val kept = lines.take(maxLines)
        var cut = lines.size > kept.size
        var body = kept.joinToString("\n")
        if (body.length > maxChars) {
            var end = maxChars
            if (Character.isHighSurrogate(body[end - 1])) end--
            val space = body.lastIndexOf(' ', end).takeIf { it > end / 2 }
            body = body.substring(0, space ?: end).trimEnd()
            cut = true
        }
        return Excerpt(if (cut) "$body…" else body, cut || maybeCut)
    }
}
