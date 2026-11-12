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

    private fun ask(what: String, vararg options: String) =
        "● $what\n\n" + options.mapIndexed { i, o -> "${if (i == 0) "❯" else " "} ${i + 1}. $o" }.joinToString("\n") + "\n"

    @Test
    fun an_answer_button_is_refused_when_another_question_has_the_same_labels() {
        val first = ask("Allow edit to a.txt?", "Yes", "No")
        val second = ask("Allow edit to b.txt?", "Yes", "No")
        val mark = Prompts.question(first).mark
        assertEquals(Prompts.Pick.Changed, Prompts.pick(second, mark, "Yes", held = false))
        assertEquals(Prompts.Pick.Changed, Prompts.pick(second, mark, "No", held = true))
    }

    @Test
    fun an_answer_button_is_accepted_for_the_question_it_was_drawn_for() {
        val screen = ask("Allow edit to a.txt?", "Yes", "No")
        val mark = Prompts.question(screen).mark
        assertEquals(Prompts.Pick.Send(listOf("enter")), Prompts.pick(screen, mark, "Yes", held = false))
        assertEquals(Prompts.Pick.Send(listOf("down", "enter")), Prompts.pick(screen + "\n\n", mark, "No", held = false))
        assertEquals(Prompts.Pick.Changed, Prompts.pick(screen, mark, "Maybe", held = false), "no such option")
    }

    @Test
    fun risky_options_and_dangerous_questions_need_a_hold() {
        val risky = ask("Allow edit?", "Yes", "Yes, and don't ask again")
        val m = Prompts.question(risky).mark
        assertEquals(Prompts.Pick.NeedsHold, Prompts.pick(risky, m, "Yes, and don't ask again", held = false))
        assertEquals(Prompts.Pick.Send(listOf("down", "enter")), Prompts.pick(risky, m, "Yes, and don't ask again", held = true))
        val danger = "Run this?\n\n  \$ rm -rf build\n\n❯ 1. Yes\n  2. No\n"
        val dm = Prompts.question(danger).mark
        assertEquals(Prompts.Pick.NeedsHold, Prompts.pick(danger, dm, "Yes", held = false))
        assertEquals(Prompts.Pick.Send(listOf("enter")), Prompts.pick(danger, dm, "Yes", held = true))
    }

    private fun boxed(above: String, vararg cmd: String): String =
        above + "╭──────────────────────────────╮\n" + cmd.joinToString("") { "│ $it │\n" } +
            "│ ❯ 1. Yes                     │\n│   2. No                      │\n╰──────────────────────────────╯\n"

    private val tailLines = (1..8).map { "echo step$it" }.toTypedArray()

    @Test
    fun two_long_boxed_commands_with_the_same_last_lines_have_different_marks() {
        val a = boxed("", "curl https://a.example/x", *tailLines)
        val b = boxed("", "curl https://b.example/x", *tailLines)
        assertEquals(Prompts.question(a).text, Prompts.question(b).text, "the lines shown are the same")
        assertTrue(Prompts.question(a).mark != Prompts.question(b).mark)
        assertEquals(Prompts.Pick.Changed, Prompts.pick(b, Prompts.question(a).mark, "Yes", held = true))
    }

    @Test
    fun the_mark_stays_when_lines_outside_the_box_change_or_the_text_is_rewrapped() {
        val base = boxed("old output\nmore output\n", "git status && ls", "echo done")
        val mark = Prompts.question(base).mark
        assertEquals(mark, Prompts.question(boxed("different output\ntimer 12s\n", "git status && ls", "echo done")).mark, "lines above the box")
        assertEquals(mark, Prompts.question(boxed("", "git status && ls", "echo done")).mark, "top rows dropped")
        assertEquals(mark, Prompts.question(boxed("old output\n", "git status", "&& ls echo", "done")).mark, "wrapped elsewhere")
        assertEquals(mark, Prompts.question(base + "\n\n").mark)
    }

    @Test
    fun a_long_command_with_danger_high_in_the_box_is_dangerous() {
        val q = Prompts.question(boxed("", "rm -rf build", *tailLines))
        assertTrue(q.dangerous)
        assertEquals(null, Prompts.allowKeys(q))
        assertTrue(!Prompts.question(boxed("", "echo hi", *tailLines)).dangerous)
        assertTrue(Prompts.question("Run it?\n\n  \$ rm -rf x\n\n  \$ ls\n❯ 1. Yes\n  2. No\n").dangerous, "unboxed: as before")
    }

    @Test
    fun an_unnamed_tab_shows_its_first_agents_title_then_its_number() {
        assertEquals("Work", tabLabel("Work", listOf("title"), 2))
        assertEquals("Fix the build", tabLabel("", listOf(null, "", "  Fix the build "), 2))
        assertEquals("2", tabLabel("", emptyList(), 2))
        assertEquals("3", tabLabel(" ", listOf(" ", null), 3))
    }
}
