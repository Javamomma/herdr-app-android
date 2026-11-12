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

    /** The panel is on screen: beside the terminal, or as an open drawer. */
    fun open(wide: Boolean, hidden: Boolean, drawerOpen: Boolean): Boolean = if (wide) besideShown(wide, hidden) else drawerOpen

    /** Back closes the panel when it is on screen (wide: hides it, like the button); only the next Back leaves the app. */
    fun backCloses(wide: Boolean, hidden: Boolean, drawerOpen: Boolean): Boolean = open(wide, hidden, drawerOpen)

    /** The drawer's swipe is on only while it is open (to swipe it shut); sideways drags on the terminal stay the terminal's. */
    fun drawerGestures(drawerOpen: Boolean): Boolean = drawerOpen
}
