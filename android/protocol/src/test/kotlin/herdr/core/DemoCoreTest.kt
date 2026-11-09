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

        val v = doc["version"].long!!
        val r = core.call("doc.set", obj("name" to DOC_NAME, "version" to v, "value" to captureDocument(spaces.drop(1), null)))
        assertEquals(v + 1, r["version"].long)
        assertTrue(gotEvent.await(5, TimeUnit.SECONDS), "writes announce a doc event")
        val addr = core.run(emptyList(), null, mapOf("herdr.space" to "sp2"), null, 80, 24, obj())
        assertTrue(addr.startsWith("t"))
        core.close()
    }
}
