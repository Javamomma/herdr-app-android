package herdr.core

import kotlinx.serialization.json.JsonElement
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DemoCoreTest {
    @Test
    fun the_demo_core_serves_the_whole_client_flow() {
        val demo = DemoCore()
        val core = CoreClient.connect(demo.clientInput, demo.clientOutput) { demo.close() }
        assertNull(mismatch(core.hello))
        var lines: EventLines? = null
        val gotEvent = CountDownLatch(1)
        core.subscribe { ch, snapshot ->
            val l = EventLines(snapshot).also { lines = it }
            object : StreamListener {
                override fun onData(bytes: ByteArray) { synchronized(l) { l.push(bytes) }; core.credit(ch, bytes.size); gotEvent.countDown() }
                override fun onClosed(reason: JsonElement) {}
                override fun onLost() {}
            }
        }
        assertTrue(lines!!.live().any { it["status"]["state"].str == "blocked" })
        val doc = core.call("doc.get", obj("name" to DOC_NAME))
        val spaces = parseDocument(doc["value"], null)
        assertEquals(3, spaces.size)
        assertEquals(listOf("t1", "t2", "t3"), spaces[0].tabs[0].root.panes().map { it.terminal })
        assertEquals("vps/sp9", parseDocument(core.call("doc.get", obj("name" to DOC_NAME, "on" to "vps"))["value"], "vps")[0].id)

        val painted = CountDownLatch(1)
        core.attach("t1", 100, 30, obj(), object : StreamListener {
            override fun onData(bytes: ByteArray) { if (bytes.decodeToString().contains("Allow this edit")) painted.countDown() }
            override fun onClosed(reason: JsonElement) {}
            override fun onLost() {}
        })
        assertTrue(painted.await(5, TimeUnit.SECONDS))

        val read = core.call("terminal.read", obj("terminal" to "t1"))["text"].str!!
        assertEquals(listOf("Yes", "Yes, and don't ask again this session", "No, and tell the agent what to do"), Prompts.choices(read).map { it.label })

        val v = doc["version"].long!!
        val r = core.call("doc.set", obj("name" to DOC_NAME, "version" to v, "value" to captureDocument(spaces.drop(1), null)))
        assertEquals(v + 1, r["version"].long)
        assertTrue(gotEvent.await(5, TimeUnit.SECONDS), "writes announce a doc event")
        val addr = core.run(emptyList(), null, mapOf("herdr.space" to "sp2"), null, 80, 24, obj())
        assertTrue(addr.startsWith("t"))
        // A thread that writes and ends (the demo's story) must not break the pipe.
        Thread { demo.setStatus("t6", "working", "Again") }.apply { start(); join() }
        Thread.sleep(200)
        assertEquals("terminal.list", core.call("terminal.list").let { "terminal.list" })
        assertTrue(!core.closed, "the connection survives a finished writer thread")
        core.close()
    }

    @Test
    fun the_demo_shows_a_pin_and_an_unread_agent_and_takes_label_writes() {
        val demo = DemoCore()
        val core = CoreClient.connect(demo.clientInput, demo.clientOutput) { demo.close() }
        var lines: EventLines? = null
        core.subscribe { ch, snapshot ->
            val l = EventLines(snapshot).also { lines = it }
            object : StreamListener {
                override fun onData(bytes: ByteArray) { synchronized(l) { l.push(bytes) }; core.credit(ch, bytes.size) }
                override fun onClosed(reason: JsonElement) {}
                override fun onLost() {}
            }
        }
        fun label(t: String, key: String): String? = synchronized(lines!!) { lines!!.live() }.first { it["address"].str == t }["labels"][key].str
        fun await(what: String, ok: () -> Boolean) {
            val end = System.currentTimeMillis() + 5_000
            while (!ok() && System.currentTimeMillis() < end) Thread.sleep(20)
            assertTrue(ok(), what)
        }
        assertTrue(Pins.isPinned(label("t4", Labels.PINNED)))
        assertTrue(Seen.unread("done", 6, Seen.parse(label("t6", Labels.SEEN))))
        core.call("terminal.label", obj("terminal" to "t6", "labels" to obj(Labels.SEEN to "6")))
        await("seen written") { label("t6", Labels.SEEN) == "6" }
        core.call("terminal.label", obj("terminal" to "t4", "labels" to obj(Labels.PINNED to "")))
        await("unpinned") { label("t4", Labels.PINNED) == null }
        core.close()
    }

    @Test
    fun the_demo_answers_the_default_queue_command() {
        val demo = DemoCore()
        val core = CoreClient.connect(demo.clientInput, demo.clientOutput) { demo.close() }
        val r = core.call("machine.exec", obj("argv" to listOf("sh", "-c", Queue.DEFAULT_COMMAND)))
        val text = String(java.util.Base64.getDecoder().decode(r["stdout"].str!!))
        val out = Queue.outcome(r["code"].long, text, "", false, false)
        val items = (out as QueueResult.Loaded).items
        assertEquals(listOf("demo-1", "demo-2", "demo-3"), items.map { it.id })
        assertEquals(
            listOf(QueueTarget.Detail("Sam asked whether Saturday still works."), QueueTarget.Url("https://example.com/demo/pull/12"), QueueTarget.File("/Users/you/demo.md")),
            items.map { Queue.target(it) },
        )
        core.close()
    }
}
