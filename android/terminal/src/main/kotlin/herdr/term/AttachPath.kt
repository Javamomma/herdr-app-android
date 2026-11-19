package herdr.term

/** Putting the path of an uploaded photo into the message box; plain Kotlin so the rules are tested on the JVM. */
object AttachPath {
    private val SAFE = Regex("[A-Za-z0-9_@%+=:,./~-]+")

    /**
     * [path] as the person would type it in a shell: unchanged when it holds only letters, digits and
     * `_ @ % + = : , . / ~ -`; otherwise in single quotes, each `'` written `'\''`. A path with a line break
     * or NUL is refused (null): the core never makes one, and a pasted newline would send the message.
     */
    fun quote(path: String): String? {
        if (path.isEmpty() || path.any { it == '\n' || it == '\r' || it == '\u0000' }) return null
        return if (SAFE.matches(path)) path else "'" + path.replace("'", "'\\''") + "'"
    }

    /**
     * Inserts [path] (quoted by [quote]) into [draft] over the selection [start, end), like Paste
     * ([Clip.insertAt]), with a space before it unless it starts the draft or follows white space.
     * Returns the new text and cursor, or null when the path can't be used.
     */
    fun insert(draft: String, start: Int, end: Int, path: String): Pair<String, Int>? {
        val q = quote(path) ?: return null
        val a = minOf(start, end).coerceIn(0, draft.length)
        val lead = if (a > 0 && !draft[a - 1].isWhitespace()) " " else ""
        return Clip.insertAt(draft, start, end, lead + q)
    }
}
