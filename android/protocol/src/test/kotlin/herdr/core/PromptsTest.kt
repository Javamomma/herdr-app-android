package herdr.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PromptsTest {
    private val claude = """
        ● I'll update the key bar.

        ╭──────────────────────────────────────╮
        │ Allow this edit?                     │
        │ ❯ 1. Yes                             │
        │   2. Yes, and don't ask again        │
        │   3. No, and tell Claude what to do  │
        ╰──────────────────────────────────────╯
    """.trimIndent()

    @Test
    fun reads_a_boxed_question_with_its_highlight() {
        val c = Prompts.choices(claude)
        assertEquals(listOf("Yes", "Yes, and don't ask again", "No, and tell Claude what to do"), c.map { it.label })
        assertEquals(listOf(true, false, false), c.map { it.selected })
        assertEquals(listOf(false, true, false), c.map { it.risky })
        assertEquals(listOf("enter"), Prompts.keys(c, c[0]))
        assertEquals(listOf("down", "down", "enter"), Prompts.keys(c, c[2]))
    }

    @Test
    fun without_a_highlight_the_number_picks() {
        val c = Prompts.choices("Run migration?\n  1) Yes\n  2) No\n")
        assertEquals(2, c.size)
        assertEquals(listOf("2"), Prompts.keys(c, c[1]))
    }

    @Test
    fun only_the_last_counting_block_and_not_a_lone_list_item() {
        val screen = "Steps:\n1. build\n2. test\n\nlater output\n\n› 1. Approve\n  2. Reject (git push --force)\n"
        val c = Prompts.choices(screen)
        assertEquals(listOf("Approve", "Reject (git push --force)"), c.map { it.label })
        assertTrue(c[1].risky)
        assertTrue(Prompts.choices("> 1. only one").isEmpty())
        assertTrue(Prompts.choices("no options here").isEmpty())
    }

    @Test
    fun tail_keeps_the_last_lines_with_text() {
        assertEquals(listOf("b", "c"), Prompts.tail("a\n\nb\n  \nc\n\n", 2))
    }
}
