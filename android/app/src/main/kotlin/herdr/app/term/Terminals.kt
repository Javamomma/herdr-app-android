package herdr.app.term

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.view.ViewGroup
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import herdr.app.Session
import herdr.app.Theme
import herdr.core.CoreClient
import herdr.core.StreamListener
import herdr.core.get
import herdr.core.str
import herdr.term.Emulator
import herdr.term.Key
import herdr.term.Keys
import herdr.term.Mods
import kotlinx.serialization.json.JsonElement
import kotlin.concurrent.thread

/**
 * One core terminal on screen: an [Emulator] fed by the core's attach
 * stream and drawn by a native [TerminalView]. Bytes are parsed on the
 * connection's reader thread under the emulator's lock, credited back once
 * parsed, and the view redraws at most once per display frame.
 */
class TerminalController(context: Context, val address: String, private val session: Session, var fontPx: Int, private val onFont: (Int) -> Unit) {
    private val ui = Handler(Looper.getMainLooper())
    val emu = Emulator(80, 24)
    val view = TerminalView(context, this)
    private var grid: Pair<Int, Int>? = null
    private var cell = intArrayOf(0, 0)
    @Volatile private var channel: Int? = null
    @Volatile private var attachedTo: CoreClient? = null
    @Volatile private var token = 0
    @Volatile private var pendingCredit = 0
    private val pendingReplies = java.io.ByteArrayOutputStream()
    private var attaching = false
    private var focusedHere = false
    /** Why the stream ended (null while attached). */
    var ended by mutableStateOf<String?>(null)
        private set
    var title by mutableStateOf("")
        private set
    /** What the person selected (empty: nothing). */
    var selection by mutableStateOf("")
        private set

    init {
        emu.onTitle = { t -> ui.post { title = t } }
    }

    // ---- from the view ---------------------------------------------------------

    /** The view's size in cells changed (laid out, folded, unfolded, font). */
    fun onGrid(cols: Int, rows: Int, cell: IntArray) {
        val cellChanged = !cell.contentEquals(this.cell)
        this.cell = cell
        if (grid == cols to rows && !cellChanged) return
        grid = cols to rows
        synchronized(emu) { emu.resize(cols, rows) }
        val core = attachedTo
        val chn = channel
        if (core != null && chn != null) {
            core.resize(chn, cols, rows)
            if (cellChanged) core.facts(chn, Theme.display(cell))
        } else {
            attach()
        }
        view.invalidate()
    }

    fun onFocus(gained: Boolean) {
        focusedHere = gained
        val core = attachedTo
        val chn = channel
        if (core != null && chn != null) core.focus(chn, gained)
        if (gained) session.onTerminalFocused(address)
    }

    fun onPinch(px: Int) {
        fontPx = px
        view.setFontSize(px)
        onFont(px)
    }

    fun onSelection(text: String) {
        selection = text
    }

    /** Text typed on a keyboard; the key bar's sticky Ctrl/Alt apply once. */
    fun typed(text: String, mods: Mods = Mods()) {
        val m = latched(mods)
        val t = text.replace('\n', '\r')
        send(if (m.ctrl || m.alt) Keys.text(t, m) else t)
    }

    fun sendKey(key: Key, mods: Mods) {
        val appCursor = synchronized(emu) { emu.appCursorKeys }
        send(Keys.encode(key, latched(mods), appCursor))
    }

    private fun latched(mods: Mods): Mods {
        if (!session.ctrl && !session.alt) return mods
        val m = mods.copy(ctrl = mods.ctrl || session.ctrl, alt = mods.alt || session.alt)
        ui.post { session.clearMods() }
        return m
    }

    fun send(text: String) {
        if (text.isEmpty()) return
        val core = attachedTo ?: return
        val chn = channel ?: return
        core.input(chn, text.toByteArray(Charsets.UTF_8))
        ui.post { view.scrollToBottom() }
    }

    // ---- from the key bar and composer ---------------------------------------

    fun key(name: String) {
        val k = when (name) {
            "esc" -> Key.ESCAPE; "tab" -> Key.TAB; "enter" -> Key.ENTER; "backspace" -> Key.BACKSPACE
            "up" -> Key.UP; "down" -> Key.DOWN; "left" -> Key.LEFT; "right" -> Key.RIGHT
            "home" -> Key.HOME; "end" -> Key.END; "pgup" -> Key.PAGE_UP; "pgdn" -> Key.PAGE_DOWN; "del" -> Key.DELETE
            "shift-tab" -> return sendKey(Key.TAB, Mods(shift = true))
            else -> return
        }
        sendKey(k, Mods())
    }

    fun type(text: String) = typed(text)

    fun paste(text: String) {
        val bracketed = synchronized(emu) { emu.bracketedPaste }
        send(Keys.paste(text, bracketed))
    }

