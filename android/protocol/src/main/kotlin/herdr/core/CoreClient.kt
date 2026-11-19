package herdr.core

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import java.io.BufferedInputStream
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutionException
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicLong

/** The core API generation this client speaks (same as the desktop app's `hc::GENERATION`). */
const val GENERATION = 1L

/** What this client calls on the core. */
val USES = listOf(
    "terminal.run", "terminal.list", "terminal.kill", "terminal.label", "terminal.attach",
    "terminal.keep", "doc.get", "doc.set", "events.subscribe",
)

/** The oldest core the desktop app works with (distribution/core-version). */
const val CORE_MIN = "0.1.11"

class RpcException(val error: JsonElement, method: String) :
    Exception("$method: ${error["message"].str ?: "core call failed"}" + (error["data"]["hint"].str?.let { " ($it)" } ?: "")) {
    val kind: String? get() = error["data"]["kind"].str
}

/**
 * The connection is gone or gave no answer. [afterWrite] is true when the
 * request had already been written, so the core may have acted on it; false
 * when it failed before anything was written.
 */
open class CoreLostException(message: String, val afterWrite: Boolean = false) : Exception(message)

/** The core did not answer in time (the link may be fine). */
class CoreTimedOutException(message: String) : CoreLostException(message, afterWrite = true)

/** Receives one channel's frames, on the connection's reader thread. */
interface StreamListener {
    fun onData(bytes: ByteArray)
    /** An observer heard the terminal's size (first, and whenever it changes); a repaint follows. */
    fun onResize(cols: Int, rows: Int) {}
    /** The core ended the stream; [reason] says why (e.g. the program exited). */
    fun onClosed(reason: JsonElement)
    /** The connection dropped. The program most likely still runs: reattach later. */
    fun onLost()
}

private sealed interface Early {
    class Data(val bytes: ByteArray) : Early
    class Closed(val reason: JsonElement) : Early
    class Resize(val cols: Int, val rows: Int) : Early
}

fun semver(v: String): Triple<Long, Long, Long> {
    val p = v.trim().removePrefix("hc ").split('.', '-').map { it.toLongOrNull() ?: 0 }
    return Triple(p.getOrElse(0) { 0 }, p.getOrElse(1) { 0 }, p.getOrElse(2) { 0 })
}

private operator fun Triple<Long, Long, Long>.compareTo(o: Triple<Long, Long, Long>): Int =
    compareValuesBy(this, o, { it.first }, { it.second }, { it.third })

/** Why a core can't serve this client, or null when it can. */
fun mismatch(hello: JsonElement): String? {
    val generation = hello["generation"].long ?: 0
    if (generation > GENERATION) return "The core on this machine is newer (generation $generation) than this app understands. Update the Android app."
    val have = hello["version"].str ?: "0.0.0"
    if (generation < GENERATION || semver(have) < semver(CORE_MIN)) return "herdr-core $have is too old: update it to $CORE_MIN or newer (run `hc update` on the Mac)."
    val methods = hello["methods"].arr.mapNotNull { it.str }.toSet()
    val missing = USES.filter { it !in methods }
    return if (missing.isEmpty()) null else "This herdr-core lacks ${missing.joinToString()}: update it (`hc update`)."
}

/**
 * One framed connection to herdr-core, over any byte pipe: here, the stdin and
 * stdout of `hc connect` run over SSH on the Mac. JSON-RPC on channel 0,
 * terminal byte streams on their own channels.
 */
