package herdr.core

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** One thing waiting on the person, as the Mac's queue command reports it. All text is untrusted: show it as plain text only. */
data class QueueItem(
    val id: String,
    val kind: String,
    val source: String,
    val title: String,
    val detail: String,
    val ageDays: Int?,
    /** A path on the Mac, opened only in the app's file viewer. */
    val file: String?,
    /** As given; [Queue.target] decides whether it may be opened. */
    val url: String?,
    val expiresInDays: Int?,
)

/** What a tap on an item does. */
sealed interface QueueTarget {
    /** An http or https address: an Android VIEW intent. */
    data class Url(val url: String) : QueueTarget
    /** A path on the Mac: the in-app file viewer, never an intent. */
    data class File(val path: String) : QueueTarget
    /** Nothing to open: show the item's detail text ([url] too, as text, when it was refused). */
    data class Detail(val text: String) : QueueTarget
}

/** Why the queue could not be loaded. [message] is what the person reads. */
sealed class QueueFailure(val message: String) {
    data object NotConnected : QueueFailure("Not connected to the Mac")
    data object TimedOut : QueueFailure("The command took longer than ${Queue.TIMEOUT_MS / 1000} seconds")
    data class Exited(val code: Long, val line: String) : QueueFailure(
        "The command failed (exit $code)" + if (line.isNotEmpty()) ": $line" else "",
    )
    data class BadOutput(val why: String) : QueueFailure("The command's output was not the expected list ($why)")
    data class Other(val line: String) : QueueFailure(line.ifEmpty { "Could not run the command" })
}

/** What parsing the command's output gave. */
sealed interface QueueParse {
    data class Parsed(val items: List<QueueItem>, val skipped: Int, val agentRows: Int, val cut: Int) : QueueParse
    data class Bad(val why: String) : QueueParse
}

/** The queue as the screen shows it: the last good list, and how the last try went. */
data class QueueState(
    val items: List<QueueItem> = emptyList(),
    /** When [items] was loaded (epoch ms), null before the first good load. */
    val loadedAt: Long? = null,
    val loading: Boolean = false,
    val failure: QueueFailure? = null,
) {
    /** The list shown is older than the last try: that try failed. */
    val stale get() = failure != null && loadedAt != null
    fun started() = copy(loading = true)
    fun loaded(items: List<QueueItem>, now: Long) = QueueState(items, now, false, null)
    /** The last good list is kept. */
    fun failed(why: QueueFailure) = copy(loading = false, failure = why)
}

object Queue {
    const val DEFAULT_COMMAND = "python3 ~/cos/scripts/panel/panel.py queue"
    const val MAX_ITEMS = 100
    /** Output past this is refused rather than parsed (the real output is a few KB). */
    const val MAX_OUTPUT_CHARS = 512 * 1024
    const val TIMEOUT_MS = 10_000L
    const val REFRESH_MS = 180_000L
    /** Coming back on screen refreshes, unless it was tried this recently. */
    const val SHOW_MIN_MS = 30_000L
    private const val MAX_TITLE = 300
    private const val MAX_DETAIL = 2_000
    private const val MAX_FIELD = 200
    private const val MAX_PATH = 4_096

    /**
     * Reads the command's stdout. Unknown fields are ignored; an entry without `id` or `title` is skipped;
     * rows whose `kind` starts with "agent" are left out (the agents list shows those); the script's order is kept;
     * at most [MAX_ITEMS] are returned.
     */
    fun parse(text: String): QueueParse {
        if (text.length > MAX_OUTPUT_CHARS) return QueueParse.Bad("too much output")
        val root = runCatching { json.parseToJsonElement(text) }.getOrNull() ?: return QueueParse.Bad("not JSON")
        if (root !is JsonArray) return QueueParse.Bad("not a list")
        var skipped = 0
        var agentRows = 0
        val out = ArrayList<QueueItem>()
        for (e in root) {
            val item = item(e)
            if (item == null) { skipped++; continue }
            if (item.kind.trim().startsWith("agent", ignoreCase = true)) { agentRows++; continue }
            out.add(item)
        }
        val cut = (out.size - MAX_ITEMS).coerceAtLeast(0)
        return QueueParse.Parsed(out.take(MAX_ITEMS), skipped, agentRows, cut)
    }

