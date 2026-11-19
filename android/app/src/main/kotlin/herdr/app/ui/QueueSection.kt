package herdr.app.ui

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import herdr.app.Session
import herdr.app.Theme
import herdr.core.Queue
import herdr.core.QueueItem
import herdr.core.QueueTarget
import kotlinx.coroutines.delay

/**
 * What is waiting on the person, read from a command run on the Mac (Settings). Read-only: it opens things and
 * shows text; it runs nothing. Item text is untrusted and is only ever drawn as plain text. Nothing shows when the
 * command is cleared. Refreshes while it is on screen and the app is in front, never in the background.
 */
@Composable
fun QueueSection(session: Session, onScreen: Boolean, onOpened: () -> Unit, modifier: Modifier = Modifier) {
    if (session.queueCommand.isBlank()) return
    val state = session.queue
    val connected = session.conn is herdr.app.Conn.Connected
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    // Runs only while the section is really drawn ([onScreen]: drawer open, panel shown, agents home, nothing over it) and the app is resumed.
    LaunchedEffect(session.queueCommand, connected, onScreen) {
        if (!onScreen) return@LaunchedEffect
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            var first = true
            while (true) {
                now = System.currentTimeMillis()
                // Back on screen: refresh unless just tried; then every three minutes.
                if (session.queueDue(if (first) Queue.SHOW_MIN_MS else Queue.REFRESH_MS)) session.refreshQueue()
                first = false
                delay(15_000)
            }
        }
    }
    val context = LocalContext.current
    val open = session.queueOpen
    var shown by remember { mutableStateOf<String?>(null) }
    Column(modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth().clickable { session.updateQueueOpen(!open) }.padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                Queue.heading(state) + if (state.stale) " · stale" else "",
                color = if (state.items.isNotEmpty()) Theme.text else Theme.dim, fontSize = 13.sp, maxLines = 1,
                overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f),
            )
            if (state.loading) CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp, color = Theme.accent)
            Icon(if (open) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown, if (open) "Fold the queue" else "Open the queue", tint = Theme.dim, modifier = Modifier.padding(start = 6.dp).size(18.dp))
        }
        if (open) {
            val why = state.failure
            Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
                if (why != null) Text("Couldn't load the queue: ${why.message}", color = Theme.stateColor("blocked"), fontSize = 12.sp, maxLines = 4, overflow = TextOverflow.Ellipsis)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = { session.requestQueueRefresh() }, enabled = !state.loading) { Text(if (why != null) "Retry" else "Refresh") }
                    if (state.stale) Text("Showing the last list", color = Theme.dim, fontSize = 11.sp)
                }
            }
            state.loadedAt?.let {
                Text(Queue.updatedText(it, now) + if (state.stale) " (out of date)" else "", color = Theme.dim, fontSize = 11.sp, modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp))
            }
            for (item in state.items) QueueRow(item, expanded = shown == item.id, onTap = {
                when (val t = Queue.target(item)) {
                    is QueueTarget.Url -> openUrl(context, session, t.url)
                    is QueueTarget.File -> { session.openQueueFile(t.path); onOpened() }
                    is QueueTarget.Detail -> shown = if (shown == item.id) null else item.id
                }
            })
        }
    }
}

@Composable
private fun QueueRow(item: QueueItem, expanded: Boolean, onTap: () -> Unit) {
    val target = remember(item) { Queue.target(item) }
    Column(Modifier.fillMaxWidth().clickable(onClick = onTap).padding(horizontal = 16.dp, vertical = 6.dp)) {
        Text(item.title, color = Theme.text, fontSize = 13.sp, maxLines = if (expanded) 6 else 2, overflow = TextOverflow.Ellipsis)
        val meta = listOfNotNull(
            item.kind.ifBlank { null }, item.source.ifBlank { null }, Queue.ageText(item),
            item.expiresInDays?.takeIf { it >= 0 }?.let { "expires in $it d" },
        ).joinToString(" · ")
        if (meta.isNotEmpty()) Text(meta, color = Theme.dim, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        if (expanded && target is QueueTarget.Detail) {
            Text(target.text, color = Theme.dim, fontSize = 12.sp, modifier = Modifier.padding(top = 4.dp))
        }
    }
}

/** http and https only (checked again here); a VIEW intent hands it to the browser or the app that owns it. */
private fun openUrl(context: android.content.Context, session: Session, url: String) {
    if (!Queue.openable(url)) return
    try {
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    } catch (_: ActivityNotFoundException) {
        session.notice = "Nothing on this phone can open that link"
    } catch (_: SecurityException) {
        session.notice = "Couldn't open that link"
    }
}
