package herdr.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class FindTest {
    private fun item(a: String, name: String = "zsh", title: String = "", folder: String = "/Users/me/src/app", machine: String = "mac", space: String? = "app", tab: String? = "1", pinned: Boolean = false) =
        FindItem(a, name, title, folder, machine, space, tab, pinned)

    @Test fun everyWordMustMatchSomewhereCaseInsensitive() {
        val a = item("t1", name = "Claude Code", title = "Edit Session.kt", folder = "/Users/me/src/herdr", machine = "Mac-Mini")
        assertEquals(listOf("t1"), Find.search("CLAUDE herdr", listOf(a)).map { it.address })
        assertEquals(listOf("t1"), Find.search("  session   mini ", listOf(a)).map { it.address })
        assertTrue(Find.search("claude nothere", listOf(a)).isEmpty())
    }

    @Test fun matchesTerminalsWithNoAgentAndByFolderOrMachine() {
        val shell = item("vps/t9", name = "htop", folder = "/srv/app", machine = "vps", space = "prod", tab = null)
        assertEquals(listOf("vps/t9"), Find.search("srv", listOf(shell)).map { it.address })
        assertEquals(listOf("vps/t9"), Find.search("VPS", listOf(shell)).map { it.address })
    }

    @Test fun emptyQueryKeepsOrderPinnedFirst() {
        val l = listOf(item("a"), item("b", pinned = true), item("c"), item("d", pinned = true))
        assertEquals(listOf("b", "d", "a", "c"), Find.search("", l).map { it.address })
        assertEquals(listOf("b", "d", "a", "c"), Find.search("   ", l).map { it.address })
    }

    @Test fun nameBeatsTitleBeatsFolderBeatsMachineAndTiesKeepOrder() {
        val machine = item("m", name = "x", machine = "nav")
        val folder = item("f", name = "x", folder = "/src/nav")
        val title = item("t", name = "x", title = "nav bar")
        val name = item("n", name = "codex nav")
        val prefix = item("p", name = "nav-agent")
        val tie = item("t2", name = "x", title = "nav again")
        assertEquals(listOf("p", "n", "t", "t2", "f", "m"), Find.search("nav", listOf(machine, folder, title, name, prefix, tie)).map { it.address })
    }

    @Test fun resultLabelHasSpaceAndTab() {
        assertEquals("website › nav", Find.where(item("a", space = "website", tab = "nav")))
        assertEquals("website", Find.where(item("a", space = "website", tab = null)))
        assertEquals("nav", Find.where(item("a", space = null, tab = "nav")))
        assertEquals("no tab", Find.where(item("a", space = null, tab = null)))
        assertEquals("no tab", Find.where("", " "))
    }

    @Test fun termsSplitAndLowercase() {
        assertEquals(listOf("a", "b"), Find.terms(" A\tB\n"))
        assertFalse(Find.terms("").isNotEmpty())
    }
}

class AgentTreeTest {
    private fun tree(order: List<String>, parents: Map<String, String?>, pinned: Set<String> = emptySet()) =
        AgentTree.build(order, { parents[it] }, { it in pinned }).map { it.root to it.helpers }

    @Test fun helpersGoUnderTheirParent() {
        val t = tree(listOf("p", "x", "h1", "h2"), mapOf("h1" to "p", "h2" to "p"))
        assertEquals(listOf("p" to listOf("h1", "h2"), "x" to emptyList()), t)
    }

    @Test fun missingOrEmptyOrSelfParentIsTopLevel() {
        val t = tree(listOf("a", "b", "c", "d"), mapOf("a" to "gone", "b" to "", "c" to "c", "d" to null))
        assertEquals(listOf("a", "b", "c", "d"), t.map { it.first })
        assertTrue(t.all { it.second.isEmpty() })
    }

    @Test fun helperOfHelperIsListedUnderTheTopAncestor() {
        val t = tree(listOf("p", "h", "g", "gg"), mapOf("h" to "p", "g" to "h", "gg" to "g"))
        assertEquals(listOf("p" to listOf("h", "g", "gg")), t)
    }

    @Test fun childListedBeforeItsParentStillNests() {
        val t = tree(listOf("h", "p"), mapOf("h" to "p"))
        assertEquals(listOf("p" to listOf("h")), t)
    }

    @Test fun cyclesTerminate() {
        val t = tree(listOf("a", "b", "c"), mapOf("a" to "b", "b" to "c", "c" to "a"))
        assertEquals(listOf("a" to listOf("b", "c")), t)
        val tail = tree(listOf("x", "a", "b"), mapOf("x" to "a", "a" to "b", "b" to "a"))
        assertEquals(listOf("a" to listOf("x", "b")), tail)
        assertEquals(listOf("a" to emptyList()), tree(listOf("a"), mapOf("a" to "a")))
    }

    @Test fun duplicateAddressesAreOneRow() {
        assertEquals(listOf("a" to emptyList()), tree(listOf("a", "a"), emptyMap()))
    }

    @Test fun pinnedParentKeepsHelpersAndPinnedHelperStaysUnderParent() {
        // order given: pinned first (p, then pinned helper h2), then the rest.
        val parents = mapOf("h1" to "p", "h2" to "p", "k" to "q")
        val t = tree(listOf("p", "h2", "q", "h1", "k"), parents, pinned = setOf("p", "h2"))
        assertEquals(listOf("p" to listOf("h2", "h1"), "q" to listOf("k")), t)
    }

    @Test fun pinnedHelperDoesNotLiftAnUnpinnedParentIntoThePinnedRows() {
        val t = tree(listOf("pinnedRoot", "h", "other", "p"), mapOf("h" to "p"), pinned = setOf("pinnedRoot", "h"))
        assertEquals(listOf("pinnedRoot", "other", "p"), t.map { it.first })
    }

    @Test fun urgentHelperLiftsItsParent() {
        // The helper is blocked (first), its parent idle (last): the parent rises with it.
        val t = tree(listOf("h", "other", "p"), mapOf("h" to "p"))
        assertEquals(listOf("p", "other"), t.map { it.first })
    }

    @Test fun orderIsStable() {
        val order = listOf("a", "b", "c", "d")
        assertEquals(tree(order, mapOf("c" to "a")), tree(order, mapOf("c" to "a")))
        assertEquals(listOf("a" to listOf("c"), "b" to emptyList(), "d" to emptyList()), tree(order, mapOf("c" to "a")))
    }

    @Test fun expandedRuleAndCountText() {
        assertTrue(AgentTree.expanded(null, listOf("working", "blocked")))
        assertTrue(AgentTree.expanded(null, listOf("error")))
        assertTrue(AgentTree.expanded(null, listOf("done")))
        assertFalse(AgentTree.expanded(null, listOf("working", "idle")))
        assertFalse(AgentTree.expanded(null, emptyList()))
        assertFalse(AgentTree.expanded(false, listOf("blocked")))
        assertTrue(AgentTree.expanded(true, listOf("idle")))
        assertEquals("1 helper", AgentTree.countText(1))
        assertEquals("3 helpers", AgentTree.countText(3))
    }
}
