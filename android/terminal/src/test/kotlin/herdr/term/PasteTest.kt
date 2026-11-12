package herdr.term

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PasteTest {
    @Test
    fun committed_text_with_line_breaks_is_a_paste_one_line_stays_typed() {
        assertFalse(Keys.commitsAsPaste("ls -la"))
        assertFalse(Keys.commitsAsPaste(""))
        assertTrue(Keys.commitsAsPaste("one\ntwo"))
        assertTrue(Keys.commitsAsPaste("one\r\ntwo"))
        assertTrue(Keys.commitsAsPaste("a\nb\n"))
        assertFalse(Keys.commitsAsPaste("\n"), "Enter from the keyboard")
        assertFalse(Keys.commitsAsPaste("\r\n"))
        assertFalse(Keys.commitsAsPaste("ls\n"))
    }

    @Test
    fun three_lines_are_one_bracketed_paste() {
        val out = Keys.paste("one\ntwo\nthree", bracketed = true)
        assertEquals("\u001b[200~one\rtwo\rthree\u001b[201~", out)
        assertEquals(1, out.split("\u001b[200~").size - 1)
        assertEquals("one\rtwo\rthree", Keys.paste("one\r\ntwo\nthree", bracketed = false))
    }

    @Test
    fun copy_leaves_the_clipboard_alone_when_nothing_is_selected() {
        assertFalse(Clip.shouldWrite(""))
        assertTrue(Clip.shouldWrite("abc"))
        assertEquals("Copied.", Clip.copyNotice("abc"))
        assertTrue(Clip.copyNotice("").startsWith("Nothing selected"))
    }

    @Test
    fun paste_into_a_draft_goes_at_the_cursor_or_over_the_selection() {
        assertEquals("hello world" to 11, Clip.insertAt("hello ", 6, 6, "world"))
        assertEquals("heXXllo" to 4, Clip.insertAt("hello", 2, 2, "XX"))
        assertEquals("hXo" to 2, Clip.insertAt("hello", 4, 1, "X"))
        assertEquals("abc" to 3, Clip.insertAt("", 9, 9, "abc"))
    }
}
