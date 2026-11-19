package herdr.core

/** How a terminal shown on the phone is attached to the core. */
enum class AttachMode {
    /** An observer: sees everything, never resizes the Mac's terminal, can't type. */
    Watch,
    /** A typing viewer: its size counts, so the Mac's terminal takes the phone's size. */
    Control,
}

/** Scale and offset that put the watched grid on the view: screen = offset + scale × content. */
data class Fit(val scale: Float, val dx: Float, val dy: Float) {
    fun toContentX(x: Float) = (x - dx) / scale
    fun toContentY(y: Float) = (y - dy) / scale
}

/**
 * Watching a terminal without resizing it. A phone that attaches as a typing
 * viewer becomes the terminal's size leader, so the Mac's window reflows to the
 * phone's grid. Attached as an observer, the phone draws the terminal at the
 * Mac's size, scaled to fit, and only takes control when the person types.
 */
object Watch {
    /** The core feature that tells an observer the terminal's size (herdr-core 0.1.17+). */
    const val FEATURE = "resize_notifications"

    /**
     * Watch first, unless the person turned it off, already typed here, the core can't tell
     * an observer its size, or nobody is sizing the terminal (then typing resizes nothing anyone sees,
     * and the phone's own size reads better than a shrunken 200-column screen).
     */
    fun startMode(watchFirst: Boolean, features: Set<String>, tookControl: Boolean, nobodySizing: Boolean = false): AttachMode =
        if (watchFirst && !tookControl && !nobodySizing && FEATURE in features) AttachMode.Watch else AttachMode.Control

    /** herdr-core's default size for a terminal no typing viewer is showing (its `headless` setting). */
    const val HEADLESS_COLS = 200L
    const val HEADLESS_ROWS = 120L

    /**
     * The terminal is at the headless size, so no window is showing it. A Mac with a custom
     * `headless` size isn't recognised; those terminals are watched, which is the safe side.
     */
    fun nobodySizing(cols: Long?, rows: Long?): Boolean = cols == HEADLESS_COLS && rows == HEADLESS_ROWS

    /**
     * How far a watched grid wider than the view can be panned right, in unscaled pixels,
     * so the last column ends at the right padding.
     */
    fun maxPan(viewW: Float, pad: Float, cellW: Float, cols: Int, scale: Float): Float =
        ((cols * cellW * scale + 2 * pad - viewW) / scale.coerceAtLeast(0.01f)).coerceAtLeast(0f)

    /** The most typing that waits for control to arrive; more is dropped rather than replayed late. */
    const val HOLD_LIMIT = 64 * 1024

    /**
     * Fits a [cols]×[rows] grid of [cellW]×[cellH] cells, drawn starting at [pad],
     * into a [viewW]×[viewH] view. Never enlarges. Below [minScale] (text too small
     * to read) it stops shrinking and keeps the bottom-left in view: an agent's
     * latest output and its prompt are at the bottom.
     */
    fun fit(viewW: Float, viewH: Float, pad: Float, cellW: Float, cellH: Float, cols: Int, rows: Int, minScale: Float): Fit {
        val contentW = cols * cellW
        val contentH = rows * cellH
        val availW = (viewW - 2 * pad).coerceAtLeast(1f)
        val availH = (viewH - 2 * pad).coerceAtLeast(1f)
        if (contentW <= 0f || contentH <= 0f) return Fit(1f, 0f, 0f)
        val wanted = minOf(1f, availW / contentW, availH / contentH)
        val scale = maxOf(wanted, minOf(1f, minScale))
        // Column 0 and row 0 stay at the padding, as when not scaled.
        val dx = pad - scale * pad
        val top = pad - scale * pad
        val dy = if (contentH * scale > availH) viewH - pad - scale * (pad + contentH) else top
        return Fit(scale, dx, dy)
    }
}

/**
 * Typing that waits for control to arrive. Once something does not fit, everything after it
 * is dropped too: a later Enter must not be sent without the paste it was meant to follow.
 */
class HeldInput(private val limit: Int = Watch.HOLD_LIMIT) {
    private val bytes = java.io.ByteArrayOutputStream()
    var overflowed = false
        private set

    /** False when this (or earlier) input was dropped. */
    fun add(more: ByteArray): Boolean {
        if (overflowed || bytes.size() + more.size > limit) { overflowed = true; return false }
        bytes.write(more)
        return true
    }

    /** What was held, in order; clears it (and the overflow). */
    fun take(): ByteArray = bytes.toByteArray().also { reset() }

    fun reset() { bytes.reset(); overflowed = false }
}
