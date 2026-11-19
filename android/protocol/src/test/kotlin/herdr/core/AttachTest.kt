package herdr.core

import java.time.ZoneId
import java.util.Base64
import java.util.concurrent.CancellationException
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertContentEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject

class AttachTest {
    @Test
    fun shrinking_keeps_the_shape_and_never_enlarges() {
        assertEquals(1000 to 500, Attach.target(1000, 500))
        assertEquals(2000 to 1000, Attach.target(2000, 1000))
        assertEquals(2000 to 1500, Attach.target(4000, 3000))
        assertEquals(1500 to 2000, Attach.target(3000, 4000), "portrait")
        assertEquals(2000 to 1, Attach.target(200_000, 10), "a side never goes below 1")
        assertEquals(1 to 1, Attach.target(0, 0))
        assertEquals(900 to 2000, Attach.target(1080, 2400), "a phone screenshot")
    }

    @Test
    fun the_decoder_samples_down_without_going_below_the_target() {
        assertEquals(1, Attach.sampleSize(1000, 500, 1000, 500))
        assertEquals(2, Attach.sampleSize(4000, 3000, 2000, 1500))
        assertEquals(4, Attach.sampleSize(8000, 6000, 2000, 1500))
        assertEquals(2, Attach.sampleSize(3000, 4000, 1500, 2000), "4000/2 = 2000 still fits")
    }

    @Test
    fun only_png_sources_are_candidates_for_png() {
        assertTrue(Attach.wantsPng("image/png"))
        assertTrue(Attach.wantsPng("IMAGE/PNG"))
        assertFalse(Attach.wantsPng("image/jpeg"))
        assertFalse(Attach.wantsPng("image/heic"))
        assertFalse(Attach.wantsPng(null))
    }

    @Test
    fun a_png_stays_png_unless_it_is_big_and_much_bigger_than_the_jpeg() {
        assertEquals(Attach.Format.Png, Attach.chooseFormat(400_000, 50_000), "small: keep PNG")
        assertEquals(Attach.Format.Png, Attach.chooseFormat(1_900_000, 1_000_000), "big but under 3x")
        assertEquals(Attach.Format.Jpeg, Attach.chooseFormat(1_900_000, 600_000), "big and 3.2x")
        assertEquals(Attach.Format.Png, Attach.chooseFormat(1_500_000, 10_000), "exactly the keep size")
        assertEquals(Attach.Format.Jpeg, Attach.chooseFormat(Attach.MAX_BYTES + 1, Attach.MAX_BYTES), "over the cap")
        assertEquals(Attach.Format.Png, Attach.chooseFormat(Attach.MAX_BYTES, Attach.MAX_BYTES), "at the cap")
    }

    @Test
    fun the_file_name_has_the_time_and_no_original_name() {
        // 2026-11-19 19:56:07 UTC
        val ms = 1_795_118_167_000L
        assertEquals("photo-20261119-195607.jpg", Attach.fileName(ms, ZoneId.of("UTC"), Attach.Format.Jpeg))
        assertEquals("photo-20261119-135607.png", Attach.fileName(ms, ZoneId.of("America/Chicago"), Attach.Format.Png))
    }

    @Test
    fun the_timeout_grows_with_the_base64_size_up_to_three_minutes() {
        assertEquals(20_000L, Attach.timeoutMs(0))
        assertEquals(4_000_000L, Attach.wireBytes(3_000_000))
        assertEquals(4L, Attach.wireBytes(1), "padding counts")
        assertEquals(20_000L + 4_000_000 / 50, Attach.timeoutMs(3_000_000))
        assertTrue(Attach.timeoutMs(Attach.MAX_BYTES) <= 180_000L)
        assertEquals(180_000L, Attach.timeoutMs(Int.MAX_VALUE - 10))
    }

    @Test
    fun the_cap_is_two_megabytes_and_the_ladder_only_goes_down() {
        assertEquals(2 * 1024 * 1024, Attach.MAX_BYTES)
        val l = Attach.LADDER
        assertEquals(Attach.MAX_SIDE, l.first().side)
        assertTrue(l.any { it.side == 1600 } && l.any { it.side == 1280 })
        for ((x, y) in l.zipWithNext()) assertTrue(y.side <= x.side && y.quality <= x.quality && x != y, "$x then $y")
    }

    @Test
    fun the_ladder_stops_at_the_first_step_that_fits_and_refuses_after_the_last() {
        val tried = mutableListOf<Attach.Step>()
        val got = Attach.fit { step -> tried.add(step); step to (if (step.side <= 1600) Attach.MAX_BYTES.toLong() else Attach.MAX_BYTES + 1L) }
        assertEquals(Attach.Step(1600, 70), got)
        assertEquals(Attach.LADDER.take(3), tried, "no step after the one that fits")
        tried.clear()
        assertEquals(Attach.Step(2000, 85), Attach.fit { it.also { s -> tried.add(s) } to 10L })
        assertEquals(1, tried.size)
        tried.clear()
        assertNull(Attach.fit { tried.add(it); it to Attach.MAX_BYTES + 1L })
        assertEquals(Attach.LADDER, tried)
    }

