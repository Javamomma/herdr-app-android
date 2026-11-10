package herdr.term

import java.io.ByteArrayOutputStream

/** What a program asked the mouse to report. */
enum class Mouse { OFF, CLICKS, DRAG, ALL }

enum class CursorShape { BLOCK, UNDERLINE, BAR }

/**
 * A VT/xterm terminal: bytes in, a screen of styled cells out. Plain Kotlin
 * (no Android), so it is tested on the JVM. Bounded everywhere: parameters,
 * sequence and string lengths, scrollback.
 *
 * Not thread-safe: the caller holds one lock around [write] and any reads.
 */
class Emulator(cols: Int, rows: Int, private val scrollbackLimit: Int = 5000) {
    var cols = cols.coerceIn(1, MAX_DIM); private set
    var rows = rows.coerceIn(1, MAX_DIM); private set

    private var main = Array(this.rows) { Line(this.cols) }
    private var alt = Array(this.rows) { Line(this.cols) }
    var altScreen = false; private set
    private val screen get() = if (altScreen) alt else main
    /** Rows that scrolled off the top of the main screen, oldest first. */
    val scrollback = ArrayDeque<Line>()
    /** Rises when scrollback gains lines: a view scrolled up keeps its place. */
    var scrollbackAdded = 0L; private set

    var cursorRow = 0; private set
    var cursorCol = 0; private set
    private var pendingWrap = false
    private var style = Style.DEFAULT
    private var top = 0
    private var bottom = this.rows - 1
    private var tabs = BooleanArray(this.cols) { it % 8 == 0 && it > 0 }

    // Modes.
    var appCursorKeys = false; private set
    var appKeypad = false; private set
    var bracketedPaste = false; private set
    var focusReports = false; private set
    var cursorVisible = true; private set
    var cursorBlink = true; private set
    var cursorShape = CursorShape.BLOCK; private set
    var mouse = Mouse.OFF; private set
    var mouseSgr = false; private set
    var reverseVideo = false; private set
    var synchronizedOutput = false; private set
    private var autowrap = true
    private var originMode = false
    private var insertMode = false
    private var newlineMode = false
    private var g0Graphics = false
    private var g1Graphics = false
    private var shiftOut = false

    private class Saved(val row: Int, val col: Int, val style: Long, val origin: Boolean, val wrap: Boolean, val g0: Boolean, val g1: Boolean, val shift: Boolean)
    private var savedMain: Saved? = null
    private var savedAlt: Saved? = null

    var title = ""; private set
    var onTitle: ((String) -> Unit)? = null
    var onBell: (() -> Unit)? = null
    private val replies = ByteArrayOutputStream()
    /** Rises with every change, so a view knows to redraw. */
    var changes = 0L; private set

    // Parser.
    private enum class State { GROUND, ESC, ESC_INTER, CSI, CSI_IGNORE, OSC, STRING_IGNORE, DCS }
    private var state = State.GROUND
    private val params = IntArray(MAX_PARAMS)
    /** Per parameter: it was followed by `:` sub-parameters (kept in [sub]). */
    private val sub = Array(MAX_PARAMS) { IntArray(MAX_SUB) }
    private val subCount = IntArray(MAX_PARAMS)
    private var paramCount = 0
    private var paramStarted = false
    private var inSub = false
    private var privateMarker = 0
    private val intermediates = StringBuilder()
    private val osc = ByteArrayOutputStream()
    private var oscOverflow = false
    private var stringEsc = false
    private var utf8Need = 0
    private var utf8Cp = 0
    private var utf8Min = 0
    private var lastPrinted = -1

    /** Answers to the program's queries (DA, DSR, ...), to send back as automatic input. */
    fun takeReplies(): ByteArray = replies.toByteArray().also { replies.reset() }

    fun line(row: Int): Line = screen[row]

    /** A row by index from the oldest scrollback line (0) to the last screen row. */
    fun lineAt(index: Int): Line? {
        val sb = if (altScreen) 0 else scrollback.size
        return when {
            index < 0 -> null
            index < sb -> scrollback[index]
            index - sb < rows -> screen[index - sb]
            else -> null
        }
    }

    /** Lines above the screen that can be scrolled to (none on the alternate screen). */
    val historySize get() = if (altScreen) 0 else scrollback.size

    fun screenText(): String = (0 until rows).joinToString("\n") { screen[it].string(cols).trimEnd() }

