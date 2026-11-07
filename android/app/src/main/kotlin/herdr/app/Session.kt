package herdr.app

import android.content.Context
import android.util.Base64
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import herdr.app.ssh.DeviceKey
import herdr.app.ssh.KnownHosts
import herdr.app.ssh.Profile
import herdr.app.ssh.Secrets
import herdr.app.ssh.SshLink
import herdr.app.ssh.UnknownHostKey
import herdr.core.Axis
import herdr.core.CoreClient
import herdr.core.DOC_NAME
import herdr.core.EventLines
import herdr.core.RpcException
import herdr.core.Space
import herdr.core.StreamListener
import herdr.core.Tab
import herdr.core.arr
import herdr.core.bool
import herdr.core.captureDocument
import herdr.core.find
import herdr.core.folderName
import herdr.core.freshId
import herdr.core.get
import herdr.core.json
import herdr.core.local
import herdr.core.long
import herdr.core.machineOf
import herdr.core.mismatch
import herdr.core.newTab
import herdr.core.obj
import herdr.core.panes
import herdr.core.parseDocument
import herdr.core.qualify
import herdr.core.remove
import herdr.core.setWeights
import herdr.core.split
import herdr.core.str
import herdr.core.terminal
import herdr.core.terminalPane
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import net.schmizz.sshj.userauth.UserAuthException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

sealed interface Conn {
    data object Idle : Conn
    data class Connecting(val step: String) : Conn
    data object Connected : Conn
    data class Failed(val message: String, val hostKey: UnknownHostKey? = null, val retrying: Boolean = false) : Conn
}

data class MachineInfo(val name: String, val host: String, val temporary: Boolean)

/** An agent: a live terminal whose program reports a status (OSC 7501). */
data class Agent(
    val address: String,
    /** blocked, done, working, idle (or error), as the desktop shows it. */
    val state: String,
    val app: String,
    val title: String,
    val parent: String?,
    val spaceId: String?,
    val tabId: String?,
    val paneId: String?,
)

/** Where a pane lives. */
data class PanePlace(val space: Space, val tab: Tab, val pane: JsonObject)

private data class DocSlot(val version: Long, val spaces: List<Space>)

val URGENCY = listOf("blocked", "error", "done", "working", "idle")

/**
 * One connection to the Mac's herdr-core and everything shown from it: the
 * live terminals, the machines, and each machine's layout document, plus
 * this screen's own view (which space and tab are shown, focus, zoom), which
 * like on the desktop is never shared.
 */
class Session(private val context: Context) {
    private val main = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    val secrets = Secrets(context)
    val deviceKey = DeviceKey(secrets)
    private val known = KnownHosts(context)
    private val viewPrefs = context.getSharedPreferences("view", Context.MODE_PRIVATE)

    var profile by mutableStateOf(Profile.load(context))
        private set
    var conn by mutableStateOf<Conn>(Conn.Idle)
        private set
    var core: CoreClient? = null
        private set
    /** Rises with every new connection: terminal views reattach. */
    var generation by mutableIntStateOf(0)
        private set
    var coreVersion by mutableStateOf("")
        private set
    var terminals by mutableStateOf<Map<String, JsonObject>>(emptyMap())
        private set
    var machines by mutableStateOf<List<MachineInfo>>(emptyList())
        private set
    var spaces by mutableStateOf<List<Space>>(emptyList())
        private set
    /** Machines whose layout can't be read right now, and why. */
    var layoutErrors by mutableStateOf<Map<String, String>>(emptyMap())
        private set
    var home by mutableStateOf<String?>(null)
        private set
    var notice by mutableStateOf<String?>(null)

    var activeSpace by mutableStateOf<String?>(viewPrefs.getString("space", null))
        private set
    val activeTab = mutableStateMapOf<String, String>()
    val focused = mutableStateMapOf<String, String>()
    val zoomed = mutableStateMapOf<String, String>()
    /** A terminal shown over the tab (a sub-agent without a pane). */
    var peek by mutableStateOf<String?>(null)
    /** The key bar's sticky Ctrl and Alt, for the next key typed. */
    var ctrl by mutableStateOf(false)
    var alt by mutableStateOf(false)