class CoreClient private constructor(
    private val input: InputStream,
    private val output: OutputStream,
    val hello: JsonObject,
    private val onClose: () -> Unit,
) {
    private val queue = LinkedBlockingQueue<ByteArray>()
    private val nextId = AtomicLong(1)
    private val replies = ConcurrentHashMap<Long, CompletableFuture<JsonElement>>()
    private val streams = ConcurrentHashMap<Int, StreamListener>()
    private val early = HashMap<Int, MutableList<Early>>()
    /** Channels this side detached: late frames for them are dropped, not kept for a listener that will never come. */
    private val released = HashSet<Int>()
    private val automaticInput = ConcurrentHashMap.newKeySet<Int>()
    private val lock = Any()
    @Volatile var closed = false
        private set
    private val lostListeners = mutableListOf<() -> Unit>()

    val features: Set<String> = hello["features"].arr.mapNotNull { it.str }.toSet()

    companion object {
        /**
         * Says hello in framed mode and starts the reader and writer threads.
         * Lines before the reply that aren't JSON (a chatty login profile) are
         * skipped, up to a limit.
         */
        fun connect(rawInput: InputStream, output: OutputStream, onClose: () -> Unit = {}): CoreClient {
            val input = BufferedInputStream(rawInput, 1 shl 16)
            val hello = obj("jsonrpc" to "2.0", "id" to 0, "method" to "hello", "params" to obj("framed" to true, "generation" to GENERATION))
            output.write((hello.toString() + "\n").toByteArray())
            output.flush()
            val noise = StringBuilder()
            var reply: JsonObject? = null
            for (i in 0 until 200) {
                val line = readLine(input) ?: break
                val parsed = runCatching { json.parseToJsonElement(line).jsonObject }.getOrNull()
                if (parsed != null && (parsed.containsKey("result") || parsed.containsKey("error"))) {
                    reply = parsed
                    break
                }
                if (noise.length < 2000) noise.appendLine(line)
            }
            if (reply == null) {
                val extra = if (noise.isNotBlank()) "\n\nThe Mac printed instead:\n${noise.toString().trim().take(800)}" else ""
                throw IllegalStateException("No hello from herdr-core. Is it installed on the Mac (`hc --version`) and does the command reach it?$extra")
            }
            reply["error"]?.let { throw IllegalStateException("core refused hello: ${it["message"].str ?: it}") }
            val result = reply["result"].obj ?: JsonObject(emptyMap())
            return CoreClient(input, output, result, onClose).also { it.start() }
        }
    }

    private fun start() {
        Thread({
            try {
                while (true) {
                    val bytes = queue.take()
                    if (bytes.isEmpty()) break
                    output.write(bytes)
                    if (queue.isEmpty()) output.flush()
                }
            } catch (_: Exception) {
            }
            shutdown()
        }, "hc-writer").apply { isDaemon = true }.start()
        Thread({
            try {
                while (true) {
                    val frame = readFrame(input) ?: break
                    route(frame)
                }
            } catch (_: Exception) {
            }
            shutdown()
        }, "hc-reader").apply { isDaemon = true }.start()
    }

    private fun route(frame: Frame) {
        if (frame.channel == 0) {
            val msg = runCatching { json.parseToJsonElement(frame.payload.decodeToString()) }.getOrNull() ?: return
            val id = msg["id"].long ?: return
            val waiting = replies.remove(id) ?: return
            val error = msg["error"]
            if (error != null && error !is JsonNull) waiting.completeExceptionally(RpcException(error, "call"))
            else waiting.complete(msg["result"] ?: JsonNull)
            return
        }
        val item = when (frame.kind) {
            Kind.DATA -> Early.Data(frame.payload)
            Kind.CLOSE -> Early.Closed(runCatching { json.parseToJsonElement(frame.payload.decodeToString()) }.getOrDefault(JsonNull))
            Kind.RESIZE -> sizeOf(frame.payload)?.let { (c, r) -> Early.Resize(c, r) } ?: return
            else -> return
        }
        val listener = synchronized(lock) {
            if (frame.channel in released) return
            val l = streams[frame.channel]
            if (l == null) early.getOrPut(frame.channel) { mutableListOf() }.add(item)
            if (item is Early.Closed) {
                streams.remove(frame.channel)
                automaticInput.remove(frame.channel)
            }
            l
        } ?: return
        deliver(listener, item)
    }

    private fun deliver(l: StreamListener, item: Early) = when (item) {
        is Early.Data -> l.onData(item.bytes)
        is Early.Closed -> l.onClosed(item.reason)
        is Early.Resize -> l.onResize(item.cols, item.rows)
    }

    private fun shutdown() {
        val (listeners, lost) = synchronized(lock) {
            if (closed) return
            closed = true
            val l = streams.values.toList()
            streams.clear()
            automaticInput.clear()
            l to lostListeners.toList()
        }
        queue.clear()
        queue.offer(ByteArray(0))
        for (r in replies.values) r.completeExceptionally(CoreLostException("lost the core", afterWrite = true))
        replies.clear()
        for (l in listeners) runCatching { l.onLost() }
        for (l in lost) runCatching { l() }
        runCatching { input.close() }
        runCatching { output.close() }
        onClose()
    }

    /** Runs [f] once the connection is gone (at once if it already is). */
    fun onLost(f: () -> Unit) {
        val now = synchronized(lock) { if (closed) true else { lostListeners.add(f); false } }
        if (now) f()
    }

    fun close() = shutdown()

    fun send(channel: Int, kind: Int, payload: ByteArray) {
        if (!closed) queue.offer(encodeFrame(channel, kind, payload))
    }

    /** A JSON-RPC call on channel 0; blocks the calling thread (never call it on the UI thread). */
    fun call(method: String, params: JsonObject = JsonObject(emptyMap()), timeoutMs: Long = 10_000): JsonElement {
        if (closed) throw CoreLostException("$method: lost the core")
        val id = nextId.getAndIncrement()
        val future = CompletableFuture<JsonElement>()
        replies[id] = future
        if (closed) {
            replies.remove(id)
            throw CoreLostException("$method: lost the core")
        }
        val req = obj("jsonrpc" to "2.0", "id" to id, "method" to method, "params" to params)
        send(0, Kind.DATA, req.toString().toByteArray())
        try {
            return future.get(timeoutMs, TimeUnit.MILLISECONDS)
        } catch (e: TimeoutException) {
            replies.remove(id)
            throw CoreTimedOutException("$method: no reply from core")
        } catch (e: ExecutionException) {
            when (val cause = e.cause) {
                is RpcException -> throw RpcException(cause.error, method)
                is CoreLostException -> throw CoreLostException("$method: ${cause.message}", cause.afterWrite)
                else -> throw e
            }
        }
    }

    private fun register(channel: Int, listener: StreamListener) {
        // Frames that beat the attach reply wait in early[channel]. Deliver them in order, outside the lock;
        // frames that arrive meanwhile queue behind them (the stream is registered only once none are left),
        // so the size before the paint can't be overtaken by the reader thread.
        while (true) {
            val pending = synchronized(lock) {
                val p = early.remove(channel).orEmpty()
                if (p.isEmpty()) streams[channel] = listener
                p
            }
            if (pending.isEmpty()) return
            for (item in pending) deliver(listener, item)
        }
    }

    /**
     * Subscribes to events. [listen] gets the channel and the snapshot (`seq`,
     * `terminals`) to apply the stream to, before any event is delivered.
     */
    fun subscribe(listen: (channel: Int, snapshot: JsonObject) -> StreamListener): Pair<Int, JsonObject> {
        val result = call("events.subscribe").obj ?: throw IllegalStateException("subscription returned nothing")
        val channel = result["channel"].long?.toInt() ?: throw IllegalStateException("subscription returned no channel")
        register(channel, listen(channel, result))
        credit(channel, 1024 * 1024)
        return channel to result
    }

    /** [observing]: attached as an observer that hears the terminal's size (it never resizes it or types). */
    class Attached(val channel: Int, val terminal: JsonElement, val observing: Boolean = false)

    /**
     * Attaches to a terminal's screen; the first paint and its output arrive on [listener].
     * [observe]: watch only (needs [Watch.FEATURE]); the core then tells [StreamListener.onResize]
     * the terminal's size before the first paint, and ignores this viewer's typing and size.
     */
    fun attach(terminal: String, cols: Int, rows: Int, display: JsonObject, listener: StreamListener, observe: Boolean = false): Attached {
        val watch: Boolean? = if (observe) true else null // omitted, not false, for a typing viewer
        val result = call(
            "terminal.attach",
            obj("terminal" to terminal, "cols" to cols, "rows" to rows, "history" to 2000, "display" to display, "observe" to watch, "resize_notifications" to watch),
        )
        val channel = result["channel"].long?.toInt() ?: throw IllegalStateException("attach returned no channel")
        if ("automatic_input" in features && result["automatic_input"].bool == true) automaticInput.add(channel)
        register(channel, listener)
        return Attached(channel, result["terminal"] ?: JsonNull, observing = observe && result["resize_notifications"].bool == true)
    }

    fun input(channel: Int, bytes: ByteArray) {
        if (bytes.isNotEmpty()) send(channel, Kind.DATA, bytes)
    }

    /** Terminal-generated replies (answers to the program's queries), not typing. */
    fun automaticInput(channel: Int, bytes: ByteArray) {
        if (bytes.isEmpty()) return
        send(channel, if (channel in automaticInput) Kind.AUTOMATIC_INPUT else Kind.DATA, bytes)
    }

    fun credit(channel: Int, n: Int) = send(channel, Kind.CREDIT, u32(n))
    fun resize(channel: Int, cols: Int, rows: Int) = send(channel, Kind.RESIZE, resizePayload(cols, rows))
    fun focus(channel: Int, focused: Boolean) = send(channel, Kind.FOCUS, byteArrayOf(if (focused) 1 else 0))
    fun facts(channel: Int, display: JsonObject) = send(channel, Kind.FACTS, display.toString().toByteArray())

    fun detach(channel: Int) {
        synchronized(lock) {
            streams.remove(channel)
            automaticInput.remove(channel)
            early.remove(channel)
            released.add(channel)
        }
        send(channel, Kind.CLOSE, ByteArray(0))
    }

    /** Starts a program in a new kept core terminal; returns its address (`t7`, or `box/t7` elsewhere). */
    fun run(argv: List<String>, cwd: String?, labels: Map<String, String>, on: String?, cols: Int?, rows: Int?, display: JsonObject): String {
        val env = obj("TERM" to "xterm-256color", "COLORTERM" to "truecolor", "TERM_PROGRAM" to "herdr")
        var params = obj(
            "argv" to argv, "cwd" to cwd?.takeIf { it.isNotEmpty() }, "env" to env, "labels" to labels,
            "on" to on, "keep" to true, "display" to display,
        )
        if (cols != null && rows != null) params = params.with("size" to obj("cols" to cols, "rows" to rows))
        val r = call("terminal.run", params)
        return r["address"].str ?: throw IllegalStateException("terminal.run returned no address: $r")
    }
}
