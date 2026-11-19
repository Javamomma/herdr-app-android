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

    @Test fun multiWordRankingSumsEachWordsBestField() {
        val both = item("both", name = "claude", title = "fix nav")            // 5 + 3
        val onlyTitle = item("title", name = "x", title = "claude fix nav")     // 3 + 3
        val folderToo = item("folder", name = "x", title = "fix", folder = "/src/claude/nav") // 3 + 2 (nav in folder)
        val miss = item("miss", name = "claude", title = "other")
        assertEquals(listOf("both", "title"), Find.search("claude nav", listOf(folderToo, onlyTitle, miss, both)).map { it.address }.take(2))
        assertEquals(listOf("both", "title"), Find.search("claude fix", listOf(onlyTitle, both, miss)).map { it.address })
        assertTrue("miss" !in Find.search("claude nav", listOf(miss)).map { it.address })
    }

    @Test fun spaceAndTabNamesAreShownNotSearched() {
        val a = item("a", name = "zsh", space = "website", tab = "deploy")
        assertTrue(Find.search("website", listOf(a)).isEmpty())
        assertTrue(Find.search("deploy", listOf(a)).isEmpty())
        assertEquals("website › deploy", Find.where(a))
    }

    @Test fun blankFieldsNeverCrashOrMatchEverything() {
        val blank = FindItem("t1", "", "", "", "")
        assertTrue(Find.search("x", listOf(blank)).isEmpty())
        assertEquals(listOf("t1"), Find.search("", listOf(blank)).map { it.address })
        assertEquals("no tab", Find.where(blank))
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
        val parents = mapOf("c" to "a", "d" to "a")
        assertEquals(listOf("a" to listOf("c", "d"), "b" to emptyList()), tree(listOf("a", "b", "c", "d"), parents))
        // Helpers keep the order given; the parent rises to its first helper.
        assertEquals(listOf("a" to listOf("d", "c"), "b" to emptyList()), tree(listOf("d", "c", "b", "a"), parents))
        assertEquals(listOf("x", "y", "z"), tree(listOf("x", "y", "z"), emptyMap()).map { it.first })
    }

    @Test fun pinnedRootWithUrgentHelperStaysInThePinnedRows() {
        val t = tree(listOf("other", "p", "h"), mapOf("h" to "p"), pinned = setOf("p"))
        assertEquals(listOf("other", "p"), t.map { it.first }) // given order: other is first here, p keeps its own place
        val t2 = tree(listOf("p", "x", "h"), mapOf("h" to "p"), pinned = setOf("p"))
        assertEquals(listOf("p" to listOf("h"), "x" to emptyList()), t2)
    }

    @Test fun pinnedHelperIsStillListedUnderItsUnpinnedParent() {
        val t = tree(listOf("h", "p", "q"), mapOf("h" to "p"), pinned = setOf("h"))
        assertEquals(listOf("p" to listOf("h"), "q" to emptyList()), t)
    }

    @Test fun chainThroughAPlainTerminalGoesUnderTheAgent() {
        // g -> shell -> p, where shell is not an agent.
        val parents = mapOf("g" to "shell", "shell" to "p")
        val agents = setOf("g", "p")
        fun up(a: String) = AgentTree.nearestAgent(parents[a], { it in agents }, { parents[it] })
        assertEquals("p", up("g"))
        assertEquals(null, up("p"))
        val t = AgentTree.build(listOf("g", "p"), { up(it) }).map { it.root to it.helpers }
        assertEquals(listOf("p" to listOf("g")), t)
        // Loops and dead ends through plain terminals end in none.
        assertEquals(null, AgentTree.nearestAgent("s1", { false }, { if (it == "s1") "s2" else "s1" }))
        assertEquals(null, AgentTree.nearestAgent("gone", { false }, { null }))
        assertEquals(null, AgentTree.nearestAgent("", { true }, { null }))
    }

    @Test fun machineLocalParentIsResolvedOnTheHelpersMachine() {
        val live = setOf("t7", "vps/t7", "vps/t9", "vps/t8")
        val exists = { a: String -> a in live }
        // `parent` unverified against a real remote core: both spellings work.
        assertEquals("vps/t7", AgentTree.resolveParent("vps/t9", "t7", exists))      // machine-local: not the local t7
        assertEquals("vps/t7", AgentTree.resolveParent("vps/t9", "vps/t7", exists))   // already qualified
        assertEquals("t7", AgentTree.resolveParent("t9", "t7", exists))               // local helper
        assertEquals("t5", AgentTree.resolveParent("vps/t9", "t5", exists))           // no such one on its machine: as given
        assertEquals(null, AgentTree.resolveParent("vps/t9", " ", exists))
        assertEquals(null, AgentTree.resolveParent("vps/t9", null, exists))
    }

    @Test fun summaryNamesWhatWaitsWhenFolded() {
        assertEquals("2 helpers · 1 done", AgentTree.summary(listOf("working", "done"), open = false))
        assertEquals("2 helpers", AgentTree.summary(listOf("working", "done"), open = true))
        assertEquals("1 helper", AgentTree.summary(listOf("idle"), open = false))
    }

    @Test fun expandedRuleAndCountText() {
        assertTrue(AgentTree.expanded(null, listOf("working", "blocked")))
        assertTrue(AgentTree.expanded(null, listOf("error")))
        assertTrue(AgentTree.expanded(null, listOf("done")))
        assertFalse(AgentTree.expanded(null, listOf("working", "idle")))
        assertFalse(AgentTree.expanded(null, emptyList()))
        // A fold chosen earlier never hides a helper that is blocked or in error.
        assertTrue(AgentTree.expanded(false, listOf("blocked")))
        assertTrue(AgentTree.expanded(false, listOf("idle", "error")))
        // Done-unread may stay folded when the person folded it (the folded header says so); unfolded stays open.
        assertFalse(AgentTree.expanded(false, listOf("done")))
        assertTrue(AgentTree.expanded(true, listOf("idle")))
        assertEquals("1 helper", AgentTree.countText(1))
        assertEquals("3 helpers", AgentTree.countText(3))
    }
}

class StackJumpTest {
    private fun doc(): kotlinx.serialization.json.JsonObject {
        val stack = container("st", Axis.Row, listOf(terminalPane("p2", "t2"), terminalPane("p3", "t3"))).let {
            it.with("type" to kotlinx.serialization.json.JsonPrimitive("stack"), "active" to kotlinx.serialization.json.JsonPrimitive("p2"))
        }
        return container("root", Axis.Row, listOf(terminalPane("p1", "t1"), stack))
    }

    @Test fun jumpToAHiddenStackChildShowsIt() {
        val root = doc()
        assertEquals(listOf("p1", "p2"), root.drawnPanes().map { it.nodeId })
        val shown = root.stackPath("p3")
        assertEquals(mapOf("st" to "p3"), shown)
        assertEquals(listOf("p1", "p3"), root.drawnPanes(shown).map { it.nodeId })
        assertEquals("p3", root.find("st")!!.activeChild(shown)!!.nodeId)
        assertEquals("p2", root.find("st")!!.activeChild()!!.nodeId) // the document itself is untouched
    }

    @Test fun visiblePaneAndMissingPaneNeedNoOverride() {
        assertEquals(emptyMap(), doc().stackPath("p1"))
        assertEquals(emptyMap(), doc().stackPath("nope"))
    }
}
