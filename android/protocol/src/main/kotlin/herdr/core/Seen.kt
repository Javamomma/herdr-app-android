package herdr.core

/** The shared terminal labels (docs/labels.md): the desktop app and the official mobile app read and write the same ones. */
object Labels {
    const val SEEN = "herdr.seen"
    const val PINNED = "herdr.pinned"
}

/** What the mark-read / mark-unread menu entry does for an agent. */
enum class Mark { READ, UNREAD, NONE }

/** `herdr.seen=<turn>`: how an agent shows, and what the menu writes. */
object Seen {
    /** A missing or unreadable label is not 0: it is null. */
    fun parse(raw: String?): Long? = raw?.trim()?.toLongOrNull()?.takeIf { it >= 0 }

    /**
     * The state shown. Blocked, error and working are never changed. Otherwise
     * (idle, done, ...) it is done when `turn > seen`, else idle. With no
     * label the client's own rule applies: the reported state is kept.
     */
    fun display(state: String, turn: Long, seen: Long?): String = when {
        state == "blocked" || state == "error" || state == "working" -> state
        seen == null -> state
        turn > seen -> "done"
        else -> "idle"
    }

    /** Finished and not yet looked at. */
    fun unread(state: String, turn: Long, seen: Long?): Boolean = if (seen != null) turn > seen else state == "done"

    /** Mark read when unread, else Mark unread (not offered at turn 0: nothing has finished). */
    fun mark(state: String, turn: Long, seen: Long?): Mark = when {
        unread(state, turn, seen) -> Mark.READ
        turn > 0 -> Mark.UNREAD
        else -> Mark.NONE
    }

    fun readValue(turn: Long): Long = turn

    /** `turn - 1`; null at turn 0 (nothing to mark). */
    fun unreadValue(turn: Long): Long? = if (turn > 0) turn - 1 else null
}

/** `herdr.pinned=1`: pinned. Present and non-empty means pinned; unpin writes it empty. */
object Pins {
    fun isPinned(raw: String?): Boolean = !raw.isNullOrEmpty()

    fun value(pinned: Boolean): String = if (pinned) "1" else ""

    /** Pinned first, otherwise the order given (stable). */
    fun <T> order(items: List<T>, pinned: (T) -> Boolean): List<T> = items.filter(pinned) + items.filterNot(pinned)
}

/** One terminal as the seen rules need it. */
data class SeenTerm(val turn: Long, val label: Long?)

/**
 * When `herdr.seen` is written. One tracker per connection.
 *
 * - Only while the agent is really looked at ([Looking]).
 * - Once per turn while it stays on screen: the decision is taken the first time
 *   the turn is seen on screen (the `turn` event comes after the `status` event,
 *   so the turn number is the new one by then), so a "mark unread" later, from
 *   here or another client, is not overruled by a screen that has not changed.
 * - Only to raise it, or when the label is missing.
 * - Leaving the screen forgets the decision: looking again raises it again.
 * - What we wrote shows at once ([seen]) until the core's label says otherwise.
 */
class SeenTracker {
    private class Pending(val value: Long, val labelAtWrite: Long?)

    private val decided = HashMap<String, Long>()
    private val pending = HashMap<String, Pending>()

    /** The seen value to show: our own fresh write, else the label (null: missing). */
    fun seen(address: String, label: Long?): Long? {
        val p = pending[address] ?: return label
        if (label != p.labelAtWrite) {
            pending.remove(address)
            return label
        }
        return p.value
    }

    /** Values to write for what is [looking] now. [terms] holds only terminals that report a status and a turn. */
    fun raises(looking: Set<String>, terms: Map<String, SeenTerm>): List<Pair<String, Long>> {
        decided.keys.retainAll(looking)
        val out = ArrayList<Pair<String, Long>>()
        for (addr in looking) {
            val t = terms[addr] ?: continue
            if (decided[addr] == t.turn) continue
            decided[addr] = t.turn
            val now = seen(addr, t.label)
            if (now == null || now < t.turn) {
                pending[addr] = Pending(t.turn, t.label)
                out += addr to t.turn
            }
        }
        return out
    }

    /** Mark read / unread: the value to write (null: nothing). [looking]: the person is on it, so it is not overruled. */
    fun mark(address: String, mark: Mark, turn: Long, label: Long?, looking: Boolean): Long? {
        val v = when (mark) {
            Mark.READ -> Seen.readValue(turn)
            Mark.UNREAD -> Seen.unreadValue(turn)
            Mark.NONE -> null
        } ?: return null
        pending[address] = Pending(v, label)
        if (looking) decided[address] = turn
        return v
    }

    /** A write failed: forget it so the next look tries again. */
    fun failed(address: String) {
        pending.remove(address)
        decided.remove(address)
    }

    /** Terminals that are gone. */
    fun retain(live: Set<String>) {
        pending.keys.retainAll(live)
        decided.keys.retainAll(live)
    }

    /** The app left the front or the screen: nothing is looked at. */
    fun stopLooking() = decided.clear()
}

/** What is really in front of someone. */
object Looking {
    data class Screen(
        val foreground: Boolean,
        /** The app lock, the connect screen or another screen is in front. */
        val covered: Boolean,
        /** Settings or the saved commands, drawn over everything. */
        val dialogOver: Boolean,
        /** The wide layout (panes side by side) rather than the folded one. */
        val wide: Boolean,
        /** The folded agents home replaces the panes. */
        val agentsHome: Boolean,
        /** Folded: the left drawer is open over the panes. */
        val drawerOpen: Boolean,
        /** Files / Changes drawn over the panes (not beside them). */
        val panelOver: Boolean,
        /** Terminals of the shown tab, in pane order. */
        val panes: List<String>,
        val zoomed: String?,
        /** Folded with several panes: the one shown. */
        val focused: String?,
        val peek: String?,
    )

    /**
     * The queue section is really drawn: the app in front, nothing over it, and the list it is in shown: the wide
     * left panel ([sidebarBeside]), the folded drawer when open, or the folded agents home. [otherDialog]: any
     * other dialog (new space, close, rename…) is up.
     */
    fun queueShown(s: Screen, sidebarBeside: Boolean, otherDialog: Boolean, inAgentsHome: Boolean): Boolean {
        if (!s.foreground || s.covered || s.dialogOver || otherDialog || s.peek != null || s.panelOver) return false
        return if (inAgentsHome) !s.wide && s.agentsHome
        else if (s.wide) sidebarBeside
        else s.drawerOpen
    }

    fun panes(s: Screen): Set<String> {
        if (!s.foreground || s.covered || s.dialogOver) return emptySet()
        s.peek?.let { return setOf(it) }
        if (s.agentsHome && !s.wide) return emptySet()
        if (!s.wide && s.drawerOpen) return emptySet()
        if (s.panelOver) return emptySet()
        s.zoomed?.let { return setOf(it) }
        if (!s.wide && s.panes.size > 1) return setOfNotNull(s.focused?.takeIf { it in s.panes } ?: s.panes.first())
        return s.panes.toSet()
    }
}

/** Notices: when "done" is news. */
object Alerting {
    /**
     * A turn finished while nobody looked: the agent shows done now and was
     * working, or its turn number just rose (status event first, then turn).
     * A label changed elsewhere (mark unread) is not news.
     */
    fun doneIsNews(prevState: String?, prevTurn: Long?, state: String, turn: Long): Boolean =
        state == "done" && prevState != "done" && (prevState == "working" || (prevTurn != null && turn > prevTurn))
}
