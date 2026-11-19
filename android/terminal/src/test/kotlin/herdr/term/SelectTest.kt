package herdr.term

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SelectTest {
    private fun emu(cols: Int, rows: Int, text: String, limit: Int = 100) = Emulator(cols, rows, limit).also { it.write(text) }
    private fun allText(e: Emulator): Picked = Select.text(e, assertNotNull(Select.all(e)))

    @Test
    fun select_all_with_no_scrollback_leaves_out_blank_lines_at_the_bottom() {
        val e = emu(10, 4, "a\r\nb")
        assertEquals(Sel(Cell(0, 0), Cell(1, 9)), Select.all(e))
        assertEquals(Picked("a\nb"), allText(e))
    }

    @Test
    fun select_all_of_an_empty_terminal_is_nothing() {
        assertNull(Select.all(emu(10, 4, "")))
        assertNull(Select.all(emu(10, 4, "  \r\n   ")))
    }

    @Test
    fun select_all_takes_the_scrollback_and_the_screen() {
        val e = emu(10, 2, "l1\r\nl2\r\nl3\r\nl4")
        assertEquals(2, e.historySize)
        assertEquals(Sel(Cell(0, 0), Cell(3, 9)), Select.all(e))
        assertEquals(Picked("l1\nl2\nl3\nl4"), allText(e))
    }

    @Test
    fun blank_screen_rows_below_the_text_are_left_out_even_with_scrollback() {
        val e = emu(10, 3, "a\r\nb\r\nc\r\nd\r\ne\r\nf" + "\u001b[2;1H\u001b[J")
        assertEquals(3, e.historySize)
        assertEquals(Picked("a\nb\nc\nd"), allText(e))
    }

    @Test
    fun blank_lines_in_the_middle_stay() {
        assertEquals(Picked("a\n\nb"), allText(emu(10, 4, "a\r\n\r\nb")))
    }

    @Test
    fun on_the_alternate_screen_only_the_screen_is_selected() {
        val e = emu(10, 2, "hist1\r\nhist2\r\nhist3")
        assertEquals(1, e.historySize)
        e.write("\u001b[?1049h\u001b[Halt1\r\nalt2")
        assertTrue(e.altScreen)
        assertEquals(0, e.historySize)
        assertEquals(Picked("alt1\nalt2"), allText(e))
        e.write("\u001b[?1049l")
        assertEquals(Picked("hist1\nhist2\nhist3"), allText(e))
    }

    @Test
    fun trimmed_short_scrollback_lines_are_never_read_past_their_end() {
        val e = emu(20, 2, "ab cd\r\nxy\r\nlast\r\nmore")
        assertTrue(e.lineAt(0)!!.capacity < 20)
        assertEquals(Picked("ab cd\nxy\nlast\nmore"), allText(e))
        // A drag that ends far right of a short line.
        assertEquals(Picked("cd\nxy"), Select.text(e, Sel(Cell(0, 3), Cell(1, 19))))
        // And one that starts past the end of one.
        assertEquals(Picked("\nlast"), Select.text(e, Sel(Cell(1, 15), Cell(2, 19))))
    }

    @Test
    fun the_ends_may_come_in_either_order() {
        val e = emu(10, 4, "abc\r\ndef")
        assertEquals(Select.text(e, Sel(Cell(0, 1), Cell(1, 1))), Select.text(e, Sel(Cell(1, 1), Cell(0, 1))))
        assertEquals(Picked("bc\nde"), Select.text(e, Sel(Cell(1, 1), Cell(0, 1))))
    }

    @Test
    fun wide_characters_count_once() {
        val e = emu(6, 2, "漢字\r\nx\r\n猫")
        assertEquals(Picked("漢字\nx\n猫"), allText(e))
        assertEquals(Picked("字"), Select.text(e, Sel(Cell(0, 2), Cell(0, 3))))
    }

    @Test
    fun wrapped_rows_join_without_a_line_break() {
        val e = emu(5, 2, "hello world")
        assertEquals(Picked("hello world"), allText(e))
    }

    @Test
    fun a_selection_that_fits_is_not_cut() {
        val e = Emulator(80, 24, 5000)
        for (i in 0 until 100) e.write("line $i\r\n")
        val p = allText(e)
        assertFalse(p.cut)
        assertTrue(p.text.startsWith("line 0\n") && p.text.endsWith("line 99"))
    }

    @Test
    fun a_huge_selection_keeps_the_newest_text_within_the_cap_and_says_so() {
        val e = Emulator(80, 24, 5000)
        for (i in 0 until 4500) e.write(i.toString().padStart(6, '0') + "y".repeat(70) + "\r\n")
        assertTrue(e.historySize >= 4400)
        val p = allText(e)
        assertTrue(p.cut)
        assertTrue(p.text.length <= Clip.MAX_CHARS, "${p.text.length}")
        assertTrue(p.text.length > Clip.MAX_CHARS - 200, "most of the cap is used: ${p.text.length}")
        assertTrue(Regex("""\d{6}y{70}""").matches(p.text.substringBefore('\n')), "starts at a line start")
        assertTrue(p.text.endsWith("004499" + "y".repeat(70)), "ends with the newest line")
    }

    @Test
    fun one_line_longer_than_the_cap_is_cut_too() {
        val raw = "x".repeat(Clip.MAX_CHARS + 50)
        val p = Clip.limit(raw)
        assertTrue(p.cut)
        assertEquals(Clip.MAX_CHARS, p.text.length)
        assertEquals(Picked("short"), Clip.limit("short"))
    }

    @Test
    fun the_cap_never_splits_a_surrogate_pair() {
        val raw = "a" + "😀".repeat(Clip.MAX_CHARS / 2 + 10)
        val p = Clip.limit(raw)
        assertTrue(p.cut)
        assertFalse(Character.isLowSurrogate(p.text[0]))
        assertTrue(p.text.length <= Clip.MAX_CHARS)
    }

    @Test
    fun the_copy_notice_says_when_it_was_cut_short() {
        assertEquals("Copied.", Clip.copyNotice("x"))
        assertTrue(Clip.copyNotice("x".repeat(1234), cut = true).contains("last 1,234 characters"))
        assertTrue(Clip.copyNotice("").startsWith("Nothing selected"))
    }

    @Test
    fun moving_the_start_or_the_end_of_a_selection() {
        val s = Sel(Cell(2, 3), Cell(5, 1))
        assertEquals(Sel(Cell(1, 0), Cell(5, 1)) to true, s.move(true, Cell(1, 0)))
        assertEquals(Sel(Cell(2, 3), Cell(7, 4)) to false, s.move(false, Cell(7, 4)))
        // Held in either order.
        assertEquals(Sel(Cell(2, 3), Cell(7, 4)) to false, Sel(Cell(5, 1), Cell(2, 3)).move(false, Cell(7, 4)))
    }

    @Test
    fun dragging_an_end_past_the_other_swaps_which_one_is_held() {
        val s = Sel(Cell(2, 3), Cell(5, 1))
        assertEquals(Sel(Cell(5, 1), Cell(9, 0)) to false, s.move(true, Cell(9, 0)))
        assertEquals(Sel(Cell(0, 0), Cell(2, 3)) to true, s.move(false, Cell(0, 0)))
        // Same cell: a one-cell selection, still held.
        assertEquals(Sel(Cell(5, 1), Cell(5, 1)) to true, s.move(true, Cell(5, 1)))
    }

    @Test
    fun contains_follows_reading_order() {
        val s = Sel(Cell(5, 1), Cell(2, 3))
        assertTrue(s.contains(2, 3) && s.contains(3, 0) && s.contains(5, 1))
        assertFalse(s.contains(2, 2) || s.contains(5, 2))
    }
}