    private val docs = HashMap<String, DocSlot>()
    private val writeLocks = HashMap<String, Mutex>()
    private val writtenSeen = HashMap<String, Long>()
    private var reconnect: Job? = null
    private var foreground = true
    var wantConnected = false
        private set

    init {
        runCatching {
            val saved = json.parseToJsonElement(viewPrefs.getString("tabs", "{}")!!).jsonObject
            for ((k, v) in saved) v.str?.let { activeTab[k] = it }
        }
    }

    private fun key(machine: String?) = machine ?: ""

    fun saveProfile(p: Profile, password: String?) {
        p.save(context)
        if (password != null) secrets.put("password", password.ifEmpty { null })
        profile = p
    }

    // ---- connection ---------------------------------------------------------

    fun connect(acceptFingerprint: String? = null) {
        if (conn is Conn.Connecting || conn is Conn.Connected) return
        if (!profile.ready) return
        wantConnected = true
        reconnect?.cancel()
        conn = Conn.Connecting("Connecting to ${profile.host}…")
        val p = profile
        main.launch {
            try {
                val client = withContext(Dispatchers.IO) {
                    val link = SshLink.open(p, known, deviceKey, secrets.get("password"), acceptFingerprint)
                    withContext(Dispatchers.Main) { conn = Conn.Connecting("Starting hc connect on the Mac…") }
                    try {
                        CoreClient.connect(link.input, link.output) { link.close() }
                    } catch (e: Exception) {
                        Thread.sleep(300)
                        val err = link.errors().trim()
                        link.close()
                        throw IllegalStateException(e.message + if (err.isNotEmpty()) "\n\n$err" else "")
                    }
                }
                mismatch(client.hello)?.let {
                    client.close()
                    throw IllegalStateException(it)
                }
                conn = Conn.Connecting("Loading spaces…")
                start(client)
            } catch (e: UnknownHostKey) {
                conn = Conn.Failed(e.message ?: "", e)
            } catch (e: Exception) {
                conn = Conn.Failed(describe(e))
                scheduleReconnect()
            }
        }
    }

    fun disconnect() {
        wantConnected = false
        reconnect?.cancel()
        core?.close()
        core = null
        conn = Conn.Idle
    }

    /** The app came to the front or went away: reconnect when back. */
    fun setForeground(front: Boolean) {
        foreground = front
        if (front && wantConnected && core == null && conn !is Conn.Connecting) {
            conn = Conn.Idle
            connect()
        }
    }

    private fun scheduleReconnect() {
        if (!wantConnected) return
        reconnect?.cancel()
        reconnect = main.launch {
            var wait = 2_000L
            while (wantConnected && core == null) {
                val c = conn
                if (c is Conn.Failed && c.hostKey != null) return@launch
                if (c is Conn.Failed) conn = c.copy(retrying = true)
                delay(wait)
                wait = minOf(wait * 2, 30_000)
                if (!foreground || conn is Conn.Connecting || core != null) continue
                conn = Conn.Idle
                connect()
                return@launch
            }
        }
    }

    private fun describe(e: Throwable): String {
        var c: Throwable? = e
        while (c != null) {
            when (c) {
                is UserAuthException -> return if (profile.auth == "password") {
                    "The Mac refused the password for ${profile.user}."
                } else {
                    "The Mac refused this phone's key. Add it to ~/.ssh/authorized_keys on the Mac (Settings → Copy public key), or use a password."
                }
                is UnknownHostException -> return "Can't find ${profile.host}. On another network, use the Mac's Tailscale name or IP."
                is ConnectException, is NoRouteToHostException -> return "Can't reach ${profile.host}:${profile.port}. Is the Mac awake, with Remote Login turned on (System Settings → General → Sharing)?"
                is SocketTimeoutException -> return "${profile.host} didn't answer in time."
            }
            c = c.cause
        }
        return e.message ?: e.toString()
    }

