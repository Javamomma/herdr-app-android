package herdr.core

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** What a push changed. */
data class Pushed(
    /** A layout document changed, or events were missed: read the documents again. */
    val docs: Boolean = false,
    /** Machines whose document changed (`""`: the Mac itself), when known. */
    val docMachines: Set<String> = emptySet(),
    val terminals: Boolean = false,
)

/**
 * The event stream applied to the subscription's snapshot: every terminal
 * the core reports, kept as the core has it (herdr-core schema/README.md,
 * Events). A port of the desktop app's `hc::EventLines`.
 */
class EventLines(snapshot: JsonElement) {
    private val pending = java.io.ByteArrayOutputStream()
    var seq: Long = snapshot["seq"].long ?: 0
        private set
    val terminals: MutableList<JsonObject> = snapshot["terminals"].arr.mapNotNull { it.obj }.toMutableList()

    fun live(): List<JsonObject> = terminals.filter { it["state"].str == "live" }

    fun push(bytes: ByteArray): Pushed {
        pending.write(bytes)
        val all = pending.toByteArray()
        var consumed = 0
        val lines = mutableListOf<JsonElement>()
        for (i in all.indices) {
            if (all[i] == '\n'.code.toByte()) {
                val text = String(all, consumed, i - consumed, Charsets.UTF_8)
                lines.add(runCatching { json.parseToJsonElement(text) }.getOrDefault(JsonNull))
                consumed = i + 1
            }
        }
        pending.reset()
        pending.write(all, consumed, all.size - consumed)
        var docs = false
        val machines = mutableSetOf<String>()
        var changed = false
        for (line in lines) {
            val p = line["params"]
            when (line["method"].str) {
                "missed" -> {
                    terminals.clear()
                    terminals.addAll(p["terminals"].arr.mapNotNull { it.obj })
                    seq = p["seq"].long ?: seq
                    docs = true
                    changed = true
                }
                "event" -> {
                    val s = p["seq"].long
                    if (s != null && s <= seq) continue
                    if (s != null) seq = s
                    if (p["type"].str == "doc") {
                        docs = true
                        machines.add(p["machine"].str ?: "")
                    } else {
                        changed = apply(p) || changed
                    }
                }
            }
        }
        return Pushed(docs, machines, changed)
    }

    private fun apply(p: JsonElement?): Boolean {
        val kind = p["type"].str ?: ""
        val address = p["terminal"].str
        val data = p["data"]
        val at = terminals.indexOfFirst { it["address"].str == address }
        val whole = when (kind) {
            "created", "restarted" -> data.obj
            "labels" -> data["record"].obj
            else -> null
        }
        if (whole != null) {
            if (at >= 0) terminals[at] = whole else terminals.add(whole)
            return true
        }
        if (at < 0) return false
        if (kind == "removed") {
            terminals.removeAt(at)
            return true
        }
        var t = terminals[at]
        fun set(vararg keys: String) {
            t = t.with(*keys.map { it to (data[it] ?: JsonNull) }.toTypedArray())
        }
        when (kind) {
            "finished", "lost" -> {
                set("reason", "exit_code", "signal", "finished_ms")
                t = t.with("state" to JsonPrimitive(if (kind == "lost") "live" else "finished"), "foreground" to null)
                if (kind == "lost") t = t.with("pid" to JsonNull)
            }
            "status" -> set("status", "records", "reported")
            "turn" -> set("turn")
            "title" -> set("title")
            "labels" -> set("labels")
            "keep" -> set("keep")
            "size" -> set("cols", "rows")
            "foreground" -> t = t.with("foreground" to (data ?: JsonNull))
            else -> return false
        }
        terminals[at] = t
        return true
    }
}
