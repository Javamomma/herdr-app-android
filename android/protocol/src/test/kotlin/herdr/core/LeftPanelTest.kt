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

    @Test fun `back closes the panel when it is on screen`() {
        assertTrue(LeftPanel.backCloses(wide = false, hidden = false, drawerOpen = true))
        assertFalse(LeftPanel.backCloses(wide = false, hidden = false, drawerOpen = false))
        assertTrue(LeftPanel.backCloses(wide = true, hidden = false, drawerOpen = false), "wide and shown: Back hides it")
        assertFalse(LeftPanel.backCloses(wide = true, hidden = true, drawerOpen = false), "wide and hidden: Back leaves the app")
    }

    @Test fun `open says what the button's icon shows`() {
        assertTrue(LeftPanel.open(wide = true, hidden = false, drawerOpen = false))
        assertFalse(LeftPanel.open(wide = true, hidden = true, drawerOpen = false))
        assertTrue(LeftPanel.open(wide = false, hidden = true, drawerOpen = true))
        assertFalse(LeftPanel.open(wide = false, hidden = false, drawerOpen = false))
    }

    @Test fun `drawer swipe is on only while open`() {
        assertFalse(LeftPanel.drawerGestures(drawerOpen = false))
        assertTrue(LeftPanel.drawerGestures(drawerOpen = true))
    }
}