    private suspend fun start(client: CoreClient) {
        var lines: EventLines? = null
        withContext(Dispatchers.IO) {
            client.subscribe { channel, snapshot ->
                val l = EventLines(snapshot)
                lines = l
                object : StreamListener {
                    override fun onData(bytes: ByteArray) {
                        val pushed = synchronized(l) { l.push(bytes) }
                        client.credit(channel, bytes.size)
                        val live = if (pushed.terminals) synchronized(l) { l.live() } else null
                        main.launch {
                            if (core !== client) return@launch
                            live?.let { setTerminals(it) }
                            if (pushed.docs) {
                                if (pushed.docMachines.isEmpty()) refreshAll() else pushed.docMachines.forEach { refresh(it.ifEmpty { null }) }
                            }
                        }
                    }

                    override fun onClosed(reason: JsonElement) { client.close() }
                    override fun onLost() {}
                }
            }
        }
        core = client
        coreVersion = client.hello["version"].str ?: ""
        client.onLost {
            main.launch {
                if (core !== client) return@launch
                core = null
                conn = Conn.Failed("Lost the connection to the Mac.")
                scheduleReconnect()
            }
        }
        lines?.let { l -> setTerminals(synchronized(l) { l.live() }) }
        generation++
        conn = Conn.Connected
        main.launch {
            home = runCatching { exec(null, listOf("sh", "-c", "printf %s \"\$HOME\"")).trim() }.getOrNull()?.ifEmpty { null }
        }
        refreshAll()
    }

    private fun setTerminals(live: List<JsonObject>) {
        terminals = live.associateBy { it["address"].str ?: "" }
        shareSeen()
    }

    private suspend fun <T> io(f: (CoreClient) -> T): T {
        val c = core ?: throw IllegalStateException("Not connected to the Mac")
        return withContext(Dispatchers.IO) { f(c) }
    }

    private suspend fun refreshAll() {
        val list = runCatching { io { it.call("machine.list") } }.getOrNull()
        if (list != null) {
            machines = list["machines"].arr.map {
                MachineInfo(it["name"].str ?: "", it["host"].str ?: "", it["temporary"].bool == true)
            }.filter { !it.temporary && it.name.isNotEmpty() }
        }
        refresh(null)
        for (m in machines) refresh(m.name)
        for (k in docs.keys.toList()) if (k.isNotEmpty() && machines.none { it.name == k }) docs.remove(k)
        rebuild()
    }

    private suspend fun refresh(machine: String?) {
        try {
            val r = io { it.call("doc.get", obj("name" to DOC_NAME, "on" to machine)) }
            val version = r["version"].long ?: 0
            docs[key(machine)] = DocSlot(version, parseDocument(r["value"], machine))
            layoutErrors = layoutErrors - key(machine)
        } catch (e: Exception) {
            layoutErrors = layoutErrors + (key(machine) to (e.message ?: "unavailable"))
        }
        rebuild()
    }

    private fun rebuild() {
        val order = listOf("") + machines.map { it.name }.sorted()
        spaces = order.flatMap { docs[it]?.spaces.orEmpty() }
        if (spaces.none { it.id == activeSpace }) selectSpace(spaces.firstOrNull()?.id)
        for (s in spaces) {
            if (s.tabs.none { it.id == activeTab[s.id] }) s.tabs.firstOrNull()?.let { activeTab[s.id] = it.id }
            for (t in s.tabs) {
                if (t.root.find(focused[t.id] ?: "") == null) t.root.panes().firstOrNull()?.let { focused[t.id] = it["id"].str ?: "" }
                if (zoomed[t.id]?.let { t.root.find(it) } == null) zoomed.remove(t.id)
            }
        }
        saveView()
    }

    private fun saveView() {
        viewPrefs.edit().putString("space", activeSpace)
            .putString("tabs", obj(*activeTab.map { it.key to it.value }.toTypedArray()).toString()).apply()
    }

