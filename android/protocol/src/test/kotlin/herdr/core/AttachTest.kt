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
        assertEquals(Attach.Format.Png, Attach.chooseFormat(3_000_000, 1_500_000), "big but under 3x")
        assertEquals(Attach.Format.Jpeg, Attach.chooseFormat(3_000_000, 900_000), "big and 3.3x")
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
    fun the_timeout_grows_with_the_size_up_to_three_minutes() {
        assertEquals(20_000L, Attach.timeoutMs(0))
        assertEquals(120_000L, Attach.timeoutMs(5_000_000))
        assertTrue(Attach.timeoutMs(Attach.MAX_BYTES) <= 180_000L)
        assertEquals(180_000L, Attach.timeoutMs(Int.MAX_VALUE))
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
    fun upload_refuses_without_a_core_over_the_cap_and_when_the_core_lacks_the_call() {
        assertIs<Attach.Failure.NotConnected>(assertIs<Attach.Outcome.Failed>(Attach.upload(null, "t1", "a.jpg", ByteArray(1))).why)
        val core = demoCore()
        val big = assertIs<Attach.Outcome.Failed>(Attach.upload(core, "t1", "a.jpg", ByteArray(Attach.MAX_BYTES + 1)))
        assertIs<Attach.Failure.TooLarge>(big.why)
        assertTrue(big.why.notice.contains("5 MB"))
        core.close()
        assertIs<Attach.Failure.NotConnected>(assertIs<Attach.Outcome.Failed>(Attach.upload(core, "t1", "a.jpg", ByteArray(1))).why)
    }
}
