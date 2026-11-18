package herdr.term

/** Clipboard rules the app applies; plain Kotlin so they are tested on the JVM. */
object Clip {
    /** Copy writes the clipboard only when something is selected, so an empty selection leaves it as it was. */
    fun shouldWrite(selection: String): Boolean = selection.isNotEmpty()

    /**
     * Android's clipboard takes about 1 MB per transaction (text goes over as UTF-16), so a copy is
     * held to this many characters, about 400 KB, with room to spare.
     */
    const val MAX_CHARS = 200_000

    /** [text] held to [MAX_CHARS]: when it is longer, the end is kept (never half a surrogate pair). */
    fun limit(text: String): Picked {
        if (text.length <= MAX_CHARS) return Picked(text)
        var from = text.length - MAX_CHARS
        if (Character.isLowSurrogate(text[from])) from++
        return Picked(text.substring(from), true)
    }

    fun copyNotice(selection: String, cut: Boolean = false): String = when {
        selection.isEmpty() -> "Nothing selected, so the clipboard is unchanged. Long-press text in the terminal first."
        cut -> "Copied the last ${"%,d".format(java.util.Locale.US, selection.length)} characters: the rest was too much for the clipboard."
        else -> "Copied."
    }

    /** Paste into a draft: [clip] replaces the selection [start, end) (or goes at the cursor); returns the new text and cursor. */
    fun insertAt(draft: String, start: Int, end: Int, clip: String): Pair<String, Int> {
        val a = minOf(start, end).coerceIn(0, draft.length)
        val b = maxOf(start, end).coerceIn(0, draft.length)
        return (draft.substring(0, a) + clip + draft.substring(b)) to (a + clip.length)
    }
}
