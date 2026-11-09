package herdr.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import herdr.app.Session
import herdr.app.Theme
import herdr.app.term.TerminalPool
import herdr.core.machineOf

/** Files or Changes for the focused terminal's folder, read through the core on its machine. */
@Composable
fun SidePanel(session: Session, pool: TerminalPool, kind: String, onClose: () -> Unit) {
    val address = session.focusedTerminal()
    val space = session.spaces.firstOrNull { it.id == session.activeSpace }
    val machine = address?.let { machineOf(it) } ?: space?.machine
    val start = session.cwdOf(address) ?: space?.folder?.ifEmpty { null } ?: session.home ?: "/"
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(start = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(if (kind == "files") "Files" else "Changes", color = Theme.text, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
            IconButton(onClick = onClose) { Icon(Icons.Default.Close, "Close", tint = Theme.dim) }
        }
        if (kind == "files") Files(session, pool, machine, start, address) else Changes(session, machine, start)
    }
}

private sealed interface Load<out T> {
    data object Busy : Load<Nothing>
    data class Done<T>(val value: T) : Load<T>
    data class Failed(val message: String) : Load<Nothing>
}

@Composable
private fun <T> Loaded(state: Load<T>, content: @Composable (T) -> Unit) {
    when (state) {
        Load.Busy -> Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) {
            CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp, color = Theme.accent)
        }
        is Load.Failed -> Text(state.message, color = Theme.stateColor("blocked"), fontSize = 12.sp, modifier = Modifier.padding(12.dp))
        is Load.Done -> content(state.value)
    }
}

private fun join(dir: String, name: String) = if (dir.endsWith("/")) dir + name else "$dir/$name"

private fun quote(s: String) = "'" + s.replace("'", "'\\''") + "'"

@Composable
private fun Files(session: Session, pool: TerminalPool, machine: String?, start: String, address: String?) {
    var dir by remember(start, machine) { mutableStateOf(start) }
    var open by remember(start, machine) { mutableStateOf<String?>(null) }
    // A path tapped in a terminal: relative ones start at the terminal's folder.
    LaunchedEffect(session.fileToOpen) {
        val f = session.fileToOpen ?: return@LaunchedEffect
        session.fileToOpen = null
        val path = f.substringBefore(':').let { p -> if (p.startsWith("/")) p else if (p.startsWith("~/") && session.home != null) session.home + p.substring(1) else join(start, p.removePrefix("./")) }
        dir = path.substringBeforeLast('/').ifEmpty { "/" }
        open = path
    }
    var reload by remember { mutableIntStateOf(0) }
    var list by remember { mutableStateOf<Load<List<String>>>(Load.Busy) }
    LaunchedEffect(dir, machine, reload) {
        list = Load.Busy
        list = runCatching { session.exec(machine, listOf("ls", "-1Ap", dir)).lines().filter { it.isNotBlank() } }
            .fold({ Load.Done(it.sortedWith(compareBy({ !it.endsWith("/") }, { it.lowercase() }))) }, { Load.Failed(it.message ?: "cannot list") })
    }
    Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = { if (open != null) open = null else dir = dir.trimEnd('/').substringBeforeLast('/').ifEmpty { "/" } }) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, "Up", tint = Theme.dim)
        }
        Text(
            open ?: dir, color = Theme.dim, fontSize = 11.sp, fontFamily = FontFamily.Monospace, maxLines = 1,
            overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f),
        )
        IconButton(onClick = { reload++ }) { Icon(Icons.Default.Refresh, "Reload", tint = Theme.dim) }
    }
    if (open == null && address != null) {
        TextButton(onClick = { pool.get(address)?.type("cd ${quote(dir)}\r") }, modifier = Modifier.padding(start = 8.dp)) { Text("cd here in the terminal") }
    }
    val file = open
    if (file != null) {
        var text by remember(file) { mutableStateOf<Load<String>>(Load.Busy) }
        LaunchedEffect(file) { text = runCatching { session.readFile(machine, file) }.fold({ Load.Done(it) }, { Load.Failed(it.message ?: "cannot read") }) }
        Loaded(text) { CodeView(it.lines()) { Theme.text } }
        return
    }
    Loaded(list) { entries ->
        LazyColumn(Modifier.fillMaxSize()) {
            items(entries) { e ->
                val isDir = e.endsWith("/")
                Text(
                    (if (isDir) "▸ " else "  ") + e, color = if (isDir) Theme.text else Theme.dim, fontSize = 13.sp,
                    fontFamily = FontFamily.Monospace, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.fillMaxWidth().clickable { if (isDir) { dir = join(dir, e.trimEnd('/')); open = null } else open = join(dir, e) }
                        .padding(horizontal = 12.dp, vertical = 7.dp),
                )
            }
        }
    }
}

