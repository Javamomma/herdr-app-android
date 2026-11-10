package herdr.term

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class EmulatorTest {
    private fun emu(cols: Int = 10, rows: Int = 4, text: String = "") = Emulator(cols, rows, 100).also { it.write(text) }
    private fun Emulator.row(r: Int) = line(r).string(cols).trimEnd()

    @Test
    fun prints_wraps_and_scrolls_into_scrollback() {
        val e = emu(5, 2, "hello world\r\nnext")
        assertEquals(listOf("hello", " worl"), e.scrollback.map { it.string().trimEnd() }.plus(e.row(0)).take(2))
        assertEquals("d", e.row(0))
        assertEquals("next", e.row(1))
        assertTrue(e.scrollback.first().wrapped, "a filled row wraps into the next")
        assertEquals(2, e.historySize)
    }

    @Test
    fun the_last_column_waits_before_wrapping() {
        val e = emu(3, 2, "abc")
        assertEquals(0 to 2, e.cursorRow to e.cursorCol)
        e.write("\r")
        assertEquals("abc", e.row(0), "CR at the margin doesn't wrap")
        e.write("x")
        assertEquals("xbc", e.row(0))
    }

    @Test
    fun cursor_movement_and_erase() {
        val e = emu(10, 3, "0123456789\u001b[2;3Hab\u001b[1;5H\u001b[K")
        assertEquals("0123", e.row(0))
        assertEquals("  ab", e.row(1))
        e.write("\u001b[2J\u001b[H")
        assertEquals("", e.row(1))
        assertEquals(0 to 0, e.cursorRow to e.cursorCol)
        e.write("xyz\u001b[2D\u001b[P")
        assertEquals("xz", e.row(0), "DCH pulls the rest left")
        e.write("\u001b[1@")
        assertEquals("x z", e.row(0), "ICH opens a blank")
        e.write("\u001b[3X")
        assertEquals("x", e.row(0), "ECH blanks without moving")
    }

    @Test
    fun sgr_colors_and_attributes() {
        val e = emu(20, 1, "\u001b[1;31mA\u001b[38;5;200mB\u001b[38;2;1;2;3mC\u001b[48:2::9:8:7mD\u001b[0mE\u001b[7;4mF\u001b[24m")
        val s = e.line(0).styles
        assertEquals(Color.palette(1), Style.fg(s[0]))
        assertTrue(Style.attrs(s[0]) and Attr.BOLD != 0)
        assertEquals(Color.palette(200), Style.fg(s[1]))
        assertEquals(Color.rgb(1, 2, 3), Style.fg(s[2]))
        assertEquals(Color.rgb(9, 8, 7), Style.bg(s[3]))
        assertEquals(Style.DEFAULT, s[4])
        assertEquals(Attr.INVERSE or Attr.UNDERLINE, Style.attrs(s[5]))
        e.write("\u001b[44m\u001b[K")
        assertEquals(Color.palette(4), Style.bg(e.line(0).styles[19]), "erase keeps the background (BCE)")
    }

    @Test
    fun alternate_screen_saves_and_restores_the_main_one() {
        val e = emu(10, 3, "shell$ ")
        e.write("\u001b[?1049h\u001b[Hvim")
        assertTrue(e.altScreen)
        assertEquals("vim", e.row(0))
        assertEquals(0, e.historySize)
        e.write("\u001b[?1049l")
        assertFalse(e.altScreen)
        assertEquals("shell$", e.row(0))
        assertEquals(0 to 7, e.cursorRow to e.cursorCol)
    }

    @Test
    fun scroll_regions_keep_the_rest_in_place() {
        val e = emu(5, 4, "top\r\na\r\nb\r\nbot")
        e.write("\u001b[2;3r\u001b[3;1H\n")
        assertEquals(listOf("top", "b", "", "bot"), (0 until 4).map { e.row(it) })
        assertEquals(0, e.historySize, "region scrolls never reach scrollback")
        e.write("\u001b[2;1H\u001bM")
        assertEquals(listOf("top", "", "b", "bot"), (0 until 4).map { e.row(it) })
    }

    @Test
    fun wide_and_combining_characters() {
        val e = emu(6, 2, "a中b")
        assertEquals(WIDE_TAIL, e.line(0).chars[2])
        assertEquals("a中b", e.row(0))
        assertEquals(4, e.cursorCol)
        e.write("\ré")
        assertEquals("e\u0301", e.line(0).text(0))
        val w = emu(3, 2, "ab中")
        assertEquals("ab", w.row(0), "a wide char at the margin wraps whole")
        assertEquals("中", w.row(1))
        e.write("\u001b[1;3Hx")
        assertEquals(' '.code, e.line(0).chars[1], "overwriting half a wide char blanks the other half")
    }

    @Test
    fun line_drawing_charset() {
        val e = emu(5, 1, "\u001b(0lqk\u001b(Bq")
        assertEquals("┌─┐q", e.row(0))
    }

    @Test
    fun queries_are_answered() {
        val e = emu(10, 5, "\u001b[3;4H\u001b[6n\u001b[c\u001b[5n\u001b[?2004\$p")
        assertEquals("\u001b[3;4R\u001b[?62;22c\u001b[0n\u001b[?2004;2\$y", e.takeReplies().decodeToString())
        assertEquals(0, e.takeReplies().size)
    }

    @Test
    fun modes_titles_and_tabs() {
        val titles = mutableListOf<String>()
        val e = emu(20, 2)
        e.onTitle = { titles += it }
        e.write("\u001b]0;build\u0007\u001b]2;agent\u001b\\\u001b[?1h\u001b[?2004h\u001b[?1002h\u001b[?1006h\u001b[?25l\u001b[5 q")
        assertEquals(listOf("build", "agent"), titles)
        assertTrue(e.appCursorKeys && e.bracketedPaste && e.mouseSgr && !e.cursorVisible)
        assertEquals(Mouse.DRAG, e.mouse)
        assertEquals(CursorShape.BAR, e.cursorShape)
        e.write("a\tb")
        assertEquals(9, e.cursorCol)
        e.write("\u001bc")
        assertFalse(e.appCursorKeys || e.bracketedPaste)
        assertEquals(Mouse.OFF, e.mouse)
    }

    @Test
    fun resizing_keeps_hidden_cells_and_the_cursor_row() {
        val e = emu(10, 4, "0123456789\r\n1\r\n2\r\n3")
        e.resize(5, 4)
        assertEquals("01234", e.line(0).string(5))
        e.resize(10, 4)
        assertEquals("0123456789", e.row(0), "widening shows them again")
        e.resize(10, 2)
        assertEquals(listOf("2", "3"), listOf(e.row(0), e.row(1)))
        assertEquals(1, e.cursorRow)
        assertEquals(2, e.historySize)
        e.resize(10, 4)
        assertEquals(listOf("0123456789", "1", "2", "3"), (0 until 4).map { e.row(it) }, "growing pulls scrollback back")
        assertEquals(3, e.cursorRow)
    }

    @Test
    fun cancel_utf8_splits_and_garbage_never_break_it() {
        val bytes = "é中\u001b[31mx\u001b[0m\u001b]0;t\u0007".toByteArray()
        val whole = emu(20, 2).also { it.write(bytes) }
        for (seed in 0 until 50) {
            val r = Random(seed)
            val e = emu(20, 2)
            var at = 0
            while (at < bytes.size) {
                val n = r.nextInt(1, 4).coerceAtMost(bytes.size - at)
                e.write(bytes, at, n)
                at += n
            }
            assertEquals(whole.screenText(), e.screenText())
        }
        val e = emu(20, 3, "\u001b[31;1\u0018ok")
        assertEquals("ok", e.row(0), "CAN abandons the sequence")
        val noise = Random(7)
        repeat(20) {
            e.write(ByteArray(4096) { noise.nextInt().toByte() })
            e.write("\u001b[?1049l\u001b[0m\u001b[r\u001bc")
        }
        e.write("\u001b[Hfine")
        assertEquals("fine", e.row(0))
        val big = emu(10, 2)
        big.write("\u001b[" + "9".repeat(100000) + "Cz")
        assertEquals(9, big.cursorCol, "huge parameters clamp")
        big.write("\u001b]0;" + "x".repeat(200000) + "\u0007ok")
        assertTrue(big.title.isEmpty(), "oversized strings are dropped")
    }

    @Test
    fun selection_text_joins_wrapped_rows() {
        val e = emu(4, 3, "abcdef\r\ngh")
        assertEquals("abcdef\ngh", e.text(0, 0, 2, 3))
    }

    @Test
    fun keys() {
        assertEquals("\u001b[A", Keys.encode(Key.UP, Mods(), false))
        assertEquals("\u001bOA", Keys.encode(Key.UP, Mods(), true))
        assertEquals("\u001b[1;5C", Keys.encode(Key.RIGHT, Mods(ctrl = true), true))
        assertEquals("\u001b[5~", Keys.encode(Key.PAGE_UP, Mods(), false))
        assertEquals("\u001b[Z", Keys.encode(Key.TAB, Mods(shift = true), false))
        assertEquals("\u0003", Keys.text("c", Mods(ctrl = true)))
        assertEquals("\u001bx", Keys.text("x", Mods(alt = true)))
        assertEquals("\u001b[200~a\rb\u001b[201~", Keys.paste("a\nb", true))
        assertEquals("\u001b[<64;3;2M", Keys.mouse(64, 2, 1, true, true))
        assertEquals("\u001b[M`#\"", Keys.mouse(64, 2, 1, true, false))
    }
}
