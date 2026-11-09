package herdr.app

/**
 * Coding agents the + menu can start, full-screen programs a new space can
 * start with, and the shell wrappers that find and launch them in the Mac
 * account's own login shell (ported from the desktop app's
 * src/remote_shell.rs, so PATH from .zprofile/.zshrc applies).
 */
object Agents {
    data class Known(val name: String, val command: String)

    val known = listOf(
        Known("Claude Code", "claude"),
        Known("Codex", "codex"),
        Known("OpenCode", "opencode"),
        Known("Gemini", "gemini"),
        Known("Amp", "amp"),
        Known("Aider", "aider"),
        Known("Cursor Agent", "cursor-agent"),
        Known("Pi", "pi"),
    )

    /** Space templates: full-screen programs offered under "Start with" in New space, when on PATH. */
    val programs = listOf(
        Known("TUIOS", "tuios"),
    )

    /**
     * How a program starts. TUIOS reattaches to the most recent session (a new
     * one if there's none) instead of adding a session for every space.
     */
    fun argv(command: String): List<String> = when (command) {
        "tuios" -> listOf("tuios", "attach")
        else -> listOf(command)
    }

    fun nameOf(command: String) = (known + programs).firstOrNull { it.command == command }?.name ?: command

    fun check(commands: List<String>): List<String> {
        val posix = "for c; do unalias -- \"\$c\" 2>/dev/null; command -v \"\$c\" >/dev/null 2>&1 && printf 'herdr-has:%s\\n' \"\$c\"; done"
        return inShell(posix, "for c in \$argv[2..]; command -v \$c >/dev/null 2>&1; and printf 'herdr-has:%s\\n' \$c; end", posix, commands)
    }

    /** Runs [command], then stays in an interactive shell when it exits. */
    fun launch(command: List<String>): List<String> =
        inShell("\"\$@\"; exec \"\$0\" -li", "command \$argv[2..]; exec \$argv[1] -li", "\"\$@\"; exec \"\${SHELL:-sh}\" -i", command)

    private fun inShell(posix: String, fish: String, fallback: String, args: List<String>): List<String> {
        fun quote(s: String) = s.replace("'", "'\\''")
        val script = """shell=${'$'}{SHELL:-/bin/sh}
case "${'$'}{shell##*/}" in
  fish) exec "${'$'}shell" -lic '${quote(fish)}' "${'$'}shell" "${'$'}@" ;;
  sh|bash|zsh|dash|ksh) exec "${'$'}shell" -lic '${quote(posix)}' "${'$'}shell" "${'$'}@" ;;
  *) exec sh -c '${quote(fallback)}' sh "${'$'}@" ;;
esac"""
        return listOf("sh", "-c", script, "sh") + args
    }
}
