package herdr.core

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.security.SecureRandom

/*
 * The machine-owned layout document (`doc.get {name: "herdr-app"}`), shared
 * with the desktop app: spaces, their tabs, and each tab's split tree. The
 * tree is kept as JSON so fields this client doesn't draw (pane sizes in px,
 * floats, titles) survive a round trip. Rules follow the desktop app's
 * src/layout.rs and src/mirror.rs.
 */

const val DOC_NAME = "herdr-app"

enum class Axis(val type: String) { Row("row"), Column("column") }

data class Tab(val id: String, val name: String, val root: JsonObject)

data class Space(
    val id: String,
    val name: String,
    val folder: String,
    /** null: the Mac itself; else the name the Mac's core gives another machine. */
    val machine: String?,
    val tabs: List<Tab>,
)

private val random = SecureRandom()

/** A fresh id like the desktop app's (`p` + 32 hex digits), never a per-screen counter. */
fun freshId(prefix: String): String {
    val bytes = ByteArray(16).also { random.nextBytes(it) }
    return prefix + bytes.joinToString("") { "%02x".format(it) }
}

fun qualify(id: String, machine: String?): String = if (machine == null) id else "$machine/$id"

fun local(id: String, machine: String?): String =
    if (machine != null && id.startsWith("$machine/")) id.removePrefix("$machine/") else id

/** `rohan/t7` → `rohan`; a bare `t7` → null (the Mac). */
fun machineOf(address: String): String? = address.substringBefore('/', "").ifEmpty { null }

// ---- nodes ----------------------------------------------------------------

val JsonObject.nodeId: String get() = this["id"].str ?: ""
val JsonObject.type: String get() = this["type"].str ?: ""
val JsonObject.children: List<JsonObject> get() = this["children"].arr.mapNotNull { it.obj }
val JsonObject.terminal: String? get() = this["content"]["terminal"].str
val JsonObject.modName: String? get() = this["content"]["mod"].str
val JsonObject.isContainer: Boolean get() = type == "row" || type == "column" || type == "stack"
val JsonObject.isPane: Boolean get() = type == "block" && (terminal != null || modName != null)
/** A node's share of its row or column (`size.weight`, default 1); null for a fixed px size. */
val JsonObject.weight: Double? get() {
    val size = this["size"].obj ?: return 1.0
    if (size.containsKey("px")) return null
    return (size["weight"] as? JsonPrimitive)?.content?.toDoubleOrNull() ?: 1.0
}

fun JsonObject.withChildren(c: List<JsonObject>): JsonObject = with("children" to JsonArray(c))
fun JsonObject.withWeight(w: Double): JsonObject = with("size" to obj("weight" to w))

fun container(id: String, axis: Axis, children: List<JsonObject>): JsonObject =
    obj("id" to id, "type" to axis.type, "children" to children)

fun terminalPane(id: String, address: String): JsonObject =
    obj("id" to id, "type" to "block", "content" to obj("terminal" to address))

fun JsonObject.walk(): List<JsonObject> = listOf(this) + children.flatMap { it.walk() }

fun JsonObject.find(id: String): JsonObject? = walk().firstOrNull { it.nodeId == id }

fun JsonObject.panes(): List<JsonObject> = walk().filter { it.isPane }

fun JsonObject.parentOf(id: String): JsonObject? = walk().firstOrNull { p -> p.children.any { it.nodeId == id } }

/** The shown child of a stack: its `active`, else the first. */
fun JsonObject.activeChild(): JsonObject? {
    val active = this["active"].str
    return children.firstOrNull { it.nodeId == active } ?: children.firstOrNull()
}

/** Replaces the node [id] by what [f] makes of it, anywhere in the tree. */
fun JsonObject.replace(id: String, f: (JsonObject) -> JsonObject): JsonObject {
    if (nodeId == id) return f(this)
    if (!containsKey("children")) return this
    return withChildren(children.map { it.replace(id, f) })
}

/** Ids and terminal addresses mapped (qualifying or stripping a machine). */
fun JsonObject.mapIds(map: (String) -> String): JsonObject {
    var n = with("id" to JsonPrimitive(map(nodeId)))
    terminal?.let { n = n.with("content" to (this["content"].obj ?: obj()).with("terminal" to JsonPrimitive(map(it)))) }
    if (type == "stack") this["active"].str?.let { n = n.with("active" to JsonPrimitive(map(it))) }
    this["floating"]?.let { floats ->
        n = n.with("floating" to JsonArray(floats.arr.map { f ->
            val fo = f.obj ?: return@map f
            var x = fo.with("id" to JsonPrimitive(map(fo.nodeId)))
            fo["content"]["terminal"].str?.let { t -> x = x.with("content" to fo["content"].obj!!.with("terminal" to JsonPrimitive(map(t)))) }
            x
        }))
    }
    if (containsKey("children")) n = n.withChildren(children.map { it.mapIds(map) })
    return n
}

