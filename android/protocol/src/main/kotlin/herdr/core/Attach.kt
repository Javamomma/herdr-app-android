package herdr.core

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Base64
import java.util.Locale
import java.util.concurrent.CancellationException

/**
 * Attaching a photo: decisions and the `terminal.put` call (herdr-core 0.1.21), plain Kotlin so they are
 * tested on the JVM. The phone side (decoding, rotating, encoding) lives in `app/`.
 */
object Attach {
    /** The longest side of a sent picture, in pixels; smaller pictures are never enlarged. */
    const val MAX_SIDE = 2000
    /** One try at making the picture fit: the longest side and the JPEG quality (0..100). */
    data class Step(val side: Int, val quality: Int)

    /** Tried in order until the picture fits [MAX_BYTES]; after the last one it is refused. */
    val LADDER = listOf(Step(MAX_SIDE, 85), Step(MAX_SIDE, 70), Step(1600, 70), Step(1280, 70), Step(1280, 55))

    /** The first of [LADDER] whose result is at most [MAX_BYTES] ([encode] gives the result and its size), else null. */
    fun <T> fit(encode: (Step) -> Pair<T, Long>): T? {
        for (step in LADDER) {
            val (result, size) = encode(step)
            if (size <= MAX_BYTES) return result
        }
        return null
    }
    /**
     * What is sent at most (the picture after shrinking). The core's own limit is 8 MB, but the request is one frame
     * on the connection's single writer, so keys and Send wait behind it: kept small (base64 adds a third).
     */
    const val MAX_BYTES = 2 * 1024 * 1024
    /** A PNG is kept unless it is bigger than this and at least [PNG_RATIO] times the JPEG. */
    const val PNG_KEEP_BYTES = 1_500_000
    const val PNG_RATIO = 3

    /** Size after scaling so the longest side is at most [maxSide]; the shape is kept, a side never goes below 1. */
    fun target(width: Int, height: Int, maxSide: Int = MAX_SIDE): Pair<Int, Int> {
        if (width <= 0 || height <= 0) return 1 to 1
        val longest = maxOf(width, height)
        if (longest <= maxSide) return width to height
        val f = maxSide.toDouble() / longest
        return maxOf(1, Math.round(width * f).toInt()) to maxOf(1, Math.round(height * f).toInt())
    }

    /** The decoder's power-of-two sample size: the largest one that still leaves both sides at least the [target]. */
    fun sampleSize(width: Int, height: Int, targetW: Int, targetH: Int): Int {
        var s = 1
        while (width / (s * 2) >= targetW && height / (s * 2) >= targetH) s *= 2
        return s
    }

    enum class Format(val ext: String, val mime: String) { Jpeg("jpg", "image/jpeg"), Png("png", "image/png") }

    /** Only PNG sources that are opaque and not much bigger than the JPEG stay PNG; everything else is JPEG. */
    fun wantsPng(sourceMime: String?): Boolean = sourceMime?.lowercase(Locale.ROOT) == "image/png"

    /**
     * Which of the two encodings to send, when the source is a PNG ([pngBytes] and [jpegBytes] are their sizes):
     * the PNG, unless it is over [MAX_BYTES], or over [PNG_KEEP_BYTES] and [PNG_RATIO] times the JPEG or more.
     * Transparency does not keep a PNG once it is too big: the JPEG is drawn on white.
     */
    fun chooseFormat(pngBytes: Int, jpegBytes: Int): Format =
        if (pngBytes > MAX_BYTES) Format.Jpeg
        else if (pngBytes <= PNG_KEEP_BYTES || pngBytes < jpegBytes.toLong() * PNG_RATIO) Format.Png else Format.Jpeg

    /** `photo-yyyyMMdd-HHmmss.jpg|png`: no original name, it can be private. */
    fun fileName(epochMs: Long, zone: ZoneId, format: Format): String =
        "photo-" + DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss", Locale.ROOT).withZone(zone).format(Instant.ofEpochMilli(epochMs)) + "." + format.ext

    /** What base64 makes of [bytes]. */
    fun wireBytes(bytes: Int): Long = (bytes + 2L) / 3 * 4

    /** How long to wait for the core: 20 s plus 1 s for every 50 KB on the wire (a slow link), at most 3 minutes. */
    fun timeoutMs(bytes: Int): Long = (20_000L + wireBytes(bytes) / 50L).coerceAtMost(180_000L)

    /** Whether Send and the answer buttons must wait: a photo is uploading to this terminal (its frame holds the writer). */
    fun blocksSend(attaching: String?, address: String): Boolean = attaching != null && attaching == address

