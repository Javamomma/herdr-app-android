package herdr.term

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class AttachPathTest {
    @Test
    fun a_plain_path_is_left_alone() {
        assertEquals("/tmp/herdr-put/t7/photo-20261119-135600.jpg", AttachPath.quote("/tmp/herdr-put/t7/photo-20261119-135600.jpg"))
        assertEquals("~/a_b-c.d@e+f=g:h,i%j.png", AttachPath.quote("~/a_b-c.d@e+f=g:h,i%j.png"))
    }

    @Test
    fun spaces_and_shell_characters_are_single_quoted() {
        assertEquals("'/Users/a b/photo.jpg'", AttachPath.quote("/Users/a b/photo.jpg"))
        for (c in listOf("$", "`", "&", ";", "(", ")", "*", "?", "#", "!", "|", "<", ">", "\"", "\\", "{", "[")) {
            assertEquals("'/tmp/a${c}b'", AttachPath.quote("/tmp/a${c}b"), "quoted for $c")
        }
    }

    @Test
    fun a_single_quote_is_closed_escaped_and_reopened() {
        assertEquals("'/tmp/it'\\''s.png'", AttachPath.quote("/tmp/it's.png"))
    }

    @Test
    fun unsafe_or_empty_paths_are_refused() {
        assertNull(AttachPath.quote(""))
        assertNull(AttachPath.quote("/tmp/a\nb"))
        assertNull(AttachPath.quote("/tmp/a\rb"))
        assertNull(AttachPath.quote("/tmp/a\u0000b"))
        assertNull(AttachPath.insert("hi", 2, 2, "/tmp/a\nb"))
    }

    @Test
    fun any_control_character_is_refused() {
        for (c in listOf('\u001b', '\u0007', '\t', '\u007f', '\u0085', '\u009b', '\u2028', '\u2029')) {
            assertNull(AttachPath.quote("/tmp/a${c}b"), "refused: U+%04X".format(c.code))
        }
        assertEquals("'/tmp/caf\u00e9 \u65e5\u672c.png'", AttachPath.quote("/tmp/caf\u00e9 \u65e5\u672c.png"), "ordinary non-ASCII is fine")
    }

    @Test
    fun a_path_over_the_length_cap_is_refused() {
        val ok = "/" + "a".repeat(AttachPath.MAX_PATH - 1)
        assertEquals(ok, AttachPath.quote(ok))
        assertNull(AttachPath.quote(ok + "a"))
        assertNull(AttachPath.insert("", 0, 0, ok + "a"))
    }

    @Test
    fun a_space_follows_the_path_only_when_text_follows_without_one() {
        assertEquals("see /tmp/a.png now" to 15, AttachPath.insert("seenow", 3, 3, "/tmp/a.png"))
        assertEquals("/tmp/a.png word" to 11, AttachPath.insert("word", 0, 0, "/tmp/a.png"))
        assertEquals("a /tmp/a.png\nnext" to 12, AttachPath.insert("a\nnext", 1, 1, "/tmp/a.png"), "a line break after: no extra space")
        assertEquals("a /x.png" to 8, AttachPath.insert("a", 1, 1, "/x.png"), "none at the end of the text")
        assertEquals("a /x.png b" to 8, AttachPath.insert("a b", 1, 1, "/x.png"), "white space after: no extra")
    }

    @Test
    fun insertion_goes_at_the_cursor_with_a_space_only_when_needed() {
        assertEquals("/tmp/a.png" to 10, AttachPath.insert("", 0, 0, "/tmp/a.png"))
        assertEquals("look at /tmp/a.png" to 18, AttachPath.insert("look at", 7, 7, "/tmp/a.png"))
        assertEquals("look at /tmp/a.png" to 18, AttachPath.insert("look at ", 8, 8, "/tmp/a.png"))
        assertEquals("look\n/tmp/a.png" to 15, AttachPath.insert("look\n", 5, 5, "/tmp/a.png"))
        // In the middle: the text after the cursor follows the path directly.
        assertEquals("see /tmp/a.png now" to 14, AttachPath.insert("see now", 3, 3, "/tmp/a.png"))
        // A selection is replaced, like Paste.
        assertEquals("see /tmp/a.png" to 14, AttachPath.insert("see XXX", 4, 7, "/tmp/a.png"))
        // Cursor values out of range are held to the text.
        assertEquals("ab /x.png" to 9, AttachPath.insert("ab", 99, 99, "/x.png"))
    }

    @Test
    fun a_quoted_path_is_inserted_quoted() {
        assertEquals("see '/a b/x.png'" to 16, AttachPath.insert("see", 3, 3, "/a b/x.png"))
    }
}
