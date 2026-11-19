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

    @Test fun statusEventBeforeTurnEventWritesNothingEarly() {
        val t = SeenTracker()
        t.raises(on, terms(5, 5))
        // status event: turn still 5; then the turn event
        assertEquals(emptyList(), t.raises(on, terms(5, 5)))
        assertEquals(listOf("t1" to 6L), t.raises(on, terms(6, 5)))
    }

    @Test fun markReadAndUnreadFromTheMenuAwayFromTheScreen() {
        val t = SeenTracker()
        assertEquals(5L, t.mark("t1", Mark.READ, 5, 3, looking = false))
        assertEquals(5L, t.seen("t1", 3))
        assertNull(t.mark("t1", Mark.UNREAD, 0, 0, looking = false))
    }

    @Test fun failedWriteIsForgotten() {
        val t = SeenTracker()
        t.raises(on, terms(5, 4))
        t.failed("t1")
        assertEquals(4L, t.seen("t1", 4))
        assertEquals(listOf("t1" to 5L), t.raises(on, terms(5, 4)))
    }

    @Test fun doneIsNews() {
        assertTrue(Alerting.doneIsNews("working", 4, "done", 4))
        assertTrue(Alerting.doneIsNews("idle", 4, "done", 5))
        assertTrue(!Alerting.doneIsNews("idle", 5, "done", 5)) // mark unread elsewhere
        assertTrue(!Alerting.doneIsNews("done", 5, "done", 6))
        assertTrue(!Alerting.doneIsNews(null, null, "done", 5)) // first sight
    }
}