    // ---- input -------------------------------------------------------------

    fun write(bytes: ByteArray, off: Int = 0, len: Int = bytes.size - off) {
        for (i in off until off + len) feed(bytes[i].toInt() and 0xff)
        changes++
    }

    fun write(text: String) = write(text.toByteArray())

    private fun feed(b: Int) {
        // Strings end on BEL or ST and take everything else as content.
        when (state) {
            State.OSC, State.STRING_IGNORE, State.DCS -> return stringByte(b)
            else -> {}
        }
        if (b == 0x1b) {
            utf8Need = 0
            enterEsc()
            return
        }
        if (b == 0x18 || b == 0x1a) { // CAN, SUB: abandon the sequence
            utf8Need = 0
            if (state != State.GROUND) state = State.GROUND else if (b == 0x1a) print(0xFFFD)
            return
        }
        if (b < 0x20 || b == 0x7f) {
            if (state == State.GROUND && utf8Need > 0) {
                utf8Need = 0
                print(0xFFFD)
            }
            if (b != 0x7f) control(b)
            return
        }
        when (state) {
            State.GROUND -> ground(b)
            State.ESC -> escByte(b)
            State.ESC_INTER -> escInter(b)
            State.CSI -> csiByte(b)
            State.CSI_IGNORE -> if (b in 0x40..0x7e) state = State.GROUND
            else -> {}
        }
    }

    private fun enterEsc() {
        state = State.ESC
        intermediates.setLength(0)
    }

    private fun ground(b: Int) {
        if (utf8Need > 0) {
            if (b and 0xc0 == 0x80) {
                utf8Cp = (utf8Cp shl 6) or (b and 0x3f)
                if (--utf8Need == 0) {
                    val cp = utf8Cp
                    print(if (cp < utf8Min || cp > 0x10FFFF || cp in 0xD800..0xDFFF) 0xFFFD else cp)
                }
                return
            }
            utf8Need = 0
            print(0xFFFD)
        }
        when {
            b < 0x80 -> print(b)
            b and 0xe0 == 0xc0 -> { utf8Need = 1; utf8Cp = b and 0x1f; utf8Min = 0x80 }
            b and 0xf0 == 0xe0 -> { utf8Need = 2; utf8Cp = b and 0x0f; utf8Min = 0x800 }
            b and 0xf8 == 0xf0 -> { utf8Need = 3; utf8Cp = b and 0x07; utf8Min = 0x10000 }
            else -> print(0xFFFD)
        }
    }

    private fun control(b: Int) {
        when (b) {
            0x07 -> onBell?.invoke()
            0x08 -> { if (cursorCol > 0) cursorCol--; pendingWrap = false }
            0x09 -> tab(1)
            0x0a, 0x0b, 0x0c -> { index(); if (newlineMode) cursorCol = 0 }
            0x0d -> { cursorCol = 0; pendingWrap = false }
            0x0e -> shiftOut = true
            0x0f -> shiftOut = false
        }
    }

    private fun escByte(b: Int) {
        if (b in 0x20..0x2f) {
            intermediates.append(b.toChar())
            state = State.ESC_INTER
            return
        }
        state = State.GROUND
        when (b.toChar()) {
            '[' -> startCsi()
            ']' -> startString(State.OSC)
            'P' -> startString(State.DCS)
            'X', '^', '_' -> startString(State.STRING_IGNORE)
            '7' -> saveCursor()
            '8' -> restoreCursor()
            'D' -> index()
            'E' -> { index(); cursorCol = 0 }
            'H' -> if (cursorCol < cols) tabs[cursorCol] = true
            'M' -> reverseIndex()
            'c' -> reset()
            '=' -> appKeypad = true
            '>' -> appKeypad = false
            '\\' -> {}
        }
    }

    private fun escInter(b: Int) {
        if (b in 0x20..0x2f) {
            if (intermediates.length < 4) intermediates.append(b.toChar())
            return
        }
        state = State.GROUND
        val i = intermediates.toString()
        when {
            i == "(" -> g0Graphics = b == '0'.code
            i == ")" -> g1Graphics = b == '0'.code
            i == "#" && b == '8'.code -> for (r in 0 until rows) screen[r].let { l -> l.clear(0, cols, Style.DEFAULT); for (c in 0 until cols) l.chars[c] = 'E'.code }
        }
    }