@Composable
private fun Changes(session: Session, machine: String?, cwd: String) {
    var reload by remember { mutableIntStateOf(0) }
    var open by remember(cwd) { mutableStateOf<String?>(null) }
    var status by remember { mutableStateOf<Load<List<String>>>(Load.Busy) }
    LaunchedEffect(cwd, machine, reload) {
        status = Load.Busy
        status = runCatching { session.exec(machine, listOf("git", "-C", cwd, "status", "--porcelain=v1", "-b")).lines().filter { it.isNotBlank() } }
            .fold({ Load.Done(it) }, { Load.Failed(if (it.message?.contains("not a git repository") == true) "Not in a git repository." else it.message ?: "git failed") })
    }
    Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        if (open != null) IconButton(onClick = { open = null }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = Theme.dim) }
        Text(open ?: cwd, color = Theme.dim, fontSize = 11.sp, fontFamily = FontFamily.Monospace, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f).padding(start = 8.dp))
        IconButton(onClick = { reload++ }) { Icon(Icons.Default.Refresh, "Reload", tint = Theme.dim) }
    }
    val file = open
    if (file != null) {
        var diff by remember(file, reload) { mutableStateOf<Load<String>>(Load.Busy) }
        LaunchedEffect(file, reload) {
            diff = runCatching {
                session.exec(machine, listOf("git", "-C", cwd, "diff", "HEAD", "--", file)).ifBlank {
                    session.readFile(machine, join(cwd, file)).lines().joinToString("\n") { "+$it" }
                }
            }.fold({ Load.Done(it) }, { Load.Failed(it.message ?: "cannot diff") })
        }
        Loaded(diff) { text ->
            CodeView(text.lines()) { line ->
                when {
                    line.startsWith("+++") || line.startsWith("---") -> Theme.dim
                    line.startsWith("+") -> Color(0xFF98C379)
                    line.startsWith("-") -> Color(0xFFE06C75)
                    line.startsWith("@@") -> Color(0xFF61AFEF)
                    else -> Theme.text
                }
            }
        }
        return
    }
    Loaded(status) { lines ->
        LazyColumn(Modifier.fillMaxSize()) {
            items(lines) { l ->
                if (l.startsWith("##")) {
                    Text(l.removePrefix("## "), color = Theme.accent, fontSize = 12.sp, fontFamily = FontFamily.Monospace, modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp))
                } else {
                    val code = l.take(2)
                    val path = l.drop(3).substringAfter(" -> ").trim('"')
                    Row(Modifier.fillMaxWidth().clickable { open = path }.padding(horizontal = 12.dp, vertical = 7.dp)) {
                        Text(code, color = if ('?' in code) Theme.dim else Theme.accent, fontSize = 13.sp, fontFamily = FontFamily.Monospace)
                        Spacer(Modifier.width(10.dp))
                        Text(path, color = Theme.text, fontSize = 13.sp, fontFamily = FontFamily.Monospace, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
            if (lines.none { !it.startsWith("##") }) item { Text("No changes.", color = Theme.dim, fontSize = 12.sp, modifier = Modifier.padding(12.dp)) }
        }
    }
}

@Composable
private fun CodeView(lines: List<String>, color: (String) -> Color) {
    val h = rememberScrollState()
    LazyColumn(Modifier.fillMaxSize().background(Theme.bg).horizontalScroll(h)) {
        items(lines) { line ->
            Text(
                line.ifEmpty { " " }, color = color(line), fontSize = 12.sp, fontFamily = FontFamily.Monospace,
                softWrap = false, modifier = Modifier.padding(horizontal = 10.dp),
            )
        }
    }
}
