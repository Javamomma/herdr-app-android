package herdr.term

/*
 * Cell storage. Each cell is a code point plus one packed style word:
 *   bits  0..25  foreground color   bits 26..51  background color
 *   bits 52..59  attributes
 * A color is 0 (the default), PALETTE|index, or RGB|0xRRGGBB.
 */

object Color {
    const val DEFAULT = 0
    const val PALETTE = 1 shl 24
    const val RGB = 1 shl 25
    fun palette(i: Int) = PALETTE or (i and 0xff)
    fun rgb(r: Int, g: Int, b: Int) = RGB or ((r and 0xff) shl 16) or ((g and 0xff) shl 8) or (b and 0xff)
}

object Attr {
    const val BOLD = 1
    const val DIM = 2
    const val ITALIC = 4
    const val UNDERLINE = 8
    const val BLINK = 16
    const val INVERSE = 32
    const val HIDDEN = 64
    const val STRIKE = 128
}

object Style {
    const val DEFAULT = 0L
    private const val MASK = (1L shl 26) - 1
    fun of(fg: Int, bg: Int, attrs: Int): Long = (fg.toLong() and MASK) or ((bg.toLong() and MASK) shl 26) or ((attrs.toLong() and 0xff) shl 52)
    fun fg(s: Long): Int = (s and MASK).toInt()
    fun bg(s: Long): Int = ((s shr 26) and MASK).toInt()
    fun attrs(s: Long): Int = ((s shr 52) and 0xff).toInt()
    /** What an erased cell keeps of the current style: its background (xterm's BCE). */
    fun erased(s: Long): Long = of(Color.DEFAULT, bg(s), 0)
}

/** The second half of a wide character. */
const val WIDE_TAIL = -1

/**
 * One row. Its arrays may be longer than the screen is wide: shrinking the
 * window hides cells instead of dropping them, so widening shows them again.
 */
class Line(cols: Int) {
    var chars = IntArray(cols) { ' '.code }
        private set
    var styles = LongArray(cols)
        private set
    /** Clusters (a base and its combining marks, emoji sequences) by column. */
    var extra: HashMap<Int, String>? = null
    /** The row continues on the next one (it was filled and wrapped). */
    var wrapped = false

    val capacity get() = chars.size

    fun ensure(cols: Int) {
        if (cols <= chars.size) return
        val old = chars.size
        chars = chars.copyOf(cols).also { it.fill(' '.code, old, cols) }
        styles = styles.copyOf(cols)
    }

    fun clear(from: Int, to: Int, style: Long) {
        val end = minOf(to, chars.size)
        if (from >= end) return
        chars.fill(' '.code, from, end)
        styles.fill(style, from, end)
        extra?.let { e -> e.keys.removeAll { it in from until end } }
    }

    fun text(col: Int): String? {
        val c = chars[col]
        if (c == WIDE_TAIL) return null
        return extra?.get(col) ?: String(Character.toChars(c))
    }

    /** Cells after the last non-blank, default-styled one. */
    fun usedLength(): Int {
        var n = chars.size
        while (n > 0 && chars[n - 1] == ' '.code && styles[n - 1] == Style.DEFAULT && extra?.containsKey(n - 1) != true) n--
        return n
    }

    fun copy(): Line = Line(0).also {
        it.chars = chars.copyOf()
        it.styles = styles.copyOf()
        it.extra = extra?.let { e -> HashMap(e) }
        it.wrapped = wrapped
    }

    /** A copy without trailing blanks, for scrollback. */
    fun trimmed(): Line = Line(0).also {
        val n = usedLength()
        it.chars = chars.copyOf(n)
        it.styles = styles.copyOf(n)
        it.extra = extra?.filterKeys { k -> k < n }?.let { e -> if (e.isEmpty()) null else HashMap(e) }
        it.wrapped = wrapped
    }

    fun string(cols: Int = chars.size): String {
        val sb = StringBuilder()
        for (i in 0 until minOf(cols, chars.size)) text(i)?.let { sb.append(it) }
        return sb.toString()
    }
}

/** How many cells a code point takes: 0 (combining), 1, or 2 (wide). */
fun cellWidth(cp: Int): Int {
    if (cp < 0x300) return if (cp < 0x20 || cp in 0x7f..0x9f) 0 else 1
    if (cp == 0x200D || cp in 0xFE00..0xFE0F || cp in 0xE0100..0xE01EF) return 0
    when (Character.getType(cp)) {
        Character.NON_SPACING_MARK.toInt(), Character.ENCLOSING_MARK.toInt(), Character.FORMAT.toInt() -> return 0
    }
    if (cp in 0x1160..0x11FF) return 0 // Hangul jamo medial vowels and finals
    return if (wide(cp)) 2 else 1
}