    @Test
    fun send_and_answers_wait_only_for_the_terminal_that_is_uploading() {
        assertTrue(Attach.blocksSend("t1", "t1"))
        assertFalse(Attach.blocksSend("t1", "t2"))
        assertFalse(Attach.blocksSend(null, "t1"))
        // The rule that keeps a delivered message out of the draft: with Send blocked, the draft cannot have moved on
        // from what was sent by an insertion during the send (see draftAfter), because there is no send during an upload.
        assertEquals("hi /tmp/a.png", draftAfter("hi /tmp/a.png", "hi", SendOutcome.Accepted), "an accepted send never wipes a changed draft")
    }

    @Test
    fun only_content_addresses_are_read() {
        assertTrue(Attach.acceptableScheme("content"))
        assertTrue(Attach.acceptableScheme("CONTENT"))
        assertFalse(Attach.acceptableScheme("file"))
        assertFalse(Attach.acceptableScheme("http"))
        assertFalse(Attach.acceptableScheme("android.resource"))
        assertFalse(Attach.acceptableScheme(null))
    }

    @Test
    fun the_request_carries_the_bytes_as_base64() {
        val data = byteArrayOf(0, 1, 2, -1, -2, 127)
        val r = Attach.request("mac/t7", "photo-1.jpg", data)
        assertEquals("mac/t7", r["terminal"].str)
        assertEquals("photo-1.jpg", r["name"].str)
        assertContentEquals(data, Base64.getDecoder().decode(r["data"].str))
        assertEquals(setOf("terminal", "name", "data"), r.keys)
    }

    @Test
    fun the_reply_is_read_tolerantly() {
        assertEquals(Attach.Placed("/tmp/x/photo.jpg", 12), Attach.parse(obj("path" to "/tmp/x/photo.jpg", "bytes" to 12, "extra" to true)))
        assertEquals(Attach.Placed("/p", 0), Attach.parse(obj("path" to "/p")))
        assertNull(Attach.parse(obj("bytes" to 3)))
        assertNull(Attach.parse(obj("path" to "  ")))
        assertNull(Attach.parse(obj("path" to 5)))
    }

    private fun rpc(kind: String, message: String = "boom") = RpcException(obj("code" to -32000, "message" to message, "data" to obj("kind" to kind)), "terminal.put")

    @Test
    fun failures_have_a_reason_the_person_can_read() {
        assertTrue(Attach.failure(rpc("not_found")).notice.contains("terminal is gone"))
        assertTrue(Attach.failure(rpc("finished")).notice.contains("finished"))
        assertTrue(Attach.failure(rpc("machine_unknown")).notice.contains("not known"))
        assertTrue(Attach.failure(rpc("invalid_params", "data too big\nsecond line")).notice.endsWith("terminal.put: data too big"), "first line, with the call name the app adds")
        assertIs<Attach.Failure.TimedOut>(Attach.failure(CoreLostException("terminal.put: no reply from core", afterWrite = true)))
        assertIs<Attach.Failure.Lost>(Attach.failure(CoreLostException("terminal.put: link closed", afterWrite = true)))
        assertIs<Attach.Failure.NotConnected>(Attach.failure(CoreLostException("terminal.put: lost the core")))
        assertIs<Attach.Failure.NotConnected>(Attach.failure(IllegalStateException("Not connected to the Mac")))
        assertFailsWith<CancellationException> { Attach.failure(CancellationException()) }
        assertFailsWith<InterruptedException> { Attach.failure(InterruptedException()) }
    }

    @Test
    fun the_default_share_target_is_the_last_opened_with_fallbacks() {
        val list = listOf("t1", "t2", "t3")
        assertEquals("t3", Attach.defaultTarget(list, "t3", "t2"))
        assertEquals("t2", Attach.defaultTarget(list, "t9", "t2"), "last opened is gone: the one in view")
        assertEquals("t2", Attach.defaultTarget(list, null, "t2"))
        assertEquals("t1", Attach.defaultTarget(list, "t9", "t8"), "first listed")
        assertNull(Attach.defaultTarget(emptyList(), "t1", "t1"))
    }

    // ---- against a pretend core ----

    private fun demoCore(): CoreClient {
        val demo = DemoCore()
        return CoreClient.connect(demo.clientInput, demo.clientOutput) { demo.close() }
    }

    @Test
    fun a_demo_core_answers_with_a_fake_path() {
        val core = demoCore()
        assertTrue(Attach.supported(core))
        val terminal = "t1"
        val out = Attach.upload(core, terminal, "photo-20261119-135607.jpg", ByteArray(1234))
        val done = assertIs<Attach.Outcome.Done>(out)
        assertEquals("/tmp/herdr-put/$terminal/photo-20261119-135607.jpg", done.path)
        val gone = Attach.upload(core, "t999", "photo.jpg", ByteArray(3))
        assertTrue(assertIs<Attach.Outcome.Failed>(gone).why.notice.contains("gone"))
        core.close()
    }

