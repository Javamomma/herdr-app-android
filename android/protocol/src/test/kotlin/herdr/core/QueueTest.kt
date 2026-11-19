package herdr.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class QueueTest {
    private fun parsed(text: String) = assertIs<QueueParse.Parsed>(Queue.parse(text))
    private fun item(url: String? = null, file: String? = null, detail: String = "") =
        QueueItem("1", "email", "mail", "Reply to Sam", detail, 2, file, url, null)

    @Test fun readsTheDocumentedShapeInOrder() {
        val p = parsed("""[
          {"id":"a","kind":"email","source":"mail","title":"First","detail":"d","age_days":3,"file":null,"verbs":[{"x":1}]},
          {"id":"b","kind":"pull request","source":"gh","title":"Second","detail":"https://example.com/p/1","age_days":0,"file":"/tmp/x.md","url":"https://example.com/p/1","expires_in_days":4}
        ]""")
        assertEquals(listOf("a", "b"), p.items.map { it.id })
        assertEquals(3, p.items[0].ageDays)
        assertNull(p.items[0].file)
        assertEquals("/tmp/x.md", p.items[1].file)
        assertEquals(4, p.items[1].expiresInDays)
    }

    @Test fun leavesOutAgentRowsOnly() {
        val p = parsed("""[
          {"id":"1","kind":"agent ask","title":"t"},{"id":"2","kind":"Agent stopped","title":"t"},
          {"id":"3","kind":"agents","title":"t"},{"id":"4","kind":"decision","title":"keep"},{"id":"5","title":"no kind"}
        ]""")
        assertEquals(listOf("4", "5"), p.items.map { it.id })
        assertEquals(3, p.agentRows)
    }

    @Test fun skipsBrokenEntriesAndIgnoresUnknownFields() {
        val p = parsed("""[1,"x",null,{"title":"no id"},{"id":"i"},{"id":"","title":"t"},{"id":7,"title":"t"},{"id":"ok","title":"fine","future":{"a":[1]},"age_days":"3"}]""")
        assertEquals(listOf("ok"), p.items.map { it.id })
        assertEquals(7, p.skipped)
        assertNull(p.items[0].ageDays)
    }

    @Test fun notTheExpectedJsonIsReportedNeverACrash() {
        for (t in listOf("", "hello", "{}", "{\"a\":1}", "[", "null", "\"x\"", "42"))
            assertIs<QueueParse.Bad>(Queue.parse(t), t)
        assertEquals(emptyList(), parsed("[]").items)
    }

    @Test fun capsItemsAndOutputAndLongFields() {
        val many = (1..150).joinToString(",", "[", "]") { """{"id":"$it","title":"t"}""" }
        val p = parsed(many)
        assertEquals(100, p.items.size)
        assertEquals(50, p.cut)
        assertEquals("1", p.items.first().id)
        assertIs<QueueParse.Bad>(Queue.parse("[" + " ".repeat(Queue.MAX_OUTPUT_CHARS) + "]"))
        val long = parsed("""[{"id":"a","title":"${"x".repeat(5000)}","detail":"${"y".repeat(9000)}"}]""").items[0]
        assertEquals(300, long.title.length)
        assertEquals(2000, long.detail.length)
    }

    @Test fun onlyHttpAndHttpsOpen() {
        for (u in listOf("https://example.com/a?b=1", "http://example.com"))
            assertEquals(QueueTarget.Url(u), Queue.target(item(url = u)), u)
        // The scheme is lower-cased before it is opened (the rest is left alone).
        assertEquals(QueueTarget.Url("https://Example.com/A"), Queue.target(item(url = "HTTPS://Example.com/A")))
        assertEquals(QueueTarget.Url("http://a.b"), Queue.target(item(url = "HtTp://a.b")))
        for (u in listOf("javascript:alert(1)", "file:///etc/passwd", "intent://x#Intent;end", "content://a/b", "tel:123", "ftp://a.b", "https://", "https:///x", "https://a b", "https://a\nb", "//example.com", "example.com", "https://@", "https://@host/x", "https://:80/x"))
            assertFalse(Queue.openable(u), u)
    }

    @Test fun targetOrderIsUrlThenFileThenDetail() {
        assertEquals(QueueTarget.Url("https://a.b/c"), Queue.target(item(url = "https://a.b/c", file = "/x")))
        assertEquals(QueueTarget.File("/x"), Queue.target(item(file = "/x")))
        // A refused url falls to the file, or is shown as text with the detail.
        assertEquals(QueueTarget.File("/x"), Queue.target(item(url = "javascript:1", file = "/x")))
        assertEquals(QueueTarget.Detail("why\njavascript:1"), Queue.target(item(url = "javascript:1", detail = "why")))
        assertEquals(QueueTarget.Detail("some detail"), Queue.target(item(detail = "some detail")))
        // A detail that looks like a URL is not opened.
        assertEquals(QueueTarget.Detail("https://a.b"), Queue.target(item(detail = "https://a.b")))
        assertEquals(QueueTarget.Detail("Reply to Sam"), Queue.target(item()))
    }

    @Test fun headingAndUpdatedText() {
        val one = parsed("""[{"id":"1","title":"t"}]""").items
        assertEquals("Queue · 1", Queue.heading(QueueState().loaded(one, 0)))
        assertEquals("Queue · nothing waiting", Queue.heading(QueueState().loaded(emptyList(), 0)))
        assertEquals("Queue · couldn't load", Queue.heading(QueueState().failed(QueueFailure.NotConnected)))
        assertEquals("Queue · 1", Queue.heading(QueueState().loaded(one, 0).failed(QueueFailure.TimedOut)))
        assertEquals("updated just now", Queue.updatedText(1_000, 30_000))
        assertEquals("updated 3 min ago", Queue.updatedText(0, 3 * 60_000L + 5))
        assertEquals("updated 2 h ago", Queue.updatedText(0, 2 * 3_600_000L))
        assertEquals("updated just now", Queue.updatedText(10_000, 0))
    }

    @Test fun failureKeepsTheLastGoodListAsStale() {
        val one = parsed("""[{"id":"1","title":"t"}]""").items
        var s = QueueState()
        assertFalse(s.stale)
        s = s.started().loaded(one, 5)
        assertFalse(s.stale)
        s = s.started().failed(QueueFailure.TimedOut)
        assertTrue(s.stale)
        assertEquals(one, s.items)
        assertEquals(5L, s.loadedAt)
        assertFalse(s.loading)
        s = s.started().loaded(one, 9)
        assertFalse(s.stale)
        assertNull(s.failure)
        assertFalse(QueueState().failed(QueueFailure.NotConnected).stale)
    }

    @Test fun outcomeNamesTheReason() {
        val ok = """[{"id":"1","title":"t"}]"""
        assertIs<QueueResult.Loaded>(Queue.outcome(0, ok, "", false, false))
        assertIs<QueueResult.Loaded>(Queue.outcome(null, ok, "", false, false))
        assertEquals(QueueFailure.TimedOut, (Queue.outcome(null, "", "", true, false) as QueueResult.Failed).why)
        val ex = (Queue.outcome(2, "", "\n  boom: no such file\nTraceback…", false, false) as QueueResult.Failed).why
        assertEquals(QueueFailure.Exited(2, "boom: no such file"), ex)
        assertEquals("The command failed (exit 2): boom: no such file", ex.message)
        assertEquals("The command failed (exit 127)", (Queue.outcome(127, "", "", false, false) as QueueResult.Failed).why.message)
        assertIs<QueueFailure.BadOutput>((Queue.outcome(0, "oops", "", false, false) as QueueResult.Failed).why)
        assertIs<QueueFailure.BadOutput>((Queue.outcome(0, ok, "", false, true) as QueueResult.Failed).why)
        // A failing command wins over its output.
        assertIs<QueueFailure.Exited>((Queue.outcome(1, ok, "", false, false) as QueueResult.Failed).why)
    }

    @Test fun refreshIsDueEveryThreeMinutes() {
        val idle = QueueState()
        assertTrue(Queue.due(idle, null, 0))
        assertFalse(Queue.due(idle, 0, 179_999))
        assertTrue(Queue.due(idle, 0, 180_000))
        assertFalse(Queue.due(idle.started(), null, 999_999))
        assertTrue(Queue.due(idle, 0, 30_000, Queue.SHOW_MIN_MS))
        assertFalse(Queue.due(idle, 0, 29_999, Queue.SHOW_MIN_MS))
        // Failed for lack of a connection: try again as soon as there is one, not three minutes later.
        val offline = idle.failed(QueueFailure.NotConnected)
        assertTrue(Queue.due(offline, 0, 1, connected = true))
        assertFalse(Queue.due(offline, 0, 1, connected = false))
    }

    @Test fun pathRules() {
        for (bad in listOf("", "   ", "a\u0000b", "/tmp/a\nb", "/tmp/a\rb", "/tmp/a\tb", "/tmp/\u001b[2Jx", "/x".repeat(3000)))
            assertFalse(Queue.validPath(bad), bad.take(20))
        for (good in listOf("/tmp/a.md", "~/notes/a b.md", "/tmp/a:12", "/x".repeat(2000)))
            assertTrue(Queue.validPath(good), good.take(20))
        // An item whose file is refused is kept, and opens as text.
        val p = parsed("""[{"id":"1","title":"t","detail":"d","file":"/tmp/a\nb"}]""").items[0]
        assertNull(p.file)
        assertEquals(QueueTarget.Detail("d"), Queue.target(p))
        // A colon stays in the path (the exact path is opened).
        assertEquals(QueueTarget.File("/tmp/a:12"), Queue.target(item(file = "/tmp/a:12")))
    }

    @Test fun kindWithLeadingSpaceIsStillAnAgentRow() {
        val p = parsed("""[{"id":"1","kind":"  agent ask","title":"t"},{"id":"2","kind":"\tAgent","title":"t"},{"id":"3","kind":"an agent","title":"t"}]""")
        assertEquals(listOf("3"), p.items.map { it.id })
    }

    @Test fun urlWhitespaceAndLength() {
        assertEquals(QueueTarget.Url("https://a.b/c"), Queue.target(item(url = "   https://a.b/c  ")))
        val p = parsed("""[{"id":"1","title":"t","url":"   https://a.b/c "}]""").items[0]
        assertEquals("https://a.b/c", p.url)
        val ok = "https://a.b/" + "x".repeat(Queue.MAX_URL - 12)
        assertEquals(Queue.MAX_URL, ok.length)
        assertEquals(QueueTarget.Url(ok), Queue.target(item(url = ok)))
        // Too long: never cut and opened as another address; shown as text.
        val long = ok + "y"
        assertFalse(Queue.openable(long))
        val t = Queue.target(item(url = long, detail = "d"))
        assertIs<QueueTarget.Detail>(t)
        val huge = "https://a.b/" + "z".repeat(50_000)
        val kept = parsed("""[{"id":"1","title":"t","url":"$huge"}]""").items[0]
        assertFalse(Queue.openable(kept.url!!))
        assertIs<QueueTarget.Detail>(Queue.target(kept))
    }

    @Test fun draftChangesNothingUntilSaved() {
        var d = QueueDraft("python3 a.py queue")
        assertFalse(d.dirty)
        assertNull(d.commit())
        // Every keystroke of a long command is only the draft.
        var typed = ""
        for (c in "rm -rf ~/x; python3 a.py") { typed += c; d = d.edit(typed); assertEquals("python3 a.py queue", d.saved) }
        assertTrue(d.dirty)
        assertEquals("rm -rf ~/x; python3 a.py", d.commit())
        assertEquals("", QueueDraft("x").edit("  \n ").commit())
        assertEquals(Queue.DEFAULT_COMMAND, QueueDraft("x").reset().commit())
        assertNull(QueueDraft(Queue.DEFAULT_COMMAND).reset().commit())
        assertNull(QueueDraft("x").edit(" x ").commit())
    }

    @Test fun onScreenGate() {
        fun screen(wide: Boolean = false, agentsHome: Boolean = false, drawerOpen: Boolean = false, dialogOver: Boolean = false, panelOver: Boolean = false,
                   foreground: Boolean = true, covered: Boolean = false, peek: String? = null) =
            Looking.Screen(foreground, covered, dialogOver, wide, agentsHome, drawerOpen, panelOver, emptyList(), emptyList(), null, null, peek)
        // Folded in a terminal with the drawer shut: not on screen, however long it stays.
        assertFalse(Looking.queueShown(screen(), sidebarBeside = false, otherDialog = false, inAgentsHome = false))
        assertTrue(Looking.queueShown(screen(drawerOpen = true), false, false, false))
        assertTrue(Looking.queueShown(screen(wide = true), sidebarBeside = true, otherDialog = false, inAgentsHome = false))
        assertFalse(Looking.queueShown(screen(wide = true), sidebarBeside = false, otherDialog = false, inAgentsHome = false))
        assertTrue(Looking.queueShown(screen(agentsHome = true), false, false, inAgentsHome = true))
        assertFalse(Looking.queueShown(screen(), false, false, inAgentsHome = true))
        assertFalse(Looking.queueShown(screen(wide = true, agentsHome = true), true, false, inAgentsHome = true))
        // Nothing over it, app in front.
        for (s in listOf(screen(drawerOpen = true, dialogOver = true), screen(drawerOpen = true, panelOver = true), screen(drawerOpen = true, foreground = false),
            screen(drawerOpen = true, covered = true), screen(drawerOpen = true, peek = "t1")))
            assertFalse(Looking.queueShown(s, true, false, false))
        assertFalse(Looking.queueShown(screen(drawerOpen = true), false, otherDialog = true, inAgentsHome = false))
    }

    private val ok = QueueResult.Loaded(parsed("""[{"id":"1","title":"t"}]""").items)
    private val oldCore = Any()
    private val newCore = Any()

    @Test fun anAnswerFromAnOldCoreIsDropped() {
        val m = QueueModel()
        val t = m.begin(oldCore, 100)!!
        assertTrue(m.state.loading)
        // The link dropped and came back (another core) before the answer.
        assertFalse(m.finish(t, newCore, ok, 200))
        assertEquals(emptyList(), m.state.items)
        assertFalse(m.state.loading)
        assertTrue(m.due(Queue.SHOW_MIN_MS, 201, true), "that try does not count")
        val t2 = m.begin(newCore, 300)!!
        assertTrue(m.finish(t2, newCore, ok, 310))
        assertEquals(1, m.state.items.size)
    }

    @Test fun clearingDropsWhatIsInFlightAndWhatIsKept() {
        val m = QueueModel()
        m.finish(m.begin(oldCore, 1)!!, oldCore, ok, 2)
        assertEquals(1, m.state.items.size)
        val t = m.begin(oldCore, 3)!!
        m.clear()
        assertEquals(QueueState(), m.state)
        assertFalse(m.finish(t, oldCore, ok, 4))
        assertEquals(QueueState(), m.state)
        // After a clear a new refresh is due at once, and the old ticket cannot disturb it.
        val t2 = m.begin(oldCore, 5)!!
        assertFalse(m.finish(t, oldCore, ok, 6))
        assertTrue(m.state.loading)
        assertTrue(m.finish(t2, oldCore, ok, 7))
    }

    @Test fun aCancelledRefreshIsNotATryAndCannotStopANewerOne() {
        val m = QueueModel()
        val t1 = m.begin(oldCore, 1_000)!!
        assertNull(m.begin(oldCore, 1_001), "one at a time")
        m.cancelled(t1)
        assertFalse(m.state.loading)
        assertTrue(m.due(Queue.REFRESH_MS, 1_002, true), "not counted as tried")
        val t2 = m.begin(oldCore, 2_000)!!
        m.cancelled(t1) // the old one's handler runs late
        assertTrue(m.state.loading, "a newer refresh keeps loading")
        assertTrue(m.finish(t2, oldCore, QueueResult.Failed(QueueFailure.TimedOut), 3_000))
        assertEquals(QueueFailure.TimedOut, m.state.failure)
    }

    @Test fun aFailedTryKeepsTheLastListStale() {
        val m = QueueModel()
        m.finish(m.begin(oldCore, 1)!!, oldCore, ok, 2)
        m.finish(m.begin(oldCore, 3)!!, oldCore, QueueResult.Failed(QueueFailure.NotConnected), 4)
        assertTrue(m.state.stale)
        assertEquals(1, m.state.items.size)
    }

    @Test fun failuresAreMappedByTypeNotText() {
        assertEquals(QueueFailure.TimedOut, Queue.failureOf(CoreTimedOutException("whatever words"), true))
        assertEquals(QueueFailure.NotConnected, Queue.failureOf(CoreLostException("machine.exec: no reply from core", afterWrite = true), true))
        assertEquals(QueueFailure.NotConnected, Queue.failureOf(CoreLostException("lost the core"), false))
        assertEquals(QueueFailure.NotConnected, Queue.failureOf(IllegalStateException("Not connected to the Mac"), false))
        assertEquals(QueueFailure.Other("machine.exec: boom"), Queue.failureOf(RpcException(obj("message" to "boom\nmore"), "machine.exec"), true))
    }
}