    private fun item(e: JsonElement): QueueItem? {
        val o = e as? JsonObject ?: return null
        val id = o["id"].str?.trim()?.ifEmpty { null } ?: return null
        val title = o["title"].str?.trim()?.ifEmpty { null } ?: return null
        return QueueItem(
            id = id.take(MAX_FIELD),
            kind = o["kind"].str.orEmpty().take(MAX_FIELD),
            source = o["source"].str.orEmpty().take(MAX_FIELD),
            title = title.take(MAX_TITLE),
            detail = o["detail"].str.orEmpty().take(MAX_DETAIL),
            ageDays = o["age_days"].int(),
            file = o["file"].str?.takeIf { it.isNotBlank() && it.length <= MAX_PATH && '\u0000' !in it },
            url = o["url"].str?.trim()?.ifEmpty { null }?.take(MAX_PATH),
            expiresInDays = o["expires_in_days"].int(),
        )
    }

    private fun JsonElement?.int(): Int? = (this as? JsonPrimitive)?.takeIf { !it.isString }?.content?.toLongOrNull()?.coerceIn(-1_000_000, 1_000_000)?.toInt()

    /** Only http and https, with a host and no spaces or control characters; anything else is text. */
    fun openable(url: String): Boolean {
        val u = url.trim()
        if (u.any { it.isWhitespace() || it.isISOControl() }) return false
        val rest = when {
            u.startsWith("https://", ignoreCase = true) -> u.substring(8)
            u.startsWith("http://", ignoreCase = true) -> u.substring(7)
            else -> return false
        }
        val host = rest.substringBefore('/').substringBefore('?').substringBefore('#')
        return host.isNotEmpty() && !host.startsWith("@") && !host.startsWith(":")
    }

    /** url (http/https only), else file, else the detail text. */
    fun target(item: QueueItem): QueueTarget {
        item.url?.let { if (openable(it)) return QueueTarget.Url(it.trim()) }
        item.file?.let { return QueueTarget.File(it) }
        val text = listOfNotNull(item.detail.ifBlank { null }, item.url?.takeIf { !openable(it) }).joinToString("\n")
        return QueueTarget.Detail(text.ifEmpty { item.title })
    }

    /** The heading: "Queue · 7", or "Queue · nothing waiting". */
    fun heading(state: QueueState): String = when {
        state.items.isNotEmpty() -> "Queue · ${state.items.size}"
        state.loadedAt != null -> "Queue · nothing waiting"
        state.failure != null -> "Queue · couldn't load"
        state.loading -> "Queue · loading"
        else -> "Queue"
    }

    /** "updated just now", "updated 3 min ago", "updated 2 h ago". */
    fun updatedText(loadedAt: Long, now: Long): String {
        val min = ((now - loadedAt).coerceAtLeast(0) / 60_000)
        return when {
            min < 1 -> "updated just now"
            min < 60 -> "updated $min min ago"
            min < 24 * 60 -> "updated ${min / 60} h ago"
            else -> "updated ${min / (24 * 60)} d ago"
        }
    }

    /** "2 d" style age for a row, null when unknown or today. */
    fun ageText(item: QueueItem): String? = item.ageDays?.takeIf { it > 0 }?.let { "$it d old" }

    /** What running the command came to, from the core's `machine.exec` answer. [stderr] may be long: only its first line is kept. */
    fun outcome(code: Long?, stdout: String, stderr: String, timedOut: Boolean, truncated: Boolean): QueueResult {
        if (timedOut) return QueueResult.Failed(QueueFailure.TimedOut)
        if (code != null && code != 0L) {
            val line = stderr.lineSequence().map { it.trim() }.firstOrNull { it.isNotEmpty() }.orEmpty().take(200)
            return QueueResult.Failed(QueueFailure.Exited(code, line))
        }
        if (truncated) return QueueResult.Failed(QueueFailure.BadOutput("too much output"))
        return when (val p = parse(stdout)) {
            is QueueParse.Bad -> QueueResult.Failed(QueueFailure.BadOutput(p.why))
            is QueueParse.Parsed -> QueueResult.Loaded(p.items)
        }
    }

    /**
     * Whether to refresh now: none is running and the last try is older than [every] (every [REFRESH_MS] while the
     * section stays on screen; [SHOW_MIN_MS] when it comes back on screen). Never tried, or "not connected" with a
     * connection now: yes.
     */
    fun due(state: QueueState, lastTry: Long?, now: Long, every: Long = REFRESH_MS, connected: Boolean = true): Boolean {
        if (state.loading) return false
        if (lastTry == null) return true
        if (state.failure == QueueFailure.NotConnected && connected) return true
        return now - lastTry >= every
    }
}

sealed interface QueueResult {
    data class Loaded(val items: List<QueueItem>) : QueueResult
    data class Failed(val why: QueueFailure) : QueueResult
}
