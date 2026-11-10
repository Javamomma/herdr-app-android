package herdr.app

import android.app.Application
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import org.bouncycastle.jce.provider.BouncyCastleProvider
import java.security.Security

class HerdrApplication : Application() {
    lateinit var session: Session
        private set
    lateinit var notifier: Notifier
        private set
    private var watching = false
    private val main = MainScope()
    private var lastNetwork: Network? = null

    override fun onCreate() {
        super.onCreate()
        // Android ships a cut-down "BC" provider; sshj needs the full one
        // (Ed25519 keys, curve25519 key exchange).
        Security.removeProvider("BC")
        Security.insertProviderAt(BouncyCastleProvider(), 1)
        session = Session(this)
        notifier = Notifier(this)
        session.onAlert = { kind, agent, where -> main.launch { announce(kind, agent, where) } }
        session.onSettled = { notifier.settle(it) }
        session.onAgents = { status(it) }
        session.onConn = { status(session.agents()) }
        session.onLock = {
            notifier.settleAll()
            status(session.agents())
        }
        watchNetwork()
    }

    /** The widget and the watching service's notification say what is true right now. */
    private fun status(agents: List<Agent>) {
        AgentsWidget.render(this, agents, session.conn is Conn.Connected, session.lockEnabled)
        keepWatching()
        if (watching) WatchService.update(this, session.machineLabel(null), agents, session.conn, notifier)
    }

    private fun blocked(address: String) = session.agents().any { it.address == address && it.state == "blocked" }

    /**
     * Notifies about an agent. For a blocked one, its question is read from
     * its screen first, so the notification shows what is asked and its
     * buttons answer that question only. The status can change a moment
     * before the question is drawn, so it is read once more shortly after.
     */
    private suspend fun announce(kind: Alert, agent: Agent, where: String) {
        val hidden = session.lockEnabled
        if (kind != Alert.BLOCKED || hidden) {
            notifier.alert(kind, agent, where, null, hidden)
            return
        }
        delay(400)
        val first = session.question(agent.address)
        if (!blocked(agent.address)) return
        notifier.alert(kind, agent, where, first, hidden = false)
        delay(2_000)
        val again = session.question(agent.address) ?: return
        if (blocked(agent.address) && notifier.showing(agent.address) && again.mark != first?.mark) {
            notifier.alert(kind, agent, where, again, hidden = false, quiet = true)
        }
    }

    /**
     * An answer tapped on a notification: sent only if the agent still shows
     * the question that notification showed. If it isn't sent, a notification
     * says so; [done] runs when it is settled either way.
     */
    fun answer(address: String, mark: String, deny: Boolean, reply: String?, done: () -> Unit) {
        main.launch {
            val name = session.agents().firstOrNull { it.address == address }?.app ?: "the agent"
            val result = withTimeoutOrNull(9_000) { session.answerShown(address, mark, deny, reply) }
                ?: Answered.Failed("Your Mac took too long to answer.")
            val kept = if (reply != null) " Your reply is in its Type box." else ""
            if (reply != null && result != Answered.Sent) session.drafts[address] = reply
            when (result) {
                Answered.Sent -> notifier.settle(address)
                Answered.Moot -> if (reply == null) notifier.settle(address) else notifier.problem(address, "Not sent: $name isn't waiting any more", "Open Herdr to see where it is.$kept")
                Answered.Changed -> notifier.problem(address, "Not sent: $name asks something else now", "Nothing was sent. Open Herdr to see its question.$kept")
                Answered.Offline -> notifier.problem(address, "Not sent: can't reach your Mac", "Your answer to $name was not sent. Open Herdr to try again.$kept")
                is Answered.Failed -> notifier.problem(address, "Not sent to $name", "${result.why}$kept")
            }
            done()
        }
    }

    /** The watching service says whether it runs: only then is its notification there to keep true. */
    fun watching(on: Boolean) {
        watching = on
        if (on) WatchService.update(this, session.machineLabel(null), session.agents(), session.conn, notifier)
    }

    /** The background service runs while alerts are on and a real Mac is connected. */
    fun keepWatching() {
        val want = session.alerts && session.wantConnected && !session.demo
        if (want && !watching) WatchService.start(this) else if (!want && watching) WatchService.stop(this)
    }

    /** A new default network (Wi-Fi ⇄ cellular, a hotel network): check the link at once. */
    private fun watchNetwork() {
        val cm = getSystemService(ConnectivityManager::class.java) ?: return
        runCatching {
            cm.registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    val changed = lastNetwork != null && lastNetwork != network
                    lastNetwork = network
                    if (changed) android.os.Handler(mainLooper).post { session.networkChanged() }
                }

                override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
                    if (caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) && session.core == null) {
                        android.os.Handler(mainLooper).post { session.networkChanged() }
                    }
                }
            })
        }
    }
}
