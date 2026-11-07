package herdr.app.ssh

import android.content.Context
import net.schmizz.sshj.DefaultConfig
import net.schmizz.sshj.SSHClient
import net.schmizz.sshj.common.Buffer
import net.schmizz.sshj.connection.channel.direct.Session
import net.schmizz.sshj.transport.verification.HostKeyVerifier
import java.io.InputStream
import java.io.OutputStream
import java.security.PublicKey

/** How to reach the Mac: saved in preferences (secrets apart, in [Secrets]). */
data class Profile(
    val host: String = "",
    val port: Int = 22,
    val user: String = "",
    /** `key`: this phone's own key; `password`: the Mac account's password. */
    val auth: String = "key",
    /**
     * Run on the Mac. sshd starts it through the account's shell without a
     * login profile, so the usual install places are put on PATH first.
     */
    val command: String = DEFAULT_COMMAND,
) {
    val ready get() = host.isNotBlank() && user.isNotBlank()

    companion object {
        const val DEFAULT_COMMAND =
            "PATH=\"\$HOME/.local/bin:/opt/homebrew/bin:/usr/local/bin:\$HOME/.cargo/bin:\$HOME/.nix-profile/bin:\$PATH\"; exec hc connect"

        fun load(context: Context): Profile {
            val p = context.getSharedPreferences("profile", Context.MODE_PRIVATE)
            return Profile(
                host = p.getString("host", "") ?: "",
                port = p.getInt("port", 22),
                user = p.getString("user", "") ?: "",
                auth = p.getString("auth", "key") ?: "key",
                command = p.getString("command", DEFAULT_COMMAND)?.ifBlank { DEFAULT_COMMAND } ?: DEFAULT_COMMAND,
            )
        }
    }

    fun save(context: Context) {
        context.getSharedPreferences("profile", Context.MODE_PRIVATE).edit()
            .putString("host", host.trim()).putInt("port", port).putString("user", user.trim())
            .putString("auth", auth).putString("command", command).apply()
    }
}

/** The Mac's host key wasn't the one seen before, or was never seen: ask the person. */
class UnknownHostKey(val host: String, val fingerprint: String, val changed: Boolean) :
    Exception(if (changed) "The Mac's host key CHANGED ($fingerprint). If you didn't reinstall it, someone may be intercepting the connection." else "First connection: check the Mac's host key $fingerprint")

/** Trust on first use: one pinned host key per host:port. */
class KnownHosts(context: Context) {
    private val prefs = context.getSharedPreferences("known-hosts", Context.MODE_PRIVATE)
    fun get(host: String, port: Int): String? = prefs.getString("$host:$port", null)
    fun pin(host: String, port: Int, fingerprint: String) = prefs.edit().putString("$host:$port", fingerprint).apply()
}

/** An SSH session running `hc connect` on the Mac: its stdout and stdin are the core's pipe. */
class SshLink private constructor(private val client: SSHClient, private val session: Session, private val cmd: Session.Command) {
    val input: InputStream get() = cmd.inputStream
    val output: OutputStream get() = cmd.outputStream

    /** What the command wrote to stderr so far (why `hc connect` failed). */
    fun errors(): String = runCatching {
        val s = cmd.errorStream
        val buf = ByteArray(minOf(s.available(), 8192))
        if (buf.isEmpty()) "" else String(buf, 0, maxOf(s.read(buf), 0))
    }.getOrDefault("")

    fun close() {
        runCatching { cmd.close() }
        runCatching { session.close() }
        runCatching { client.disconnect() }
    }

    companion object {
        /**
         * Connects and starts the command. [accept] is the fingerprint the
         * person just confirmed for an unknown or changed host key.
         */
        fun open(profile: Profile, known: KnownHosts, key: DeviceKey, password: String?, accept: String?): SshLink {
            val client = SSHClient(DefaultConfig())
            client.connectTimeout = 15_000
            client.timeout = 0
            client.addHostKeyVerifier(object : HostKeyVerifier {
                override fun verify(hostname: String, port: Int, key: PublicKey): Boolean {
                    val fp = fingerprint(Buffer.PlainBuffer().putPublicKey(key).compactData)
                    val pinned = known.get(profile.host, profile.port)
                    if (pinned == fp) return true
                    if (accept == fp) {
                        known.pin(profile.host, profile.port, fp)
                        return true
                    }
                    throw UnknownHostKey(profile.host, fp, pinned != null)
                }

                override fun findExistingAlgorithms(hostname: String, port: Int): List<String> = emptyList()
            })
            try {
                client.connect(profile.host.trim(), profile.port)
                client.connection.keepAlive.keepAliveInterval = 15
                if (profile.auth == "password") {
                    client.authPassword(profile.user.trim(), (password ?: "").toCharArray())
                } else {
                    client.authPublickey(profile.user.trim(), client.loadKeys(key.privateKey(), null, null))
                }
                val session = client.startSession()
                // No PTY: the connection carries a byte protocol, not a terminal session.
                val cmd = session.exec(profile.command)
                return SshLink(client, session, cmd)
            } catch (e: Exception) {
                runCatching { client.disconnect() }
                // sshj wraps the verifier's exception; surface ours.
                var cause: Throwable? = e
                while (cause != null) {
                    if (cause is UnknownHostKey) throw cause
                    cause = cause.cause
                }
                throw e
            }
        }
    }
}