    /**
     * Changes one machine's document: [op] runs on the latest spaces and its
     * result is written with the version it was made from. A conflict (another
     * screen wrote first) reads again and reapplies [op]; newest write wins, as
     * on the desktop.
     */
    private suspend fun mutate(machine: String?, op: (List<Space>) -> List<Space>?): Boolean {
        val k = key(machine)
        return writeLocks.getOrPut(k) { Mutex() }.withLock {
            repeat(4) {
                if (docs[k] == null) refresh(machine)
                val slot = docs[k] ?: run { notice = "This machine's layout is unavailable. Try again when it reconnects."; return@withLock false }
                val next = op(slot.spaces) ?: return@withLock false
                docs[k] = slot.copy(spaces = next)
                rebuild()
                try {
                    val r = io { it.call("doc.set", obj("name" to DOC_NAME, "on" to machine, "version" to slot.version, "value" to captureDocument(next, machine))) }
                    docs[k] = DocSlot(r["version"].long ?: (slot.version + 1), next)
                    rebuild()
                    return@withLock true
                } catch (e: RpcException) {
                    if (e.kind != "conflict") {
                        notice = e.message
                        refresh(machine)
                        return@withLock false
                    }
                    refresh(machine)
                } catch (e: Exception) {
                    notice = e.message
                    refresh(machine)
                    return@withLock false
                }
            }
            notice = "Another screen kept changing this layout; try again."
            false
        }
    }

    // ---- what is shown ------------------------------------------------------

    fun selectSpace(id: String?) {
        activeSpace = id
        saveView()
    }

    fun showTab(spaceId: String, tabId: String) {
        activeTab[spaceId] = tabId
        selectSpace(spaceId)
    }

    fun focusPane(tabId: String, paneId: String) {
        focused[tabId] = paneId
    }

    fun clearMods() {
        ctrl = false
        alt = false
    }

    /** The keyboard went to a terminal: its pane is the focused one. */
    fun onTerminalFocused(address: String) {
        val p = placeOfTerminal(address) ?: return
        if (p.tab.id == activeTab[p.space.id]) focused[p.tab.id] = p.pane["id"].str ?: return
    }

    fun toggleZoom(tabId: String, paneId: String) {
        if (zoomed[tabId] == paneId) zoomed.remove(tabId) else zoomed[tabId] = paneId
        focused[tabId] = paneId
    }

    fun place(paneId: String): PanePlace? {
        for (s in spaces) for (t in s.tabs) t.root.find(paneId)?.let { return PanePlace(s, t, it) }
        return null
    }

    fun placeOfTerminal(address: String): PanePlace? {
        for (s in spaces) for (t in s.tabs) t.root.panes().firstOrNull { it.terminal == address }?.let { return PanePlace(s, t, it) }
        return null
    }

    /** Opens what an agent runs in: its pane, else its parent's tab with a peek. */
    fun showAgent(a: Agent) {
        val place = placeOfTerminal(a.address)
        if (place != null) {
            showTab(place.space.id, place.tab.id)
            focused[place.tab.id] = place.pane["id"].str ?: ""
            peek = null
        } else {
            a.parent?.let { placeOfTerminal(it) }?.let { showTab(it.space.id, it.tab.id) }
            peek = a.address
        }
    }

    fun cwdOf(address: String?): String? {
        val t = terminals[address ?: return null] ?: return null
        return t["foreground"]["cwd"].str ?: t["cwd"].str
    }

    /** What a space is called: its name, else where its first terminal is now. */
    fun spaceName(s: Space): String {
        if (s.name.isNotBlank()) return s.name
        val first = s.tabs.firstOrNull()?.root?.panes()?.firstOrNull { it.terminal != null }?.terminal
        val cwd = cwdOf(first) ?: s.folder
        return folderName(cwd, if (s.machine == null) home else null).ifEmpty { "space" }
    }

    fun tabName(s: Space, t: Tab): String = t.name.ifBlank { (s.tabs.indexOf(t) + 1).toString() }

    fun machineLabel(machine: String?): String = machine ?: profile.host.substringBefore('.').ifEmpty { "Mac" }

