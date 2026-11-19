package herdr.app.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import herdr.app.Conn
import herdr.app.Session
import herdr.app.Theme
import herdr.core.Attach

/** "Uploading photo…" with a way out, above the message box while a photo goes to [address]. */
@Composable
fun AttachProgress(session: Session, address: String) {
    if (session.attaching != address) return
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp, color = Theme.accent)
        Text(if (session.attachCancelling) "Stopping the upload…" else "Uploading photo…", color = Theme.text, fontSize = 13.sp, modifier = Modifier.padding(start = 8.dp).weight(1f))
        if (!session.attachCancelling) TextButton(onClick = { session.cancelAttach() }) { Text("Cancel upload") }
    }
}

/**
 * A picture shared to Herdr: once connected (and unlocked: this is only drawn inside the app), asks which
 * agent it is for, the last one opened preselected. Not connected: says so and drops the picture.
 */
@Composable
fun ShareChooser(session: Session) {
    val uri = session.sharedPhoto ?: return
    val connected = session.conn is Conn.Connected
    if (!connected) return
    val agents = session.agents()
    if (agents.isEmpty()) {
        LaunchedEffect(uri) { session.notice = "No agents on the Mac to send the photo to."; session.sharedPhoto = null }
        return
    }
    val addresses = agents.map { it.address }
    var picked by remember(uri) { mutableStateOf(Attach.defaultTarget(addresses, session.lastAgent, session.focusedTerminal())) }
    AlertDialog(
        onDismissRequest = { session.sharedPhoto = null },
        title = { Text("Send photo to…") },
        text = {
            Column(Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                for (a in agents) {
                    Row(Modifier.fillMaxWidth().clickable { picked = a.address }, verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(selected = picked == a.address, onClick = { picked = a.address })
                        Text(a.title.ifBlank { a.app.ifBlank { "terminal" } } + "  ·  " + a.address, fontSize = 14.sp, maxLines = 1, modifier = Modifier.width(220.dp))
                    }
                }
            }
        },
        confirmButton = {
            TextButton(enabled = picked != null, onClick = {
                val to = picked ?: return@TextButton
                session.sharedPhoto = null
                if (!session.canAttach) { session.notice = if (session.attaching != null) "Another photo is still uploading." else Attach.Failure.Unsupported.notice; return@TextButton }
                agents.firstOrNull { it.address == to }?.let { session.showAgent(it) }
                session.attachPhoto(uri, to) { path -> session.insertAttached(to, path) }
            }) { Text("Upload") }
        },
        dismissButton = { TextButton(onClick = { session.sharedPhoto = null }) { Text("Cancel") } },
    )
}
