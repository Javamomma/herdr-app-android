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
    /** The most lines of a prompt's own block the mark and the danger check read. */
    private const val MARK_LINES = 40

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
        val cleaned = above.map(::clean)
        val shown = cleaned.indices.filter { cleaned[it].isNotBlank() }
        val tail = shown.takeLast(if (block.isEmpty()) 8 else 6)
        val text = tail.map { cleaned[it] }
        // The mark and the danger check read the last lines shown, extended upward to the prompt's own block
        // (up to the rule or box edge above its options): two long commands that end in the same lines, or
        // that a rule separates from the question, must still differ, in mark and in danger.
        val rule = if (block.isEmpty()) -1 else above.indexOfLast(::isRule)
        // A box's top edge is a firm start (what is outside the box may change without the question changing);
        // a plain rule only extends the cover upward when it is further up than the last lines.
        val boxTop = rule >= 0 && above[rule].any { it in "╭┌╔┏" }
        val first = tail.firstOrNull() ?: 0
        val start = when {
            rule < 0 -> first
            boxTop -> rule + 1
            else -> minOf(first, rule + 1)
        }
        val covered = shown.filter { it >= start }.map { cleaned[it] }.takeLast(MARK_LINES)
        val all = covered + choices.map { "${if (it.selected) '>' else '.'}${it.number}.${it.label}" }
        // No whitespace in the hash: the same text wrapped differently keeps its mark.
        val digest = java.security.MessageDigest.getInstance("SHA-256")
            .digest(all.joinToString("").filter { !it.isWhitespace() }.toByteArray())
        val joined = covered.joinToString("\n")
        return Question(
            text = text,
            choices = choices,
            mark = digest.take(8).joinToString("") { "%02x".format(it) },
            dangerous = dangerous.any { it.containsMatchIn(joined) },
        )
    }

    /** A line of box-drawing characters only (a rule or box edge); `---` or `====` in content is not one. */
    private fun isRule(line: String): Boolean {
        val t = line.trim()
        return t.length >= 3 && t.all { it in '\u2500'..'\u257F' }
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

    /** What tapping one of the answer buttons does. */
    sealed interface Pick {
        /** Send these keys. */
        data class Send(val keys: List<String>) : Pick
        /** The screen no longer shows the question the button belonged to. */
        object Changed : Pick
        /** Risky option or dangerous command: only a press-and-hold takes it. */
        object NeedsHold : Pick
    }

    /**
     * The answer button [label], drawn for the question with [mark], read against
     * the [screen] as it is now: nothing is sent unless it is still that question.
     * Risky options and any option of a dangerous question need [held].
     */
    fun pick(screen: String, mark: String, label: String, held: Boolean): Pick {
        val q = question(screen)
        if (q.mark != mark) return Pick.Changed
        val target = q.choices.firstOrNull { it.label == label } ?: return Pick.Changed
        if ((target.risky || q.dangerous) && !held) return Pick.NeedsHold
        return Pick.Send(keys(q.choices, target))
    }

    /** The last [n] lines with text, for a preview. */
    fun tail(screen: String, n: Int = 3): List<String> =
        screen.lines().map { it.trimEnd() }.filter { it.isNotBlank() }.takeLast(n)
}
