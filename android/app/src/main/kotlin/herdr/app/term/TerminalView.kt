package herdr.app.term

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.text.InputType
import android.view.GestureDetector
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.view.inputmethod.BaseInputConnection
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputMethodManager
import android.widget.OverScroller
import herdr.term.Attr
import herdr.term.Color
import herdr.term.CursorShape
import herdr.term.Emulator
import herdr.term.Key
import herdr.term.Mods
import herdr.term.Mouse
import herdr.term.Style
import herdr.term.WIDE_TAIL
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.roundToInt

/**
 * Draws one [Emulator] on a Canvas and turns touch and keys into terminal
 * input. Only visible rows are drawn, in runs of one style; paints and font
 * metrics are cached. Scrolling moves a pixel offset over the scrollback
 * (with fling); a program that asked for the mouse gets wheel reports instead.
 */
@SuppressLint("ViewConstructor")
class TerminalView(context: Context, private val term: TerminalController) : View(context) {
    private val emu: Emulator get() = term.emu
    private val text = Paint(Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG).apply { typeface = Typeface.MONOSPACE }
    private val fill = Paint()
    private val regular = Typeface.MONOSPACE
    private val bold = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
    private var cellW = 1f
    private var cellH = 1f
    private var ascent = 0f
    private val pad = 4f * resources.displayMetrics.density
    private val chars = CharArray(1024)

    /** How far the view is scrolled up into the scrollback, in pixels (0: following the bottom). */
    private var scrollPx = 0f
    private var lastHistory = 0
    private val scroller = OverScroller(context)
    private var wheelAccum = 0f
    private var focused = false

    // Selection, as (row index, column) pairs in [Emulator.lineAt] coordinates.
    private var selecting = false
    private var selStart: Pair<Int, Int>? = null
    private var selEnd: Pair<Int, Int>? = null

    init {
        isFocusable = true
        isFocusableInTouchMode = true
        setFontSize(term.fontPx)
    }

    fun setFontSize(sp: Int) {
        text.textSize = sp * resources.displayMetrics.scaledDensity
        val fm = text.fontMetrics
        cellW = text.measureText("M")
        cellH = (fm.descent - fm.ascent + fm.leading).let { kotlin.math.ceil(it) }
        ascent = -fm.ascent
        requestLayout()
        regrid()
        invalidate()
    }

