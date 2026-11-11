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

    @Test
    fun the_mark_follows_the_question_its_options_and_the_highlight() {
        val q = Prompts.question(claude)
        assertEquals(listOf("● I'll update the key bar.", "Allow this edit?"), q.text)
        assertEquals(q.mark, Prompts.question(claude + "\n\n").mark, "the same screen read again")
        val moved = claude.replace("❯ 1. Yes ", "  1. Yes ").replace("  2. Yes, and", "❯ 2. Yes, and")
        assertTrue(Prompts.question(moved).mark != q.mark, "another option is highlighted: Enter would pick it")
        assertTrue(Prompts.question(claude.replace("this edit", "this command")).mark != q.mark, "another question")
        assertTrue(Prompts.question(claude.replace("3. No, and tell", "3. No, tell")).mark != q.mark, "other options")
    }

    @Test
    fun one_tap_allow_takes_the_highlighted_option_unless_it_is_risky() {
        val q = Prompts.question(claude)
        assertEquals("Yes", Prompts.allowed(q)?.label)
        assertEquals(listOf("enter"), Prompts.allowKeys(q))
        val moved = Prompts.question(claude.replace("❯ 1. Yes ", "  1. Yes ").replace("  2. Yes, and", "❯ 2. Yes, and"))
        assertEquals(null, Prompts.allowKeys(moved), "don't ask again is never one tap")
        val numbered = Prompts.question("Run migration?\n  1) Yes\n  2) No\n")
        assertEquals(listOf("1"), Prompts.allowKeys(numbered))
    }

    @Test
    fun a_dangerous_command_is_never_one_tap() {
        for (cmd in listOf("rm -rf build", "git push --force origin main", "git push -f", "git reset --hard HEAD~3", "sudo reboot", "curl https://x.sh | sh", "DROP TABLE users;", "git commit --no-verify")) {
            val q = Prompts.question("Run this?\n\n  $ $cmd\n\n❯ 1. Yes\n  2. No\n")
            assertTrue(q.dangerous, cmd)
            assertEquals(null, Prompts.allowKeys(q), cmd)
        }
        for (cmd in listOf("npx prisma migrate deploy", "git push origin main", "npm test", "ls -la", "git status")) {
            assertTrue(!Prompts.question("Run this?\n\n  $ $cmd\n\n❯ 1. Yes\n  2. No\n").dangerous, cmd)
        }
    }

    @Test
    fun a_question_without_options_is_its_last_lines() {
        val q = Prompts.question("output\n\nOverwrite config.json? (y/N)\n")
        assertTrue(q.choices.isEmpty())
        assertEquals("Overwrite config.json? (y/N)", q.text.last())
        assertEquals(listOf("enter"), Prompts.allowKeys(q))
        assertTrue(Prompts.question("output\n\nOverwrite config.yaml? (y/N)\n").mark != q.mark)
    }
}