    /** A message from the Type composer: pasted as one piece, then Enter. */
    fun submit(text: String) {
        if (text.isNotEmpty()) paste(text)
        // Let the program take the paste before the Enter that sends it.
        ui.postDelayed({ send("\r") }, 60)
    }

    fun setFont(px: Int) {
        fontPx = px
        view.setFontSize(px)
    }

    fun focus() = view.showKeyboard()

    fun copySelection(done: (String) -> Unit) = done(view.selectionText())

    fun clearSelection() = view.clearSelection()

    // ---- the core stream ---------------------------------------------------------

    /** Attaches if not attached to the current core (a new connection, a restarted terminal). */
    fun attach() {
        val core = session.core ?: return
        val (cols, rows) = grid ?: return
        if (attaching || (channel != null && attachedTo === core)) return
        attaching = true
        val mine = ++token
        channel = null
        attachedTo = null
        pendingCredit = 0
        synchronized(pendingReplies) { pendingReplies.reset() }
        // The core's first paint is the whole screen plus history: start clean.
        synchronized(emu) { emu.clearAll() }
        view.invalidate()
        val display = Theme.display(cell)
        thread(name = "attach-$address") {
            val listener = object : StreamListener {
                override fun onData(bytes: ByteArray) {
                    if (token != mine) return
                    val replies = synchronized(emu) {
                        emu.write(bytes)
                        emu.takeReplies()
                    }
                    val c = channel
                    if (c != null) {
                        if (replies.isNotEmpty()) core.automaticInput(c, replies)
                        core.credit(c, bytes.size)
                    } else {
                        // Frames that beat the attach reply: credited once its channel is known.
                        pendingCredit += bytes.size
                        synchronized(pendingReplies) { pendingReplies.write(replies) }
                    }
                    view.postInvalidateOnAnimation()
                }

                override fun onClosed(reason: JsonElement) {
                    ui.post {
                        if (token != mine) return@post
                        channel = null
                        val why = reason["reason"].str ?: "closed"
                        ended = why
                        val note = if (why == "exited" || why == "killed") "[$why]" else "[$why: waiting for its new shell…]"
                        synchronized(emu) { emu.write("\r\n\u001b[2m$note\u001b[0m\r\n") }
                        view.invalidate()
                    }
                }

                override fun onLost() {
                    ui.post { if (token == mine) channel = null }
                }
            }
            try {
                val a = core.attach(address, cols, rows, display, listener)
                if (token == mine) {
                    attachedTo = core
                    channel = a.channel
                    val owed = pendingCredit
                    pendingCredit = 0
                    if (owed > 0) core.credit(a.channel, owed)
                    val early = synchronized(pendingReplies) { pendingReplies.toByteArray().also { pendingReplies.reset() } }
                    if (early.isNotEmpty()) core.automaticInput(a.channel, early)
                }
                ui.post {
                    attaching = false
                    if (token != mine) return@post
                    ended = null
                    if (focusedHere) core.focus(a.channel, true)
                    grid?.let { (c, r) -> if (c != cols || r != rows) core.resize(a.channel, c, r) }
                }
            } catch (e: Exception) {
                ui.post {
                    attaching = false
                    if (token != mine) return@post
                    ended = e.message ?: "cannot attach"
                    synchronized(emu) { emu.write("\r\n\u001b[2m[cannot attach: ${e.message}]\u001b[0m\r\n") }
                    view.invalidate()
                }
            }
        }
    }

    fun dispose() {
        token++
        val core = attachedTo
        val chn = channel
        if (core != null && chn != null) core.detach(chn)
        channel = null
        attachedTo = null
        (view.parent as? ViewGroup)?.removeView(view)
    }
}

/**
 * The terminal views alive on this screen, by address. A view nobody shows
 * for a while is released (detached); showing it again reattaches and the
 * core repaints it.
 */
class TerminalPool(private val context: Context, private val session: Session) {
    private val ui = Handler(Looper.getMainLooper())
    private val views = HashMap<String, TerminalController>()
    private val users = HashMap<String, Int>()
    var onFontChanged: (Int) -> Unit = {}
    var fontSize = 13
        set(v) {
            field = v
            views.values.forEach { it.setFont(v) }
        }

    fun acquire(address: String): TerminalController {
        users[address] = (users[address] ?: 0) + 1
        return views.getOrPut(address) {
            TerminalController(context, address, session, fontSize) { px ->
                fontSize = px
                onFontChanged(px)
            }
        }
    }

    fun release(address: String) {
        users[address] = (users[address] ?: 1) - 1
        ui.postDelayed({
            if ((users[address] ?: 0) <= 0) {
                users.remove(address)
                views.remove(address)?.dispose()
            }
        }, 20_000)
    }

    fun get(address: String?): TerminalController? = views[address]

    /** A new connection, or terminals restarted: reattach what needs it. */
    fun reattach() = views.values.forEach { it.attach() }

    fun disposeAll() {
        views.values.forEach { it.dispose() }
        views.clear()
        users.clear()
    }
}
