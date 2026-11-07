package herdr.app.term

import android.annotation.SuppressLint
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Base64
import android.view.View
import android.view.ViewGroup
import android.webkit.JavascriptInterface
import android.webkit.WebView
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import herdr.app.Session
import herdr.app.Theme
import herdr.core.CoreClient
import herdr.core.StreamListener
import herdr.core.str
import herdr.core.get
import kotlinx.serialization.json.JsonElement
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import kotlin.concurrent.thread

/**
 * One core terminal on screen: an xterm.js WebView attached to the core's
 * stream. Output is batched to the page every frame and credited back once
 * xterm has written it, the same flow control as the desktop app.
 */
@SuppressLint("SetJavaScriptEnabled")
class TerminalController(context: Context, val address: String, private val session: Session, fontSize: Int) {
    private val ui = Handler(Looper.getMainLooper())
    val web: WebView = WebView(context)
    private var ready = false
    private var cols = 80
    private var rows = 24
    private var cell = intArrayOf(0, 0)
    @Volatile private var channel: Int? = null
    @Volatile private var attachedTo: CoreClient? = null
    private var attaching = false
    private var token = 0
    private val pending = ByteArrayOutputStream()
    private var flushQueued = false
    private var focusedHere = false
    /** Why the stream ended (null while attached). */
    var ended by mutableStateOf<String?>(null)
        private set
    var title by mutableStateOf("")
        private set

    init {
        web.settings.javaScriptEnabled = true
        web.settings.domStorageEnabled = true
        web.settings.allowFileAccess = false
        web.setBackgroundColor(Theme.BG_INT)
        web.overScrollMode = View.OVER_SCROLL_NEVER
        web.isFocusable = true
        web.isFocusableInTouchMode = true
        web.addJavascriptInterface(Bridge(), "Herdr")
        web.loadUrl("file:///android_asset/term/index.html?fs=$fontSize")
    }

    private fun js(code: String) = web.evaluateJavascript(code, null)

    private inner class Bridge {
        @JavascriptInterface fun ready(c: Int, r: Int, cw: Int, ch: Int) = ui.post {
            cols = c; rows = r; cell = intArrayOf(cw, ch)
            ready = true
            attach()
        }

        @JavascriptInterface fun input(d: String) = send(d.toByteArray(Charsets.UTF_8))
        @JavascriptInterface fun binary(d: String) = send(d.toByteArray(Charsets.ISO_8859_1))

        @JavascriptInterface fun resize(c: Int, r: Int, cw: Int, ch: Int) = ui.post {
            val cellChanged = cw != cell[0] || ch != cell[1]
            cols = c; rows = r; cell = intArrayOf(cw, ch)
            val core = attachedTo
            val chn = channel
            if (core != null && chn != null) {
                core.resize(chn, c, r)
                if (cellChanged && cw > 0) core.facts(chn, Theme.display(cell))
            }
        }

        @JavascriptInterface fun written(n: Int) {
            val core = attachedTo ?: return
            val chn = channel ?: return
            core.credit(chn, n)
        }

        @JavascriptInterface fun focus(f: Boolean) = ui.post {
            focusedHere = f
            val core = attachedTo
            val chn = channel
            if (core != null && chn != null) core.focus(chn, f)
            if (f) session.onTerminalFocused(address)
        }

        @JavascriptInterface fun title(t: String) = ui.post { title = t }
        @JavascriptInterface fun modsUsed() = ui.post { session.clearMods() }
    }

    private fun send(bytes: ByteArray) {
        val core = attachedTo ?: return
        val chn = channel ?: return
        core.input(chn, bytes)
    }

    /** Attaches if not attached to the current core (a new connection, a restarted terminal). */
    fun attach() {
        val core = session.core ?: return
        if (!ready || attaching || (channel != null && attachedTo === core)) return
        attaching = true
        val mine = ++token
        val display = Theme.display(cell)
        val (c, r) = cols to rows
        thread(name = "attach-$address") {
            val listener = object : StreamListener {
                override fun onData(bytes: ByteArray) = ui.post { if (token == mine) queue(bytes) }.let {}
                override fun onClosed(reason: JsonElement) = ui.post {
                    if (token != mine) return@post
                    channel = null
                    val why = reason["reason"].str ?: "closed"
                    ended = why
                    when (why) {
                        "exited", "killed" -> js("note(${JSONObject.quote("[$why]")})")
                        else -> js("note(${JSONObject.quote("[$why: waiting for its new shell…]")})")
                    }
                }.let {}
                override fun onLost() = ui.post { if (token == mine) channel = null }.let {}
            }
            try {
                ui.post {
                    if (token == mine) {
                        pending.reset()
                        js("reset()")
                    }
                }
                val a = core.attach(address, c, r, display, listener)
                if (token == mine) {
                    attachedTo = core
                    channel = a.channel
                }
                ui.post {
                    attaching = false
                    if (token != mine) return@post
                    ended = null
                    if (focusedHere) core.focus(a.channel, true)
                    if (c != cols || r != rows) core.resize(a.channel, cols, rows)
                }
            } catch (e: Exception) {
                ui.post {
                    attaching = false
                    if (token != mine) return@post
                    ended = e.message ?: "cannot attach"
                    js("note(${JSONObject.quote("[cannot attach: ${e.message}]")})")
                }
            }
        }
    }

    private fun queue(bytes: ByteArray) {
        pending.write(bytes)
        if (!flushQueued) {
            flushQueued = true
            ui.postDelayed(::flush, 8)
        }
    }

    private fun flush() {
        flushQueued = false
        val all = pending.toByteArray()
        pending.reset()
        var at = 0
        while (at < all.size) {
            val n = minOf(192 * 1024, all.size - at)
            js("feed('${Base64.encodeToString(all, at, n, Base64.NO_WRAP)}')")
            at += n
        }
    }

    fun key(name: String) = js("key('$name')")
    fun setMods(ctrl: Boolean, alt: Boolean) = js("setMods($ctrl, $alt)")
    fun paste(text: String) = js("paste(${JSONObject.quote(text)})")
    fun type(text: String) = js("type(${JSONObject.quote(text)})")
    fun setFont(px: Int) = js("setFont($px)")
    fun focus() {
        web.requestFocus()
        js("focusTerm()")
    }

    fun copySelection(done: (String) -> Unit) = web.evaluateJavascript("copySelection()") { v ->
        done(runCatching { org.json.JSONTokener(v).nextValue() as? String }.getOrNull().orEmpty())
    }

    fun dispose() {
        token++
        val core = attachedTo
        val chn = channel
        if (core != null && chn != null) core.detach(chn)
        channel = null
        attachedTo = null
        (web.parent as? ViewGroup)?.removeView(web)
        web.destroy()
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
    var fontSize = 13
        set(v) {
            field = v
            views.values.forEach { it.setFont(v) }
        }

    fun acquire(address: String): TerminalController {
        users[address] = (users[address] ?: 0) + 1
        return views.getOrPut(address) { TerminalController(context, address, session, fontSize) }
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