    private fun startString(s: State) {
        state = s
        osc.reset()
        oscOverflow = false
        stringEsc = false
    }

    private fun stringByte(b: Int) {
        if (stringEsc) {
            stringEsc = false
            if (b == '\\'.code) return endString()
            // Another escape inside a string: the string ends, the escape begins.
            endString()
            enterEsc()
            feed(b)
            return
        }
        when (b) {
            0x1b -> stringEsc = true
            0x07 -> endString()
            0x18, 0x1a -> state = State.GROUND
            else -> if (state == State.OSC) {
                if (osc.size() < MAX_STRING) osc.write(b) else oscOverflow = true
            }
        }
    }

    private fun endString() {
        val s = state
        state = State.GROUND
        if (s != State.OSC || oscOverflow) return
        val text = osc.toString(Charsets.UTF_8)
        val code = text.substringBefore(';')
        val rest = text.substringAfter(';', "")
        when (code) {
            "0", "2" -> {
                title = rest.filter { !it.isISOControl() }.take(256)
                onTitle?.invoke(title)
            }
        }
    }

    private fun startCsi() {
        state = State.CSI
        paramCount = 0
        paramStarted = false
        inSub = false
        privateMarker = 0
        intermediates.setLength(0)
        params.fill(0)
        subCount.fill(0)
    }

    private fun csiByte(b: Int) {
        when (b) {
            in '0'.code..'9'.code -> {
                if (intermediates.isNotEmpty()) { state = State.CSI_IGNORE; return }
                if (!paramStarted) {
                    paramStarted = true
                    if (paramCount == 0) paramCount = 1
                }
                val i = paramCount - 1
                if (inSub) {
                    val k = subCount[i] - 1
                    if (k >= 0) sub[i][k] = minOf(sub[i][k] * 10 + (b - '0'.code), MAX_PARAM)
                } else {
                    params[i] = minOf(params[i] * 10 + (b - '0'.code), MAX_PARAM)
                }
            }
            ';'.code -> {
                if (paramCount == 0) paramCount = 1
                if (paramCount < MAX_PARAMS) paramCount++ else { state = State.CSI_IGNORE; return }
                paramStarted = true
                inSub = false
            }
            ':'.code -> {
                if (paramCount == 0) paramCount = 1
                val i = paramCount - 1
                if (subCount[i] < MAX_SUB) sub[i][subCount[i]++] = 0
                paramStarted = true
                inSub = true
            }
            in 0x3c..0x3f -> if (paramCount == 0 && privateMarker == 0) privateMarker = b else state = State.CSI_IGNORE
            in 0x20..0x2f -> if (intermediates.length < 4) intermediates.append(b.toChar()) else state = State.CSI_IGNORE
            in 0x40..0x7e -> {
                state = State.GROUND
                csi(b.toChar())
            }
            else -> state = State.CSI_IGNORE
        }
    }

    private fun p(i: Int, default: Int = 0): Int = if (i < paramCount && params[i] != 0) params[i] else default
    private fun p1(i: Int) = p(i, 1)

