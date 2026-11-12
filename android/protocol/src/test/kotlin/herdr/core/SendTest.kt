package herdr.core

import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.util.concurrent.CompletableFuture
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class SendTest {
    /** A core that says hello, records channel frames, and never answers a call named "hang". */
    private class Quiet {
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
                    if (f.channel != 0) received.add(f)
                }
            }.apply { isDaemon = true }.start()
            return CoreClient.connect(clientIn, toCore)
        }
    }

    @Test
    fun send_with_no_terminal_attached_is_rejected_and_keeps_the_draft() {
        val bytes = "hello\r".toByteArray()
        assertEquals(SendOutcome.Rejected, sendInput(null, 3, bytes))
        val core = Quiet().connect()
        assertEquals(SendOutcome.Rejected, sendInput(core, null, bytes), "no channel: not attached")
        assertEquals("hello", draftAfter("hello", "hello", SendOutcome.Rejected))
        assertNotNull(SendOutcome.Rejected.notice)
    }

    @Test
    fun a_demo_terminal_accepts_a_send_like_a_live_one() {
        val demo = DemoCore()
        val core = CoreClient.connect(demo.clientInput, demo.clientOutput) { demo.close() }
        val a = core.attach("t1", 100, 30, obj(), object : StreamListener {
            override fun onData(bytes: ByteArray) {}
            override fun onClosed(reason: kotlinx.serialization.json.JsonElement) {}
            override fun onLost() {}
        })
        assertEquals(SendOutcome.Accepted, sendInput(core, a.channel, "hi\r".toByteArray()))
        core.close()
    }

    @Test
    fun send_on_a_lost_core_is_rejected() {
        val core = Quiet().connect()
        core.close()
        assertTrue(core.closed)
        assertEquals(SendOutcome.Rejected, sendInput(core, 3, "x".toByteArray()))
        val e = assertFailsWith<CoreLostException> { core.call("terminal.list") }
        assertEquals(SendOutcome.Rejected, outcomeOfFailure(e), "failed before anything was written")
    }

    @Test
    fun an_accepted_send_reaches_the_core_and_clears_only_the_sent_draft() {
        val fake = Quiet()
        val core = fake.connect()
        assertEquals(SendOutcome.Accepted, sendInput(core, 3, "hi there".toByteArray()))
        val frame = fake.received.poll(5, TimeUnit.SECONDS)!!
        assertEquals(3, frame.channel)
        assertEquals("hi there", frame.payload.decodeToString())
        assertEquals("", draftAfter("hi there", "hi there", SendOutcome.Accepted))
        assertEquals("hi there, and more", draftAfter("hi there, and more", "hi there", SendOutcome.Accepted), "typed on while sending")
        assertEquals(null, SendOutcome.Accepted.notice)
    }

    @Test
    fun a_timeout_after_writing_is_unknown_and_keeps_the_draft() {
        val fake = Quiet()
        val core = fake.connect()
        val e = assertFailsWith<CoreLostException> { core.call("hang", timeoutMs = 100) }
        assertTrue(e.afterWrite, "the request was written before the wait ran out")
        assertEquals(SendOutcome.Unknown, outcomeOfFailure(e))
        assertEquals(SendOutcome.Unknown, awaitOutcome(CompletableFuture(), 50), "no word in time")
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
