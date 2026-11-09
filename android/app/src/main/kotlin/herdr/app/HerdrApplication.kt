package herdr.app

import android.app.Application
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import org.bouncycastle.jce.provider.BouncyCastleProvider
import java.security.Security

class HerdrApplication : Application() {
    lateinit var session: Session
        private set
    lateinit var notifier: Notifier
        private set
    private var watching = false
    private var lastNetwork: Network? = null

    override fun onCreate() {
        super.onCreate()
        // Android ships a cut-down "BC" provider; sshj needs the full one
        // (Ed25519 keys, curve25519 key exchange).
        Security.removeProvider("BC")
        Security.insertProviderAt(BouncyCastleProvider(), 1)
        session = Session(this)
        notifier = Notifier(this)
        session.onAlert = { kind, agent, where -> notifier.alert(kind, agent, where) }
        session.onSettled = { notifier.settle(it) }
        session.onAgents = { agents ->
            AgentsWidget.render(this, agents, session.conn is Conn.Connected)
            keepWatching()
            if (watching) WatchService.update(this, session.machineLabel(null), agents, notifier)
        }
        watchNetwork()
    }

    /** The background service runs while alerts are on and a real Mac is connected. */
    fun keepWatching() {
        val want = session.alerts && session.wantConnected && !session.demo
        if (want && !watching) {
            WatchService.start(this)
            watching = true
        } else if (!want && watching) {
            WatchService.stop(this)
            watching = false
        }
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