private val WIDE = intArrayOf(
    0x1100, 0x115F, 0x231A, 0x231B, 0x2329, 0x232A, 0x23E9, 0x23EC, 0x23F0, 0x23F0, 0x23F3, 0x23F3,
    0x25FD, 0x25FE, 0x2614, 0x2615, 0x2648, 0x2653, 0x267F, 0x267F, 0x2693, 0x2693, 0x26A1, 0x26A1,
    0x26AA, 0x26AB, 0x26BD, 0x26BE, 0x26C4, 0x26C5, 0x26CE, 0x26CE, 0x26D4, 0x26D4, 0x26EA, 0x26EA,
    0x26F2, 0x26F3, 0x26F5, 0x26F5, 0x26FA, 0x26FA, 0x26FD, 0x26FD, 0x2705, 0x2705, 0x270A, 0x270B,
    0x2728, 0x2728, 0x274C, 0x274C, 0x274E, 0x274E, 0x2753, 0x2755, 0x2757, 0x2757, 0x2795, 0x2797,
    0x27B0, 0x27B0, 0x27BF, 0x27BF, 0x2B1B, 0x2B1C, 0x2B50, 0x2B50, 0x2B55, 0x2B55, 0x2E80, 0x303E,
    0x3041, 0x33FF, 0x3400, 0x4DBF, 0x4E00, 0x9FFF, 0xA000, 0xA4CF, 0xA960, 0xA97F, 0xAC00, 0xD7A3,
    0xF900, 0xFAFF, 0xFE10, 0xFE19, 0xFE30, 0xFE6F, 0xFF00, 0xFF60, 0xFFE0, 0xFFE6,
    0x16FE0, 0x16FE4, 0x17000, 0x18CFF, 0x1B000, 0x1B2FF, 0x1F004, 0x1F004, 0x1F0CF, 0x1F0CF,
    0x1F18E, 0x1F18E, 0x1F191, 0x1F19A, 0x1F200, 0x1F251, 0x1F300, 0x1F320, 0x1F32D, 0x1F335,
    0x1F337, 0x1F37C, 0x1F37E, 0x1F393, 0x1F3A0, 0x1F3CA, 0x1F3CF, 0x1F3D3, 0x1F3E0, 0x1F3F0,
    0x1F3F4, 0x1F3F4, 0x1F3F8, 0x1F43E, 0x1F440, 0x1F440, 0x1F442, 0x1F4FC, 0x1F4FF, 0x1F53D,
    0x1F54B, 0x1F54E, 0x1F550, 0x1F567, 0x1F57A, 0x1F57A, 0x1F595, 0x1F596, 0x1F5A4, 0x1F5A4,
    0x1F5FB, 0x1F64F, 0x1F680, 0x1F6C5, 0x1F6CC, 0x1F6CC, 0x1F6D0, 0x1F6D2, 0x1F6D5, 0x1F6D7,
    0x1F6DC, 0x1F6DF, 0x1F6EB, 0x1F6EC, 0x1F6F4, 0x1F6FC, 0x1F7E0, 0x1F7EB, 0x1F7F0, 0x1F7F0,
    0x1F90C, 0x1F93A, 0x1F93C, 0x1F945, 0x1F947, 0x1F9FF, 0x1FA70, 0x1FAFF, 0x20000, 0x2FFFD, 0x30000, 0x3FFFD,
)

private fun wide(cp: Int): Boolean {
    var lo = 0
    var hi = WIDE.size / 2 - 1
    while (lo <= hi) {
        val mid = (lo + hi) ushr 1
        when {
            cp < WIDE[mid * 2] -> hi = mid - 1
            cp > WIDE[mid * 2 + 1] -> lo = mid + 1
            else -> return true
        }
    }
    return false
}

/** DEC special graphics (`ESC ( 0`): line drawing for `j`..`x` and friends. */
fun decGraphics(cp: Int): Int = when (cp) {
    '`'.code -> 0x25C6; 'a'.code -> 0x2592; 'b'.code -> 0x2409; 'c'.code -> 0x240C; 'd'.code -> 0x240D
    'e'.code -> 0x240A; 'f'.code -> 0x00B0; 'g'.code -> 0x00B1; 'h'.code -> 0x2424; 'i'.code -> 0x240B
    'j'.code -> 0x2518; 'k'.code -> 0x2510; 'l'.code -> 0x250C; 'm'.code -> 0x2514; 'n'.code -> 0x253C
    'o'.code -> 0x23BA; 'p'.code -> 0x23BB; 'q'.code -> 0x2500; 'r'.code -> 0x23BC; 's'.code -> 0x23BD
    't'.code -> 0x251C; 'u'.code -> 0x2524; 'v'.code -> 0x2534; 'w'.code -> 0x252C; 'x'.code -> 0x2502
    'y'.code -> 0x2264; 'z'.code -> 0x2265; '{'.code -> 0x03C0; '|'.code -> 0x2260; '}'.code -> 0x00A3
    '~'.code -> 0x00B7; else -> cp
}
