package herdr.core

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import java.io.ByteArrayInputStream
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FramesTest {
    @Test
    fun frames_round_trip_little_endian() {
        val bytes = encodeFrame(7, Kind.RESIZE, resizePayload(120, 40))
        assertEquals(listOf<Byte>(7, 0, 0, 0, 3, 4, 0, 0, 0, 120, 0, 40, 0), bytes.toList())
        val f = readFrame(ByteArrayInputStream(bytes))!!
        assertEquals(7, f.channel)
        assertEquals(Kind.RESIZE, f.kind)
        assertEquals(4, f.payload.size)
        assertNull(readFrame(ByteArrayInputStream(bytes.copyOf(5))), "a cut frame is the end")
    }

    @Test
    fun hello_checks_generation_version_and_methods() {
        fun hello(g: Int, v: String, m: List<String>) = obj("generation" to g, "version" to v, "methods" to m)
        assertNull(mismatch(hello(1, "9.9.9", USES)))
        assertNotNull(mismatch(hello(1, "0.0.1", USES)))
        assertNotNull(mismatch(hello(2, "9.9.9", USES)))
        assertNotNull(mismatch(hello(1, "9.9.9", listOf("terminal.run"))))
        assertTrue(semver("0.10.0").first == 0L && semver("0.10.0").second == 10L)
    }
}

class EventLinesTest {
    private fun event(seq: Int, kind: String, t: String, data: JsonObject) =
        obj("method" to "event", "params" to obj("seq" to seq, "type" to kind, "terminal" to t, "data" to data)).toString() + "\n"

    private fun rec(a: String) = obj("address" to a, "state" to "live", "title" to "", "labels" to obj())

    @Test
    fun the_event_stream_keeps_the_terminals_as_the_core_has_them() {
        val lines = EventLines(obj("seq" to 5, "terminals" to listOf(rec("t1"))))
        fun addresses() = lines.live().map { it["address"].str }
        assertEquals(listOf("t1"), addresses())
        assertFalse(lines.push(event(5, "title", "t1", obj("title" to "old")).toByteArray()).terminals, "not newer than the snapshot")
        val got = lines.push((event(6, "created", "t2", rec("t2")) + event(7, "title", "t1", obj("title" to "hi"))).toByteArray())
        assertTrue(got.terminals && !got.docs)
        assertEquals(listOf("t1", "t2"), addresses())
        assertEquals("hi", lines.live()[0]["title"].str)
        lines.push(event(8, "status", "t2", obj("status" to obj("state" to "working"), "records" to obj(), "reported" to true)).toByteArray())
        lines.push(event(9, "foreground", "t2", obj("pid" to 7, "name" to "vim", "cwd" to "/src")).toByteArray())
        assertEquals("working", lines.live()[1]["status"]["state"].str)
        assertEquals("/src", lines.live()[1]["foreground"]["cwd"].str)
        lines.push(event(10, "finished", "t1", obj("reason" to "exited", "exit_code" to 0)).toByteArray())
        assertEquals(listOf("t2"), addresses(), "finished: no longer live")
        lines.push(event(11, "removed", "t2", obj()).toByteArray())
        assertTrue(addresses().isEmpty())
        val missed = obj("method" to "missed", "params" to obj("seq" to 20, "terminals" to listOf(rec("t9")))).toString() + "\n"
        val m = lines.push(missed.toByteArray())
        assertTrue(m.terminals && m.docs)
        assertEquals(listOf("t9"), addresses())
        val doc = obj("method" to "event", "params" to obj("seq" to 21, "type" to "doc", "machine" to "box", "data" to obj("name" to "herdr-app"))).toString() + "\n"
        val d = lines.push(doc.toByteArray())
        assertTrue(d.docs)
        assertEquals(setOf("box"), d.docMachines)
    }

    @Test
    fun events_survive_arbitrary_frame_boundaries() {
        val line = "{\"method\":\"event\",\"params\":{\"type\":\"doc\"}}\n".toByteArray()
        for (at in 1 until line.size) {
            val lines = EventLines(obj())
            assertFalse(lines.push(line.copyOfRange(0, at)).docs)
            assertTrue(lines.push(line.copyOfRange(at, line.size)).docs)
        }
    }
}

class LayoutTest {
    private fun tab() = newTab("tb", "", "p1", "t1")

