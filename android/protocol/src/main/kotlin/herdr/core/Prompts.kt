package herdr.core

/** One answer an agent's question offers, as read from its screen. */
data class Choice(
    /** Its place in the list, from 0. */
    val index: Int,
    /** The number the agent shows (`1` in `1. Yes`), when it shows one. */
    val number: Int?,
    val label: String,
    /** The highlighted one (`❯ 1. Yes`): Enter picks it. */
    val selected: Boolean,
    /** Needs a press-and-hold: it can't be taken back or stops future questions. */
    val risky: Boolean,
)

/**
 * What an agent is asking, as the person was shown it. [mark] changes when
 * the question, its options or the highlighted option change: an answer
 * given to one question is never sent to another.
 */
data class Question(
    /** The lines that say what is asked. */
    val text: List<String>,
    val choices: List<Choice>,
    val mark: String,
    /** It asks about something that can't be taken back (a force push, `rm -rf`). */
    val dangerous: Boolean,
)

/**
 * Reading an agent's question from its screen text: the numbered options
 * agents like Claude Code and Codex draw, which one is highlighted, and the
 * keys that pick one. Pure text work, so it's tested without a device.
 */
object Prompts {
    private val option = Regex("""^[\s│|]*([❯›>▶→●*]?)\s*(\d{1,2})[.)]\s+(.+?)\s*[│|]?\s*$""")
    private val riskyWords = listOf(
        "don't ask", "dont ask", "do not ask", "always", "for this session", "all edits", "push", "force",
        "delete", "remove", "rm -", "drop", "reset --hard", "overwrite", "bypass", "skip permissions",
    )

    /** The last block of numbered options on the screen, or none (at most 9 lines apart). */
    fun choices(screen: String): List<Choice> = choices(block(screen.lines().map { it.trimEnd() }))

    /** The option lines of the last counting block (`1.` `2.` …), with their line numbers. */
    private fun block(lines: List<String>): List<Pair<Int, MatchResult>> {
        val found = mutableListOf<Pair<Int, MatchResult>>()
        for ((i, line) in lines.withIndex()) option.find(line)?.let { found += i to it }
        if (found.isEmpty()) return emptyList()
        // Walk back from the last option while the numbers count down.
        val block = ArrayDeque<Pair<Int, MatchResult>>()
        for ((i, m) in found.asReversed()) {
            val n = m.groupValues[2].toInt()
            val next = block.firstOrNull()
            if (next == null || (next.second.groupValues[2].toInt() == n + 1 && next.first - i <= 9)) block.addFirst(i to m) else break
        }
        return if (block.size < 2 || block.first().second.groupValues[2].toInt() != 1) emptyList() else block
    }

    private fun choices(block: List<Pair<Int, MatchResult>>): List<Choice> = block.mapIndexed { k, (_, m) ->
        val label = m.groupValues[3].trim()
        Choice(
            index = k,
            number = m.groupValues[2].toInt(),
            label = label,
            selected = m.groupValues[1].isNotEmpty(),
            risky = riskyWords.any { label.lowercase().contains(it) },
        )
    }

    /** Commands that can't be taken back: answering yes to one is never a single tap. */
    private val dangerous = listOf(
        """\brm\s+-[a-z]*[rf]""", """\bgit\s+push\b[^\n]*(--force|\s-f\b)""", """\bgit\s+reset\s+--hard""",
        """\bgit\s+clean\s+-[a-z]*f""", """\bdrop\s+(table|database|schema)\b""", """\btruncate\s+table\b""",
        """\bdelete\s+from\b""", """\bsudo\b""", """\bmkfs""", """\bdd\s+if=""", """\|\s*(sudo\s+)?(sh|bash|zsh)\b""",
        """--no-verify""", """\bch(mod|own)\s+-R""", """\bkubectl\s+delete""", """\bterraform\s+destroy""",
        """\bdocker\s+system\s+prune""",
    ).map { Regex(it, RegexOption.IGNORE_CASE) }

    /** A line without the box an agent draws around its question. */
    private fun clean(line: String): String =
        line.filter { it !in '\u2500'..'\u257F' }.trim().trim('|').trim()

    /**
     * What the screen asks: the lines above its options (or, with no options,
     * its last lines), the options, and a mark of all of it.
     */
    fun question(screen: String): Question {
        val lines = screen.lines().map { it.trimEnd() }
        val block = block(lines)
        val choices = choices(block)
        val above = if (block.isEmpty()) lines else lines.subList(0, block.first().first)
        val text = above.map(::clean).filter { it.isNotBlank() }.takeLast(if (block.isEmpty()) 8 else 6)
        val all = text + choices.map { "${if (it.selected) '>' else ' '}${it.number}.${it.label}" }
        val digest = java.security.MessageDigest.getInstance("SHA-256").digest(all.joinToString("\n").toByteArray())
        val joined = text.joinToString("\n")
        return Question(
            text = text,
            choices = choices,
            mark = digest.take(8).joinToString("") { "%02x".format(it) },
            dangerous = dangerous.any { it.containsMatchIn(joined) },
        )
    }

    /**
     * The option one tap on "allow" takes: the highlighted one, else the
     * first. None when the question is dangerous or that option is risky.
     */
    fun allowed(q: Question): Choice? {
        if (q.dangerous) return null
        val target = q.choices.firstOrNull { it.selected } ?: q.choices.firstOrNull() ?: return null
        return target.takeIf { !it.risky }
    }

    /**
     * Keys for a one-tap "allow", or null when it must be answered in the
     * app: the highlighted option, or plain Enter for a question without options.
     */
    fun allowKeys(q: Question): List<String>? = when {
        q.dangerous -> null
        q.choices.isEmpty() -> listOf("enter")
        else -> allowed(q)?.let { keys(q.choices, it) }
    }

    /**
     * Keys (herdr-core `terminal.keys` names) that pick [target]: arrows from
     * the highlighted option, then Enter. With nothing highlighted, its number.
     */
    fun keys(choices: List<Choice>, target: Choice): List<String> {
        val at = choices.indexOfFirst { it.selected }
        if (at < 0) return listOf((target.number ?: (target.index + 1)).toString())
        val moves = target.index - at
        val arrow = if (moves > 0) "down" else "up"
        return List(kotlin.math.abs(moves)) { arrow } + "enter"
    }

    /** The last [n] lines with text, for a preview. */
    fun tail(screen: String, n: Int = 3): List<String> =
        screen.lines().map { it.trimEnd() }.filter { it.isNotBlank() }.takeLast(n)
}