    private fun csi(f: Char) {
        val inter = intermediates.toString()
        if (privateMarker == '?'.code) {
            when (f) {
                'h' -> for (i in 0 until maxOf(paramCount, 1)) decMode(params[i], true)
                'l' -> for (i in 0 until maxOf(paramCount, 1)) decMode(params[i], false)
                'n' -> if (p(0) == 6) reply("\u001b[?${cursorRow + 1};${cursorCol + 1}R")
                'p' -> if (inter == "$") reply("\u001b[?${p(0)};${decModeState(p(0))}\$y")
            }
            return
        }
        if (privateMarker == '>'.code) {
            if (f == 'c') reply("\u001b[>1;10;0c")
            return
        }
        if (privateMarker != 0) return
        when (inter) {
            "" -> {}
            " " -> {
                if (f == 'q') {
                    val n = p(0)
                    cursorShape = when (n) { 3, 4 -> CursorShape.UNDERLINE; 5, 6 -> CursorShape.BAR; else -> CursorShape.BLOCK }
                    cursorBlink = n == 0 || n % 2 == 1
                }
                return
            }
            "!" -> { if (f == 'p') softReset(); return }
            "$" -> { if (f == 'p') reply("\u001b[${p(0)};${if (p(0) == 4) (if (insertMode) 1 else 2) else 0}\$y"); return }
            else -> return
        }
        when (f) {
            '@' -> insertChars(p1(0))
            'A' -> moveTo(cursorRow - p1(0), cursorCol, clampToRegion = true)
            'B', 'e' -> moveTo(cursorRow + p1(0), cursorCol, clampToRegion = true)
            'C', 'a' -> moveTo(cursorRow, cursorCol + p1(0))
            'D' -> moveTo(cursorRow, cursorCol - p1(0))
            'E' -> moveTo(cursorRow + p1(0), 0, clampToRegion = true)
            'F' -> moveTo(cursorRow - p1(0), 0, clampToRegion = true)
            'G', '`' -> moveTo(cursorRow, p1(0) - 1)
            'H', 'f' -> cup(p1(0) - 1, p1(1) - 1)
            'I' -> tab(p1(0))
            'J' -> eraseDisplay(p(0))
            'K' -> eraseLine(p(0))
            'L' -> insertLines(p1(0))
            'M' -> deleteLines(p1(0))
            'P' -> deleteChars(p1(0))
            'S' -> scrollUp(top, bottom, p1(0))
            'T' -> scrollDown(top, bottom, p1(0))
            'X' -> { val l = screen[cursorRow]; l.clear(cursorCol, minOf(cols, cursorCol + p1(0)), Style.erased(style)); pendingWrap = false }
            'Z' -> backTab(p1(0))
            'b' -> if (lastPrinted >= 0) repeat(minOf(p1(0), cols * rows)) { print(lastPrinted) }
            'c' -> if (p(0) == 0) reply("\u001b[?62;22c")
            'd' -> cup(p1(0) - 1, cursorCol)
            'g' -> when (p(0)) { 0 -> if (cursorCol < cols) tabs[cursorCol] = false; 3 -> tabs.fill(false) }
            'h' -> for (i in 0 until maxOf(paramCount, 1)) ansiMode(params[i], true)
            'l' -> for (i in 0 until maxOf(paramCount, 1)) ansiMode(params[i], false)
            'm' -> sgr()
            'n' -> when (p(0)) {
                5 -> reply("\u001b[0n")
                6 -> reply("\u001b[${(if (originMode) cursorRow - top else cursorRow) + 1};${cursorCol + 1}R")
            }
            'r' -> {
                val t = p1(0) - 1
                val b = (if (paramCount >= 2 && params[1] != 0) params[1] else rows) - 1
                if (t < b && b < rows) {
                    top = t
                    bottom = b
                    cup(0, 0)
                }
            }
            's' -> saveCursor()
            'u' -> restoreCursor()
            't' -> when (p(0)) {
                18 -> reply("\u001b[8;$rows;${cols}t")
                22, 23 -> {}
            }
        }
    }

    private fun reply(s: String) {
        if (replies.size() < MAX_REPLIES) replies.write(s.toByteArray())
    }

    private fun ansiMode(n: Int, on: Boolean) {
        when (n) {
            4 -> insertMode = on
            20 -> newlineMode = on
        }
    }

    private fun decModeState(n: Int): Int {
        val v: Boolean = when (n) {
            1 -> appCursorKeys; 6 -> originMode; 7 -> autowrap; 12 -> cursorBlink; 25 -> cursorVisible
            1000 -> mouse == Mouse.CLICKS; 1002 -> mouse == Mouse.DRAG; 1003 -> mouse == Mouse.ALL
            1004 -> focusReports; 1006 -> mouseSgr; 1049, 1047, 47 -> altScreen; 2004 -> bracketedPaste
            2026 -> synchronizedOutput; 5 -> reverseVideo
            else -> return 0
        }
        return if (v) 1 else 2
    }

    private fun decMode(n: Int, on: Boolean) {
        when (n) {
            1 -> appCursorKeys = on
            5 -> reverseVideo = on
            6 -> { originMode = on; cup(0, 0) }
            7 -> autowrap = on
            12 -> cursorBlink = on
            25 -> cursorVisible = on
            47, 1047 -> setAlt(on, clear = n == 1047 && !on)
            1048 -> if (on) saveCursor() else restoreCursor()
            1049 -> if (on) {
                saveCursor()
                setAlt(true, clear = false)
                clearScreen(alt)
            } else {
                setAlt(false, clear = false)
                restoreCursor()
            }
            1000 -> mouse = if (on) Mouse.CLICKS else Mouse.OFF
            1002 -> mouse = if (on) Mouse.DRAG else Mouse.OFF
            1003 -> mouse = if (on) Mouse.ALL else Mouse.OFF
            1004 -> focusReports = on
            1006 -> mouseSgr = on
            2004 -> bracketedPaste = on
            2026 -> synchronizedOutput = on
        }
    }

