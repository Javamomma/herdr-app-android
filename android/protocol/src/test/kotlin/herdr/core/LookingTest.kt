package herdr.core

import kotlin.test.Test
import kotlin.test.assertEquals

class LookingTest {
    private val base = Looking.Screen(
        foreground = true, covered = false, dialogOver = false, wide = true, agentsHome = false, drawerOpen = false,
        panelOver = false, panes = listOf("a", "b"), zoomed = null, focused = null, peek = null,
    )

    @Test fun widePanesAreAllShown() = assertEquals(setOf("a", "b"), Looking.panes(base))

    @Test fun foldedShowsOnlyTheFocusedPane() {
        val s = base.copy(wide = false, focused = "b")
        assertEquals(setOf("b"), Looking.panes(s))
        assertEquals(setOf("a"), Looking.panes(s.copy(focused = "zzz")))
        assertEquals(setOf("a"), Looking.panes(base.copy(wide = false, panes = listOf("a"))))
    }

    @Test fun zoomedIsAlone() = assertEquals(setOf("b"), Looking.panes(base.copy(zoomed = "b")))

    @Test fun nobodyLooksWhenAwayOrCovered() {
        assertEquals(emptySet(), Looking.panes(base.copy(foreground = false)))
        assertEquals(emptySet(), Looking.panes(base.copy(covered = true)))
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
}
