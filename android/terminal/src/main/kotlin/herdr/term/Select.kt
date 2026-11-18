package herdr.term

/** A cell by row index (as in [Emulator.lineAt]) and column. */
data class Cell(val row: Int, val col: Int) : Comparable<Cell> {
    override fun compareTo(other: Cell): Int = if (row != other.row) row.compareTo(other.row) else col.compareTo(other.col)
}

/** A selection between two cells, either end first. */
data class Sel(val start: Cell, val end: Cell) {
    fun ordered(): Sel = if (start <= end) this else Sel(end, start)

    fun contains(row: Int, col: Int): Boolean {
        val (a, b) = ordered()
        return Cell(row, col) >= a && Cell(row, col) <= b
    }

    /**
     * Moves one end of the selection to [to]. [grabStart] says which end (of the ordered selection) is held.
     * Dragging an end past the other makes the other one the moving end: the second value says whether
     * the end still being held is the start.
     */
    fun move(grabStart: Boolean, to: Cell): Pair<Sel, Boolean> {
        val o = ordered()
        return if (grabStart) {
            if (to <= o.end) Sel(to, o.end) to true else Sel(o.end, to) to false
        } else {
            if (to >= o.start) Sel(o.start, to) to false else Sel(to, o.start) to true
        }
    }
}

/** Selected text, and whether it was cut short to fit the clipboard. */
data class Picked(val text: String, val cut: Boolean = false)

/** Selecting in the terminal; plain Kotlin so it is tested on the JVM. */
object Select {
    /**
     * Everything: scrollback and screen (on the alternate screen: the screen only, which is all
     * [Emulator.lineAt] has there), without the blank lines at the bottom. Null when it is all blank.
     */
    fun all(e: Emulator): Sel? {
        var last = e.historySize + e.rows - 1
        while (last >= 0 && (e.lineAt(last)?.isBlank(e.cols) != false)) last--
        if (last < 0) return null
        return Sel(Cell(0, 0), Cell(last, e.cols - 1))
    }

    /**
     * The text of [sel], at most [Clip.MAX_CHARS] long. Too long: the end of it is kept (what is
     * newest), and the work stays bounded because only the lines that fit are read.
     */
    fun text(e: Emulator, sel: Sel): Picked {
        val (a, b) = sel.ordered()
        var from = a.row
        var cut = false
        var total = 0
        var r = b.row
        while (r >= a.row) {
            val line = e.lineAt(r)
            total += (if (line == null) 0 else minOf(line.usedLength(), e.cols)) + 1
            if (total > Clip.MAX_CHARS) {
                from = if (r < b.row) r + 1 else r
                cut = true
                break
            }
            r--
        }
        val fromCol = if (from == a.row) a.col else 0
        val raw = e.text(from, fromCol, b.row, b.col)
        val limited = Clip.limit(raw)
        return Picked(limited.text, cut || limited.cut)
    }
}