    private fun setAlt(on: Boolean, clear: Boolean) {
        if (on == altScreen) return
        if (!on && clear) clearScreen(alt)
        altScreen = on
        pendingWrap = false
    }

    private fun clearScreen(s: Array<Line>) {
        for (l in s) {
            l.clear(0, l.capacity, Style.erased(style))
            l.wrapped = false
        }
    }

    private fun sgr() {
        if (paramCount == 0) {
            style = Style.DEFAULT
            return
        }
        var fg = Style.fg(style)
        var bg = Style.bg(style)
        var a = Style.attrs(style)
        var i = 0
        while (i < paramCount) {
            val n = params[i]
            when (n) {
                0 -> { fg = Color.DEFAULT; bg = Color.DEFAULT; a = 0 }
                1 -> a = a or Attr.BOLD
                2 -> a = a or Attr.DIM
                3 -> a = a or Attr.ITALIC
                4 -> a = if (subCount[i] > 0 && sub[i][0] == 0) a and Attr.UNDERLINE.inv() else a or Attr.UNDERLINE
                5, 6 -> a = a or Attr.BLINK
                7 -> a = a or Attr.INVERSE
                8 -> a = a or Attr.HIDDEN
                9 -> a = a or Attr.STRIKE
                21 -> a = a or Attr.UNDERLINE
                22 -> a = a and (Attr.BOLD or Attr.DIM).inv()
                23 -> a = a and Attr.ITALIC.inv()
                24 -> a = a and Attr.UNDERLINE.inv()
                25 -> a = a and Attr.BLINK.inv()
                27 -> a = a and Attr.INVERSE.inv()
                28 -> a = a and Attr.HIDDEN.inv()
                29 -> a = a and Attr.STRIKE.inv()
                in 30..37 -> fg = Color.palette(n - 30)
                39 -> fg = Color.DEFAULT
                in 40..47 -> bg = Color.palette(n - 40)
                49 -> bg = Color.DEFAULT
                in 90..97 -> fg = Color.palette(n - 90 + 8)
                in 100..107 -> bg = Color.palette(n - 100 + 8)
                38, 48, 58 -> {
                    val (color, used) = extendedColor(i)
                    if (color != null) when (n) { 38 -> fg = color; 48 -> bg = color }
                    i += used
                }
            }
            i++
        }
        style = Style.of(fg, bg, a)
    }

    /** `38;5;n`, `38;2;r;g;b`, or the colon forms `38:5:n`, `38:2::r:g:b`; returns the color and parameters used after [i]. */
    private fun extendedColor(i: Int): Pair<Int?, Int> {
        if (subCount[i] > 0) {
            val s = sub[i]
            return when (s[0]) {
                5 -> (if (subCount[i] >= 2) Color.palette(s[1]) else null) to 0
                2 -> {
                    // With a color space id (38:2:id:r:g:b) or without (38:2:r:g:b).
                    val k = if (subCount[i] >= 5) 2 else 1
                    (if (subCount[i] >= k + 3) Color.rgb(s[k], s[k + 1], s[k + 2]) else null) to 0
                }
                else -> null to 0
            }
        }
        return when (if (i + 1 < paramCount) params[i + 1] else -1) {
            5 -> (if (i + 2 < paramCount) Color.palette(params[i + 2]) else null) to 2
            2 -> (if (i + 4 < paramCount) Color.rgb(params[i + 2], params[i + 3], params[i + 4]) else null) to 4
            else -> null to 0
        }
    }

    // ---- screen operations -------------------------------------------------

