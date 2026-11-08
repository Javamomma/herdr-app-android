package herdr.term

/** Keys that aren't text, as the terminal sends them. */
enum class Key { UP, DOWN, RIGHT, LEFT, HOME, END, PAGE_UP, PAGE_DOWN, INSERT, DELETE, ENTER, TAB, BACKSPACE, ESCAPE, F1, F2, F3, F4, F5, F6, F7, F8, F9, F10, F11, F12 }

data class Mods(val shift: Boolean = false, val alt: Boolean = false, val ctrl: Boolean = false) {
    /** xterm's modifier parameter: 1 + shift + 2·alt + 4·ctrl. */
    val param get() = 1 + (if (shift) 1 else 0) + (if (alt) 2 else 0) + (if (ctrl) 4 else 0)
    val any get() = shift || alt || ctrl
}

object Keys {
    private const val ESC = "\u001b"

    /** The bytes for [key] with [mods], given the program's cursor-key mode. */
    fun encode(key: Key, mods: Mods, appCursor: Boolean): String {
        val m = mods.param
        fun csi(final: Char) = if (mods.any) "$ESC[1;$m$final" else if (appCursor) "${ESC}O$final" else "$ESC[$final"
        fun tilde(n: Int) = if (mods.any) "$ESC[$n;$m~" else "$ESC[$n~"
        fun ss3(final: Char) = if (mods.any) "$ESC[1;$m$final" else "${ESC}O$final"
        val alt = if (mods.alt) ESC else ""
        return when (key) {
            Key.UP -> csi('A')
            Key.DOWN -> csi('B')
            Key.RIGHT -> csi('C')
            Key.LEFT -> csi('D')
            Key.HOME -> csi('H')
            Key.END -> csi('F')
            Key.PAGE_UP -> tilde(5)
            Key.PAGE_DOWN -> tilde(6)
            Key.INSERT -> tilde(2)
            Key.DELETE -> tilde(3)
            Key.ENTER -> alt + "\r"
            Key.TAB -> if (mods.shift) "$ESC[Z" else alt + "\t"
            Key.BACKSPACE -> alt + if (mods.ctrl) "\u0008" else "\u007f"
            Key.ESCAPE -> alt + ESC
            Key.F1 -> ss3('P')
            Key.F2 -> ss3('Q')
            Key.F3 -> ss3('R')
            Key.F4 -> ss3('S')
            Key.F5 -> tilde(15)
            Key.F6 -> tilde(17)
            Key.F7 -> tilde(18)
            Key.F8 -> tilde(19)
            Key.F9 -> tilde(20)
            Key.F10 -> tilde(21)
            Key.F11 -> tilde(23)
            Key.F12 -> tilde(24)
        }
    }

    /** Text typed with Ctrl and/or Alt held: `ctrl-c` is 0x03, alt adds ESC before. */
    fun text(text: String, mods: Mods): String {
        var t = text
        if (mods.ctrl && t.length == 1) {
            val c = t[0]
            t = when {
                c in 'a'..'z' -> (c.code - 'a'.code + 1).toChar().toString()
                c in '@'..'_' -> (c.code - '@'.code).toChar().toString()
                c == ' ' || c == '2' -> "\u0000"
                c == '3' -> "\u001b"
                c == '4' -> "\u001c"
                c == '5' -> "\u001d"
                c == '6' -> "\u001e"
                c == '7' || c == '/' -> "\u001f"
                c == '8' || c == '?' -> "\u007f"
                else -> t
            }
        }
        return if (mods.alt) ESC + t else t
    }

    /** Text pasted, bracketed when the program asked (mode 2004) so it isn't run line by line. */
    fun paste(text: String, bracketed: Boolean): String {
        val clean = text.replace("\r\n", "\r").replace('\n', '\r')
        return if (bracketed) "$ESC[200~${clean.replace("$ESC[201~", "")}$ESC[201~" else clean
    }

    /**
     * A mouse report: [button] 0 left, 1 middle, 2 right, 64 wheel up, 65 wheel
     * down; [col]/[row] from 0. SGR (1006) when asked, else the classic form.
     */
    fun mouse(button: Int, col: Int, row: Int, press: Boolean, sgr: Boolean, motion: Boolean = false): String {
        val b = button + if (motion) 32 else 0
        if (sgr) return "$ESC[<$b;${col + 1};${row + 1}${if (press) 'M' else 'm'}"
        val cb = if (press) b else 3
        if (col > 222 || row > 222) return ""
        return "$ESC[M" + (32 + cb).toChar() + (33 + col).toChar() + (33 + row).toChar()
    }

    fun focus(gained: Boolean) = if (gained) "$ESC[I" else "$ESC[O"
}