    @Test
    fun upload_refuses_without_a_core_and_over_the_cap() {
        assertIs<Attach.Failure.NotConnected>(assertIs<Attach.Outcome.Failed>(Attach.upload(null, "t1", "a.jpg", ByteArray(1))).why)
        val core = demoCore()
        val big = assertIs<Attach.Outcome.Failed>(Attach.upload(core, "t1", "a.jpg", ByteArray(Attach.MAX_BYTES + 1)))
        assertIs<Attach.Failure.TooLarge>(big.why)
        assertTrue(big.why.notice.contains("2 MB"))
        core.close()
        assertIs<Attach.Failure.NotConnected>(assertIs<Attach.Outcome.Failed>(Attach.upload(core, "t1", "a.jpg", ByteArray(1))).why)
    }

    /** A core that says hello with [methods], then (after [gate]) records frames; it never answers a terminal.put. */
    private class Fake(val methods: List<String>, val gate: java.util.concurrent.CountDownLatch? = null) {
        val toCore = java.io.PipedOutputStream()
        val coreIn = java.io.PipedInputStream(toCore, 1 shl 16)
        val fromCore = java.io.PipedOutputStream()
        val clientIn = java.io.PipedInputStream(fromCore, 1 shl 16)
        val puts = java.util.concurrent.LinkedBlockingQueue<Frame>()

        fun connect(): CoreClient {
            Thread {
                readLine(coreIn)
                val hello = obj("jsonrpc" to "2.0", "id" to 0, "result" to obj("generation" to 1, "version" to "0.1.21", "methods" to methods, "features" to emptyList<String>()))
                fromCore.write((hello.toString() + "\n").toByteArray())
                gate?.await()
                while (true) {
                    val f = readFrame(coreIn) ?: break
                    if (f.channel == 0 && f.payload.decodeToString().contains("terminal.put")) puts.add(f)
                }
            }.apply { isDaemon = true }.start()
            return CoreClient.connect(clientIn, toCore)
        }
    }

    private val withPut = USES + "terminal.put"

    @Test
    fun a_core_without_terminal_put_is_unsupported_and_gets_no_frame() {
        val fake = Fake(USES)
        val core = fake.connect()
        assertFalse(Attach.supported(core))
        assertIs<Attach.Failure.Unsupported>(assertIs<Attach.Outcome.Failed>(Attach.upload(core, "t1", "a.jpg", ByteArray(10))).why)
        assertNull(fake.puts.poll(300, TimeUnit.MILLISECONDS))
        core.close()
    }

    @Test
    fun a_cancel_before_the_upload_queues_nothing() {
        val fake = Fake(withPut)
        val core = fake.connect()
        Thread.currentThread().interrupt()
        try {
            assertFailsWith<InterruptedException> { Attach.upload(core, "t1", "a.jpg", ByteArray(10)) }
        } finally {
            Thread.interrupted()
        }
        assertNull(fake.puts.poll(300, TimeUnit.MILLISECONDS), "no frame was sent")
        core.close()
    }

    @Test
    fun a_cancel_while_waiting_ends_the_wait_with_an_interrupt() {
        val fake = Fake(withPut)
        val core = fake.connect()
        var thrown: Throwable? = null
        val t = Thread { try { Attach.upload(core, "t1", "a.jpg", ByteArray(10)) } catch (e: Throwable) { thrown = e } }.apply { start() }
        assertNotNull(fake.puts.poll(5, TimeUnit.SECONDS), "the request went out")
        t.interrupt()
        t.join(5000)
        assertIs<InterruptedException>(thrown)
        core.close()
    }

    @Test
    fun a_cancelled_upload_still_waiting_in_the_queue_never_goes_out() {
        val gate = java.util.concurrent.CountDownLatch(1)
        val fake = Fake(withPut, gate)
        val core = fake.connect()
        assertTrue(core.idle())
        // The first upload is bigger than the pipe, so the writer is stuck in the middle of it.
        val first = Thread { runCatching { Attach.upload(core, "t1", "first.jpg", ByteArray(300_000)) } }.apply { isDaemon = true; start() }
        val until = System.currentTimeMillis() + 5000
        while (core.idle() && System.currentTimeMillis() < until) Thread.sleep(10)
        assertFalse(core.idle(), "the first frame is being written")
        var thrown: Throwable? = null
        val second = Thread { try { Attach.upload(core, "t1", "second.jpg", ByteArray(10)) } catch (e: Throwable) { thrown = e } }.apply { start() }
        Thread.sleep(300)
        second.interrupt()
        second.join(5000)
        assertIs<InterruptedException>(thrown)
        gate.countDown()
        val written = System.currentTimeMillis() + 5000
        while (!core.idle() && System.currentTimeMillis() < written) Thread.sleep(10)
        assertTrue(core.idle(), "the first frame has left")
        val sent = fake.puts.poll(5, TimeUnit.SECONDS)!!
        assertTrue(sent.payload.decodeToString().contains("first.jpg"))
        assertNull(fake.puts.poll(500, TimeUnit.MILLISECONDS), "the cancelled second one was taken back")
        first.interrupt()
        core.close()
    }
}
