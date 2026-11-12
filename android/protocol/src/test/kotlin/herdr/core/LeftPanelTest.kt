package herdr.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LeftPanelTest {
    @Test fun `wide shows the panel unless hidden`() {
        assertTrue(LeftPanel.besideShown(wide = true, hidden = false))
        assertFalse(LeftPanel.besideShown(wide = true, hidden = true))
        assertFalse(LeftPanel.besideShown(wide = false, hidden = false))
    }

    @Test fun `button flips the saved choice when wide and drives the drawer when folded`() {
        assertEquals(PanelToggle.FlipSaved, LeftPanel.toggle(wide = true, drawerOpen = false))
        assertEquals(PanelToggle.FlipSaved, LeftPanel.toggle(wide = true, drawerOpen = true))
        assertEquals(PanelToggle.OpenDrawer, LeftPanel.toggle(wide = false, drawerOpen = false))
        assertEquals(PanelToggle.CloseDrawer, LeftPanel.toggle(wide = false, drawerOpen = true))
    }

    @Test fun `back closes only an open drawer`() {
        assertTrue(LeftPanel.backCloses(wide = false, drawerOpen = true))
        assertFalse(LeftPanel.backCloses(wide = false, drawerOpen = false))
        assertFalse(LeftPanel.backCloses(wide = true, drawerOpen = true))
        assertFalse(LeftPanel.backCloses(wide = true, drawerOpen = false))
    }

    @Test fun `drawer swipe is on only while open`() {
        assertFalse(LeftPanel.drawerGestures(drawerOpen = false))
        assertTrue(LeftPanel.drawerGestures(drawerOpen = true))
    }
}
