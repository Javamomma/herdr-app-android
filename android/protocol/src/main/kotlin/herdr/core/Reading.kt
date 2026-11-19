package herdr.core

import kotlinx.serialization.json.JsonElement
import java.util.Locale

/** One answer of the core's `file.read`: whole lines `first..last` of a file with `lines` lines. */
data class FilePart(val text: String, val first: Int, val last: Int, val lines: Int) {
    companion object {
        fun of(r: JsonElement?): FilePart {
            val text = r["text"].str ?: ""
            val first = (r["first"].long ?: 1).toInt()
            val last = (r["last"].long ?: text.count { it == '\n' }.toLong()).toInt()
            return FilePart(text, first, last, (r["lines"].long ?: last.toLong()).toInt())
        }
    }
}

/**
 * Reading a file: what the core's `file.read` gives (a number of whole lines
 * from an `offset`, at most 8 MB of them, UTF-8 text only, else the error kind
 * `not_text`), what the viewer says about it, and which files it will not draw.
 */
object Reading {
    /** Lines asked for in one read (the core stops sooner if the lines are over 8 MB together). */
    const val LINE_LIMIT = 20_000

    /** The core said the file is not UTF-8 text (`hc exec cat` is the way to get bytes). */
    const val NOT_TEXT = "not_text"

    const val BINARY_NOTICE = "This file is not text (binary or not UTF-8), so it is not shown."

    /** The error to show for a failed read. */
    fun errorText(kind: String?, message: String?): String = if (kind == NOT_TEXT) BINARY_NOTICE else message ?: "cannot read"

    /** Text that is really bytes: a NUL, or more than 1 in 10 of the first 8,000 characters a control or replacement character. */
    fun looksBinary(text: String): Boolean {
        val n = minOf(text.length, 8000)
        if (n == 0) return false
        var bad = 0
        for (i in 0 until n) {
            val c = text[i]
            if (c == '\u0000') return true
            if (c == '�' || (c < ' ' && c != '\n' && c != '\r' && c != '\t' && c != '\u000C' && c != '\u001B')) bad++
        }
        return bad * 10 > n
    }

    /** True when the core stopped before the end of the file (the line cap, or its 8 MB limit). */
    fun isCut(p: FilePart): Boolean = p.last < p.lines

    /** What the viewer says under a cut file, or null when the whole file is shown. */
    fun cutNotice(p: FilePart): String? {
        if (!isCut(p)) return null
        val bytes = p.text.toByteArray(Charsets.UTF_8).size
        return "Showing the first ${grouped(p.last)} of ${grouped(p.lines)} lines (${size(bytes)}). The rest is not loaded."
    }

    /** The next read when the file was cut: the line after the last one shown, else null. */
    fun nextOffset(p: FilePart): Int? = if (isCut(p) && p.last >= p.first - 1) p.last + 1 else null

    /** [more] read from where [have] ended, joined on; null when it does not follow on (the file changed): read again from the top. */
    fun append(have: FilePart, more: FilePart): FilePart? =
        if (more.first != have.last + 1) null else FilePart(have.text + more.text, have.first, more.last, more.lines)

    /** Past this the viewer lays the text out lazily (only what scrolls into view), and selection only reaches what is composed. */
    const val EAGER_CHARS = 60_000

    fun lazy(text: String): Boolean = text.length > EAGER_CHARS

    fun size(bytes: Int): String = when {
        bytes < 1024 -> "$bytes B"
        bytes < 1024 * 1024 -> "${bytes / 1024} KB"
        else -> String.format(Locale.US, "%.1f MB", bytes / 1048576.0)
    }

    fun grouped(n: Int): String = String.format(Locale.US, "%,d", n)

    /** The Changes view of a file git does not track yet: every line added, or a note when it is not text or was cut. */
    fun addedDiff(p: FilePart): String {
        if (looksBinary(p.text)) return "Binary file, not shown."
        val body = if (p.text.isEmpty()) "" else p.text.removeSuffix("\n").lines().joinToString("\n") { "+$it" }
        val note = cutNotice(p) ?: return body
        return if (body.isEmpty()) note else "$body\n$note"
    }
}

/** A run of lines of a file, drawn as one block of text. */
data class Chunk(
    /** The lines, joined by `\n`, without a trailing one. */
    val text: String,
    /** The file's line this block starts at (1-based). */
    val firstLine: Int,
    /** A very long line was cut here: the block continues the line before it (no line break between them). */
    val continues: Boolean = false,
)