    private fun print(cp0: Int) {
        var cp = cp0
        val graphics = if (shiftOut) g1Graphics else g0Graphics
        if (graphics && cp in 0x5f..0x7e) cp = decGraphics(cp)
        val w = cellWidth(cp).let { if (it == 2 && cols < 2) 1 else it }
        if (w == 0) {
            combine(cp)
            return
        }
        lastPrinted = cp
        if (pendingWrap && autowrap) {
            screen[cursorRow].wrapped = true
            index()
            cursorCol = 0
        }
        pendingWrap = false
        if (w == 2 && cursorCol == cols - 1) {
            if (!autowrap) return
            screen[cursorRow].clear(cursorCol, cols, Style.erased(style))
            screen[cursorRow].wrapped = true
            index()
            cursorCol = 0
        }
        val line = screen[cursorRow]
        if (insertMode) shiftRight(line, cursorCol, w)
        fixWideAt(line, cursorCol)
        if (w == 2) fixWideAt(line, cursorCol + 1)
        line.chars[cursorCol] = cp
        line.styles[cursorCol] = style
        line.extra?.remove(cursorCol)
        if (w == 2) {
            line.chars[cursorCol + 1] = WIDE_TAIL
            line.styles[cursorCol + 1] = style
            line.extra?.remove(cursorCol + 1)
        }
        if (cursorCol + w >= cols) {
            cursorCol = cols - 1
            pendingWrap = autowrap
        } else {
            cursorCol += w
        }
    }

    /** Writing over half of a wide character blanks its other half. */
    private fun fixWideAt(line: Line, col: Int) {
        if (col >= cols) return
        if (line.chars[col] == WIDE_TAIL && col > 0) line.chars[col - 1] = ' '.code
        if (col + 1 < cols && line.chars[col + 1] == WIDE_TAIL && line.chars[col] != WIDE_TAIL) line.chars[col + 1] = ' '.code
    }

    private fun combine(cp: Int) {
        val line = screen[cursorRow]
        var col = if (pendingWrap) cursorCol else cursorCol - 1
        if (col < 0) return
        if (line.chars[col] == WIDE_TAIL && col > 0) col--
        val e = line.extra ?: HashMap<Int, String>().also { line.extra = it }
        val base = e[col] ?: String(Character.toChars(line.chars[col]))
        if (base.length < MAX_CLUSTER) e[col] = base + String(Character.toChars(cp))
    }

    private fun shiftRight(line: Line, at: Int, n: Int) {
        for (c in cols - 1 downTo at + n) {
            line.chars[c] = line.chars[c - n]
            line.styles[c] = line.styles[c - n]
        }
        line.extra = line.extra?.let { e -> HashMap(e.filterKeys { it < at }.plus(e.filterKeys { it >= at && it + n < cols }.mapKeys { it.key + n })) }
    }

    private fun moveTo(row: Int, col: Int, clampToRegion: Boolean = false) {
        val (lo, hi) = if (clampToRegion && cursorRow in top..bottom) top to bottom else 0 to rows - 1
        cursorRow = row.coerceIn(lo, hi)
        cursorCol = col.coerceIn(0, cols - 1)
        pendingWrap = false
    }

    private fun cup(row: Int, col: Int) {
        cursorRow = if (originMode) (row + top).coerceIn(top, bottom) else row.coerceIn(0, rows - 1)
        cursorCol = col.coerceIn(0, cols - 1)
        pendingWrap = false
    }

    private fun index() {
        if (cursorRow == bottom) scrollUp(top, bottom, 1)
        else if (cursorRow < rows - 1) cursorRow++
    }

    private fun reverseIndex() {
        if (cursorRow == top) scrollDown(top, bottom, 1)
        else if (cursorRow > 0) cursorRow--
    }

    private fun blank(): Line = Line(cols).also { it.clear(0, cols, Style.erased(style)) }

    private fun scrollUp(t: Int, b: Int, n0: Int) {
        val s = screen
        val n = n0.coerceIn(0, b - t + 1)
        for (k in 0 until n) {
            val gone = s[t]
            if (!altScreen && t == 0) pushScrollback(gone)
            for (r in t until b) s[r] = s[r + 1]
            s[b] = blank()
        }
        pendingWrap = false
    }

    private fun scrollDown(t: Int, b: Int, n0: Int) {
        val s = screen
        val n = n0.coerceIn(0, b - t + 1)
        for (k in 0 until n) {
            for (r in b downTo t + 1) s[r] = s[r - 1]
            s[t] = blank()
        }
        pendingWrap = false
    }

    private fun pushScrollback(line: Line) {
        if (scrollbackLimit <= 0) return
        scrollback.addLast(line.trimmed())
        while (scrollback.size > scrollbackLimit) scrollback.removeFirst()
        scrollbackAdded++
    }

