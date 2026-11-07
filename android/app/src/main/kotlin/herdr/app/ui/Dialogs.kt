package herdr.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import herdr.app.Agents
import herdr.app.Session
import herdr.app.Theme
import herdr.app.term.TerminalPool
import herdr.core.Space
import herdr.core.Tab

sealed interface DialogKind {
    /** [machine]: null for the Mac itself. */
    data class NewSpace(val machine: String?) : DialogKind
    data class RenameSpace(val space: Space) : DialogKind
    data class CloseSpace(val space: Space) : DialogKind
    data class RenameTab(val tab: Tab) : DialogKind
    data class Command(val space: String?) : DialogKind
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun Dialogs(session: Session, kind: DialogKind, onDismiss: () -> Unit) {
    when (kind) {
        is DialogKind.NewSpace -> {
            var machine by remember { mutableStateOf(kind.machine) }
            var folder by remember { mutableStateOf("") }
            var name by remember { mutableStateOf("") }
            var start by remember { mutableStateOf<String?>(null) }
            var found by remember(machine) { mutableStateOf<List<String>>(emptyList()) }
            LaunchedEffect(machine) { found = runCatching { session.installedAgents(machine) }.getOrDefault(emptyList()) }
            AlertDialog(
                onDismissRequest = onDismiss,
                title = { Text("New space") },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        if (session.machines.isNotEmpty()) {
                            Text("Machine", color = Theme.dim, fontSize = 12.sp)
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                FilterChip(selected = machine == null, onClick = { machine = null }, label = { Text(session.machineLabel(null)) })
                                for (m in session.machines) FilterChip(selected = machine == m.name, onClick = { machine = m.name }, label = { Text(m.name) })
                            }
                        }
                        Field("Folder", folder, { folder = it }, if (machine == null) "~/src/project (empty: home)" else "empty: that machine's home", mono = false)
                        Field("Name", name, { name = it }, "optional: follows its folder", mono = false)
                        Text("Start with", color = Theme.dim, fontSize = 12.sp)
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            FilterChip(selected = start == null, onClick = { start = null }, label = { Text("Terminal") })
                            for (cmd in found) FilterChip(selected = start == cmd, onClick = { start = cmd }, label = { Text(Agents.nameOf(cmd)) })
                        }
                    }
                },
                confirmButton = {
                    TextButton(onClick = {
                        val dir = folder.trim().let { f -> if (f.startsWith("~/") && machine == null) session.home?.let { it + f.substring(1) } ?: f else f }
                        session.newSpace(machine, dir, name, start?.let { Agents.launch(listOf(it)) })
                        onDismiss()
                    }) { Text("Create") }
                },
                dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
            )
        }
        is DialogKind.RenameSpace -> Rename("Rename space", kind.space.name, "Empty: named after its folder", onDismiss) { session.renameSpace(kind.space.id, it) }
        is DialogKind.RenameTab -> Rename("Rename tab", kind.tab.name, "Empty: numbered", onDismiss) { session.renameTab(kind.tab.id, it) }
        is DialogKind.CloseSpace -> AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text("Close ${session.spaceName(kind.space)}?") },
            text = { Text("This ends every terminal in its ${kind.space.tabs.size} tab(s) on ${session.machineLabel(kind.space.machine)}, on every screen.") },
            confirmButton = { TextButton(onClick = { session.closeSpace(kind.space.id); onDismiss() }) { Text("Close space") } },
            dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
        )
        is DialogKind.Command -> {
            var line by remember { mutableStateOf("") }
            AlertDialog(
                onDismissRequest = onDismiss,
                title = { Text("Run a command in a new tab") },
                text = { Field("Command", line, { line = it }, "npm run dev", mono = true) },
                confirmButton = {
                    TextButton(enabled = line.isNotBlank(), onClick = {
                        kind.space?.let { session.newTab(it, Agents.launch(listOf("sh", "-c", line))) }
                        onDismiss()
                    }) { Text("Run") }
                },
                dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
            )
        }
    }
}

@Composable
private fun Rename(title: String, current: String, hint: String, onDismiss: () -> Unit, onSave: (String) -> Unit) {
    var name by remember { mutableStateOf(current) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Field("Name", name, { name = it }, hint) },
        confirmButton = { TextButton(onClick = { onSave(name); onDismiss() }) { Text("Save") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
fun SettingsDialog(session: Session, pool: TerminalPool, onDismiss: () -> Unit, onEditConnection: () -> Unit) {
    val clipboard = LocalClipboardManager.current
    var font by remember { mutableFloatStateOf(pool.fontSize.toFloat()) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Settings") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                val p = session.profile
                Text("${p.user}@${p.host}:${p.port}", fontFamily = FontFamily.Monospace, fontSize = 13.sp)
                Text("herdr-core ${session.coreVersion.ifEmpty { "–" }} · ${session.terminals.size} live terminals", color = Theme.dim, fontSize = 12.sp)
                Text("Terminal text: ${font.toInt()} px", fontSize = 13.sp)
                Slider(value = font, onValueChange = { font = it }, valueRange = 8f..24f, steps = 15, onValueChangeFinished = {
                    pool.fontSize = font.toInt()
                    session.saveFont(font.toInt())
                })
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.fillMaxWidth()) {
                    TextButton(onClick = { clipboard.setText(AnnotatedString(session.deviceKey.publicKey())) }) { Text("Copy public key") }
                    TextButton(onClick = onEditConnection) { Text("Edit connection") }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    TextButton(onClick = { session.disconnect(); session.connect() }) { Text("Reconnect") }
                    TextButton(onClick = { session.disconnect(); onEditConnection() }) { Text("Disconnect") }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Done") } },
    )
}