/**
 * Puts [new] beside [target] along [axis] (after it): reuses the parent if it
 * already runs along that axis, else wraps the target in a new container.
 */
fun JsonObject.split(target: String, axis: Axis, new: JsonObject, containerId: String): JsonObject? {
    val parent = parentOf(target)
    if (parent != null && parent.type == axis.type) {
        return replace(parent.nodeId) { p ->
            val c = p.children.toMutableList()
            val i = c.indexOfFirst { it.nodeId == target }
            val w = c[i].weight
            val share = w ?: 1.0
            if (w != null) c[i] = c[i].withWeight(share / 2)
            c.add(i + 1, new.withWeight(share / 2))
            p.withChildren(c)
        }
    }
    find(target) ?: return null
    return replace(target) { old ->
        var wrapper = container(containerId, axis, listOf(old.with("size" to null), new))
        old["size"]?.let { wrapper = wrapper.with("size" to it) }
        wrapper
    }
}

/**
 * Removes the node [id]. Its share goes back to the neighbor it was split
 * from; a row or column left with one child gives way to it; emptied ones
 * vanish. The root itself stays (the tab decides when it closes).
 */
fun JsonObject.remove(id: String): JsonObject = collapse(removeInner(id))

private fun JsonObject.removeInner(id: String): JsonObject {
    if (!containsKey("children")) return this
    val c = children.toMutableList()
    val i = c.indexOfFirst { it.nodeId == id }
    if (i < 0) return withChildren(c.map { it.removeInner(id) })
    val removed = c.removeAt(i)
    var n = this
    if (type == "row" || type == "column") {
        val gone = removed.weight
        val j = maxOf(i - 1, 0)
        val w = c.getOrNull(j)?.weight
        if (gone != null && w != null) c[j] = c[j].withWeight(w + gone)
    }
    if (type == "stack" && this["active"].str == id) {
        n = n.with("active" to (c.getOrNull(i) ?: c.lastOrNull())?.let { JsonPrimitive(it.nodeId) })
    }
    return n.withChildren(c)
}

private fun collapse(node: JsonObject): JsonObject {
    if (!node.containsKey("children")) return node
    var c = node.children.map { collapse(it) }
    if (node.type == "stack") return node.withChildren(c)
    val line = { n: JsonObject -> n.type == "row" || n.type == "column" }
    c = c.filterNot { line(it) && it.children.isEmpty() }
    c = c.map { n ->
        if (line(n) && n.children.size == 1) {
            val only = n.children[0]
            n["size"]?.let { only.with("size" to it) } ?: only.with("size" to null)
        } else n
    }
    return node.withChildren(c)
}

/** Sets the weights of a row's or column's children (a divider dragged). */
fun JsonObject.setWeights(containerId: String, weights: List<Double>): JsonObject = replace(containerId) { p ->
    p.withChildren(p.children.mapIndexed { i, ch -> if (i < weights.size && ch.weight != null) ch.withWeight(weights[i]) else ch })
}

// ---- documents ------------------------------------------------------------

/** One machine's document as spaces with machine-qualified ids. */
fun parseDocument(value: JsonElement?, machine: String?): List<Space> {
    val map = { s: String -> qualify(s, machine) }
    return value["spaces"].arr.mapNotNull { it.obj }.map { s ->
        Space(
            id = map(s["id"].str ?: ""),
            name = s["name"].str ?: "",
            folder = s["folder"].str ?: "",
            machine = machine,
            tabs = s["tabs"].arr.mapNotNull { it.obj }.map { t ->
                val root = t["root"].obj ?: container("", Axis.Row, emptyList())
                Tab(map(t["id"].str ?: ""), t["name"].str ?: "", root.mapIds(map))
            },
        )
    }
}

/** The document for [machine] from its spaces, ids made local again. */
fun captureDocument(spaces: List<Space>, machine: String?): JsonObject {
    val map = { s: String -> local(s, machine) }
    return obj("spaces" to spaces.filter { it.machine == machine }.map { s ->
        obj(
            "id" to map(s.id), "name" to s.name, "folder" to s.folder,
            "tabs" to s.tabs.map { t -> obj("id" to map(t.id), "name" to t.name, "root" to t.root.mapIds(map)) },
        )
    })
}

/** A new tab holding one terminal pane; its root container has the tab's id, as on the desktop. */
fun newTab(id: String, name: String, paneId: String, address: String): Tab =
    Tab(id, name, container(id, Axis.Row, listOf(terminalPane(paneId, address))))

/** A folder as a name: its last part, `~` for home. */
fun folderName(path: String, home: String?): String {
    val p = path.trimEnd('/', '\\')
    if (p.isEmpty()) return if (path.isEmpty()) "" else "/"
    if (home != null && p == home.trimEnd('/', '\\')) return "~"
    return p.substringAfterLast('/').substringAfterLast('\\')
}
