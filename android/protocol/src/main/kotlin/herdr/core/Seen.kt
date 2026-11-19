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
 * When `herdr.seen` is written. One tracker per host; [reset] it when the host changes.
 *
 * - Only while the agent is really looked at ([Looking]).
 * - Once per turn while it stays on screen: the decision is taken the first time
 *   the turn is seen on screen (the `turn` event comes after the `status` event,
 *   so the turn number is the new one by then), so a later "mark unread", from here
 *   or another client, is not overruled by a screen that has not changed.
 * - Only to raise it, or when the label is missing.
 * - Leaving the screen forgets the decision: looking again raises it again.
 * - A terminal marked unread on this phone is held: not raised again for that turn
 *   until it has been on screen after the mark, then off screen, then on screen again
 *   (the menu lives in the drawer or the agents home, so closing it is not "coming back").
 * - A failed raise is not retried for [RETRY_MS] (no flicker, no write per event).
 * - What we wrote shows at once ([seen]) until the core's label changes.
 */
class SeenTracker {
    private class Pending(val value: Long, val labelAtWrite: Long?, val auto: Boolean)
    private class Held(val turn: Long, var phase: Int) // 0: not shown yet, 1: shown, 2: left

    private val decided = HashMap<String, Long>()
    private val pending = HashMap<String, Pending>()
    private val held = HashMap<String, Held>()
    private val retryAt = HashMap<String, Long>()

    /** The seen value to show: our own fresh write, else the label (null: missing). */
    fun seen(address: String, label: Long?): Long? {
        val p = pending[address] ?: return label
        if (label != p.labelAtWrite) {
            pending.remove(address)
            return label
        }
        return p.value
    }

    /**
     * Values to write for what is [looking] now, at time [now] (ms). [terms] holds only terminals that
     * report a status and a turn. With [canWrite] false (no link) only the look/leave bookkeeping runs.
     */
    fun raises(looking: Set<String>, terms: Map<String, SeenTerm>, now: Long = 0, canWrite: Boolean = true): List<Pair<String, Long>> {
        decided.keys.retainAll(looking)
        retryAt.keys.retainAll(decided.keys)
        for ((addr, h) in held.entries.toList()) {
            val turn = terms[addr]?.turn
            if (turn != null && turn != h.turn) {
                held.remove(addr)
            } else if (addr in looking) {
                if (h.phase == 0) h.phase = 1 else if (h.phase == 2) held.remove(addr)
            } else if (h.phase == 1) {
                h.phase = 2
            }
        }
        if (!canWrite) return emptyList()
        val out = ArrayList<Pair<String, Long>>()
        for (addr in looking) {
            val t = terms[addr] ?: continue
            if (addr in held) continue
            if (decided[addr] == t.turn) {
                val r = retryAt[addr]
                if (r == null || now < r) continue
                retryAt.remove(addr)
            }
            decided[addr] = t.turn
            val cur = seen(addr, t.label)
            if (cur == null || cur < t.turn) {
                pending[addr] = Pending(t.turn, t.label, auto = true)
                out += addr to t.turn
            }
        }
        return out
    }

    /** Mark read / unread: the value to write (null: nothing). [looking]: the person is on it now. */
    fun mark(address: String, mark: Mark, turn: Long, label: Long?, looking: Boolean): Long? {
        val v = when (mark) {
            Mark.READ -> Seen.readValue(turn)
            Mark.UNREAD -> Seen.unreadValue(turn)
            Mark.NONE -> null
        } ?: return null
        pending[address] = Pending(v, label, auto = false)
        retryAt.remove(address)
        if (mark == Mark.UNREAD) held[address] = Held(turn, if (looking) 1 else 0) else held.remove(address)
        if (looking) decided[address] = turn
        return v
    }

