package herdr.core

import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject

class SendTest {
    /**
     * A core that says hello and records calls. It answers every call, except "hang" (and every call
     * when [hang]), and refuses terminal.send/keys when [refuse].
     */
    private class Quiet(val refuse: Boolean = false, val hang: Boolean = false) {
        val toCore = PipedOutputStream()
        val coreIn = PipedInputStream(toCore, 1 shl 16)
        val fromCore = PipedOutputStream()
        val clientIn = PipedInputStream(fromCore, 1 shl 16)
        val received = LinkedBlockingQueue<Frame>()

        fun connect(): CoreClient {
            Thread {
                readLine(coreIn)
                val hello = obj("jsonrpc" to "2.0", "id" to 0, "result" to obj("generation" to 1, "version" to "0.2.0", "methods" to USES, "features" to emptyList<String>()))
                fromCore.write((hello.toString() + "\n").toByteArray())
                while (true) {
                    val f = readFrame(coreIn) ?: break
                    received.add(f)
                    if (f.channel != 0) continue
                    val req = Json.parseToJsonElement(f.payload.decodeToString()).jsonObject
                    if (hang || req["method"].str == "hang") continue
                    val body = if (refuse && req["method"].str.orEmpty().startsWith("terminal."))
                        "error" to obj("code" to -32000, "message" to "blocked", "data" to obj("kind" to "blocked"))
                    else "result" to obj()
                    fromCore.write(encodeFrame(0, Kind.DATA, obj("jsonrpc" to "2.0", "id" to req["id"], body).toString().toByteArray()))
                }
            }.apply { isDaemon = true }.start()
            return CoreClient.connect(clientIn, toCore)
        }
    }

    @Test
    fun send_with_no_terminal_attached_is_rejected_and_keeps_the_draft() {
        assertEquals(SendOutcome.Rejected, sendMessage(null, "t1", "hello"), "no core: not attached")
        assertEquals("hello", draftAfter("hello", "hello", SendOutcome.Rejected))
        assertNotNull(SendOutcome.Rejected.notice)
    }

    @Test
    fun a_demo_terminal_accepts_a_send_like_a_live_one() {
        val demo = DemoCore()
        val core = CoreClient.connect(demo.clientInput, demo.clientOutput) { demo.close() }
        assertEquals(SendOutcome.Accepted, sendMessage(core, "t1", "hi"))
        assertEquals(SendOutcome.Accepted, sendMessage(core, "t1", ""), "a bare Enter")
        core.close()
    }

    @Test
    fun send_on_a_lost_core_is_rejected() {
        val core = Quiet().connect()
        core.close()
        assertTrue(core.closed)
        assertEquals(SendOutcome.Rejected, sendMessage(core, "t1", "x"))
        val e = assertFailsWith<CoreLostException> { core.call("terminal.list") }
        assertEquals(SendOutcome.Rejected, outcomeOfFailure(e), "failed before anything was written")
    }

    @Test
    fun an_accepted_send_is_a_reply_from_the_core_and_clears_only_the_sent_draft() {
        val fake = Quiet()
        val core = fake.connect()
        assertEquals(SendOutcome.Accepted, sendMessage(core, "t1", "hi there"))
        val req = Json.parseToJsonElement(fake.received.poll(5, TimeUnit.SECONDS)!!.payload.decodeToString()).jsonObject
        assertEquals("terminal.send", req["method"].str)
        assertEquals("hi there", req["params"].obj!!["text"].str)
        assertEquals("", draftAfter("hi there", "hi there", SendOutcome.Accepted))
        assertEquals("hi there, and more", draftAfter("hi there, and more", "hi there", SendOutcome.Accepted), "typed on while sending")
        assertEquals(null, SendOutcome.Accepted.notice)
    }

    @Test
    fun a_core_that_refuses_the_send_is_rejected() {
        val fake = Quiet(refuse = true)
        assertEquals(SendOutcome.Rejected, sendMessage(fake.connect(), "t1", "hi"))
    }

    @Test
    fun no_reply_in_time_after_writing_is_unknown() {
        val fake = Quiet(hang = true)
        assertEquals(SendOutcome.Unknown, sendMessage(fake.connect(), "t1", "hi", timeoutMs = 100))
    }

    @Test
    fun a_timeout_after_writing_is_unknown_and_keeps_the_draft() {
        val fake = Quiet()
        val core = fake.connect()
        val e = assertFailsWith<CoreLostException> { core.call("hang", timeoutMs = 100) }
        assertTrue(e.afterWrite, "the request was written before the wait ran out")
        assertEquals(SendOutcome.Unknown, outcomeOfFailure(e))
        assertEquals("keep me", draftAfter("keep me", "keep me", SendOutcome.Unknown))
        assertEquals("It may have been sent. Check the terminal before sending again.", SendOutcome.Unknown.notice)
    }

    @Test
    fun a_link_that_drops_while_a_call_waits_is_unknown() {
        val fake = Quiet()
        val core = fake.connect()
        Thread { Thread.sleep(100); fake.fromCore.close() }.start()
        val e = assertFailsWith<CoreLostException> { core.call("hang", timeoutMs = 5000) }
        assertTrue(e.afterWrite)
        assertEquals(SendOutcome.Unknown, outcomeOfFailure(e))
    }
}
