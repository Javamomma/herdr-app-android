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
import herdr.core.Alerting
import herdr.core.Axis
import herdr.core.Labels
import herdr.core.Mark
import herdr.core.Pins
import herdr.core.Seen
import herdr.core.SeenTerm
import herdr.core.SeenTracker
import herdr.core.CoreClient
import herdr.core.CoreLostException
import herdr.core.Queue
import herdr.core.QueueFailure
import herdr.core.QueueResult
import herdr.core.QueueModel
import herdr.core.QueueState
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
import herdr.core.Prompts
import herdr.core.Question
import herdr.core.obj
import herdr.core.panes
import herdr.core.parseDocument
import herdr.core.qualify
import herdr.core.remove
import herdr.core.setWeights
import herdr.core.split
import herdr.core.str
import herdr.core.tabLabel
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
import kotlinx.coroutines.withTimeoutOrNull
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

/** A saved command: typed into the focused terminal, or started in a new tab. */
data class Snippet(val name: String, val command: String, val newTab: Boolean)

/** What became of an answer given to an agent's question. */
sealed interface Answered {
    data object Sent : Answered
    /** The agent shows another question now: nothing was sent. */
    data object Changed : Answered
    /** The agent isn't waiting any more (answered elsewhere, or gone). */
    data object Moot : Answered
    /** No connection to the Mac. */
    data object Offline : Answered
    data class Failed(val why: String) : Answered
}

/** A close that needs a yes first: what it says, and what to do on yes. */
class Closing(val title: String, val text: String, val confirm: String, val run: () -> Unit)