    /**
     * The write of [value] to [address] failed for good. Ignored when a newer write owns the
     * pending value. A failed raise waits [RETRY_MS] before the next try; a failed mark is not retried.
     */
    fun failed(address: String, value: Long, now: Long = 0) {
        val p = pending[address] ?: return
        if (p.value != value) return
        pending.remove(address)
        if (p.auto) retryAt[address] = now + RETRY_MS
    }

    /** Terminals that are gone. */
    fun retain(live: Set<String>) {
        pending.keys.retainAll(live)
        decided.keys.retainAll(live)
        held.keys.retainAll(live)
        retryAt.keys.retainAll(live)
    }

    /** The link dropped: writes in flight are lost, so what they showed is not true. */
    fun connectionLost() = pending.clear()

    /** The app left the front, the screen went off or the lock came up: nothing is looked at. */
    fun stopLooking() {
        decided.clear()
        retryAt.clear()
        for (h in held.values) if (h.phase == 1) h.phase = 2
    }

    /** Another host (or the demo): addresses such as `t1` mean other terminals. */
    fun reset() {
        decided.clear(); pending.clear(); held.clear(); retryAt.clear()
    }

    companion object {
        const val RETRY_MS = 30_000L
    }
}

/**
 * Label writes, one in flight per terminal and label, the latest value winning, so a raise
 * followed by a mark unread cannot land in the other order, and a failure belongs to its write.
 */
class LabelQueue {
    class Send internal constructor(val address: String, val key: String, val value: String, internal val epoch: Int)

    /** [next]: send this now. [failed]: the last wanted value could not be written. */
    class Done(val next: Send?, val failed: String?)

    private class Slot(var wanted: String)

    private val slots = HashMap<Pair<String, String>, Slot>()
    private var epoch = 0

    /** Returns what to send now, or null when a write is in flight (this value goes after it). */
    fun submit(address: String, key: String, value: String): Send? {
        val slot = slots[address to key]
        if (slot != null) {
            slot.wanted = value
            return null
        }
        slots[address to key] = Slot(value)
        return Send(address, key, value, epoch)
    }

    fun done(send: Send, ok: Boolean): Done {
        if (send.epoch != epoch) return Done(null, null)
        val k = send.address to send.key
        val slot = slots[k] ?: return Done(null, null)
        if (slot.wanted != send.value) return Done(Send(send.address, send.key, slot.wanted, epoch), null)
        slots.remove(k)
        return Done(null, if (ok) null else send.value)
    }

    /** Other host or a fresh connection: results of writes still in flight are ignored. */
    fun reset() {
        epoch++
        slots.clear()
    }
}

/** What is really in front of someone. */
object Looking {
    /** A pane of the shown tab: its node id and its terminal (null: a mod pane, nothing to look at). */
    data class Pane(val id: String, val terminal: String?)

    data class Screen(
        /** The app is in front and the screen is on and unlocked by the phone. */
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
        /** Files / Changes drawn over the panes and the peek (not beside them). */
        val panelOver: Boolean,
        /** Every pane of the tab, in pane order (stacks included). */
        val allPanes: List<Pane>,
        /** The panes the layout draws: a stack's active child only. */
        val drawn: List<Pane>,
        /** The zoomed pane, if the tab is zoomed. */
        val zoomed: Pane?,
        /** Node id of the focused pane (folded, several panes: the one drawn). */
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
        if (!s.foreground || s.covered || s.dialogOver || s.panelOver) return emptySet()
        s.peek?.let { return setOf(it) }
        if (s.agentsHome && !s.wide) return emptySet()
        if (!s.wide && s.drawerOpen) return emptySet()
        s.zoomed?.let { return setOfNotNull(it.terminal) }
        if (!s.wide && s.allPanes.size > 1) {
            val one = s.allPanes.firstOrNull { it.id == s.focused } ?: s.allPanes.first()
            return setOfNotNull(one.terminal)
        }
        return s.drawn.mapNotNull { it.terminal }.toSet()
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
