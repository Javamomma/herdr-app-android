package herdr.core

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import java.io.InputStream
import java.io.OutputStream
import java.util.Base64

/**
 * A pretend herdr-core with sample spaces, agents and terminals, speaking the
 * real framed protocol over in-memory pipes. It lets people try the app
 * without a Mac, and CI take screenshots of it. Nothing here touches a real
 * machine: commands are answered from canned text, typing is echoed.
 */
class DemoCore {
    /** What the client reads (the core's output) and writes (the core's input). */
    val clientInput: InputStream
    val clientOutput: OutputStream
    private val coreIn: InputStream
    private val coreOut: OutputStream
    private val lock = Any()
    private var seq = 10L
    private var nextChannel = 10
    private var nextTerminal = 20
    private var eventsChannel = -1
    private val attached = HashMap<Int, String>()
    private val docs = HashMap<String, Pair<JsonElement, Long>>()
    private val terminals = LinkedHashMap<String, JsonObject>()
    @Volatile var closed = false
        private set

    init {
        val toCore = MemoryPipe()
        coreIn = toCore.input
        clientOutput = toCore.output
        val fromCore = MemoryPipe()
        clientInput = fromCore.input
        coreOut = fromCore.output
        seed()
        Thread(::serve, "demo-core").apply { isDaemon = true }.start()
        // A little life: Codex reaches a permission prompt a few seconds in.
        Thread({
            Thread.sleep(STORY_DELAY_MS)
            if (!closed) synchronized(lock) { setStatus("t4", "blocked", "Run the database migration?") }
        }, "demo-story").apply { isDaemon = true }.start()
    }

    fun close() {
        closed = true
        runCatching { coreOut.close() }
        runCatching { coreIn.close() }
    }

    // ---- sample world -------------------------------------------------------

    private fun rec(address: String, title: String, cwd: String, fg: String, status: JsonObject? = null, turn: Int = 0, seen: Int = 0, parent: String? = null, space: String? = null, tab: String? = null): JsonObject {
        val machine = machineOf(address)
        val labels = buildMap {
            if (space != null) put("herdr.space", local(space, machine))
            if (tab != null) put("herdr.tab", local(tab, machine))
            put("herdr.seen", seen.toString())
        }
        return obj(
            "address" to address, "state" to "live", "title" to title, "cwd" to cwd, "labels" to labels,
            "status" to (status ?: JsonNull), "reported" to (status != null), "records" to obj(), "turn" to turn,
            "parent" to parent, "keep" to true, "pid" to 4000 + address.hashCode() % 1000, "cols" to 100, "rows" to 30,
            "foreground" to obj("pid" to 1, "name" to fg, "argv" to listOf(fg), "cwd" to cwd),
        )
    }

    private fun status(state: String, app: String, title: String, kind: String? = null) =
        obj("state" to state, "app" to app, "title" to title, "msg" to "", "kind" to kind)