    fun agents(): List<Agent> = terminals.values.mapNotNull { t ->
        val status = t["status"].obj ?: return@mapNotNull null
        val address = t["address"].str ?: return@mapNotNull null
        var state = status["state"].str ?: return@mapNotNull null
        val turn = t["turn"].long ?: 0
        val seen = maxOf(t["labels"]["herdr.seen"].str?.toLongOrNull() ?: 0, writtenSeen[address] ?: 0)
        if (state == "done" && seen >= turn && turn > 0) state = "idle"
        val place = placeOfTerminal(address)
        val labels = t["labels"]
        val machine = machineOf(address)
        Agent(
            address = address,
            state = state,
            app = status["app"].str?.ifEmpty { null } ?: t["foreground"]["name"].str ?: "agent",
            title = status["title"].str?.ifEmpty { null } ?: status["msg"].str?.ifEmpty { null } ?: t["title"].str ?: "",
            parent = t["parent"].str?.ifEmpty { null },
            spaceId = place?.space?.id ?: labels["herdr.space"].str?.let { qualify(it, machine) },
            tabId = place?.tab?.id ?: labels["herdr.tab"].str?.let { qualify(it, machine) },
            paneId = place?.pane?.get("id").str,
        )
    }.sortedBy { URGENCY.indexOf(it.state).let { i -> if (i < 0) 99 else i } }

    fun spaceState(s: Space, agents: List<Agent>): String? =
        agents.filter { it.spaceId == s.id }.map { it.state }.minByOrNull { URGENCY.indexOf(it).let { i -> if (i < 0) 99 else i } }

    /** Terminals on screen right now: their agents count as looked at. */
    var visible: Set<String> = emptySet()
        set(v) {
            field = v
            shareSeen()
        }

    /** Like the desktop: an agent on screen is seen once per turn, shared as `herdr.seen`. */
    private fun shareSeen() {
        if (!foreground) return
        val c = core ?: return
        for (addr in visible) {
            val t = terminals[addr] ?: continue
            if (t["status"].obj == null) continue
            val turn = t["turn"].long ?: continue
            val seen = maxOf(t["labels"]["herdr.seen"].str?.toLongOrNull() ?: 0, writtenSeen[addr] ?: 0)
            if (seen >= turn) continue
            writtenSeen[addr] = turn
            main.launch(Dispatchers.IO) {
                runCatching { c.call("terminal.label", obj("terminal" to addr, "labels" to obj("herdr.seen" to turn.toString()))) }
            }
        }
    }

    // ---- changes ------------------------------------------------------------

    private suspend fun spawn(machine: String?, spaceId: String, tabId: String, cwd: String?, argv: List<String>, size: Pair<Int, Int>?): String {
        val labels = mapOf("herdr.space" to local(spaceId, machine), "herdr.tab" to local(tabId, machine), "herdr.seen" to "0")
        val display = Theme.display()
        return try {
            io { it.run(argv, cwd, labels, machine, size?.first, size?.second, display) }
        } catch (e: RpcException) {
            // Starting in an inherited folder failed (it was removed): that machine's home.
            if (cwd.isNullOrEmpty()) throw e
            io { it.run(argv, null, labels, machine, size?.first, size?.second, display) }
        }
    }

    private fun kill(addresses: List<String>) {
        val c = core ?: return
        main.launch(Dispatchers.IO) { for (a in addresses) runCatching { c.call("terminal.kill", obj("terminal" to a)) } }
    }

    private fun attempt(what: String, f: suspend () -> Unit) {
        main.launch {
            try {
                f()
            } catch (e: Exception) {
                notice = "$what: ${e.message}"
            }
        }
    }

    /** A space with its first tab and terminal (or [argv], an agent), shown at once. */
    fun newSpace(machine: String?, folder: String?, name: String, argv: List<String>?) = attempt("Cannot make a space") {
        val sp = qualify(freshId("sp"), machine)
        val tb = qualify(freshId("tb"), machine)
        val p = qualify(freshId("p"), machine)
        val cwd = folder?.trim()?.ifEmpty { null }
        val addr = spawn(machine, sp, tb, cwd, argv.orEmpty(), null)
        val space = Space(sp, name.trim(), cwd ?: (if (machine == null) home ?: "" else ""), machine, listOf(newTab(tb, "", p, addr)))
        if (!mutate(machine) { it + space }) {
            kill(listOf(addr))
            return@attempt
        }
        activeTab[sp] = tb
        focused[tb] = p
        selectSpace(sp)
    }