    @Test
    fun splitting_reuses_a_parent_along_the_axis_and_wraps_otherwise() {
        var root = tab().root
        root = root.split("p1", Axis.Row, terminalPane("p2", "t2"), "s1")!!
        assertEquals(listOf("p1", "p2"), root.children.map { it.nodeId }, "the root row is reused")
        assertEquals(0.5, root.children[0].weight)
        root = root.split("p2", Axis.Column, terminalPane("p3", "t3"), "s2")!!
        val col = root.children[1]
        assertEquals("s2", col.nodeId)
        assertEquals("column", col.type)
        assertEquals(0.5, col.weight, "the wrapper takes the target's slot size")
        assertEquals(listOf("p2", "p3"), col.children.map { it.nodeId })
        assertEquals(listOf("t1", "t2", "t3"), root.panes().map { it.terminal })
    }

    @Test
    fun removing_gives_the_share_back_and_collapses_single_child_lines() {
        var root = tab().root
        root = root.split("p1", Axis.Row, terminalPane("p2", "t2"), "s1")!!
        root = root.split("p2", Axis.Column, terminalPane("p3", "t3"), "s2")!!
        root = root.remove("p3")
        assertEquals(listOf("p1", "p2"), root.children.map { it.nodeId }, "a column of one gives way to it")
        assertEquals(0.5, root.children[1].weight)
        root = root.remove("p2")
        assertEquals(listOf("p1"), root.children.map { it.nodeId })
        assertEquals(1.0, root.children[0].weight)
        assertEquals("tb", root.nodeId, "the root stays")
        root = root.remove("p1")
        assertTrue(root.panes().isEmpty())
    }

    @Test
    fun documents_round_trip_with_machine_qualified_ids_and_unknown_fields() {
        val value = json.parseToJsonElement(
            """{"spaces":[{"id":"sp1","name":"","folder":"/src","tabs":[{"id":"tb1","name":"fix","root":
               {"id":"tb1","type":"row","children":[{"id":"p1","type":"block","content":{"terminal":"t4"},"title":"keep me"},
               {"id":"p2","type":"block","content":{"mod":"files","props":{"terminal":"t4"}},"size":{"px":300}}]}}]}]}""",
        )
        val spaces = parseDocument(value, "box")
        assertEquals("box/sp1", spaces[0].id)
        val root = spaces[0].tabs[0].root
        assertEquals("box/t4", root.find("box/p1")!!.terminal)
        assertEquals("t4", root.find("box/p2")!!["content"]["props"]["terminal"].str, "mod props are never qualified")
        assertNull(root.find("box/p2")!!.weight, "px sizes have no weight")
        assertEquals(value, captureDocument(spaces, "box"))
        assertTrue(parseDocument(null, null).isEmpty())
    }

    @Test
    fun names_and_ids() {
        assertEquals("~", folderName("/Users/me/", "/Users/me"))
        assertEquals("herdr", folderName("/Users/me/src/herdr", "/Users/me"))
        assertEquals("box", machineOf("box/t7"))
        assertNull(machineOf("t7"))
        assertEquals("t7", local("box/t7", "box"))
        assertTrue(freshId("p").matches(Regex("p[0-9a-f]{32}")))
    }
}

/** A tiny in-process core: framed hello, calls, an attach stream. */
class CoreClientTest {
    private class Fake {
        val toCore = PipedOutputStream()
        val coreIn = PipedInputStream(toCore, 1 shl 16)
        val fromCore = PipedOutputStream()
        val clientIn = PipedInputStream(fromCore, 1 shl 16)
        val received = java.util.concurrent.LinkedBlockingQueue<Frame>()

        fun reply(id: Long, result: JsonObject) = fromCore.write(encodeFrame(0, Kind.DATA, obj("jsonrpc" to "2.0", "id" to id, "result" to result).toString().toByteArray()))

        fun start(helloLines: String) {
            Thread {
                val hello = readLine(coreIn)!!
                check(json.parseToJsonElement(hello)["params"]["framed"].bool == true)
                fromCore.write(helloLines.toByteArray())
                while (true) {
                    val f = readFrame(coreIn) ?: break
                    if (f.channel == 0) {
                        val msg = json.parseToJsonElement(f.payload.decodeToString()).jsonObject
                        val id = msg["id"].long!!
                        when (msg["method"].str) {
                            "terminal.attach" -> {
                                reply(id, obj("channel" to 3, "terminal" to obj("address" to "t1"), "automatic_input" to true))
                                fromCore.write(encodeFrame(3, Kind.DATA, "hello\r\n".toByteArray()))
                            }
                            "doc.set" -> fromCore.write(encodeFrame(0, Kind.DATA, obj("jsonrpc" to "2.0", "id" to id, "error" to obj("code" to -32000, "message" to "stale", "data" to obj("kind" to "conflict"))).toString().toByteArray()))
                            "bye" -> { fromCore.close(); return@Thread }
                            else -> reply(id, obj("echo" to msg["method"]!!))
                        }
                    } else received.add(f)
                }
            }.apply { isDaemon = true }.start()
        }
    }