    private fun seed() {
        val home = "/Users/you"
        terminals["t1"] = rec("t1", "claude", "$home/src/herdr-android", "claude", status("blocked", "Claude Code", "Edit app/Session.kt", "permission"), turn = 4, seen = 3, space = "sp1", tab = "tb1")
        terminals["t2"] = rec("t2", "zsh", "$home/src/herdr-android", "zsh", space = "sp1", tab = "tb1")
        terminals["t3"] = rec("t3", "gradle", "$home/src/herdr-android", "java", space = "sp1", tab = "tb1")
        terminals["t4"] = rec("t4", "codex", "$home/src/website", "codex", status("working", "Codex", "Refactor the nav bar"), turn = 2, seen = 2, space = "sp2", tab = "tb3")
        terminals["t5"] = rec("t5", "claude", "$home/src/website", "claude", status("working", "Claude Code", "Write e2e tests"), turn = 1, seen = 1, parent = "t4", space = "sp2", tab = "tb3")
        terminals["t6"] = rec("t6", "claude", "$home/src/infra", "claude", status("done", "Claude Code", "Upgrade to Postgres 17"), turn = 6, seen = 5, space = "sp3", tab = "tb4")
        terminals["vps/t9"] = rec("vps/t9", "htop", "/srv/app", "htop", space = "vps/sp9", tab = "vps/tb9")

        fun pane(id: String, t: String, w: Double? = null) = terminalPane(id, t).let { if (w != null) it.withWeight(w) else it }
        val tb1 = container("tb1", Axis.Row, listOf(
            pane("p1", "t1", 0.55),
            container("s1", Axis.Column, listOf(pane("p2", "t2", 0.6), pane("p3", "t3", 0.4))).withWeight(0.45),
        ))
        val mac = obj("spaces" to listOf(
            obj("id" to "sp1", "name" to "", "folder" to "$home/src/herdr-android", "tabs" to listOf(
                obj("id" to "tb1", "name" to "fold layout", "root" to tb1),
                obj("id" to "tb2", "name" to "", "root" to container("tb2", Axis.Row, listOf(pane("p7", "t2")))),
            )),
            obj("id" to "sp2", "name" to "", "folder" to "$home/src/website", "tabs" to listOf(
                obj("id" to "tb3", "name" to "nav", "root" to container("tb3", Axis.Row, listOf(pane("p4", "t4")))),
            )),
            obj("id" to "sp3", "name" to "infra", "folder" to "$home/src/infra", "tabs" to listOf(
                obj("id" to "tb4", "name" to "", "root" to container("tb4", Axis.Row, listOf(pane("p5", "t6")))),
            )),
        ))
        docs[""] = mac to 3
        val vps = obj("spaces" to listOf(
            obj("id" to "sp9", "name" to "prod", "folder" to "/srv/app", "tabs" to listOf(
                obj("id" to "tb9", "name" to "", "root" to container("tb9", Axis.Row, listOf(pane("p9", "t9")))),
            )),
        ))
        docs["vps"] = vps to 1
    }

