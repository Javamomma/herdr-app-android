package herdr.term

/** Clipboard rules the app applies; plain Kotlin so they are tested on the JVM. */
object Clip {
    /** Copy writes the clipboard only when something is selected, so an empty selection leaves it as it was. */
    fun shouldWrite(selection: String): Boolean = selection.isNotEmpty()

    fun copyNotice(selection: String): String =
        if (selection.isEmpty()) "Nothing selected, so the clipboard is unchanged. Long-press text in the terminal first." else "Copied."

    /** Paste into a draft: [clip] replaces the selection [start, end) (or goes at the cursor); returns the new text and cursor. */
    fun insertAt(draft: String, start: Int, end: Int, clip: String): Pair<String, Int> {
        val a = minOf(start, end).coerceIn(0, draft.length)
        val b = maxOf(start, end).coerceIn(0, draft.length)
        return (draft.substring(0, a) + clip + draft.substring(b)) to (a + clip.length)
    }
}
