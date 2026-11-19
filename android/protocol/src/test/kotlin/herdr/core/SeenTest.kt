package herdr.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SeenTest {
    @Test fun parse() {
        assertEquals(3L, Seen.parse("3"))
        assertNull(Seen.parse(null))
        assertNull(Seen.parse(""))
        assertNull(Seen.parse("x"))
        assertEquals(0L, Seen.parse("0"))
    }

    @Test fun displayFollowsTheTurn() {
        assertEquals("blocked", Seen.display("blocked", 9, 1))
        assertEquals("error", Seen.display("error", 9, 1))
        assertEquals("working", Seen.display("working", 9, 1))
        assertEquals("done", Seen.display("idle", 5, 4))
        assertEquals("done", Seen.display("done", 5, 4))
        assertEquals("idle", Seen.display("done", 5, 5))
        assertEquals("idle", Seen.display("idle", 5, 7))
    }

    @Test fun noLabelKeepsTheReportedState() {
        assertEquals("done", Seen.display("done", 5, null))
        assertEquals("idle", Seen.display("idle", 5, null))
        assertEquals("done", Seen.display("done", 0, null))
    }

    @Test fun markWrites() {
        assertEquals(4L, Seen.unreadValue(5))
        assertNull(Seen.unreadValue(0))
        assertEquals(5L, Seen.readValue(5))
        assertEquals(Mark.READ, Seen.mark("idle", 5, 4))
        assertEquals(Mark.UNREAD, Seen.mark("idle", 5, 5))
        assertEquals(Mark.NONE, Seen.mark("idle", 0, 0))
        assertEquals(Mark.READ, Seen.mark("done", 3, null))
        assertEquals(Mark.UNREAD, Seen.mark("working", 3, 3))
    }

    @Test fun pins() {
        assertTrue(Pins.isPinned("1"))
        assertTrue(!Pins.isPinned(""))
        assertTrue(!Pins.isPinned(null))
        assertEquals("1", Pins.value(true))
        assertEquals("", Pins.value(false))
        assertEquals(listOf("b", "d", "a", "c"), Pins.order(listOf("a", "b", "c", "d")) { it in setOf("b", "d") })
    }

    private fun terms(turn: Long, label: Long?) = mapOf("t1" to SeenTerm(turn, label))
    private val on = setOf("t1")

    @Test fun writesOnceToRaise() {
        val t = SeenTracker()
        assertEquals(listOf("t1" to 5L), t.raises(on, terms(5, 4)))
        // The label has not come back yet: no second write, and it already shows as seen.
        assertEquals(emptyList(), t.raises(on, terms(5, 4)))
        assertEquals(5L, t.seen("t1", 4))
        // The label arrives.
        assertEquals(5L, t.seen("t1", 5))
        assertEquals(emptyList(), t.raises(on, terms(5, 5)))
    }

    @Test fun missingLabelIsWritten() {
        val t = SeenTracker()
        assertEquals(listOf("t1" to 0L), t.raises(on, terms(0, null)))
    }

    @Test fun neverLowers() {
        val t = SeenTracker()
        assertEquals(emptyList(), t.raises(on, terms(3, 7)))
    }

    @Test fun notLookingWritesNothing() {
        val t = SeenTracker()
        assertEquals(emptyList(), t.raises(emptySet(), terms(5, 1)))
    }

    @Test fun markUnreadWhileOnScreenIsNotOverruled() {
        val t = SeenTracker()
        t.raises(on, terms(5, 5)) // looked at: nothing to raise, but decided for turn 5
        assertEquals(4L, t.mark("t1", Mark.UNREAD, 5, 5, looking = true))
        assertEquals(4L, t.seen("t1", 5))
        assertEquals(emptyList(), t.raises(on, terms(5, 5)))
        // Another client's label arrives: still not overruled.
        assertEquals(emptyList(), t.raises(on, terms(5, 4)))
    }

    @Test fun lookingAgainRaisesAgain() {
        val t = SeenTracker()
        t.raises(on, terms(5, 5))
        assertEquals(4L, t.mark("t1", Mark.UNREAD, 5, 5, looking = true))
        t.raises(emptySet(), terms(5, 4)) // left the screen
        assertEquals(listOf("t1" to 5L), t.raises(on, terms(5, 4)))
    }

    @Test fun nextTurnOnScreenIsWritten() {
        val t = SeenTracker()
        t.raises(on, terms(5, 5))
        assertEquals(listOf("t1" to 6L), t.raises(on, terms(6, 5)))
    }

    @Test fun statusThenTurnEvent() {
        val t = SeenTracker()
        // Looking at an agent at turn 5, seen 5; it was working.
        assertEquals(emptyList(), t.raises(on, terms(5, 5)))
        // The status event says "done" first: the turn is still 5, display is idle, nothing is written.
        assertEquals("idle", Seen.display("done", 5, t.seen("t1", 5)))
        assertEquals(emptyList(), t.raises(on, terms(5, 5)))
        // Then the turn event: 6, the label 5. Raised once.
        assertEquals(listOf("t1" to 6L), t.raises(on, terms(6, 5)))
        assertEquals(emptyList(), t.raises(on, terms(6, 5)))
        // Off screen, the same pair shows "done" and writes nothing.
        val away = SeenTracker()
        assertEquals(emptyList(), away.raises(emptySet(), emptyMap()))
        assertEquals("done", Seen.display("done", 6, away.seen("t1", 5)))
    }

    @Test fun markReadAndUnreadFromTheMenuAwayFromTheScreen() {
        val t = SeenTracker()
        assertEquals(5L, t.mark("t1", Mark.READ, 5, 3, looking = false))
        assertEquals(5L, t.seen("t1", 3))
        assertNull(t.mark("t1", Mark.UNREAD, 0, 0, looking = false))
    }

    @Test fun failedWriteIsForgotten() {
        val t = SeenTracker()
        assertEquals(listOf("t1" to 5L), t.raises(on, terms(5, 4), now = 0))
        t.failed("t1", 5, now = 0)
        assertEquals(4L, t.seen("t1", 4))
        // Not retried on every event, only after the backoff.
        assertEquals(emptyList(), t.raises(on, terms(5, 4), now = 1_000))
        assertEquals(emptyList(), t.raises(on, terms(5, 4), now = SeenTracker.RETRY_MS - 1))
        assertEquals(listOf("t1" to 5L), t.raises(on, terms(5, 4), now = SeenTracker.RETRY_MS))
    }

    @Test fun doneIsNews() {
        assertTrue(Alerting.doneIsNews("working", 4, "done", 4))
        assertTrue(Alerting.doneIsNews("idle", 4, "done", 5))
        assertTrue(!Alerting.doneIsNews("idle", 5, "done", 5)) // mark unread elsewhere
        assertTrue(!Alerting.doneIsNews("done", 5, "done", 6))
        assertTrue(!Alerting.doneIsNews(null, null, "done", 5)) // first sight
    }

    @Test fun failureOfAnOlderWriteDoesNotWipeANewerMark() {
        val t = SeenTracker()
        assertEquals(listOf("t1" to 5L), t.raises(on, terms(5, 4)))
        assertEquals(4L, t.mark("t1", Mark.UNREAD, 5, 4, looking = true))
        t.failed("t1", 5) // the raise's failure arrives late
        assertEquals(4L, t.seen("t1", 4)) // the mark still shows
        assertEquals(emptyList(), t.raises(on, terms(5, 4), now = 1_000_000))
    }

    @Test fun failedMarkIsNotRetriedAndDropsItsOverlay() {
        val t = SeenTracker()
        t.raises(on, terms(5, 5))
        t.mark("t1", Mark.UNREAD, 5, 5, looking = true)
        t.failed("t1", 4, now = 0)
        assertEquals(5L, t.seen("t1", 5))
        assertEquals(emptyList(), t.raises(on, terms(5, 5), now = 1_000_000))
    }

    @Test fun markedUnreadIsHeldUntilItLeavesAndComesBack() {
        val t = SeenTracker()
        // Folded: the menu is in the drawer, so nothing is looked at when the mark is made.
        assertEquals(4L, t.mark("t1", Mark.UNREAD, 5, 5, looking = false))
        // The drawer closes: on screen again after the mark. Not raised.
        assertEquals(emptyList(), t.raises(on, terms(5, 4)))
        assertEquals(emptyList(), t.raises(on, terms(5, 4)))
        // Leaves, comes back: raised.
        assertEquals(emptyList(), t.raises(emptySet(), emptyMap()))
        assertEquals(listOf("t1" to 5L), t.raises(on, terms(5, 4)))
    }

    @Test fun markedUnreadWhileOnScreenNeedsOneLeaveAndReturn() {
        val t = SeenTracker()
        t.mark("t1", Mark.UNREAD, 5, 5, looking = true)
        assertEquals(emptyList(), t.raises(on, terms(5, 4)))
        t.raises(emptySet(), emptyMap())
        assertEquals(listOf("t1" to 5L), t.raises(on, terms(5, 4)))
    }

    @Test fun heldMarkEndsWithANewTurnOrMarkRead() {
        val t = SeenTracker()
        t.mark("t1", Mark.UNREAD, 5, 5, looking = true)
        assertEquals(listOf("t1" to 6L), t.raises(on, terms(6, 4)))
        val u = SeenTracker()
        u.mark("t1", Mark.UNREAD, 5, 5, looking = true)
        u.mark("t1", Mark.READ, 5, 4, looking = true)
        u.raises(emptySet(), emptyMap())
        assertEquals(emptyList(), u.raises(on, terms(5, 5)))
    }

    @Test fun retainAndStopLooking() {
        val t = SeenTracker()
        t.raises(on, terms(5, 4))
        t.retain(emptySet())
        assertEquals(4L, t.seen("t1", 4)) // pending gone
        val u = SeenTracker()
        u.raises(on, terms(5, 5))
        u.mark("t1", Mark.UNREAD, 5, 5, looking = true)
        u.stopLooking() // background, screen off, lock
        assertEquals(listOf("t1" to 5L), u.raises(on, terms(5, 4)))
    }

    @Test fun bookkeepingRunsWithoutALink() {
        val t = SeenTracker()
        t.raises(on, terms(5, 5))
        t.mark("t1", Mark.UNREAD, 5, 5, looking = true)
        // Disconnected: leaving is still noticed, and nothing is written or decided.
        assertEquals(emptyList(), t.raises(emptySet(), emptyMap(), canWrite = false))
        assertEquals(emptyList(), t.raises(on, terms(7, 4), canWrite = false))
        assertEquals(listOf("t1" to 7L), t.raises(on, terms(7, 4)))
    }

    @Test fun resetForgetsEverythingForAnotherHost() {
        val t = SeenTracker()
        t.raises(on, terms(5, 4))
        t.mark("t1", Mark.UNREAD, 5, 5, looking = true)
        t.reset()
        assertEquals(9L, t.seen("t1", 9))
        assertEquals(listOf("t1" to 5L), t.raises(on, terms(5, 4)))
    }

    @Test fun connectionLostDropsWhatWasInFlight() {
        val t = SeenTracker()
        t.raises(on, terms(5, 4))
        t.connectionLost()
        assertEquals(4L, t.seen("t1", 4))
    }

    // ---- the write queue ----

    @Test fun queueIsLatestWinsAndInOrder() {
        val q = LabelQueue()
        val first = q.submit("t1", Labels.SEEN, "5")!!
        assertNull(q.submit("t1", Labels.SEEN, "4")) // the mark waits for the raise
        assertNull(q.submit("t1", Labels.SEEN, "3"))
        val next = q.done(first, ok = true).next!!
        assertEquals("3", next.value)
        val end = q.done(next, ok = true)
        assertNull(end.next)
        assertNull(end.failed)
        assertTrue(q.submit("t1", Labels.SEEN, "6") != null)
    }

    @Test fun queueKeepsLabelsAndTerminalsApart() {
        val q = LabelQueue()
        assertTrue(q.submit("t1", Labels.SEEN, "5") != null)
        assertTrue(q.submit("t1", Labels.PINNED, "1") != null)
        assertTrue(q.submit("t2", Labels.SEEN, "5") != null)
    }

    @Test fun queueFailureBelongsToTheLastWrite() {
        val q = LabelQueue()
        val first = q.submit("t1", Labels.SEEN, "5")!!
        q.submit("t1", Labels.SEEN, "4")
        val second = q.done(first, ok = false) // the raise failed, but a newer mark is wanted
        assertNull(second.failed)
        assertEquals("4", second.next!!.value)
        assertEquals("4", q.done(second.next!!, ok = false).failed)
    }

    @Test fun queueIgnoresResultsFromBeforeAReset() {
        val q = LabelQueue()
        val old = q.submit("t1", Labels.SEEN, "5")!!
        q.reset()
        val fresh = q.submit("t1", Labels.SEEN, "7")!!
        val r = q.done(old, ok = false)
        assertNull(r.next)
        assertNull(r.failed)
        assertEquals("7", fresh.value)
    }
}