    /** Cell size in device pixels, for the core's display facts. */
    fun cell(): IntArray = intArrayOf(cellW.roundToInt(), cellH.roundToInt())

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) = regrid()

    private fun regrid() {
        if (width == 0 || height == 0) return
        val cols = floor((width - 2 * pad) / cellW).toInt().coerceAtLeast(2)
        val rows = floor((height - 2 * pad) / cellH).toInt().coerceAtLeast(2)
        term.onGrid(cols, rows, cell())
    }

    fun scrollToBottom() {
        if (scrollPx != 0f) {
            scroller.forceFinished(true)
            scrollPx = 0f
            invalidate()
        }
    }

    // ---- drawing ---------------------------------------------------------------

    override fun onDraw(canvas: Canvas) {
        synchronized(emu) {
            val reverse = emu.reverseVideo
            val defBg = if (reverse) Palette.FG else Palette.BG
            val defFg = if (reverse) Palette.BG else Palette.FG
            canvas.drawColor(defBg)
            val history = emu.historySize
            // New lines arriving while scrolled up keep what's on screen in place.
            if (scrollPx > 0f && history > lastHistory) scrollPx += (history - lastHistory) * cellH
            lastHistory = history
            scrollPx = scrollPx.coerceIn(0f, history * cellH)
            val topF = history - scrollPx / cellH
            val first = floor(topF).toInt()
            for (k in 0..emu.rows) {
                val idx = first + k
                val line = emu.lineAt(idx) ?: continue
                val y = pad + (idx - topF) * cellH
                if (y > height) break
                drawLine(canvas, line, idx, y, defFg, defBg)
            }
            if (emu.cursorVisible) {
                val idx = history + emu.cursorRow
                val y = pad + (idx - topF) * cellH
                val x = pad + emu.cursorCol * cellW
                if (y < height) drawCursor(canvas, x, y)
            }
        }
    }

    private fun resolve(c: Int, fg: Boolean, boldText: Boolean, def: Int): Int = when {
        c == Color.DEFAULT -> def
        c and Color.RGB != 0 -> 0xFF000000.toInt() or (c and 0xFFFFFF)
        else -> {
            var i = c and 0xff
            if (fg && boldText && i < 8) i += 8
            Palette.colors[i]
        }
    }

    private fun selected(idx: Int, col: Int): Boolean {
        val (a, b) = orderedSelection() ?: return false
        if (idx < a.first || idx > b.first) return false
        if (idx == a.first && col < a.second) return false
        if (idx == b.first && col > b.second) return false
        return true
    }

    private fun drawLine(canvas: Canvas, line: herdr.term.Line, idx: Int, y: Float, defFg: Int, defBg: Int) {
        val cols = minOf(emu.cols, line.capacity)
        val baseline = y + ascent
        var c = 0
        while (c < cols) {
            val style = line.styles[c]
            val sel = selStart != null && selected(idx, c)
            // A run: plain ASCII of one style and selection state, drawn as one
            // string; anything else (wide, clusters, symbols) is one cell on its own.
            fun plain(k: Int) = line.chars[k] in 0x20..0x7e && line.extra?.containsKey(k) != true
            val ascii = plain(c)
            var end = c + 1
            if (ascii) {
                while (end < cols && plain(end) && line.styles[end] == style && (selStart == null || selected(idx, end) == sel)) end++
            } else if (end < cols && line.chars[end] == WIDE_TAIL) {
                end++
            }
            val a = Style.attrs(style)
            var fg = resolve(Style.fg(style), true, a and Attr.BOLD != 0, defFg)
            var bg = resolve(Style.bg(style), false, false, defBg)
            if (a and Attr.INVERSE != 0) fg = bg.also { bg = fg }
            if (sel) {
                bg = Palette.SELECTION
            }
            if (a and Attr.HIDDEN != 0) fg = bg
            val x = pad + c * cellW
            val w = (end - c) * cellW
            if (bg != defBg) {
                fill.color = bg
                canvas.drawRect(x, y, x + w, y + cellH, fill)
            }
            text.color = fg
            text.alpha = if (a and Attr.DIM != 0) 150 else 255
            text.typeface = if (a and Attr.BOLD != 0) bold else regular
            text.textSkewX = if (a and Attr.ITALIC != 0) -0.2f else 0f
            if (ascii) {
                var n = 0
                for (k in c until end) if (n < chars.size) chars[n++] = line.chars[k].toChar()
                if (n > 0 && !(n == 1 && chars[0] == ' ')) canvas.drawText(chars, 0, n, x, baseline, text)
            } else {
                line.text(c)?.let { s ->
                    // Glyphs from fallback fonts may be wider than the cell: squeeze them in.
                    val span = (end - c).coerceAtLeast(1) * cellW
                    val measured = text.measureText(s)
                    if (measured > span * 1.05f) text.textScaleX = span / measured
                    canvas.drawText(s, x, baseline, text)
                    text.textScaleX = 1f
                }
            }
            if (a and Attr.UNDERLINE != 0) {
                fill.color = fg
                canvas.drawRect(x, y + cellH - 2f, x + w, y + cellH - 0.5f, fill)
            }
            if (a and Attr.STRIKE != 0) {
                fill.color = fg
                canvas.drawRect(x, y + cellH / 2 - 0.75f, x + w, y + cellH / 2 + 0.75f, fill)
            }
            c = end
        }
        text.alpha = 255
    }

    private fun drawCursor(canvas: Canvas, x: Float, y: Float) {
        fill.color = Palette.CURSOR
        if (!focused) {
            fill.style = Paint.Style.STROKE
            fill.strokeWidth = 1.5f
            canvas.drawRect(x + 0.75f, y + 0.75f, x + cellW - 0.75f, y + cellH - 0.75f, fill)
            fill.style = Paint.Style.FILL
            return
        }
        when (emu.cursorShape) {
            CursorShape.BAR -> canvas.drawRect(x, y, x + 2.5f, y + cellH, fill)
            CursorShape.UNDERLINE -> canvas.drawRect(x, y + cellH - 3f, x + cellW, y + cellH, fill)
            CursorShape.BLOCK -> {
                canvas.drawRect(x, y, x + cellW, y + cellH, fill)
                val line = emu.line(emu.cursorRow)
                line.text(emu.cursorCol)?.takeIf { it.isNotBlank() }?.let {
                    text.color = Palette.BG
                    text.typeface = regular
                    text.textSkewX = 0f
                    canvas.drawText(it, x, y + ascent, text)
                }
            }
        }
    }

    // ---- touch -----------------------------------------------------------------

    private fun cellAt(x: Float, y: Float): Pair<Int, Int> {
        val history = emu.historySize
        val topF = history - scrollPx / cellH
        val idx = floor(topF + (y - pad) / cellH).toInt().coerceIn(0, history + emu.rows - 1)
        val col = floor((x - pad) / cellW).toInt().coerceIn(0, emu.cols - 1)
        return idx to col
    }

    private fun screenCell(x: Float, y: Float): Pair<Int, Int> {
        val col = floor((x - pad) / cellW).toInt().coerceIn(0, emu.cols - 1)
        val row = floor((y - pad) / cellH).toInt().coerceIn(0, emu.rows - 1)
        return row to col
    }

    private val gestures = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(e: MotionEvent): Boolean {
            scroller.forceFinished(true)
            wheelAccum = 0f
            return true
        }

        override fun onSingleTapUp(e: MotionEvent): Boolean {
            if (selStart != null) {
                clearSelection()
                return true
            }
            showKeyboard()
            val m = synchronized(emu) { emu.mouse to emu.mouseSgr }
            if (m.first != Mouse.OFF) {
                val (row, col) = screenCell(e.x, e.y)
                term.send(herdr.term.Keys.mouse(0, col, row, true, m.second))
                term.send(herdr.term.Keys.mouse(0, col, row, false, m.second))
            }
            return true
        }

        override fun onLongPress(e: MotionEvent) {
            performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
            synchronized(emu) {
                val (idx, col) = cellAt(e.x, e.y)
                val line = emu.lineAt(idx) ?: return
                // Start with the word under the finger.
                fun isWord(c: Int) = line.text(c)?.firstOrNull()?.let { !it.isWhitespace() } == true
                var a = col
                var b = col
                if (isWord(col)) {
                    while (a > 0 && isWord(a - 1)) a--
                    while (b < emu.cols - 1 && isWord(b + 1)) b++
                }
                selStart = idx to a
                selEnd = idx to b
            }
            selecting = true
            invalidate()
        }

        override fun onScroll(e1: MotionEvent?, e2: MotionEvent, dx: Float, dy: Float): Boolean {
            if (selecting) return true
            scrollBy(dy, e2)
            return true
        }

        override fun onFling(e1: MotionEvent?, e2: MotionEvent, vx: Float, vy: Float): Boolean {
            if (selecting || remoteScroll() != null) return true
            val max = (emu.historySize * cellH).toInt()
            scroller.fling(0, scrollPx.toInt(), 0, vy.toInt(), 0, 0, 0, max)
            postInvalidateOnAnimation()
            return true
        }
    })

    private val scale = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        private var start = 0f
        override fun onScaleBegin(detector: ScaleGestureDetector): Boolean {
            start = term.fontPx.toFloat()
            return true
        }

        override fun onScale(detector: ScaleGestureDetector): Boolean {
            start *= detector.scaleFactor
            val px = start.roundToInt().coerceIn(7, 32)
            if (px != term.fontPx) term.onPinch(px)
            return true
        }
    })

    /** Where a vertical drag goes when not scrolling history: the program's mouse wheel, or arrow keys on the alternate screen. */
    private fun remoteScroll(): String? = synchronized(emu) {
        when {
            emu.mouse != Mouse.OFF -> "wheel"
            emu.altScreen -> "arrows"
            else -> null
        }
    }

    private fun scrollBy(dy: Float, e: MotionEvent) {
        when (remoteScroll()) {
            null -> {
                val max = emu.historySize * cellH
                scrollPx = (scrollPx - dy).coerceIn(0f, max)
                invalidate()
            }
            else -> {
                // One report per touch event at most, one per line moved.
                wheelAccum += dy
                if (abs(wheelAccum) < cellH) return
                val up = wheelAccum < 0
                wheelAccum = 0f
                val (sgr, appCursor, mouseOn) = synchronized(emu) { Triple(emu.mouseSgr, emu.appCursorKeys, emu.mouse != Mouse.OFF) }
                if (mouseOn) {
                    val (row, col) = screenCell(e.x, e.y)
                    term.send(herdr.term.Keys.mouse(if (up) 64 else 65, col, row, true, sgr))
                } else {
                    term.send(herdr.term.Keys.encode(if (up) Key.UP else Key.DOWN, Mods(), appCursor))
                }
            }
        }
    }

    override fun computeScroll() {
        if (scroller.computeScrollOffset()) {
            scrollPx = scroller.currY.toFloat().coerceIn(0f, emu.historySize * cellH)
            postInvalidateOnAnimation()
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        scale.onTouchEvent(event)
        if (scale.isInProgress) return true
        if (selecting && event.actionMasked == MotionEvent.ACTION_MOVE) {
            synchronized(emu) { selEnd = cellAt(event.x, event.y) }
            invalidate()
            return true
        }
        if (selecting && (event.actionMasked == MotionEvent.ACTION_UP || event.actionMasked == MotionEvent.ACTION_CANCEL)) {
            selecting = false
            term.onSelection(selectionText())
            return true
        }
        return gestures.onTouchEvent(event) || super.onTouchEvent(event)
    }

    private fun orderedSelection(): Pair<Pair<Int, Int>, Pair<Int, Int>>? {
        val a = selStart ?: return null
        val b = selEnd ?: return null
        return if (a.first < b.first || (a.first == b.first && a.second <= b.second)) a to b else b to a
    }

    fun selectionText(): String = synchronized(emu) {
        val (a, b) = orderedSelection() ?: return ""
        emu.text(a.first, a.second, b.first, b.second)
    }

    fun clearSelection() {
        selStart = null
        selEnd = null
        selecting = false
        term.onSelection("")
        invalidate()
    }

    fun showKeyboard() {
        requestFocus()
        val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        imm.showSoftInput(this, InputMethodManager.SHOW_IMPLICIT)
    }

    override fun onFocusChanged(gainFocus: Boolean, direction: Int, previouslyFocusedRect: android.graphics.Rect?) {
        super.onFocusChanged(gainFocus, direction, previouslyFocusedRect)
        focused = gainFocus
        term.onFocus(gainFocus)
        invalidate()
    }

    // ---- keyboard --------------------------------------------------------------

    override fun onCheckIsTextEditor() = true

    override fun onCreateInputConnection(outAttrs: EditorInfo): InputConnection {
        // No suggestions or autocorrect: every key goes straight to the program.
        // (The Type composer is where predictive and voice typing live.)
        outAttrs.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        outAttrs.imeOptions = EditorInfo.IME_FLAG_NO_FULLSCREEN or EditorInfo.IME_FLAG_NO_EXTRACT_UI or EditorInfo.IME_ACTION_NONE
        return object : BaseInputConnection(this, true) {
            private var composing = ""

            override fun commitText(text: CharSequence, newCursorPosition: Int): Boolean {
                composing = ""
                editable?.clear()
                term.typed(text.toString())
                return true
            }

            override fun setComposingText(text: CharSequence, newCursorPosition: Int): Boolean {
                composing = text.toString()
                return true
            }

            override fun finishComposingText(): Boolean {
                if (composing.isNotEmpty()) term.typed(composing)
                composing = ""
                editable?.clear()
                return true
            }

            override fun deleteSurroundingText(beforeLength: Int, afterLength: Int): Boolean {
                if (composing.isNotEmpty()) {
                    composing = composing.dropLast(beforeLength)
                    return true
                }
                repeat(beforeLength.coerceAtMost(64)) { term.sendKey(Key.BACKSPACE, Mods()) }
                repeat(afterLength.coerceAtMost(64)) { term.sendKey(Key.DELETE, Mods()) }
                return true
            }
        }
    }

    private val keyMap = mapOf(
        KeyEvent.KEYCODE_DPAD_UP to Key.UP, KeyEvent.KEYCODE_DPAD_DOWN to Key.DOWN,
        KeyEvent.KEYCODE_DPAD_LEFT to Key.LEFT, KeyEvent.KEYCODE_DPAD_RIGHT to Key.RIGHT,
        KeyEvent.KEYCODE_MOVE_HOME to Key.HOME, KeyEvent.KEYCODE_MOVE_END to Key.END,
        KeyEvent.KEYCODE_PAGE_UP to Key.PAGE_UP, KeyEvent.KEYCODE_PAGE_DOWN to Key.PAGE_DOWN,
        KeyEvent.KEYCODE_INSERT to Key.INSERT, KeyEvent.KEYCODE_FORWARD_DEL to Key.DELETE,
        KeyEvent.KEYCODE_DEL to Key.BACKSPACE, KeyEvent.KEYCODE_ENTER to Key.ENTER,
        KeyEvent.KEYCODE_NUMPAD_ENTER to Key.ENTER, KeyEvent.KEYCODE_TAB to Key.TAB,
        KeyEvent.KEYCODE_ESCAPE to Key.ESCAPE,
        KeyEvent.KEYCODE_F1 to Key.F1, KeyEvent.KEYCODE_F2 to Key.F2, KeyEvent.KEYCODE_F3 to Key.F3,
        KeyEvent.KEYCODE_F4 to Key.F4, KeyEvent.KEYCODE_F5 to Key.F5, KeyEvent.KEYCODE_F6 to Key.F6,
        KeyEvent.KEYCODE_F7 to Key.F7, KeyEvent.KEYCODE_F8 to Key.F8, KeyEvent.KEYCODE_F9 to Key.F9,
        KeyEvent.KEYCODE_F10 to Key.F10, KeyEvent.KEYCODE_F11 to Key.F11, KeyEvent.KEYCODE_F12 to Key.F12,
    )

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        when (keyCode) {
            KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_VOLUME_UP, KeyEvent.KEYCODE_VOLUME_DOWN, KeyEvent.KEYCODE_VOLUME_MUTE ->
                return super.onKeyDown(keyCode, event)
        }
        val mods = Mods(shift = event.isShiftPressed, alt = event.isAltPressed, ctrl = event.isCtrlPressed)
        keyMap[keyCode]?.let {
            term.sendKey(it, mods)
            return true
        }
        val meta = event.metaState and (KeyEvent.META_CTRL_MASK or KeyEvent.META_ALT_MASK).inv()
        val cp = event.getUnicodeChar(meta)
        if (cp > 0 && cp and KeyCharacterMapCombining == 0) {
            term.typed(String(Character.toChars(cp)), mods)
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    private companion object {
        const val KeyCharacterMapCombining = android.view.KeyCharacterMap.COMBINING_ACCENT
    }
}

/** The terminal palette (same look as the rest of the app). */
object Palette {
    const val BG = 0xFF16171B.toInt()
    const val FG = 0xFFD8DAE0.toInt()
    const val CURSOR = 0xFFE8A04C.toInt()
    const val SELECTION = 0xFF3A3F4B.toInt()
    val colors: IntArray = IntArray(256).also { p ->
        val base = longArrayOf(
            0xFF1E2027, 0xFFE06C75, 0xFF98C379, 0xFFE5C07B, 0xFF61AFEF, 0xFFC678DD, 0xFF56B6C2, 0xFFD8DAE0,
            0xFF5C6370, 0xFFEF8189, 0xFFA9D68B, 0xFFF0CF8F, 0xFF7CC0F7, 0xFFD495EA, 0xFF6FCAD6, 0xFFFFFFFF,
        ).map { it.toInt() }
        for (i in 0 until 16) p[i] = base[i]
        val steps = intArrayOf(0, 95, 135, 175, 215, 255)
        for (i in 0 until 216) p[16 + i] = (0xFF shl 24) or (steps[i / 36] shl 16) or (steps[(i / 6) % 6] shl 8) or steps[i % 6]
        for (i in 0 until 24) { val v = 8 + i * 10; p[232 + i] = (0xFF shl 24) or (v shl 16) or (v shl 8) or v }
    }
}
