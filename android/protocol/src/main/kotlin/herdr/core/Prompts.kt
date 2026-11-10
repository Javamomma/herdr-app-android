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
    fun choices(screen: String): List<Choice> {
        val lines = screen.lines().map { it.trimEnd() }
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
        if (block.size < 2 || block.first().second.groupValues[2].toInt() != 1) return emptyList()
        return block.mapIndexed { k, (_, m) ->
            val label = m.groupValues[3].trim()
            Choice(
                index = k,
                number = m.groupValues[2].toInt(),
                label = label,
                selected = m.groupValues[1].isNotEmpty(),
                risky = riskyWords.any { label.lowercase().contains(it) },
            )
        }
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