    /** A shared picture is read only from a `content` address (another app's grant), never a file or anything else. */
    fun acceptableScheme(scheme: String?): Boolean = scheme.equals("content", ignoreCase = true)

    /** The `terminal.put` parameters: the bytes go as base64 inside the JSON. */
    fun request(terminal: String, name: String, data: ByteArray) =
        obj("terminal" to terminal, "name" to name, "data" to Base64.getEncoder().encodeToString(data))

    /** What the core says about a stored file: where it is, on the terminal's own machine. */
    data class Placed(val path: String, val bytes: Long)

    /** Reads a `terminal.put` reply; null when it has no usable path. */
    fun parse(reply: kotlinx.serialization.json.JsonElement): Placed? {
        val path = reply["path"].str?.takeIf { it.isNotBlank() } ?: return null
        return Placed(path, reply["bytes"].long ?: 0)
    }

    /** Whether this core can take a file: it lists the call in its hello (0.1.21 and newer). */
    fun supported(core: CoreClient?): Boolean =
        core != null && !core.closed && core.hello["methods"].arr.any { it.str == "terminal.put" }

    /** Why nothing was attached; [notice] is what the person reads. The draft is never touched. */
    sealed class Failure(val notice: String) {
        object NotConnected : Failure("Not connected to the Mac: the photo was not attached.")
        object Unsupported : Failure("This herdr-core cannot take files yet: run `hc update` on the Mac.")
        object TooLarge : Failure("That photo is too large to send (over ${MAX_BYTES / (1024 * 1024)} MB even after shrinking).")
        object Unreadable : Failure("Could not read that picture.")
        object Local : Failure("Could not prepare the photo on this phone (storage full or not readable). Nothing was sent.")
        object TimedOut : Failure("Timed out: the Mac did not answer. The photo may have arrived there, but nothing was added to your message.")
        object Lost : Failure("The connection dropped while uploading. Nothing was added to your message.")
        class Refused(why: String) : Failure("The Mac refused the photo: $why")
    }

    /** Maps what a failed call threw to a [Failure]. A cancel is not a failure: it is rethrown. */
    fun failure(e: Throwable): Failure = when (e) {
        is CancellationException, is InterruptedException -> throw e
        is RpcException -> when (e.kind) {
            "not_found" -> Failure.Refused("that terminal is gone.")
            "finished" -> Failure.Refused("that terminal has finished.")
            "machine_unknown" -> Failure.Refused("that machine is not known to the core.")
            else -> Failure.Refused(oneLine(e.message))
        }
        is CoreLostException ->
            if (e.afterWrite && e.message?.contains("no reply") == true) Failure.TimedOut
            else if (e.afterWrite) Failure.Lost
            else Failure.NotConnected
        is IllegalStateException -> Failure.NotConnected
        else -> Failure.Refused(oneLine(e.message))
    }

    private fun oneLine(m: String?): String = (m ?: "no reason given").lineSequence().firstOrNull().orEmpty().trim().take(200).ifEmpty { "no reason given" }

    /** The result of an upload: the path to insert, or why not. */
    sealed class Outcome {
        class Done(val path: String) : Outcome()
        class Failed(val why: Failure) : Outcome()
    }

    /**
     * Sends [data] named [name] beside [terminal] with `terminal.put` and returns where it landed.
     * Blocks (run it on a worker thread; an interrupt cancels the wait); never retries.
     */
    fun upload(core: CoreClient?, terminal: String, name: String, data: ByteArray): Outcome {
        if (core == null || core.closed) return Outcome.Failed(Failure.NotConnected)
        if (!supported(core)) return Outcome.Failed(Failure.Unsupported)
        if (data.size > MAX_BYTES) return Outcome.Failed(Failure.TooLarge)
        // Cancelled while it was being prepared: nothing is queued.
        if (Thread.currentThread().isInterrupted) throw InterruptedException()
        return try {
            val placed = parse(core.call("terminal.put", request(terminal, name, data), timeoutMs(data.size)))
                ?: return Outcome.Failed(Failure.Refused("it gave no path."))
            Outcome.Done(placed.path)
        } catch (e: Throwable) {
            Outcome.Failed(failure(e))
        }
    }

    /**
     * The agent a shared picture is preselected for: the last one opened if it is still listed, else the one
     * in view, else the first listed; null when there are none. [agents] are addresses in list order.
     */
    fun defaultTarget(agents: List<String>, lastOpened: String?, inView: String?): String? =
        agents.firstOrNull { it == lastOpened } ?: agents.firstOrNull { it == inView } ?: agents.firstOrNull()
}