    /** A tab in [spaceId], after the shown one, starting where its focused pane is. */
    fun newTab(spaceId: String, argv: List<String>?, name: String = "") = attempt("Cannot open a tab") {
        val space = spaces.firstOrNull { it.id == spaceId } ?: return@attempt
        val m = space.machine
        val shown = space.tabs.firstOrNull { it.id == activeTab[spaceId] }
        val focusedAddr = shown?.let { t -> focused[t.id]?.let { t.root.find(it) }?.terminal }
        val cwd = cwdOf(focusedAddr) ?: space.folder
        val tb = qualify(freshId("tb"), m)
        val p = qualify(freshId("p"), m)
        val addr = spawn(m, spaceId, tb, cwd, argv.orEmpty(), null)
        val ok = mutate(m) { list ->
            list.map { s ->
                if (s.id != spaceId) return@map s
                val at = s.tabs.indexOfFirst { it.id == activeTab[spaceId] }.let { if (it < 0) s.tabs.size else it + 1 }
                s.copy(tabs = s.tabs.toMutableList().apply { add(at, newTab(tb, name.trim(), p, addr)) })
            }
        }
        if (!ok) {
            kill(listOf(addr))
            return@attempt
        }
        focused[tb] = p
        showTab(spaceId, tb)
    }

    /** Splits a pane with a new terminal beside it (Row: right, Column: below), in the same folder. */
    fun split(paneId: String, axis: Axis, argv: List<String>? = null) = attempt("Cannot split") {
        val place = place(paneId) ?: return@attempt
        val targetAddr = place.pane.terminal
        val m = place.space.machine
        val on = targetAddr?.let { machineOf(it) } ?: m
        val cwd = cwdOf(targetAddr) ?: place.space.folder
        val newPane = qualify(freshId("p"), m)
        val container = qualify(freshId("s"), m)
        val addr = spawn(on, place.space.id, place.tab.id, cwd, argv.orEmpty(), null)
        val ok = mutate(m) { list ->
            list.map { s ->
                s.copy(tabs = s.tabs.map { t ->
                    if (t.id != place.tab.id) t else t.copy(root = t.root.split(paneId, axis, terminalPane(newPane, addr), container) ?: return@mutate null)
                })
            }
        }
        if (!ok) {
            kill(listOf(addr))
            return@attempt
        }
        zoomed.remove(place.tab.id)
        focused[place.tab.id] = newPane
    }

    /** Closes a pane and ends its terminal; the last pane closes its tab, the last tab its space. */
    fun closePane(paneId: String) = attempt("Cannot close") {
        val place = place(paneId) ?: return@attempt
        val ok = mutate(place.space.machine) { list ->
            list.mapNotNull { s ->
                val tabs = s.tabs.mapNotNull { t ->
                    if (t.id != place.tab.id) t else t.copy(root = t.root.remove(paneId)).takeIf { it.root.panes().isNotEmpty() }
                }
                if (s.id == place.space.id && tabs.isEmpty()) null else s.copy(tabs = tabs)
            }
        }
        if (ok) place.pane.terminal?.let { kill(listOf(it)) }
    }

    fun closeTab(tabId: String) = attempt("Cannot close the tab") {
        val s = spaces.firstOrNull { sp -> sp.tabs.any { it.id == tabId } } ?: return@attempt
        val tab = s.tabs.first { it.id == tabId }
        val ok = mutate(s.machine) { list ->
            list.mapNotNull { sp ->
                if (sp.id != s.id) sp else sp.copy(tabs = sp.tabs.filter { it.id != tabId }).takeIf { it.tabs.isNotEmpty() }
            }
        }
        if (ok) kill(tab.root.panes().mapNotNull { it.terminal })
    }

    fun closeSpace(spaceId: String) = attempt("Cannot close the space") {
        val s = spaces.firstOrNull { it.id == spaceId } ?: return@attempt
        if (mutate(s.machine) { list -> list.filter { it.id != spaceId } }) {
            kill(s.tabs.flatMap { t -> t.root.panes().mapNotNull { it.terminal } })
        }
    }

    fun renameSpace(spaceId: String, name: String) = attempt("Cannot rename") {
        val s = spaces.firstOrNull { it.id == spaceId } ?: return@attempt
        mutate(s.machine) { list -> list.map { if (it.id == spaceId) it.copy(name = name.trim()) else it } }
    }

