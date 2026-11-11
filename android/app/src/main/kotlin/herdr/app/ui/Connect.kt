package herdr.app.ui

import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import herdr.app.Conn
import herdr.app.Session
import herdr.app.Theme
import herdr.app.ssh.Profile

/** Where to find the Mac, and how to sign in. */
@Composable
fun ConnectScreen(session: Session, onDone: (() -> Unit)? = null) {
    val p = session.profile
    var host by remember { mutableStateOf(p.host) }
    var port by remember { mutableStateOf(p.port.toString()) }
    var user by remember { mutableStateOf(p.user) }
    var auth by remember { mutableStateOf(p.auth) }
    var password by remember { mutableStateOf(session.secrets.get("password") ?: "") }
    var command by remember { mutableStateOf(p.command) }
    var advanced by remember { mutableStateOf(false) }
    val conn = session.conn
    val clipboard = LocalClipboardManager.current
    val context = LocalContext.current
    val publicKey = remember { runCatching { session.deviceKey.publicKey() }.getOrElse { "(cannot make a key: ${it.message})" } }

    fun connect(accept: String? = null) {
        session.saveProfile(Profile(host.trim(), port.toIntOrNull() ?: 22, user.trim(), auth, command), if (auth == "password") password else null)
        session.disconnect()
        session.connect(accept)
        onDone?.invoke()
    }

    Box(Modifier.fillMaxSize().background(Theme.bg).safeDrawingPadding(), contentAlignment = Alignment.TopCenter) {
        Column(
            Modifier.widthIn(max = 560.dp).fillMaxWidth().verticalScroll(rememberScrollState()).padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("Herdr", color = Theme.accent, fontSize = 30.sp, fontWeight = FontWeight.Bold)
            Text(
                "Connects to herdr-core on your Mac over SSH and shows the same spaces, tabs and agents as Herdr there. Terminals keep running on the Mac.",
                color = Theme.dim, fontSize = 14.sp,
            )
            Field("Mac address", host, { host = it }, "mac-mini.local, 192.168.1.20 or a Tailscale name")
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Box(Modifier.weight(2f)) { Field("Mac user name", user, { user = it }, "what `whoami` prints on the Mac") }
                Box(Modifier.weight(1f)) { Field("Port", port, { port = it.filter(Char::isDigit) }, null, KeyboardType.Number) }
            }
            Text("Sign in with", color = Theme.text, fontSize = 14.sp)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(selected = auth == "key", onClick = { auth = "key" }, label = { Text("This phone's key") })
                FilterChip(selected = auth == "password", onClick = { auth = "password" }, label = { Text("Password") })
            }
            if (auth == "key") {
                Text("Add this key to ~/.ssh/authorized_keys on the Mac:", color = Theme.dim, fontSize = 13.sp)
                Text(
                    publicKey, color = Theme.text, fontFamily = FontFamily.Monospace, fontSize = 11.sp,
                    modifier = Modifier.fillMaxWidth().background(Theme.panel, RoundedCornerShape(6.dp)).padding(10.dp),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { clipboard.setText(AnnotatedString(publicKey)) }) { Text("Copy key") }
                    OutlinedButton(onClick = {
                        val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, publicKey)
                        context.startActivity(Intent.createChooser(send, "Send the key to your Mac"))
                    }) { Text("Share…") }
                }
                Text(
                    "On the Mac: echo '<the key>' >> ~/.ssh/authorized_keys",
                    color = Theme.dim, fontFamily = FontFamily.Monospace, fontSize = 11.sp,
                )
            } else {
                Field("Password", password, { password = it }, "kept encrypted on this phone", KeyboardType.Password, secret = true)
            }
            TextButton(onClick = { advanced = !advanced }) { Text(if (advanced) "Hide advanced" else "Advanced") }
            if (advanced) {
                Field("Command run on the Mac", command, { command = it }, "starts herdr-core's connection", mono = true)
                TextButton(onClick = { command = Profile.DEFAULT_COMMAND }) { Text("Reset command") }
            }
            when (conn) {
                is Conn.Connecting -> Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = Theme.accent)
                    Spacer(Modifier.size(10.dp))
                    Text(conn.step, color = Theme.dim)
                }
                is Conn.Failed -> if (conn.hostKey == null) Text(
                    conn.message + if (conn.retrying) "\n\nTrying again…" else "",
                    color = Theme.stateColor("blocked"), fontSize = 13.sp,
                )
                else -> {}
            }
            Spacer(Modifier.height(4.dp))
            Button(
                onClick = { connect() }, enabled = host.isNotBlank() && user.isNotBlank() && conn !is Conn.Connecting,
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Connect") }
            OutlinedButton(onClick = { session.connectDemo(); onDone?.invoke() }, modifier = Modifier.fillMaxWidth()) {
                Text("Try the demo (no Mac needed)")
            }
            Help()
        }
    }

    val hk = (conn as? Conn.Failed)?.hostKey
    if (hk != null) {
        AlertDialog(
            onDismissRequest = { session.disconnect() },
            title = { Text(if (hk.changed) "Host key changed!" else "Trust this Mac?") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        if (hk.changed) "${hk.host} now shows a different host key. If you didn't reinstall or replace the Mac, don't connect."
                        else "First connection to ${hk.host}. Check that this matches the Mac (run: ssh-keygen -lf /etc/ssh/ssh_host_ed25519_key.pub):",
                    )
                    Text(hk.fingerprint, fontFamily = FontFamily.Monospace, fontSize = 12.sp)
                }
            },
            confirmButton = { TextButton(onClick = { connect(hk.fingerprint) }) { Text(if (hk.changed) "Trust the new key" else "Trust and connect") } },
            dismissButton = { TextButton(onClick = { session.disconnect() }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun Help() {
    Column(
        Modifier.fillMaxWidth().background(Theme.panel, RoundedCornerShape(8.dp)).padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text("On the Mac mini", color = Theme.text, fontWeight = FontWeight.SemiBold)
        Text("1. System Settings → General → Sharing → turn on Remote Login.", color = Theme.dim, fontSize = 13.sp)
        Text("2. herdr-core installed (the Herdr app installs it): `hc --version` works in Terminal.", color = Theme.dim, fontSize = 13.sp)
        Text("3. Away from home: install Tailscale on the Mac and this phone, and use the Mac's Tailscale name.", color = Theme.dim, fontSize = 13.sp)
        Text("Your spaces, tabs and splits are the Mac's: the desktop app and this phone show the same ones, live.", color = Theme.dim, fontSize = 13.sp)
    }
}

@Composable
fun Field(
    label: String, value: String, onChange: (String) -> Unit, hint: String?, type: KeyboardType = KeyboardType.Text,
    secret: Boolean = false, mono: Boolean = false,
) {
    OutlinedTextField(
        value = value, onValueChange = onChange, label = { Text(label) },
        placeholder = hint?.let { { Text(it, fontSize = 13.sp) } },
        singleLine = !mono,
        visualTransformation = if (secret) PasswordVisualTransformation() else androidx.compose.ui.text.input.VisualTransformation.None,
        keyboardOptions = KeyboardOptions(keyboardType = type, autoCorrectEnabled = false),
        textStyle = androidx.compose.ui.text.TextStyle(fontFamily = if (mono) FontFamily.Monospace else FontFamily.Default, fontSize = if (mono) 12.sp else 16.sp),
        modifier = Modifier.fillMaxWidth(),
    )
}
