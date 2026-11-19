package herdr.core

import kotlinx.serialization.json.JsonElement
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class WatchTest {
    private val features = setOf("framed", "attach", Watch.FEATURE)

    @Test
    fun a_terminal_starts_watched_only_when_the_core_can_say_its_size() {
        assertEquals(AttachMode.Watch, Watch.startMode(watchFirst = true, features = features, tookControl = false))
        assertEquals(AttachMode.Control, Watch.startMode(true, setOf("framed", "attach"), false), "older core: no size for observers")
        assertEquals(AttachMode.Control, Watch.startMode(false, features, false), "turned off in settings")
        assertEquals(AttachMode.Control, Watch.startMode(true, features, tookControl = true), "already typed here")
        assertEquals(AttachMode.Control, Watch.startMode(true, features, false, nobodySizing = true), "no window shows it")
    }

    @Test
    fun only_the_core_default_headless_size_means_nobody_is_sizing_it() {
        assertTrue(Watch.nobodySizing(200, 120))
        assertFalse(Watch.nobodySizing(187, 52), "a Mac window's size")
        assertFalse(Watch.nobodySizing(null, null), "size not known yet: watch")
    }

    @Test
    fun a_grid_that_fits_is_drawn_as_is() {
        val f = Watch.fit(viewW = 808f, viewH = 488f, pad = 4f, cellW = 10f, cellH = 20f, cols = 80, rows = 24, minScale = 0.4f)
        assertEquals(Fit(1f, 0f, 0f), f)
    }

    @Test
    fun a_bigger_grid_shrinks_to_fit_with_its_top_left_at_the_padding() {
        // 160 columns of 10 px into 800 px: half size.
        val f = Watch.fit(808f, 1008f, 4f, 10f, 20f, cols = 160, rows = 40, minScale = 0.3f)
        assertEquals(0.5f, f.scale)
        assertEquals(4f, f.dx + f.scale * 4f, "column 0 on screen at the padding")
        assertEquals(4f, f.dy + f.scale * 4f, "row 0 on screen at the padding")
        assertEquals(10f, f.toContentX(4f + 3f), 0.001f, "a touch maps back to content pixels")
    }

    @Test
    fun too_small_to_read_stops_shrinking_and_keeps_the_bottom_in_view() {
        // 400 columns would need 0.2; the floor is 0.5.
        val f = Watch.fit(808f, 408f, 4f, 10f, 20f, cols = 400, rows = 60, minScale = 0.5f)
        assertEquals(0.5f, f.scale)
        val bottomOnScreen = f.dy + f.scale * (4f + 60 * 20f)
        assertEquals(408f - 4f, bottomOnScreen, 0.001f, "the last row ends at the bottom padding")
        assertTrue(f.dy < 0f, "the top rows are above the view")
    }

    @Test
    fun observers_ask_for_the_size_and_hear_it_before_the_paint() {
        val demo = DemoCore()
        val core = CoreClient.connect(demo.clientInput, demo.clientOutput) { demo.close() }
        assertTrue(Watch.FEATURE in core.features, "the demo core can be watched")
        val order = mutableListOf<String>()
        val painted = CountDownLatch(1)
        val listener = object : StreamListener {
            override fun onResize(cols: Int, rows: Int) { synchronized(order) { order.add("size ${cols}x$rows") } }
            override fun onData(bytes: ByteArray) { synchronized(order) { if (order.none { it == "paint" }) order.add("paint") }; painted.countDown() }
            override fun onClosed(reason: JsonElement) {}
            override fun onLost() {}
        }
        val watched = core.attach("t1", 40, 12, obj(), listener, observe = true)
        assertTrue(watched.observing)
        assertTrue(painted.await(5, TimeUnit.SECONDS))
        assertEquals(listOf("size 100x30", "paint"), synchronized(order) { order.toList() }, "the Mac's size, then the screen")
        assertTrue(demo.typingSizes.isEmpty(), "an observer does not resize the terminal")

        val typing = core.attach("t1", 40, 12, obj(), object : StreamListener {
            override fun onData(bytes: ByteArray) {}
            override fun onClosed(reason: JsonElement) {}
            override fun onLost() {}
        })
        assertFalse(typing.observing)
        assertEquals(listOf(40 to 12), demo.typingSizes, "a typing viewer's size counts")
        core.close()
    }

    @Test
    fun held_typing_keeps_its_order_and_drops_everything_after_what_did_not_fit() {
        val h = HeldInput(limit = 10)
        assertTrue(h.add("ab".toByteArray()))
        assertFalse(h.add(ByteArray(20)), "a paste too big to hold")
        assertFalse(h.add("\r".toByteArray()), "its Enter must not go alone")
        assertTrue(h.overflowed)
        h.reset()
        assertFalse(h.overflowed)
        assertTrue(h.add("x".toByteArray()))
        assertEquals("x", String(h.take()))
        assertEquals(0, h.take().size, "taken once")
    }

    @Test
    fun panning_stops_with_the_last_column_at_the_right_padding() {
        // 400 columns of 10 px at 0.5: 2000 px wide on an 808 px view.
        val pan = Watch.maxPan(viewW = 808f, pad = 4f, cellW = 10f, cols = 400, scale = 0.5f)
        val f = Watch.fit(808f, 408f, 4f, 10f, 20f, cols = 400, rows = 10, minScale = 0.5f)
        val right = f.dx - f.scale * pan + f.scale * (4f + 400 * 10f)
        assertEquals(808f - 4f, right, 0.01f)
        assertEquals(0f, Watch.maxPan(808f, 4f, 10f, 80, 1f), "a grid that fits does not pan")
    }
}