    private val esc = "\u001b"
    private fun screen(address: String): String {
        val dim = "$esc[2m"; val r = "$esc[0m"; val b = "$esc[1m"; val g = "$esc[32m"; val y = "$esc[33m"; val c = "$esc[36m"; val m = "$esc[35m"; val red = "$esc[31m"
        val prompt = "$g➜$r  $c${b}herdr-android$r $m(main)$r "
        return when (address) {
            "t1" -> listOf(
                "$dim> Make the key bar follow the fold: Esc and ^C first.$r", "",
                "${y}●$r I'll move Esc and ^C to the front and add a keyboard toggle.", "",
                "  ${b}Update$r app/src/main/kotlin/herdr/app/ui/Workspace.kt",
                "  ${dim}642$r $red-        K(\"type\", composing) { … }$r",
                "  ${dim}642$r $g+        K(\"⌨\", imeVisible) { target()?.toggleKeyboard(imeVisible) }$r",
                "  ${dim}643$r          K(\"esc\") { target()?.key(\"esc\") }",
                "  ${dim}644$r $g+        K(\"^C\") { target()?.type(\"\\u0003\") }$r", "",
                "$y╭────────────────────────────────────────────────╮$r",
                "$y│$r ${b}Allow this edit?$r                               $y│$r",
                "$y│$r  ${c}❯ 1. Yes$r                                      $y│$r",
                "$y│$r    2. Yes, and don't ask again this session     $y│$r",
                "$y│$r    3. No, and tell the agent what to do         $y│$r",
                "$y╰────────────────────────────────────────────────╯$r",
            ).joinToString("\r\n")
            "t2" -> listOf(
                "${prompt}git log --oneline -5",
                "${y}e716416$r fix: import isImeVisible for the keyboard key",
                "${y}5533f60$r fix: bring the keyboard back after folding",
                "${y}7696014$r feat: android client for galaxy z fold",
                "${y}7fe7ac3$r fix: revalidate mod sources on rescan",
                "${prompt}git status -sb",
                "$g## main...origin/main$r",
                " ${red}M$r app/src/main/kotlin/herdr/app/ui/Workspace.kt",
                "${prompt}",
            ).joinToString("\r\n")
            "t3" -> listOf(
                "${prompt}./gradlew :terminal:test",
                "$dim> Task :terminal:compileKotlin$r",
                "$dim> Task :terminal:test$r",
                "",
                "${g}EmulatorTest$r  14 passed",
                "${g}BUILD SUCCESSFUL$r in 6s",
                "${prompt}",
            ).joinToString("\r\n")
            "t4" -> if (terminals["t4"]?.get("status")?.obj?.get("state").str == "blocked") listOf(
                "$dim> Refactor the nav bar into a component and add tests$r", "",
                "${c}•$r The new nav needs a column on users. Run the migration?", "",
                "  $b$ npx prisma migrate deploy$r", "",
                "› 1. Yes, run it",
                "  2. Yes, and allow migrations for this session",
                "  3. No, I'll do it myself",
            ).joinToString("\r\n") else listOf(
                "$dim> Refactor the nav bar into a component and add tests$r", "",
                "${c}•$r Reading src/components/Nav.tsx",
                "${c}•$r Splitting NavItem and NavMenu",
                "${c}•$r Started a worker for the e2e tests",
                "", "$y⠋$r Working (1m 12s · esc to interrupt)",
            ).joinToString("\r\n")
            "t5" -> "$dim> Write e2e tests for the new nav$r\r\n\r\n$y⠙$r Running playwright…"
            "t6" -> listOf(
                "$dim> Upgrade the database to Postgres 17$r", "",
                "${g}✓$r Migrated 14 tables, 2.1M rows",
                "${g}✓$r All 212 tests pass", "",
                "Done. The upgrade notes are in docs/postgres-17.md.",
            ).joinToString("\r\n")
            "vps/t9" -> listOf(
                "  ${c}1$r [$g||||||||$red||$r              23.5%]   Tasks: ${b}41$r, 112 thr; ${g}1$r running",
                "  ${c}2$r [$g|||||$r                    11.0%]   Load average: 0.42 0.37 0.31",
                "  ${c}Mem$r[$g|||||||||||$y|||$r      1.21G/3.83G]   Uptime: 41 days, 06:12:09", "",
                "$esc[30;42m  PID USER      PRI  NI  VIRT   RES   SHR S CPU%  MEM%   TIME+  Command     $r",
                "  812 app        20   0 1.1G  284M 41.2M S  9.3   7.2  4h12:03 node server.js",
                "  640 postgres   20   0  412M 96.1M 88.0M S  2.1   2.5  1h03:44 postgres",
                "    1 root       20   0  167M 11.8M  8.4M S  0.0   0.3  0:41.18 /sbin/init",
            ).joinToString("\r\n")
            else -> "${prompt}"
        }
    }

    // ---- protocol -------------------------------------------------------------

    private fun write(channel: Int, kind: Int, payload: ByteArray) {
        synchronized(lock) {
            if (closed) return
            runCatching { coreOut.write(encodeFrame(channel, kind, payload)); coreOut.flush() }
        }
    }

    private fun reply(id: JsonElement?, result: JsonElement) =
        write(0, Kind.DATA, obj("jsonrpc" to "2.0", "id" to id, "result" to result).toString().toByteArray())

    private fun error(id: JsonElement?, message: String, kind: String) =
        write(0, Kind.DATA, obj("jsonrpc" to "2.0", "id" to id, "error" to obj("code" to -32000, "message" to message, "data" to obj("kind" to kind))).toString().toByteArray())

    private fun event(type: String, terminal: String?, data: JsonElement, machine: String? = null) {
        if (eventsChannel < 0) return
        val line = obj("jsonrpc" to "2.0", "method" to "event", "params" to obj("seq" to ++seq, "type" to type, "terminal" to terminal, "machine" to machine, "data" to data)).toString() + "\n"
        write(eventsChannel, Kind.DATA, line.toByteArray())
    }

    /** Changes an agent's status, as a program reporting through OSC 7501 would. */
    fun setStatus(address: String, state: String, title: String) {
        val t = terminals[address] ?: return
        val s = (t["status"].obj ?: status(state, "Claude Code", title)).with("state" to JsonPrimitive(state), "title" to JsonPrimitive(title))
        val turn = (t["turn"].long ?: 0) + if (state == "done" || state == "blocked") 1 else 0
        terminals[address] = t.with("status" to s, "turn" to JsonPrimitive(turn))
        event("status", address, obj("status" to s, "records" to obj(), "reported" to true))
        event("turn", address, obj("turn" to turn, "state" to state))
    }