    private fun insertLines(n: Int) {
        if (cursorRow !in top..bottom) return
        scrollDown(cursorRow, bottom, n)
        cursorCol = 0
    }

    private fun deleteLines(n: Int) {
        if (cursorRow !in top..bottom) return
        val s = screen
        val count = n.coerceIn(0, bottom - cursorRow + 1)
        for (k in 0 until count) {
            for (r in cursorRow until bottom) s[r] = s[r + 1]
            s[bottom] = blank()
        }
        cursorCol = 0
        pendingWrap = false
    }

    private fun insertChars(n0: Int) {
        val line = screen[cursorRow]
        val n = n0.coerceIn(0, cols - cursorCol)
        shiftRight(line, cursorCol, n)
        line.clear(cursorCol, cursorCol + n, Style.erased(style))
        pendingWrap = false
    }

    private fun deleteChars(n0: Int) {
        val line = screen[cursorRow]
        val n = n0.coerceIn(0, cols - cursorCol)
        for (c in cursorCol until cols - n) {
            line.chars[c] = line.chars[c + n]
            line.styles[c] = line.styles[c + n]
        }
        line.extra = line.extra?.let { e -> HashMap(e.filterKeys { it < cursorCol }.plus(e.filterKeys { it >= cursorCol + n }.mapKeys { it.key - n })) }
        line.clear(cols - n, cols, Style.erased(style))
        if (line.chars[cursorCol] == WIDE_TAIL) line.chars[cursorCol] = ' '.code
        pendingWrap = false
    }

    private fun eraseLine(mode: Int) {
        val line = screen[cursorRow]
        val e = Style.erased(style)
        when (mode) {
            0 -> { line.clear(cursorCol, line.capacity, e); line.wrapped = false }
            1 -> line.clear(0, cursorCol + 1, e)
            2 -> { line.clear(0, line.capacity, e); line.wrapped = false }
        }
        pendingWrap = false
    }

    private fun eraseDisplay(mode: Int) {
        val s = screen
        val e = Style.erased(style)
        when (mode) {
            0 -> {
                eraseLine(0)
                for (r in cursorRow + 1 until rows) { s[r].clear(0, s[r].capacity, e); s[r].wrapped = false }
            }
            1 -> {
                for (r in 0 until cursorRow) { s[r].clear(0, s[r].capacity, e); s[r].wrapped = false }
                eraseLine(1)
            }
            2 -> clearScreen(s)
            3 -> if (!altScreen) { scrollback.clear(); scrollbackAdded++ }
        }
        pendingWrap = false
    }

    private fun tab(n: Int) {
        var c = cursorCol
        repeat(n) {
            c++
            while (c < cols - 1 && !tabs[c]) c++
        }
        cursorCol = c.coerceAtMost(cols - 1)
        pendingWrap = false
    }

    private fun backTab(n: Int) {
        var c = cursorCol
        repeat(n) {
            c--
            while (c > 0 && !tabs[c]) c--
        }
        cursorCol = c.coerceAtLeast(0)
        pendingWrap = false
    }

    private fun saveCursor() {
        val s = Saved(cursorRow, cursorCol, style, originMode, pendingWrap, g0Graphics, g1Graphics, shiftOut)
        if (altScreen) savedAlt = s else savedMain = s
    }

    private fun restoreCursor() {
        val s = (if (altScreen) savedAlt else savedMain) ?: Saved(0, 0, Style.DEFAULT, false, false, false, false, false)
        cursorRow = s.row.coerceIn(0, rows - 1)
        cursorCol = s.col.coerceIn(0, cols - 1)
        style = s.style
        originMode = s.origin
        pendingWrap = s.wrap
        g0Graphics = s.g0
        g1Graphics = s.g1
        shiftOut = s.shift
    }

    private fun softReset() {
        cursorVisible = true
        originMode = false
        autowrap = true
        insertMode = false
        appCursorKeys = false
        appKeypad = false
        top = 0
        bottom = rows - 1
        style = Style.DEFAULT
        g0Graphics = false
        g1Graphics = false
        shiftOut = false
        savedMain = null
        savedAlt = null
        pendingWrap = false
    }