    fun renameTab(tabId: String, name: String) = attempt("Cannot rename") {
        val s = spaces.firstOrNull { sp -> sp.tabs.any { it.id == tabId } } ?: return@attempt
        mutate(s.machine) { list -> list.map { sp -> sp.copy(tabs = sp.tabs.map { if (it.id == tabId) it.copy(name = name.trim()) else it }) } }
    }

    /** A divider dragged: the row's or column's new shares. */
    fun resize(tabId: String, containerId: String, weights: List<Double>) = attempt("Cannot resize") {
        val s = spaces.firstOrNull { sp -> sp.tabs.any { it.id == tabId } } ?: return@attempt
        mutate(s.machine) { list -> list.map { sp -> sp.copy(tabs = sp.tabs.map { if (it.id == tabId) it.copy(root = it.root.setWeights(containerId, weights)) else it }) } }
    }

    fun killTerminal(address: String) = kill(listOf(address))

    /** Gives a peeked worker its own tab in its space, kept by the core like any pane. */
    fun keepAsTab(address: String) = attempt("Cannot keep it") {
        val agent = agents().firstOrNull { it.address == address }
        val space = spaces.firstOrNull { it.id == agent?.spaceId } ?: spaces.firstOrNull { it.id == activeSpace } ?: return@attempt
        val m = space.machine
        val tb = qualify(freshId("tb"), m)
        val p = qualify(freshId("p"), m)
        val ok = mutate(m) { list -> list.map { if (it.id == space.id) it.copy(tabs = it.tabs + newTab(tb, "", p, address)) else it } }
        if (!ok) return@attempt
        runCatching {
            io { c ->
                c.call("terminal.label", obj("terminal" to address, "labels" to obj("herdr.space" to local(space.id, m), "herdr.tab" to local(tb, m))))
                c.call("terminal.keep", obj("terminal" to address, "keep" to true))
            }
        }
        peek = null
        focused[tb] = p
        showTab(space.id, tb)
    }

    /** The terminal the key bar types into: the shown tab's focused pane. */
    fun focusedTerminal(): String? {
        val s = spaces.firstOrNull { it.id == activeSpace } ?: return null
        val t = s.tabs.firstOrNull { it.id == activeTab[s.id] } ?: return null
        val pane = (zoomed[t.id] ?: focused[t.id])?.let { t.root.find(it) } ?: t.root.panes().firstOrNull()
        return pane?.terminal
    }

    /** Text copied from a terminal, for the clipboard. */
    var copied by mutableStateOf<String?>(null)

    fun saveFont(px: Int) = viewPrefs.edit().putInt("font", px).apply()

    // ---- files and git, through the core ------------------------------------

    /** Runs a program on a machine (no shell) and returns its stdout. */
    suspend fun exec(machine: String?, argv: List<String>, cwd: String? = null, check: Boolean = true): String {
        val r = io { it.call("machine.exec", obj("argv" to argv, "cwd" to cwd, "on" to machine), timeoutMs = 30_000) }
        val out = String(Base64.decode(r["stdout"].str ?: "", Base64.DEFAULT))
        val code = r["code"].long
        if (check && code != null && code != 0L) {
            val err = String(Base64.decode(r["stderr"].str ?: "", Base64.DEFAULT)).trim()
            throw IllegalStateException(err.ifEmpty { "${argv.first()} exited with $code" })
        }
        return out
    }

    suspend fun readFile(machine: String?, path: String): String {
        val r = io { it.call("file.read", obj("path" to path, "on" to machine, "limit" to 20_000), timeoutMs = 30_000) }
        return r["text"].str ?: ""
    }

    /** Agents found on the Mac's PATH (login shell), for the + menu. */
    suspend fun installedAgents(machine: String?): List<String> {
        val candidates = Agents.known.map { it.command }
        val out = runCatching { exec(machine, Agents.check(candidates), check = false) }.getOrDefault("")
        return out.lines().mapNotNull { it.removePrefix("herdr-has:").takeIf { l -> l != it && l.isNotBlank() } }
    }
}