    private fun serve() {
        try {
            readLine(coreIn) ?: return
            val methods = USES + listOf("machine.list", "machine.exec", "file.read", "terminal.send", "terminal.keys", "terminal.get")
            val hello = obj("jsonrpc" to "2.0", "id" to 0, "result" to obj("generation" to GENERATION, "version" to "0.2.0-demo", "methods" to methods, "features" to listOf("automatic_input"), "framed" to true))
            synchronized(lock) { coreOut.write((hello.toString() + "\n").toByteArray()); coreOut.flush() }
            while (!closed) {
                val f = readFrame(coreIn) ?: break
                if (f.channel == 0) handle(json.parseToJsonElement(f.payload.decodeToString()).jsonObject)
                else if (f.kind == Kind.DATA) attached[f.channel]?.let { echo(f.channel, f.payload) }
                else if (f.kind == Kind.CLOSE) { attached.remove(f.channel); write(f.channel, Kind.CLOSE, ByteArray(0)) }
            }
        } catch (_: Exception) {
        }
        closed = true
    }

    /** Typing in the demo: printable text and Enter echo back like a shell would. */
    private fun echo(channel: Int, bytes: ByteArray) {
        val s = bytes.decodeToString()
        val out = StringBuilder()
        for (ch in s) when {
            ch == '\r' -> out.append("\r\n$esc[2m(demo: nothing runs here)$esc[0m\r\n$esc[32m➜$esc[0m ")
            ch == '\u007f' -> out.append("\b \b")
            ch == '\u0003' -> out.append("^C\r\n$esc[32m➜$esc[0m ")
            ch >= ' ' -> out.append(ch)
        }
        if (out.isNotEmpty()) write(channel, Kind.DATA, out.toString().toByteArray())
    }