    /** RIS: everything back to how a new terminal starts (scrollback kept). */
    fun reset() {
        softReset()
        altScreen = false
        clearScreen(main)
        clearScreen(alt)
        cursorRow = 0
        cursorCol = 0
        tabs = BooleanArray(cols) { it % 8 == 0 && it > 0 }
        bracketedPaste = false
        focusReports = false
        mouse = Mouse.OFF
        mouseSgr = false
        reverseVideo = false
        synchronizedOutput = false
        cursorShape = CursorShape.BLOCK
        cursorBlink = true
        newlineMode = false
        state = State.GROUND
        utf8Need = 0
        changes++
    }

    /** Forgets everything, scrollback too: a fresh paint follows (a reattach). */
    fun clearAll() {
        reset()
        scrollback.clear()
        scrollbackAdded++
        title = ""
    }

    /**
     * New size. Rows keep their cells beyond the new width (widening shows
     * them again); on the main screen, rows pushed off the top go to
     * scrollback and come back when it grows, keeping the cursor's row.
     */
    fun resize(newCols0: Int, newRows0: Int) {
        val newCols = newCols0.coerceIn(1, MAX_DIM)
        val newRows = newRows0.coerceIn(1, MAX_DIM)
        if (newCols == cols && newRows == rows) return
        for (l in main) l.ensure(newCols)
        for (l in alt) l.ensure(newCols)
        if (newRows < rows) {
            // Main screen: keep the cursor's row on screen, pushing older rows to scrollback.
            val drop = (cursorRowMain() + 1 - newRows).coerceAtLeast(0)
            for (k in 0 until drop) pushScrollback(main[k])
            main = Array(newRows) { r -> main.getOrNull(r + drop) ?: Line(newCols) }
            if (!altScreen) cursorRow = (cursorRow - drop).coerceIn(0, newRows - 1)
            else savedMain = savedMain?.let { Saved((it.row - drop).coerceAtLeast(0), it.col, it.style, it.origin, it.wrap, it.g0, it.g1, it.shift) }
            alt = Array(newRows) { r -> alt[r] }
            if (altScreen) cursorRow = cursorRow.coerceIn(0, newRows - 1)
        } else if (newRows > rows) {
            val pull = if (altScreen) 0 else minOf(newRows - rows, scrollback.size)
            val back = (0 until pull).map { scrollback.removeLast() }.reversed().onEach { it.ensure(newCols) }
            main = Array(newRows) { r -> if (r < pull) back[r] else main.getOrNull(r - pull) ?: Line(newCols) }
            if (pull > 0) scrollbackAdded++
            if (!altScreen) cursorRow += pull
            else savedMain = savedMain?.let { Saved(it.row + pull, it.col, it.style, it.origin, it.wrap, it.g0, it.g1, it.shift) }
            alt = Array(newRows) { r -> alt.getOrNull(r) ?: Line(newCols) }
        }
        for (l in main) l.ensure(newCols)
        for (l in alt) l.ensure(newCols)
        cols = newCols
        rows = newRows
        top = 0
        bottom = rows - 1
        tabs = BooleanArray(cols) { it % 8 == 0 && it > 0 }
        cursorRow = cursorRow.coerceIn(0, rows - 1)
        cursorCol = cursorCol.coerceIn(0, cols - 1)
        pendingWrap = false
        changes++
    }

    private fun cursorRowMain(): Int = if (altScreen) (savedMain?.row ?: 0).coerceIn(0, rows - 1) else cursorRow

    /** The text between two cells (row indexes as in [lineAt]), lines joined unless wrapped. */
    fun text(startIndex: Int, startCol: Int, endIndex: Int, endCol: Int): String {
        val sb = StringBuilder()
        for (i in startIndex..endIndex) {
            val l = lineAt(i) ?: continue
            val from = if (i == startIndex) startCol else 0
            val to = if (i == endIndex) minOf(endCol + 1, cols) else cols
            val part = StringBuilder()
            for (c in from until minOf(to, l.capacity)) l.text(c)?.let { part.append(it) }
            sb.append(if (l.wrapped && i != endIndex) part else part.trimEnd())
            if (i != endIndex && !l.wrapped) sb.append('\n')
        }
        return sb.toString()
    }

    companion object {
        const val MAX_DIM = 1000
        private const val MAX_PARAMS = 32
        private const val MAX_SUB = 6
        private const val MAX_PARAM = 65535
        private const val MAX_STRING = 64 * 1024
        private const val MAX_REPLIES = 64 * 1024
        private const val MAX_CLUSTER = 32
    }
}
