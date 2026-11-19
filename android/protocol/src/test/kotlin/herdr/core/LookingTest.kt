package herdr.core

import kotlin.test.Test
import kotlin.test.assertEquals

class LookingTest {
    private fun p(id: String, t: String?) = Looking.Pane(id, t)
    private val a = p("pa", "a")
    private val b = p("pb", "b")
    private val mod = p("pm", null)

    private val base = Looking.Screen(
        foreground = true, covered = false, dialogOver = false, wide = true, agentsHome = false, drawerOpen = false,
        panelOver = false, allPanes = listOf(a, b), drawn = listOf(a, b), zoomed = null, focused = null, peek = null,
    )

    @Test fun widePanesAreAllShown() = assertEquals(setOf("a", "b"), Looking.panes(base))

    @Test fun foldedShowsOnlyTheFocusedPane() {
        val s = base.copy(wide = false, focused = "pb")
        assertEquals(setOf("b"), Looking.panes(s))
        assertEquals(setOf("a"), Looking.panes(s.copy(focused = "zzz")))
        assertEquals(setOf("a"), Looking.panes(base.copy(wide = false, allPanes = listOf(a), drawn = listOf(a))))
    }

    @Test fun zoomedIsAlone() = assertEquals(setOf("b"), Looking.panes(base.copy(zoomed = b)))

    @Test fun zoomedModPaneIsNobody() = assertEquals(emptySet(), Looking.panes(base.copy(zoomed = mod)))

    @Test fun foldedModPaneShownMeansNobody() {
        val s = base.copy(wide = false, allPanes = listOf(mod, a), drawn = listOf(mod, a), focused = "pm")
        assertEquals(emptySet(), Looking.panes(s))
        assertEquals(setOf("a"), Looking.panes(s.copy(focused = "pa")))
        assertEquals(emptySet(), Looking.panes(s.copy(focused = null))) // first pane is drawn
    }

    @Test fun wideModPaneIsNotAnAgent() =
        assertEquals(setOf("a"), Looking.panes(base.copy(allPanes = listOf(mod, a), drawn = listOf(mod, a))))

    @Test fun stackCountsOnlyItsActiveChild() {
        // A row of [stack(a, b active), c]: the layout draws b and c only.
        val c = p("pc", "c")
        assertEquals(setOf("b", "c"), Looking.panes(base.copy(allPanes = listOf(a, b, c), drawn = listOf(b, c))))
    }

    @Test fun nobodyLooksWhenAwayOrCovered() {
        assertEquals(emptySet(), Looking.panes(base.copy(foreground = false)))
        assertEquals(emptySet(), Looking.panes(base.copy(covered = true, peek = "a")))
        assertEquals(emptySet(), Looking.panes(base.copy(dialogOver = true, peek = "a")))
        assertEquals(emptySet(), Looking.panes(base.copy(wide = false, agentsHome = true)))
        assertEquals(emptySet(), Looking.panes(base.copy(wide = false, drawerOpen = true)))
        assertEquals(emptySet(), Looking.panes(base.copy(panelOver = true)))
    }

    @Test fun wideDrawerFlagDoesNotHide() = assertEquals(setOf("a", "b"), Looking.panes(base.copy(drawerOpen = true)))

    @Test fun peekIsLookedAtAlone() {
        assertEquals(setOf("x"), Looking.panes(base.copy(peek = "x")))
        assertEquals(setOf("x"), Looking.panes(base.copy(wide = false, agentsHome = true, peek = "x")))
    }

    @Test fun peekUnderAPanelIsNotLookedAt() = assertEquals(emptySet(), Looking.panes(base.copy(panelOver = true, peek = "x")))

    @Test fun drawnPanesFollowStacks() {
        val stack = obj("id" to "s", "type" to "stack", "active" to "pb", "children" to listOf(terminalPane("pa", "a"), terminalPane("pb", "b")))
        val root = container("r", Axis.Row, listOf(stack, terminalPane("pc", "c")))
        assertEquals(listOf("b", "c"), root.drawnPanes().map { it.terminal })
        assertEquals(listOf("a", "b", "c"), root.panes().map { it.terminal })
    }
}