    private fun handle(msg: JsonObject) {
        val id = msg["id"]
        val p = msg["params"]
        when (msg["method"].str) {
            "events.subscribe" -> {
                eventsChannel = nextChannel++
                reply(id, obj("channel" to eventsChannel, "seq" to seq, "terminals" to terminals.values.toList()))
            }
            "machine.list" -> reply(id, obj("machines" to listOf(obj("name" to "vps", "host" to "vps-1", "id" to "demo-vps", "target" to "vps", "temporary" to false, "version" to "0.2.0"))))
            "doc.get" -> {
                val (v, ver) = docs[p["on"].str ?: ""] ?: (JsonNull to 0L)
                reply(id, obj("value" to v, "version" to ver))
            }
            "doc.set" -> {
                val key = p["on"].str ?: ""
                val (_, ver) = docs[key] ?: (JsonNull to 0L)
                if ((p["version"].long ?: -1) != ver) return error(id, "stale write (version $ver)", "conflict")
                docs[key] = (p["value"] ?: JsonNull) to ver + 1
                reply(id, obj("version" to ver + 1))
                event("doc", null, obj("name" to DOC_NAME, "version" to ver + 1), machine = key.ifEmpty { null })
            }
            "terminal.attach" -> {
                val t = p["terminal"].str ?: ""
                val r = terminals[t] ?: return error(id, "no terminal $t", "not_found")
                val ch = nextChannel++
                attached[ch] = t
                reply(id, obj("channel" to ch, "terminal" to r, "automatic_input" to true))
                write(ch, Kind.DATA, ("$esc[H$esc[2J" + screen(t)).toByteArray())
            }
            "terminal.run" -> {
                val on = p["on"].str
                val addr = qualify("t${nextTerminal++}", on)
                val labels = p["labels"].obj ?: obj()
                val r = rec(addr, "zsh", p["cwd"].str ?: "/Users/you", "zsh").with("labels" to labels)
                terminals[addr] = r
                reply(id, obj("address" to addr))
                event("created", addr, r)
            }
            "terminal.kill" -> {
                val t = p["terminal"].str ?: ""
                terminals.remove(t)
                reply(id, obj())
                event("removed", t, obj())
            }
            "terminal.read" -> {
                val t = p["terminal"].str ?: ""
                if (terminals[t] == null) return error(id, "no terminal $t", "not_found")
                val text = screen(t).replace(Regex("\u001b\\[[0-9;?]*[A-Za-z]"), "").replace("\r\n", "\n")
                reply(id, obj("text" to text, "how" to "screen", "complete" to true))
            }
            "terminal.send", "terminal.keys" -> {
                val t = p["terminal"].str ?: ""
                if (terminals[t]?.get("status")?.obj?.get("state").str == "blocked") setStatus(t, "working", "Working on it")
                reply(id, obj())
            }
            "terminal.label" -> {
                val t = p["terminal"].str ?: ""
                terminals[t]?.let { r ->
                    val labels = (r["labels"].obj ?: obj()).with(*(p["labels"].obj ?: obj()).entries.map { it.key to it.value }.toTypedArray())
                    terminals[t] = r.with("labels" to labels)
                    event("labels", t, obj("labels" to labels))
                }
                reply(id, obj())
            }
            "machine.exec" -> {
                val argv = p["argv"].arr.mapNotNull { it.str }
                val out = when {
                    argv.firstOrNull() == "ls" -> "app/\nprotocol/\nterminal/\nREADME.md\nbuild.gradle.kts\nsettings.gradle.kts\n"
                    argv.firstOrNull() == "git" && "status" in argv -> "## main...origin/main\n M app/src/main/kotlin/herdr/app/ui/Workspace.kt\n?? docs/demo.md\n"
                    argv.firstOrNull() == "git" && "diff" in argv -> "--- a/Workspace.kt\n+++ b/Workspace.kt\n@@ -642,3 +642,4 @@\n-        K(\"type\", composing)\n+        K(\"⌨\", imeVisible)\n         K(\"esc\")\n+        K(\"^C\")\n"
                    argv.any { it.contains("herdr-has") } -> "herdr-has:claude\nherdr-has:codex\nherdr-has:tuios\n"
                    argv.any { it.contains("HOME") } -> "/Users/you"
                    else -> ""
                }
                reply(id, obj("code" to 0, "ms" to 3, "stdout" to Base64.getEncoder().encodeToString(out.toByteArray()), "stderr" to "", "timed_out" to false, "truncated" to false))
            }
            "file.read" -> reply(id, obj("text" to "# Herdr for Android\n\nA demo file. In the real app this is read from your Mac.\n", "first" to 1, "last" to 3, "lines" to 3))
            else -> reply(id, obj())
        }
    }
}

/** When, after the demo starts, Codex reaches its permission prompt. */
const val STORY_DELAY_MS = 15_000L

/**
 * An in-memory byte pipe. Unlike java.io's piped streams it doesn't care
 * which threads write: a writer thread ending doesn't break it, closing does.
 */
class MemoryPipe {
    private val queue = java.util.concurrent.LinkedBlockingQueue<ByteArray>()
    private var current: ByteArray = ByteArray(0)
    private var at = 0
    @Volatile private var closed = false
    private val eof = ByteArray(0)

    val output: OutputStream = object : OutputStream() {
        override fun write(b: Int) = write(byteArrayOf(b.toByte()), 0, 1)
        override fun write(b: ByteArray, off: Int, len: Int) {
            if (closed) throw java.io.IOException("pipe closed")
            if (len > 0) queue.put(b.copyOfRange(off, off + len))
        }
        override fun close() {
            closed = true
            queue.put(eof)
        }
    }

    val input: InputStream = object : InputStream() {
        private fun fill(): Boolean {
            while (at >= current.size) {
                val next = queue.take()
                if (next === eof) {
                    queue.put(eof)
                    return false
                }
                current = next
                at = 0
            }
            return true
        }
        override fun read(): Int = if (fill()) current[at++].toInt() and 0xff else -1
        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (len == 0) return 0
            if (!fill()) return -1
            val n = minOf(len, current.size - at)
            System.arraycopy(current, at, b, off, n)
            at += n
            return n
        }
        override fun available(): Int = if (at < current.size) current.size - at else queue.peek()?.size ?: 0
        override fun close() = output.close()
    }
}