/** Splits text into blocks for a lazy list: never inside a line (unless one line is over [maxChars]) and never inside a surrogate pair. */
object Chunks {
    fun split(text: String, maxLines: Int = 200, maxChars: Int = 8000): List<Chunk> {
        if (text.isEmpty()) return emptyList()
        val maxL = maxLines.coerceAtLeast(1)
        val maxC = maxChars.coerceAtLeast(2)
        val out = ArrayList<Chunk>()
        val cur = StringBuilder()
        var curLines = 0
        var curFirst = 1
        fun flush() {
            if (curLines > 0) out.add(Chunk(cur.toString(), curFirst))
            cur.setLength(0)
            curLines = 0
        }
        var lineNo = 0
        var pos = 0
        while (pos < text.length) {
            val nl = text.indexOf('\n', pos)
            val end = if (nl < 0) text.length else nl
            lineNo++
            val line = text.substring(pos, end)
            pos = if (nl < 0) text.length else nl + 1
            if (line.length > maxC) {
                flush()
                var a = 0
                var first = true
                while (a < line.length) {
                    var b = minOf(a + maxC, line.length)
                    if (b < line.length && Character.isHighSurrogate(line[b - 1]) && Character.isLowSurrogate(line[b])) b--
                    out.add(Chunk(line.substring(a, b), lineNo, continues = !first))
                    first = false
                    a = b
                }
                continue
            }
            if (curLines >= maxL || (curLines > 0 && cur.length + 1 + line.length > maxC)) flush()
            if (curLines == 0) curFirst = lineNo else cur.append('\n')
            cur.append(line)
            curLines++
        }
        flush()
        return out
    }

    /** The text back from its blocks (a trailing line break of the original is dropped). */
    fun join(chunks: List<Chunk>): String {
        val sb = StringBuilder()
        chunks.forEachIndexed { i, c -> if (i > 0 && !c.continues) sb.append('\n'); sb.append(c.text) }
        return sb.toString()
    }
}

/**
 * An agent's last reply, as the card shows it. It is the status message the
 * agent's program reported (`status.msg` of the terminal record, from OSC 7501);
 * the Mac's helper that reports it is said to cut it at [HELPER_CAP] characters.
 * It is the agent's own output, so it is only ever drawn as plain text.
 */
object Reply {
    const val HELPER_CAP = 600
    const val COVER_LINES = 4
    const val LIST_LINES = 3

    /** Plain text: line ends as `\n`, no control characters, no text-direction overrides (they can reorder what is drawn). */
    fun clean(raw: String?): String {
        if (raw.isNullOrEmpty()) return ""
        val sb = StringBuilder(raw.length)
        var i = 0
        while (i < raw.length) {
            val c = raw[i]
            when {
                c == '\r' -> { sb.append('\n'); if (i + 1 < raw.length && raw[i + 1] == '\n') i++ }
                c == '\n' || c == '\t' -> sb.append(c)
                c < ' ' || c == '\u007F' || c in '\u0080'..'\u009F' -> {}
                c in '‪'..'‮' || c in '⁦'..'⁩' || c == '‎' || c == '‏' -> {}
                else -> sb.append(c)
            }
            i++
        }
        return sb.toString().trim()
    }

    /** Whether the reply is as long as the helper's limit, so the Mac probably cut it. */
    fun cutByHelper(text: String): Boolean = text.codePointCount(0, text.length) >= HELPER_CAP

    /** What the reader sheet says when the Mac cut the reply, else null. */
    fun cutNotice(text: String): String? =
        if (cutByHelper(text)) "Cut at $HELPER_CAP characters by the Mac. The rest of this reply is not available here." else null

    /** The card's text and whether "Show all" belongs under it. */
    data class Excerpt(val text: String, val more: Boolean)

    /**
     * The start of [text] for a card: at most [maxLines] non-blank lines (each trimmed) and [maxChars]
     * characters, cut at a space where it can and ending with an ellipsis when something was left out.
     * [more] is true when text was left out or the Mac cut the reply.
     */
    fun excerpt(text: String, maxLines: Int, maxChars: Int = maxLines * 100): Excerpt {
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
        return Excerpt(if (cut) "$body…" else body, cut || cutByHelper(text))
    }
}