    private val goodHello = obj("jsonrpc" to "2.0", "id" to 0, "result" to obj("generation" to 1, "version" to "0.2.0", "methods" to USES, "features" to listOf("automatic_input"))).toString() + "\n"

    @Test
    fun calls_streams_and_loss() {
        val fake = Fake()
        fake.start("Last login: yesterday\n$goodHello")
        val core = CoreClient.connect(fake.clientIn, fake.toCore)
        assertNull(mismatch(core.hello))
        assertEquals("terminal.list", core.call("terminal.list")["echo"].str)
        val e = assertFailsWith<RpcException> { core.call("doc.set", obj("name" to DOC_NAME)) }
        assertEquals("conflict", e.kind)

        val data = StringBuilder()
        val got = CountDownLatch(1)
        val lost = CountDownLatch(1)
        val attached = core.attach("t1", 80, 24, obj(), object : StreamListener {
            override fun onData(bytes: ByteArray) { data.append(bytes.decodeToString()); got.countDown() }
            override fun onClosed(reason: kotlinx.serialization.json.JsonElement) {}
            override fun onLost() { lost.countDown() }
        })
        assertEquals(3, attached.channel)
        assertTrue(got.await(5, TimeUnit.SECONDS), "frames that beat the reply are kept")
        assertEquals("hello\r\n", data.toString())
        core.automaticInput(3, "\u001b[0n".toByteArray())
        core.input(3, "ls\r".toByteArray())
        core.resize(3, 100, 30)
        val kinds = (0 until 3).map { fake.received.poll(5, TimeUnit.SECONDS)!!.kind }
        assertEquals(listOf(Kind.AUTOMATIC_INPUT, Kind.DATA, Kind.RESIZE), kinds)

        assertFailsWith<CoreLostException> { core.call("bye", timeoutMs = 5000) }
        assertTrue(lost.await(5, TimeUnit.SECONDS))
        assertTrue(core.closed)
        assertFailsWith<CoreLostException> { core.call("terminal.list") }
    }

    @Test
    fun no_hello_reports_what_the_mac_printed() {
        val fake = Fake()
        fake.start("zsh: command not found: hc\n")
        Thread { Thread.sleep(200); fake.fromCore.close() }.start()
        val e = assertFailsWith<IllegalStateException> { CoreClient.connect(fake.clientIn, fake.toCore) }
        assertTrue(e.message!!.contains("command not found: hc"), e.message)
    }

    @Test
    fun run_sends_size_labels_and_keep() {
        val toCore = PipedOutputStream()
        val coreIn = PipedInputStream(toCore, 1 shl 16)
        val fromCore = PipedOutputStream()
        val clientIn = PipedInputStream(fromCore, 1 shl 16)
        var params: JsonObject? = null
        Thread {
            readLine(coreIn)
            fromCore.write(goodHello.toByteArray())
            val f = readFrame(coreIn)!!
            val msg = json.parseToJsonElement(f.payload.decodeToString()).jsonObject
            params = msg["params"].obj
            fromCore.write(encodeFrame(0, Kind.DATA, obj("jsonrpc" to "2.0", "id" to msg["id"]!!, "result" to obj("address" to "box/t9")).toString().toByteArray()))
        }.start()
        val core = CoreClient.connect(clientIn, toCore)
        val addr = core.run(emptyList(), "", mapOf("herdr.space" to "sp1"), "box", 80, 24, obj("fg" to "#ffffff"))
        assertEquals("box/t9", addr)
        assertEquals(JsonPrimitive(true), params!!["keep"])
        assertEquals("box", params!!["on"].str)
        assertNull(params!!["cwd"], "an empty folder is the machine's home")
        assertEquals(80L, params!!["size"]["cols"].long)
        assertEquals("sp1", params!!["labels"]["herdr.space"].str)
    }
}
