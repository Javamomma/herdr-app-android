package herdr.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import herdr.app.Session
import herdr.app.Theme
import herdr.app.term.TerminalPool
import kotlinx.coroutines.launch
import herdr.core.FilePart
import herdr.core.Reading
import herdr.core.machineOf
import herdr.term.Clip

/** Files or Changes for the focused terminal's folder, read through the core on its machine. */
@Composable
fun SidePanel(session: Session, pool: TerminalPool, kind: String, onClose: () -> Unit) {
    val address = session.focusedTerminal()
    val space = session.spaces.firstOrNull { it.id == session.activeSpace }
    // A file from the queue is on the core's own machine, whatever terminal is focused.
    // Only Files, only for a queue item: Changes and every other way in use the focused terminal's machine.
    val local = session.filesLocal && kind == "files"
    androidx.compose.runtime.DisposableEffect(Unit) { onDispose { session.filesLocal = false } }
    LaunchedEffect(kind) { if (kind != "files") session.filesLocal = false }
    val machine = if (local) null else address?.let { machineOf(it) } ?: space?.machine
    val start = (if (local) null else session.cwdOf(address) ?: space?.folder?.ifEmpty { null }) ?: session.home ?: "/"
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(start = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(if (kind == "files") "Files" else "Changes", color = Theme.text, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
            IconButton(onClick = onClose) { Icon(Icons.Default.Close, "Close", tint = Theme.dim) }
        }
        if (kind == "files") Files(session, pool, machine, start, if (local) null else address) else Changes(session, machine, start)
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
        // From the queue the path is exact (a colon is part of it); from a terminal "path:line" is cut at the colon.
        val exact = session.fileExact
        session.fileExact = false
        val path = (if (exact) f else f.substringBefore(':')).let { p -> if (p.startsWith("/")) p else if (p.startsWith("~/") && session.home != null) session.home + p.substring(1) else join(start, p.removePrefix("./")) }
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
        FileViewer(session, machine, file)
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
                    Reading.addedDiff(session.readFile(machine, join(cwd, file)))
                }
            }.fold({ Load.Done(it) }, { Load.Failed(it.message ?: "cannot diff") })
        }
        ViewBar(session, onCopyAll = null, copied = false)
        Loaded(diff) { text ->
            TextViewer(text, session.wrapLines, session.readerFontSp(), colorOf = DIFF_COLOURS)
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

/** The +/- colouring of a diff, per line. */
private val DIFF_COLOURS: (String) -> Color = { line ->
    when {
        line.startsWith("+++") || line.startsWith("---") -> Theme.dim
        line.startsWith("+") -> Color(0xFF98C379)
        line.startsWith("-") -> Color(0xFFE06C75)
        line.startsWith("@@") -> Color(0xFF61AFEF)
        else -> Theme.text
    }
}

/** A file: wrapped (or sideways) monospace text you can select, a notice when the core cut it, "Load more", and no garbage for binary. */
@Composable
private fun FileViewer(session: Session, machine: String?, file: String) {
    var part by remember(file, machine) { mutableStateOf<Load<FilePart>>(Load.Busy) }
    var more by remember(file, machine) { mutableStateOf<String?>(null) }
    var loadingMore by remember(file, machine) { mutableStateOf(false) }
    var restart by remember(file, machine) { mutableIntStateOf(0) }
    var copied by remember(file, machine) { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val clipboard = LocalClipboardManager.current
    LaunchedEffect(file, machine, restart) {
        part = Load.Busy
        part = runCatching { session.readFile(machine, file) }.fold({ Load.Done(it) }, { Load.Failed(it.message ?: "cannot read") })
    }
    val done = (part as? Load.Done)?.value
    val binary = done != null && Reading.looksBinary(done.text)
    if (done != null && !binary) ViewBar(session, onCopyAll = { clipboard.setText(AnnotatedString(Clip.limit(done.text).text)); copied = true }, copied = copied)
    Loaded(part) { p ->
        if (binary) {
            Text(Reading.BINARY_NOTICE, color = Theme.dim, fontSize = 13.sp, modifier = Modifier.padding(12.dp))
            return@Loaded
        }
        Column(Modifier.fillMaxSize()) {
            if (p.text.isEmpty()) Text("Empty file.", color = Theme.dim, fontSize = 13.sp, modifier = Modifier.padding(12.dp))
            else Box(Modifier.weight(1f)) { TextViewer(p.text, session.wrapLines, session.readerFontSp()) }
            Reading.cutNotice(p)?.let { notice ->
                Row(Modifier.fillMaxWidth().background(Theme.panel).padding(start = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(more ?: notice, color = Theme.dim, fontSize = 12.sp, modifier = Modifier.weight(1f).padding(vertical = 6.dp))
                    Reading.nextOffset(p)?.let { next ->
                        TextButton(enabled = !loadingMore, onClick = {
                            loadingMore = true
                            more = null
                            scope.launch {
                                val r = runCatching { session.readFile(machine, file, next) }
                                loadingMore = false
                                r.onSuccess { m ->
                                    val joined = Reading.append(p, m)
                                    if (joined == null) restart++ else part = Load.Done(joined)
                                }.onFailure { more = "Could not load more: ${it.message}" }
                            }
                        }) { Text(if (loadingMore) "Loading…" else "Load more") }
                    }
                }
            }
        }
    }
}