/** What an agent's change of state says to the person. */
enum class Alert { BLOCKED, DONE }

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
    /** Core's turn counter (rises each time the agent stops working). */
    val turn: Long = 0,
    /** `herdr.pinned` is set: first in every list. */
    val pinned: Boolean = false,
    /** What the menu offers: Mark read when unread, else Mark unread (not at turn 0). */
    val mark: Mark = Mark.NONE,
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
    private val connState = mutableStateOf<Conn>(Conn.Idle)
    var conn: Conn
        get() = connState.value
        private set(v) {
            connState.value = v
            onConn?.invoke(v)
        }
    /** Told when the connection's state changes (the background service's status line). */
    var onConn: ((Conn) -> Unit)? = null
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

    private val settings = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
    /** Keep the connection in the background and notify when an agent needs you. */
    var alerts by mutableStateOf(settings.getBoolean("alerts", true))
        private set
    /** Ask for a fingerprint, face or the phone's PIN to open the app. */
    var lockEnabled by mutableStateOf(settings.getBoolean("lock", false))
        private set
    /** Minutes in the background before the lock comes back. */
    var lockAfterMinutes by mutableIntStateOf(settings.getInt("lockAfter", 5))
        private set
    /** On the cover screen, open to the agents list. */
    var agentsWhenFolded by mutableStateOf(settings.getBoolean("agentsFolded", true))
        private set
    /** Open terminals watching (the Mac keeps its size) until the phone types in them. */
    var watchFirst by mutableStateOf(settings.getBoolean("watchFirst", true))
        private set
    var snippets by mutableStateOf(loadSnippets())
        private set
    /** Running against the built-in pretend core (no Mac). */
    var demo by mutableStateOf(false)
        private set
    /** Told about agents that start needing the person (notifications). */
    var onAlert: ((Alert, Agent, String) -> Unit)? = null
    /** Told when an agent no longer needs attention (clear its notification). */
    var onSettled: ((String) -> Unit)? = null
    /** Told about every change to the agents (the widget, the background service). */
    var onAgents: ((List<Agent>) -> Unit)? = null
    /** Told when the app lock is turned on or off (what notifications and the widget may show). */
    var onLock: ((Boolean) -> Unit)? = null
    /** A close waiting for a yes, because it would end something that is running. */
    var closing by mutableStateOf<Closing?>(null)
    /** Asked to show a panel (files, changes) or the agents list, e.g. from a notification or the demo. */
    var requested by mutableStateOf<String?>(null)
    /** Each agent's screen text as last read (previews, and the answers it offers). */
    val screens = mutableStateMapOf<String, String>()
    /** Quick phrases for the Type box. */
    var phrases by mutableStateOf(loadPhrases())
        private set
    /** A newer release: its version and page. */
    var update by mutableStateOf<Pair<String, String>?>(null)
        private set
    /** A file to show in the Files panel (a tapped path in a terminal). */
    var fileToOpen by mutableStateOf<String?>(null)
    /** [fileToOpen] is an exact path (from the queue): not cut at a colon as a file:line from a terminal is. */
    var fileExact = false
    /** Showing what was cached from the last connection, not live state. */
    var stale by mutableStateOf(false)
        private set
    private val cache = context.getSharedPreferences("cache", Context.MODE_PRIVATE)
    private var cacheJob: Job? = null
    private val lastStates = HashMap<String, String>()
    private var statesKnown = false

    private val docs = HashMap<String, DocSlot>()
    private val writeLocks = HashMap<String, Mutex>()
    private val seenTracker = SeenTracker()
    /** Bumped when [seenTracker] changes what agents() shows (it is not Compose state). */
    private var seenRev by mutableIntStateOf(0)
    private val lastTurns = HashMap<String, Long>()
    private var reconnect: Job? = null
    /** Rises with every connect and disconnect: an attempt that is no longer the latest gives up. */
    private var connecting = 0
    private var foreground = true
    var wantConnected = false
        private set

    init {
        runCatching {
            val saved = json.parseToJsonElement(viewPrefs.getString("tabs", "{}")!!).jsonObject
            for ((k, v) in saved) v.str?.let { activeTab[k] = it }
        }
        loadCache()
    }

    /** The last connection's spaces and agents, shown at once on launch, marked stale. */
    private fun loadCache() {
        if (!profile.ready) return
        runCatching {
            val saved = json.parseToJsonElement(cache.getString("state", null) ?: return).jsonObject
            machines = saved["machines"].arr.mapNotNull { m -> m.str?.let { MachineInfo(it, "", false) } }
            for ((k, v) in saved["docs"].obj ?: JsonObject(emptyMap())) {
                docs[k] = DocSlot(v["version"].long ?: 0, parseDocument(v["value"], k.ifEmpty { null }))
            }
            terminals = saved["terminals"].arr.mapNotNull { it.obj }.associateBy { it["address"].str ?: "" }
            home = saved["home"].str
            stale = docs.isNotEmpty()
            rebuild()
        }
    }

    /** Saved a moment after things settle, never for the demo. */
    private fun saveCache() {
        if (demo || core == null) return
        cacheJob?.cancel()
        cacheJob = main.launch {
            delay(2_000)
            val state = obj(
                "machines" to machines.map { it.name },
                "docs" to docs.mapValues { (k, v) -> obj("version" to v.version, "value" to captureDocument(v.spaces, k.ifEmpty { null })) },
                "terminals" to terminals.values.toList(),
                "home" to home,
            )
            cache.edit().putString("state", state.toString()).apply()
        }
    }

    private fun key(machine: String?) = machine ?: ""

    fun updateAlerts(on: Boolean) { alerts = on; settings.edit().putBoolean("alerts", on).apply(); onAgents?.invoke(agents()) }
    fun updateLock(on: Boolean) { lockEnabled = on; settings.edit().putBoolean("lock", on).apply(); onLock?.invoke(on) }
    fun updateLockAfter(minutes: Int) { lockAfterMinutes = minutes; settings.edit().putInt("lockAfter", minutes).apply() }
    fun updateAgentsWhenFolded(on: Boolean) { agentsWhenFolded = on; settings.edit().putBoolean("agentsFolded", on).apply() }
    fun updateWatchFirst(on: Boolean) { watchFirst = on; settings.edit().putBoolean("watchFirst", on).apply() }

    // ---- the queue: read-only, from a command run on the Mac ------------------

    /** The shell command whose JSON output is the queue; empty turns the Queue off. */
    var queueCommand by mutableStateOf(settings.getString("queueCommand", null) ?: Queue.DEFAULT_COMMAND)
        private set
    /** Expanded or folded to one line: this phone's choice. */
    var queueOpen by mutableStateOf(viewPrefs.getBoolean("queueOpen", false))
        private set
    /** Only in memory: the items are private and are never saved on the phone. Cleared on disconnect, demo and a new host. */
    var queue by mutableStateOf(QueueState())
        private set
    private val queueModel = QueueModel()
    /** A file from the queue is shown on the core's own machine, whatever terminal is focused. */
    var filesLocal by mutableStateOf(false)

    /** Save in Settings: the only way the command changes (and so the only way a new one can run, once, when the list is next on screen). */
    fun updateQueueCommand(command: String) {
        if (command == queueCommand) return
        queueCommand = command
        settings.edit().putString("queueCommand", command).apply()
        clearQueue()
    }
    fun updateQueueOpen(open: Boolean) { queueOpen = open; viewPrefs.edit().putBoolean("queueOpen", open).apply() }

    /** Nothing kept, nothing in flight lands: disconnect, demo, another host, a new command. */
    private fun clearQueue() {
        queueModel.clear()
        queue = queueModel.state
        filesLocal = false
    }

    /** Whether the section is due a refresh (never while one runs): [every] ms after the last try. */
    fun queueDue(every: Long) = queueCommand.isNotBlank() && queueModel.due(every, System.currentTimeMillis(), conn is Conn.Connected)

    /** The Retry / Refresh button: from the person, so it runs even when no refresh is due. */
    fun requestQueueRefresh() { main.launch { refreshQueue() } }

    /** Opens a queue item's file in the file viewer, on the core's machine, by its exact path. */
    fun openQueueFile(path: String) {
        if (!Queue.validPath(path)) return
        filesLocal = true
        fileExact = true
        requested = "queuefile:$path"
    }

    /**
     * Runs the queue command through a shell on the core's own machine (never a remote one) and keeps the last good
     * list when this try fails. The command is the saved setting; nothing from an item is ever part of it. The answer
     * is dropped if the core it was asked from is no longer the connected one.
     */
    suspend fun refreshQueue() {
        val command = queueCommand.trim()
        if (command.isEmpty()) return
        val asked = core
        val ticket = queueModel.begin(asked, System.currentTimeMillis()) ?: return
        queue = queueModel.state
        val result = try {
            withContext(Dispatchers.IO) {
                val c = asked ?: throw IllegalStateException("Not connected to the Mac")
                val r = c.call("machine.exec", obj("argv" to listOf("sh", "-c", command)), timeoutMs = Queue.TIMEOUT_MS)
                val out = r["stdout"].str ?: ""
                // base64 is 4/3 of the bytes: past the cap, refuse before decoding.
                if (out.length > Queue.MAX_OUTPUT_CHARS * 2) QueueResult.Failed(QueueFailure.BadOutput("too much output"))
                else Queue.outcome(
                    code = r["code"].long,
                    stdout = String(Base64.decode(out, Base64.DEFAULT)),
                    stderr = String(Base64.decode(r["stderr"].str ?: "", Base64.DEFAULT)),
                    timedOut = r["timed_out"].bool == true,
                    truncated = r["truncated"].bool == true,
                )
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            queueModel.cancelled(ticket)
            queue = queueModel.state
            throw e
        } catch (e: Exception) {
            QueueResult.Failed(Queue.failureOf(e, asked != null && core === asked))
        }
        queueModel.finish(ticket, core, result, System.currentTimeMillis())
        queue = queueModel.state
    }

    private fun loadSnippets(): List<Snippet> {
        val saved = settings.getString("snippets", null) ?: return listOf(
            Snippet("git status", "git status -sb", false),
            Snippet("git log", "git log --oneline -15", false),
            Snippet("Claude Code here", "claude", true),
            Snippet("TUIOS (new tab)", "tuios attach", true),
            Snippet("Run tests", "npm test", false),
        )
        return runCatching {
            json.parseToJsonElement(saved).arr.mapNotNull { e ->
                Snippet(e["name"].str ?: return@mapNotNull null, e["command"].str ?: return@mapNotNull null, e["newTab"].bool == true)
            }
        }.getOrDefault(emptyList())
    }

    fun saveSnippets(list: List<Snippet>) {
        snippets = list
        settings.edit().putString("snippets", kotlinx.serialization.json.JsonArray(list.map { obj("name" to it.name, "command" to it.command, "newTab" to it.newTab) }).toString()).apply()
    }

    fun saveProfile(p: Profile, password: String?) {
        p.save(context)
        if (password != null) secrets.put("password", password.ifEmpty { null })
        if (p.host != profile.host || p.user != profile.user || p.port != profile.port) clearQueue()
        profile = p
    }

    // ---- connection ---------------------------------------------------------

    fun connect(acceptFingerprint: String? = null) {
        if (conn is Conn.Connecting || conn is Conn.Connected) return
        if (!profile.ready) return
        wantConnected = true
        if (!settings.getBoolean("connected", false)) settings.edit().putBoolean("connected", true).apply()
        reconnect?.cancel()
        conn = Conn.Connecting("Connecting to ${profile.host}…")
        val p = profile
        val mine = ++connecting
        main.launch {
            try {
                val client = withContext(Dispatchers.IO) {
                    val link = SshLink.open(p, known, deviceKey, secrets.get("password"), acceptFingerprint)
                    withContext(Dispatchers.Main) { if (mine == connecting) conn = Conn.Connecting("Starting hc connect on the Mac…") }
                    try {
                        CoreClient.connect(link.input, link.output) { link.close() }
                    } catch (e: Exception) {
                        Thread.sleep(300)
                        val err = link.errors().trim()
                        link.close()
                        throw IllegalStateException(e.message + if (err.isNotEmpty()) "\n\n$err" else "")
                    }
                }
                // Disconnected (or the demo started) while this was on its way: drop it.
                if (mine != connecting) {
                    client.close()
                    return@launch
                }
                mismatch(client.hello)?.let {
                    client.close()
                    throw IllegalStateException(it)
                }
                conn = Conn.Connecting("Loading spaces…")
                start(client) { mine == connecting }
            } catch (e: UnknownHostKey) {
                if (mine == connecting) conn = Conn.Failed(e.message ?: "", e)
            } catch (e: Exception) {
                if (mine != connecting) return@launch
                conn = Conn.Failed(describe(e))
                scheduleReconnect()
            }
        }
    }

    /** Connects to the built-in pretend core: sample spaces and agents, nothing real. */
    fun connectDemo() {
        if (demo && (conn is Conn.Connecting || conn is Conn.Connected)) return
        disconnect()
        clearQueue()
        demo = true
        wantConnected = false
        conn = Conn.Connecting("Starting the demo…")
        val mine = ++connecting
        main.launch {
            try {
                val d = herdr.core.DemoCore()
                val client = withContext(Dispatchers.IO) { CoreClient.connect(d.clientInput, d.clientOutput) { d.close() } }
                if (mine != connecting) {
                    client.close()
                    return@launch
                }
                start(client) { mine == connecting }
            } catch (e: Exception) {
                if (mine == connecting) conn = Conn.Failed("The demo didn't start: ${e.message}")
            }
        }
    }

    fun disconnect() {
        connecting++
        clearQueue()
        demo = false
        wantConnected = false
        settings.edit().putBoolean("connected", false).apply()
        reconnect?.cancel()
        core?.close()
        core = null
        conn = Conn.Idle
    }

    /**
     * Android ended the app in the background and started it again for the
     * watching service: connect again if it was connected before, so the
     * service is really watching.
     */
    fun resume() {
        if (core != null || demo || conn is Conn.Connecting) return
        if (alerts && profile.ready && settings.getBoolean("connected", false)) connect()
    }

    /**
     * The phone's network changed (Wi-Fi to cellular, a hotel network). A
     * dead connection would only be noticed by its 15-second keepalive: ask
     * the core something at once, and reconnect now instead of waiting.
     */
    fun networkChanged() {
        if (demo || !wantConnected) return
        val c = core
        if (c == null) {
            if (conn !is Conn.Connecting) {
                reconnect?.cancel()
                conn = Conn.Idle
                connect()
            }
            return
        }
        main.launch(Dispatchers.IO) {
            runCatching { c.call("machine.list", timeoutMs = 5_000) }.onFailure { c.close() }
        }
    }

    /** The app came to the front or went away: reconnect when back. */
    fun setForeground(front: Boolean) {
        foreground = front
        if (front) shareSeen() else seenTracker.stopLooking()
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
                if ((!foreground && !alerts) || conn is Conn.Connecting || core != null) continue
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

    private suspend fun start(client: CoreClient, current: () -> Boolean) {
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
        if (!current()) {
            client.close()
            return
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
        if (stale) {
            // The cache said what was there; now the core says what is.
            docs.clear()
            screens.clear()
            stale = false
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
        seenTracker.retain(terminals.keys)
        shareSeen()
        noticeAgents()
        saveCache()
    }

    /** Reads an agent's screen (text only, no attach). */
    suspend fun readScreen(address: String, timeoutMs: Long = 5_000): String? =
        runCatching { io { it.call("terminal.read", obj("terminal" to address), timeoutMs = timeoutMs) }["text"].str }
            .getOrNull()?.also { screens[address] = it }

    /** What an agent asks right now, read from its screen. */
    suspend fun question(address: String): Question? = readScreen(address, 3_000)?.let { Prompts.question(it) }

    /**
     * Answers the question the person was shown, known by its [mark]: allow
     * (the highlighted option), deny (Esc) or a typed [reply]. The screen is
     * read again first, and nothing is sent unless it still shows that same
     * question. Connects first if the app isn't connected.
     */
    suspend fun answerShown(address: String, mark: String, deny: Boolean = false, reply: String? = null): Answered {
        if (core == null) {
            if (demo || !profile.ready) return Answered.Offline
            connect()
            withTimeoutOrNull(5_000) { while (core == null) delay(100) } ?: return Answered.Offline
        }
        if (agents().firstOrNull { it.address == address }?.state != "blocked") return Answered.Moot
        val q = question(address) ?: return Answered.Failed("its screen couldn't be read")
        if (q.mark != mark) return Answered.Changed
        return try {
            if (reply != null) {
                io { it.call("terminal.send", obj("terminal" to address, "text" to reply, "force" to true), timeoutMs = 3_000) }
            } else {
                val keys = if (deny) listOf("esc") else Prompts.allowKeys(q) ?: return Answered.Changed
                io { it.call("terminal.keys", obj("terminal" to address, "keys" to keys), timeoutMs = 3_000) }
            }
            Answered.Sent
        } catch (e: Exception) {
            Answered.Failed(e.message ?: "the Mac didn't take it")
        }
    }

    /** One-tap allow in the agents list, for the question shown there. */
    fun allowShown(address: String, mark: String) = attempt("Cannot answer") {
        when (val r = answerShown(address, mark)) {
            Answered.Sent -> { delay(500); readScreen(address) }
            Answered.Changed -> { readScreen(address); notice = "The question changed. Look at it again." }
            Answered.Moot -> notice = "It isn't waiting any more."
            Answered.Offline -> notice = "Not connected to the Mac."
            is Answered.Failed -> notice = "Not sent: ${r.why}"
        }
    }

    /** Refreshes the screens of these agents, for their previews and answers. */
    fun refreshScreens(addresses: Collection<String>) {
        if (core == null) return
        main.launch { for (a in addresses) readScreen(a) }
    }

    /**
     * Answers an agent's question with one of its own options, for the
     * question the person was shown ([mark]). Reads the screen again first:
     * if it shows another question, nothing is sent. Risky options and
     * dangerous questions need [held] (a press-and-hold).
     */
    fun choose(address: String, label: String, mark: String, held: Boolean) = attempt("Cannot answer") {
        if (agents().firstOrNull { it.address == address }?.state != "blocked") {
            notice = "It isn't waiting any more."
            return@attempt
        }
        val now = readScreen(address) ?: return@attempt
        when (val pick = Prompts.pick(now, mark, label, held)) {
            is Prompts.Pick.Send -> {
                io { it.call("terminal.keys", obj("terminal" to address, "keys" to pick.keys)) }
                delay(500)
                readScreen(address)
            }
            Prompts.Pick.Changed -> notice = "The question changed. Look at it again."
            Prompts.Pick.NeedsHold -> notice = "Press and hold to choose \"$label\""
        }
    }

    private fun loadPhrases(): List<String> =
        settings.getString("phrases", null)?.let { runCatching { json.parseToJsonElement(it).arr.mapNotNull { e -> e.str } }.getOrNull() }
            ?: listOf("Continue", "Yes, go ahead", "Run the tests", "Explain that simply", "Commit this with a good message")

    fun savePhrases(list: List<String>) {
        phrases = list
        settings.edit().putString("phrases", kotlinx.serialization.json.JsonArray(list.map { kotlinx.serialization.json.JsonPrimitive(it) }).toString()).apply()
    }

    /** Looks for a newer release on GitHub, at most twice a day. */
    fun checkForUpdate(currentVersion: String) {
        val last = settings.getLong("updateChecked", 0)
        if (System.currentTimeMillis() - last < 12 * 3600_000L && update == null) {
            settings.getString("updateFound", null)?.split('|')?.takeIf { it.size == 2 }?.let { (v, url) ->
                if (Updates.newer(v, currentVersion)) update = v to url
            }
            return
        }
        main.launch {
            val found = Updates.latest() ?: return@launch
            settings.edit().putLong("updateChecked", System.currentTimeMillis()).putString("updateFound", "${found.first}|${found.second}").apply()
            if (Updates.newer(found.first, currentVersion)) update = found
        }
    }

    /**
     * Alerts like the desktop's: an agent that starts blocking, or finishes
     * a turn, while the person isn't looking at it. Changes already there
     * when the app first connects aren't news.
     */
    private fun noticeAgents() {
        val list = agents()
        for (a in list) {
            val prev = lastStates[a.address]
            val looking = foreground && a.address in visible
            if (statesKnown && prev != a.state && !looking) {
                val where = spaces.firstOrNull { it.id == a.spaceId }?.let { spaceName(it) } ?: machineLabel(machineOf(a.address))
                when {
                    a.state == "blocked" -> onAlert?.invoke(Alert.BLOCKED, a, where)
                    Alerting.doneIsNews(prev, lastTurns[a.address], a.state, a.turn) -> onAlert?.invoke(Alert.DONE, a, where)
                }
            }
            if (a.state != "blocked" && a.state != "done") onSettled?.invoke(a.address)
            if (looking) onSettled?.invoke(a.address)
        }
        for (gone in lastStates.keys - list.map { it.address }.toSet()) onSettled?.invoke(gone)
        lastStates.clear()
        lastTurns.clear()
        list.forEach { lastStates[it.address] = it.state; lastTurns[it.address] = it.turn }
        statesKnown = true
        onAgents?.invoke(list)
    }

    /** Runs a saved command: typed into [address], or in a new tab of the shown space. */
    fun runSnippet(s: Snippet, address: String?) = attempt("Cannot run ${s.name}") {
        if (s.newTab || address == null) {
            val space = activeSpace ?: return@attempt
            newTab(space, Agents.launch(listOf("sh", "-c", s.command)), s.name)
        } else {
            io { it.call("terminal.send", obj("terminal" to address, "text" to s.command)) }
        }
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
        saveCache()
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

    /** A tab's name, else the status title of the first agent in it (pane order), else its number. */
    fun tabName(s: Space, t: Tab): String {
        if (t.name.isNotBlank()) return t.name
        val titles = t.root.panes().mapNotNull { it.terminal }.map { terminals[it]["status"]["title"].str }
        return tabLabel(t.name, titles, s.tabs.indexOf(t) + 1)
    }

    fun machineLabel(machine: String?): String = machine ?: if (demo) "mac-mini (demo)" else profile.host.substringBefore('.').ifEmpty { "Mac" }

    /** Pinned first, then the ones that need you, in the order the core lists them. */
    fun agents(): List<Agent> {
        seenRev // Compose: re-read after a mark or a seen write
        val list = terminals.values.mapNotNull { t ->
            val status = t["status"].obj ?: return@mapNotNull null
            val address = t["address"].str ?: return@mapNotNull null
            val reported = status["state"].str ?: return@mapNotNull null
            val turn = t["turn"].long ?: 0
            val seen = seenTracker.seen(address, Seen.parse(t["labels"][Labels.SEEN].str))
            val place = placeOfTerminal(address)
            val labels = t["labels"]
            val machine = machineOf(address)
            Agent(
                address = address,
                state = Seen.display(reported, turn, seen),
                app = status["app"].str?.ifEmpty { null } ?: t["foreground"]["name"].str ?: "agent",
                title = status["title"].str?.ifEmpty { null } ?: status["msg"].str?.ifEmpty { null } ?: t["title"].str ?: "",
                parent = t["parent"].str?.ifEmpty { null },
                spaceId = place?.space?.id ?: labels["herdr.space"].str?.let { qualify(it, machine) },
                tabId = place?.tab?.id ?: labels["herdr.tab"].str?.let { qualify(it, machine) },
                paneId = place?.pane?.get("id").str,
                turn = turn,
                pinned = Pins.isPinned(labels[Labels.PINNED].str),
                mark = Seen.mark(reported, turn, seen),
            )
        }.sortedBy { URGENCY.indexOf(it.state).let { i -> if (i < 0) 99 else i } }
        return Pins.order(list) { it.pinned }
    }

    fun spaceState(s: Space, agents: List<Agent>): String? =
        agents.filter { it.spaceId == s.id }.map { it.state }.minByOrNull { URGENCY.indexOf(it).let { i -> if (i < 0) 99 else i } }

    /** Terminals really in front of the person right now (see `Looking`): only these count as looked at. */
    var visible: Set<String> = emptySet()
        set(v) {
            field = v
            shareSeen()
        }

    /**
     * Shares what is looked at as `herdr.seen` (docs/labels.md): once per turn
     * while it stays on screen, only to raise it or when it is missing.
     */
    private fun shareSeen() {
        if (core == null) return
        val looking = if (foreground) visible else emptySet()
        val terms = HashMap<String, SeenTerm>()
        for (addr in looking) {
            val t = terminals[addr] ?: continue
            if (t["status"].obj == null) continue
            val turn = t["turn"].long ?: continue
            terms[addr] = SeenTerm(turn, Seen.parse(t["labels"][Labels.SEEN].str))
        }
        val writes = seenTracker.raises(looking, terms)
        for ((addr, turn) in writes) writeLabel(addr, Labels.SEEN, turn.toString())
        if (writes.isNotEmpty()) seenRev++
    }

    /** One label on one terminal, on any machine (remote addresses look like `mac/t7`). */
    private fun writeLabel(address: String, key: String, value: String) {
        val c = core ?: return
        main.launch(Dispatchers.IO) {
            val ok = runCatching { c.call("terminal.label", obj("terminal" to address, "labels" to obj(key to value))) }.isSuccess
            if (!ok && key == Labels.SEEN) main.launch { seenTracker.failed(address); seenRev++ }
        }
    }

    /** Pin or unpin: `herdr.pinned=1`, or empty to remove it. */
    fun setPinned(address: String, pinned: Boolean) {
        if (core == null || terminals[address] == null) return
        writeLabel(address, Labels.PINNED, Pins.value(pinned))
    }

    /** Mark read (`herdr.seen=<turn>`) or unread (`<turn - 1>`, not at turn 0), whichever the menu offers. */
    fun toggleRead(address: String) {
        val t = terminals[address] ?: return
        if (core == null) return
        val turn = t["turn"].long ?: 0
        val label = Seen.parse(t["labels"][Labels.SEEN].str)
        val reported = t["status"]["state"].str ?: return
        val m = Seen.mark(reported, turn, seenTracker.seen(address, label))
        val v = seenTracker.mark(address, m, turn, label, looking = foreground && address in visible) ?: return
        writeLabel(address, Labels.SEEN, v.toString())
        seenRev++
        noticeAgents()
    }

    // ---- changes ------------------------------------------------------------

    private suspend fun spawn(machine: String?, spaceId: String, tabId: String, cwd: String?, argv: List<String>, size: Pair<Int, Int>?): String {
        val labels = mapOf("herdr.space" to local(spaceId, machine), "herdr.tab" to local(tabId, machine), Labels.SEEN to "0")
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

    private val shells = setOf("zsh", "bash", "fish", "sh", "dash", "ksh", "tcsh", "csh", "nu", "login")

    /** What closing a terminal would end: its agent or program, or null for a shell with nothing running. */
    private fun running(address: String?): String? {
        val t = terminals[address ?: return null] ?: return null
        t["status"].obj?.let { status -> return status["app"].str?.ifEmpty { null } ?: "an agent" }
        val name = t["foreground"]["name"].str?.removePrefix("-")?.ifEmpty { null } ?: return "what is running in it"
        return if (name in shells) null else name
    }

    /** Close pane, from the screen: asks first when something is running in it. */
    fun requestClosePane(paneId: String) {
        val place = place(paneId) ?: return
        val what = running(place.pane.terminal)
        if (what == null) closePane(paneId)
        else closing = Closing("Close this pane?", "This ends $what, on every screen.", "Close pane") { closePane(paneId) }
    }

    /** Close tab, from the screen: asks first when something is running in it. */
    fun requestCloseTab(tabId: String) {
        val s = spaces.firstOrNull { sp -> sp.tabs.any { it.id == tabId } } ?: return
        val tab = s.tabs.first { it.id == tabId }
        val what = tab.root.panes().mapNotNull { running(it.terminal) }.distinct()
        if (what.isEmpty()) closeTab(tabId)
        else closing = Closing("Close tab ${tabName(s, tab)}?", "This ends ${what.joinToString(", ")}, on every screen.", "Close tab") { closeTab(tabId) }
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

    /** Per terminal: the Type composer turned on or off by hand (else: on for agents). */
    val composer = mutableStateMapOf<String, Boolean>()
    /** Unsent composer drafts, per terminal. */
    val drafts = mutableStateMapOf<String, String>()

    fun saveFont(px: Int) = viewPrefs.edit().putInt("font", px).apply()

    /** The left panel hidden on the wide layout: this phone's own choice, never written to the shared layout. */
    var sidebarHidden by mutableStateOf(viewPrefs.getBoolean("sidebarHidden", false))
        private set
    fun updateSidebarHidden(hidden: Boolean) { sidebarHidden = hidden; viewPrefs.edit().putBoolean("sidebarHidden", hidden).apply() }

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
    suspend fun installedAgents(machine: String?): List<String> = installed(machine, Agents.known.map { it.command })

    /** Which of [commands] are on [machine]'s PATH (login shell), in the order asked. */
    suspend fun installed(machine: String?, commands: List<String>): List<String> {
        val out = runCatching { exec(machine, Agents.check(commands), check = false) }.getOrDefault("")
        val has = out.lines().mapNotNull { it.removePrefix("herdr-has:").takeIf { l -> l != it && l.isNotBlank() } }.toSet()
        return commands.filter { it in has }
    }
}
