package herdr.core

/** What the show/hide button for the left panel does. */
enum class PanelToggle {
    /** Wide screen: flip the saved shown/hidden choice. */
    FlipSaved,
    /** Folded, drawer closed: slide the drawer open. */
    OpenDrawer,
    /** Folded, drawer open: slide it shut. */
    CloseDrawer,
}

/**
 * Decisions for the left panel (spaces and agents). The shown/hidden choice for
 * the wide layout is this phone's own and is never written to the shared
 * desktop layout; folded, the panel is a drawer and the choice does not apply.
 */
object LeftPanel {
    /** The panel is drawn beside the terminal (wide screen, not hidden by hand). */
    fun besideShown(wide: Boolean, hidden: Boolean): Boolean = wide && !hidden

    /** One tap on the button. */
    fun toggle(wide: Boolean, drawerOpen: Boolean): PanelToggle =
        if (wide) PanelToggle.FlipSaved else if (drawerOpen) PanelToggle.CloseDrawer else PanelToggle.OpenDrawer

    /** Back closes only an open drawer; a panel shown side by side never swallows Back. */
    fun backCloses(wide: Boolean, drawerOpen: Boolean): Boolean = !wide && drawerOpen

    /** The drawer's swipe is on only while it is open (to swipe it shut); sideways drags on the terminal stay the terminal's. */
    fun drawerGestures(drawerOpen: Boolean): Boolean = drawerOpen
}
