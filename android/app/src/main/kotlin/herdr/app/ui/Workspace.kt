package herdr.app.ui

import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.FullscreenExit
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.SwapVert
import androidx.compose.material.icons.filled.ViewColumn
import androidx.compose.material.icons.filled.ViewStream
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import herdr.app.Agent
import herdr.app.Conn
import herdr.app.Session
import herdr.app.Theme
import herdr.app.term.TerminalPool
import herdr.core.Axis
import herdr.core.Space
import herdr.core.Tab
import herdr.core.activeChild
import herdr.core.children
import herdr.core.find
import herdr.core.machineOf
import herdr.core.modName
import herdr.core.nodeId
import herdr.core.panes
import herdr.core.str
import herdr.core.get
import herdr.core.terminal
import herdr.core.type
import herdr.core.weight
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject

/** Unfolded (the Fold's inner screen, a tablet): sidebar beside the work. Folded: one column and a drawer. */
private val WIDE = 600.dp
/** Wide enough for the Files/Changes panel beside the panes too. */
private val WIDER = 900.dp

@Composable
fun HerdrRoot(session: Session, pool: TerminalPool) {
    var editing by remember { mutableStateOf(false) }
    LaunchedEffect(session.generation, session.terminals) { pool.reattach() }
    val showConnect = editing || !session.profile.ready ||
        (session.spaces.isEmpty() && session.conn !is Conn.Connected)
    androidx.compose.material3.MaterialTheme(colorScheme = androidx.compose.material3.darkColorScheme(
        primary = Theme.accent, background = Theme.bg, surface = Theme.panel, onSurface = Theme.text, onBackground = Theme.text,
        surfaceVariant = Theme.raised, outline = Theme.border,
    )) {
        if (showConnect) {
            ConnectScreen(session, onDone = { editing = false })
        } else {
            Workspace(session, pool, onEditConnection = { editing = true })
        }
    }
}

@Composable
fun Workspace(session: Session, pool: TerminalPool, onEditConnection: () -> Unit) {
    val snack = remember { SnackbarHostState() }
    LaunchedEffect(session.notice) {
        session.notice?.let {
            snack.showSnackbar(it)
            session.notice = null
        }
    }
    var dialog by remember { mutableStateOf<DialogKind?>(null) }
    var panel by remember { mutableStateOf<String?>(null) }
    var settings by remember { mutableStateOf(false) }

    // What's on screen counts as seen (shared with the desktop as herdr.seen).
    val space = session.spaces.firstOrNull { it.id == session.activeSpace }
    val tab = space?.tabs?.firstOrNull { it.id == session.activeTab[space.id] }
    LaunchedEffect(tab, session.peek, session.zoomed[tab?.id]) {
        val shown = tab?.let { t -> session.zoomed[t.id]?.let { z -> listOfNotNull(t.root.find(z)?.terminal) } ?: t.root.panes().mapNotNull { it.terminal } }.orEmpty()
        session.visible = (shown + listOfNotNull(session.peek)).toSet()
    }

    BoxWithConstraints(Modifier.fillMaxSize().background(Theme.bg).safeDrawingPadding()) {
        val wide = maxWidth >= WIDE
        val wider = maxWidth >= WIDER
        val drawer = rememberDrawerState(DrawerValue.Closed)
        val scope = rememberCoroutineScope()
        val sidebar: @Composable () -> Unit = {
            Sidebar(
                session,
                onPicked = { scope.launch { drawer.close() } },
                onNewSpace = { dialog = DialogKind.NewSpace(it) },
                onRenameSpace = { dialog = DialogKind.RenameSpace(it) },
                onCloseSpace = { dialog = DialogKind.CloseSpace(it) },
                onSettings = { settings = true },
            )
        }
        val main: @Composable () -> Unit = {
            Column(Modifier.fillMaxSize().imePadding()) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (!wide) IconButton(onClick = { scope.launch { drawer.open() } }) { Icon(Icons.Default.Menu, "Spaces", tint = Theme.text) }
                    Box(Modifier.weight(1f)) {
                        TabBar(session, space, wide, onRename = { dialog = DialogKind.RenameTab(it) }, onCustom = { dialog = DialogKind.Command(space?.id) })
                    }
                    IconButton(onClick = { panel = if (panel == "files") null else "files" }) { Icon(Icons.Default.Folder, "Files", tint = if (panel == "files") Theme.accent else Theme.dim) }
                    IconButton(onClick = { panel = if (panel == "changes") null else "changes" }) { Icon(Icons.Default.SwapVert, "Changes", tint = if (panel == "changes") Theme.accent else Theme.dim) }
                }
                ConnectionBanner(session)
                Row(Modifier.weight(1f)) {
                    Box(Modifier.weight(1f).fillMaxHeight()) {
                        if (space == null || tab == null) {
                            Empty(session, onNewSpace = { dialog = DialogKind.NewSpace(null) })
                        } else {
                            TabView(session, pool, space, tab, wide)
                        }
                    }
                    if (panel != null && wider) {
                        Box(Modifier.width(340.dp).fillMaxHeight().background(Theme.panel)) {
                            SidePanel(session, pool, panel!!, onClose = { panel = null })
                        }
                    }
                }
                KeyBar(session, pool)
            }
        }
        if (wide) {
            Row(Modifier.fillMaxSize()) {
                Box(Modifier.width(if (wider) 280.dp else 240.dp).fillMaxHeight().background(Theme.panel)) { sidebar() }
                Box(Modifier.width(1.dp).fillMaxHeight().background(Theme.border))
                Box(Modifier.weight(1f)) { main() }
            }
        } else {
            ModalNavigationDrawer(drawerState = drawer, drawerContent = {
                ModalDrawerSheet(drawerContainerColor = Theme.panel, modifier = Modifier.width(300.dp)) { sidebar() }
            }) { main() }
        }
        if (panel != null && !wider) {
            Dialog(onDismissRequest = { panel = null }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
                Box(Modifier.fillMaxSize().padding(12.dp).background(Theme.panel, RoundedCornerShape(10.dp))) {
                    SidePanel(session, pool, panel!!, onClose = { panel = null })
                }
            }
        }
        session.peek?.let { Peek(session, pool, it) }
        SnackbarHost(snack, Modifier.align(Alignment.BottomCenter).padding(bottom = 56.dp))
    }

    dialog?.let { Dialogs(session, it, onDismiss = { dialog = null }) }
    if (settings) SettingsDialog(session, pool, onDismiss = { settings = false }, onEditConnection = { settings = false; onEditConnection() })
}

@Composable
private fun ConnectionBanner(session: Session) {
    val c = session.conn
    val text = when (c) {
        is Conn.Connecting -> c.step
        is Conn.Failed -> c.message + if (c.retrying) " Reconnecting…" else ""
        Conn.Idle -> "Not connected."
        Conn.Connected -> null
    } ?: return
    Row(
        Modifier.fillMaxWidth().background(Theme.raised).clickable { session.connect() }.padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Dot(if (c is Conn.Failed) "blocked" else "working")
        Spacer(Modifier.width(8.dp))
        Text(text, color = Theme.text, fontSize = 12.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun Empty(session: Session, onNewSpace: () -> Unit) {
    Column(Modifier.fillMaxSize().padding(32.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
        Text(if (session.conn is Conn.Connected) "No spaces yet" else "Connecting…", color = Theme.text, fontSize = 18.sp)
        Spacer(Modifier.height(8.dp))
        Text("A space holds work on one machine, usually in one repo.", color = Theme.dim, fontSize = 13.sp)
        Spacer(Modifier.height(16.dp))
        androidx.compose.material3.Button(onClick = onNewSpace, enabled = session.conn is Conn.Connected) { Text("New space") }
    }
}

@Composable
fun Dot(state: String?, size: Dp = 8.dp) {
    val color = Theme.stateColor(state)
    Box(Modifier.size(size).background(color, CircleShape).let { if (color == Color.Transparent) it.border(1.dp, Theme.border, CircleShape) else it })
}

// ---- sidebar -----------------------------------------------------------------

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun Sidebar(
    session: Session, onPicked: () -> Unit, onNewSpace: (String?) -> Unit, onRenameSpace: (Space) -> Unit,
    onCloseSpace: (Space) -> Unit, onSettings: () -> Unit,
) {
    val agents = session.agents()
    val groups: List<String?> = listOf<String?>(null) + session.machines.map { it.name }.sorted()
    LazyColumn(Modifier.fillMaxSize().padding(vertical = 8.dp)) {
        item {
            Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Herdr", color = Theme.accent, fontWeight = FontWeight.Bold, fontSize = 18.sp, modifier = Modifier.weight(1f))
                Dot(if (session.conn is Conn.Connected) "idle" else "blocked")
                IconButton(onClick = onSettings) { Icon(Icons.Default.Settings, "Settings", tint = Theme.dim) }
            }
        }
        for (machine in groups) {
            val spaces = session.spaces.filter { it.machine == machine }
            item(key = "m:$machine") {
                Row(Modifier.fillMaxWidth().padding(start = 16.dp, top = 10.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(session.machineLabel(machine).uppercase(), color = Theme.dim, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                    IconButton(onClick = { onNewSpace(machine) }, modifier = Modifier.size(32.dp)) { Icon(Icons.Default.Add, "New space", tint = Theme.dim, modifier = Modifier.size(18.dp)) }
                }
                session.layoutErrors[machine ?: ""]?.let { Text(it, color = Theme.stateColor("blocked"), fontSize = 11.sp, modifier = Modifier.padding(horizontal = 16.dp)) }
            }
            items(spaces, key = { "s:${it.id}" }) { s ->
                var menu by remember { mutableStateOf(false) }
                val selected = s.id == session.activeSpace
                Box {
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 8.dp).background(if (selected) Theme.raised else Color.Transparent, RoundedCornerShape(6.dp))
                            .combinedClickable(onClick = { session.setActiveSpace(s.id); session.peek = null; onPicked() }, onLongClick = { menu = true })
                            .padding(horizontal = 10.dp, vertical = 9.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Dot(session.spaceState(s, agents))
                        Spacer(Modifier.width(10.dp))
                        Text(session.spaceName(s), color = Theme.text, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                        if (s.tabs.size > 1) Text("${s.tabs.size}", color = Theme.dim, fontSize = 11.sp)
                    }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        DropdownMenuItem(text = { Text("New tab") }, onClick = { menu = false; session.newTab(s.id, null) })
                        DropdownMenuItem(text = { Text("Rename space") }, onClick = { menu = false; onRenameSpace(s) })
                        DropdownMenuItem(text = { Text("Close space") }, onClick = { menu = false; onCloseSpace(s) })
                    }
                }
            }
        }
        item {
            HorizontalDivider(Modifier.padding(vertical = 12.dp), color = Theme.border)
            Text("AGENTS", color = Theme.dim, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp))
            if (agents.isEmpty()) Text("Agents that report status show here.", color = Theme.dim, fontSize = 12.sp, modifier = Modifier.padding(horizontal = 16.dp))
        }
        items(agents, key = { "a:${it.address}" }) { a -> AgentRow(session, a, onPicked) }
    }
}

@Composable
private fun AgentRow(session: Session, a: Agent, onPicked: () -> Unit) {
    val space = session.spaces.firstOrNull { it.id == a.spaceId }
    Row(
        Modifier.fillMaxWidth().clickable { session.showAgent(a); onPicked() }.padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Dot(a.state)
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(
                listOfNotNull(a.app, if (a.parent != null) "worker" else null).joinToString(" · "),
                color = Theme.text, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
            Text(
                listOfNotNull(a.state, space?.let { session.spaceName(it) }, a.title.ifBlank { null }).joinToString(" · "),
                color = Theme.dim, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

// ---- tabs ----------------------------------------------------------------------

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun TabBar(session: Session, space: Space?, wide: Boolean, onRename: (Tab) -> Unit, onCustom: () -> Unit) {
    if (space == null) {
        Text(" ", modifier = Modifier.padding(12.dp))
        return
    }
    val agents = session.agents()
    Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 6.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        if (!wide) {
            Text(session.spaceName(space), color = Theme.dim, fontSize = 13.sp, modifier = Modifier.padding(end = 8.dp))
        }
        for (t in space.tabs) {
            var menu by remember { mutableStateOf(false) }
            val selected = t.id == session.activeTab[space.id]
            val terms = t.root.panes().mapNotNull { it.terminal }.toSet()
            val state = agents.filter { it.address in terms || it.tabId == t.id }.map { it.state }.minByOrNull { herdr.app.URGENCY.indexOf(it) }
            Box {
                Row(
                    Modifier.padding(end = 6.dp).background(if (selected) Theme.raised else Theme.panel, RoundedCornerShape(14.dp))
                        .border(1.dp, if (selected) Theme.accent.copy(alpha = 0.6f) else Theme.border, RoundedCornerShape(14.dp))
                        .combinedClickable(onClick = { session.showTab(space.id, t.id); session.peek = null }, onLongClick = { menu = true })
                        .padding(horizontal = 12.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (state != null) {
                        Dot(state, 7.dp)
                        Spacer(Modifier.width(6.dp))
                    }
                    Text(session.tabName(space, t), color = if (selected) Theme.text else Theme.dim, fontSize = 13.sp, maxLines = 1)
                }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(text = { Text("Rename tab") }, onClick = { menu = false; onRename(t) })
                    DropdownMenuItem(text = { Text("Close tab") }, onClick = { menu = false; session.closeTab(t.id) })
                }
            }
        }
        NewMenu(session, space, onCustom)
    }
}

@Composable
private fun NewMenu(session: Session, space: Space, onCustom: () -> Unit) {
    var open by remember { mutableStateOf(false) }
    var found by remember(space.machine) { mutableStateOf<List<String>?>(null) }
    LaunchedEffect(open, space.machine) {
        if (open && found == null) found = runCatching { session.installedAgents(space.machine) }.getOrDefault(emptyList())
    }
    Box {
        IconButton(onClick = { open = true }, modifier = Modifier.size(34.dp)) { Icon(Icons.Default.Add, "New tab", tint = Theme.text) }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            DropdownMenuItem(text = { Text("Terminal") }, onClick = { open = false; session.newTab(space.id, null) })
            when (val f = found) {
                null -> DropdownMenuItem(text = { Text("Looking for agents…", color = Theme.dim) }, onClick = {}, enabled = false)
                else -> {
                    for (cmd in f) DropdownMenuItem(
                        text = { Text(herdr.app.Agents.nameOf(cmd)) },
                        onClick = { open = false; session.newTab(space.id, herdr.app.Agents.launch(listOf(cmd))) },
                    )
                }
            }
            DropdownMenuItem(text = { Text("Command…") }, onClick = { open = false; onCustom() })
        }
    }
}

// ---- panes ---------------------------------------------------------------------

@Composable
private fun TabView(session: Session, pool: TerminalPool, space: Space, tab: Tab, wide: Boolean) {
    val zoom = session.zoomed[tab.id]?.let { tab.root.find(it) }
    val panes = tab.root.panes()
    // Folded: one pane at a time, others a tap away.
    val single = zoom ?: if (!wide && panes.size > 1) (panes.firstOrNull { it.nodeId == session.focused[tab.id] } ?: panes.first()) else null
    Column(Modifier.fillMaxSize()) {
        if (!wide && panes.size > 1) {
            Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 6.dp)) {
                panes.forEachIndexed { i, p ->
                    val sel = p.nodeId == single?.nodeId
                    Text(
                        "${i + 1} ${paneTitle(session, pool, p)}", color = if (sel) Theme.text else Theme.dim, fontSize = 12.sp, maxLines = 1,
                        modifier = Modifier.padding(end = 6.dp).background(if (sel) Theme.raised else Color.Transparent, RoundedCornerShape(4.dp))
                            .clickable { session.focusPane(tab.id, p.nodeId) }.padding(horizontal = 8.dp, vertical = 3.dp),
                    )
                }
            }
        }
        Box(Modifier.fillMaxSize().padding(4.dp)) {
            if (single != null) PaneView(session, pool, space, tab, single, framed = panes.size > 1, zoomed = zoom != null)
            else NodeView(session, pool, space, tab, tab.root, framed = panes.size > 1)
        }
    }
}

@Composable
private fun NodeView(session: Session, pool: TerminalPool, space: Space, tab: Tab, node: JsonObject, framed: Boolean) {
    when (node.type) {
        "row", "column" -> SplitView(session, pool, space, tab, node, framed)
        "stack" -> node.activeChild()?.let { NodeView(session, pool, space, tab, it, framed) }
        else -> if (node.terminal != null || node.modName != null) PaneView(session, pool, space, tab, node, framed, zoomed = false)
    }
}

/** A row or column: children by weight, with dividers you drag to resize. */
@Composable
private fun SplitView(session: Session, pool: TerminalPool, space: Space, tab: Tab, node: JsonObject, framed: Boolean) {
    val horizontal = node.type == "row"
    val children = node.children
    val weights = remember(node) { mutableStateListOf(*children.map { (it.weight ?: 1.0).toFloat() }.toTypedArray()) }
    var total by remember { mutableStateOf(1f) }
    // A fixed size (desktop layouts may set px) is kept; the rest share by weight.
    fun fixed(child: JsonObject): Dp? = (child["size"]["px"] as? kotlinx.serialization.json.JsonPrimitive)?.content?.toFloatOrNull()?.dp
    val divider: @Composable (Int) -> Unit = { i ->
        Box(
            (if (horizontal) Modifier.width(8.dp).fillMaxHeight() else Modifier.height(8.dp).fillMaxWidth())
                .pointerInput(node, i) {
                    detectDragGestures(onDragEnd = {
                        session.resize(tab.id, node.nodeId, weights.map { it.toDouble() })
                    }) { change, drag ->
                        change.consume()
                        if (i + 1 >= weights.size) return@detectDragGestures
                        val sum = weights.sum()
                        val delta = (if (horizontal) drag.x else drag.y) / total * sum
                        val a = weights[i] + delta
                        val b = weights[i + 1] - delta
                        val min = sum * 0.08f
                        if (a > min && b > min) {
                            weights[i] = a
                            weights[i + 1] = b
                        }
                    }
                },
            contentAlignment = Alignment.Center,
        ) {
            Box((if (horizontal) Modifier.width(2.dp).height(28.dp) else Modifier.height(2.dp).width(28.dp)).background(Theme.border, RoundedCornerShape(1.dp)))
        }
    }
    val measure = Modifier.fillMaxSize().onSizeChanged { total = (if (horizontal) it.width else it.height).toFloat().coerceAtLeast(1f) }
    if (horizontal) {
        Row(measure) {
            children.forEachIndexed { i, c ->
                val size = fixed(c)
                Box(if (size != null) Modifier.width(size).fillMaxHeight() else Modifier.weight(weights.getOrElse(i) { 1f }.coerceAtLeast(0.01f)).fillMaxHeight()) {
                    NodeView(session, pool, space, tab, c, framed)
                }
                if (i < children.size - 1) divider(i)
            }
        }
    } else {
        Column(measure) {
            children.forEachIndexed { i, c ->
                val size = fixed(c)
                Box(if (size != null) Modifier.height(size).fillMaxWidth() else Modifier.weight(weights.getOrElse(i) { 1f }.coerceAtLeast(0.01f)).fillMaxWidth()) {
                    NodeView(session, pool, space, tab, c, framed)
                }
                if (i < children.size - 1) divider(i)
            }
        }
    }
}

private fun paneTitle(session: Session, pool: TerminalPool, pane: JsonObject): String {
    pane.modName?.let { return it }
    val addr = pane.terminal ?: return ""
    val rec = session.terminals[addr]
    val status = rec?.get("status")
    status?.get("app").str?.ifEmpty { null }?.let { return it }
    pool.get(addr)?.title?.ifEmpty { null }?.let { return it }
    rec?.get("title").str?.ifEmpty { null }?.let { return it }
    return rec?.get("foreground")?.get("name").str ?: addr
}

@Composable
private fun PaneView(session: Session, pool: TerminalPool, space: Space, tab: Tab, pane: JsonObject, framed: Boolean, zoomed: Boolean) {
    val id = pane.nodeId
    val focused = session.focused[tab.id] == id
    val addr = pane.terminal
    var menu by remember { mutableStateOf(false) }
    val state = addr?.let { a -> session.agents().firstOrNull { it.address == a }?.state }
    Column(
        Modifier.fillMaxSize().padding(2.dp)
            .border(1.dp, if (framed && focused) Theme.accent.copy(alpha = 0.7f) else Theme.border, RoundedCornerShape(6.dp))
            .background(Theme.bg, RoundedCornerShape(6.dp)),
    ) {
        Row(
            Modifier.fillMaxWidth().height(28.dp).background(Theme.panel, RoundedCornerShape(topStart = 6.dp, topEnd = 6.dp))
                .clickable { session.focusPane(tab.id, id); pool.get(addr)?.focus() }.padding(start = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (state != null) {
                Dot(state, 7.dp)
                Spacer(Modifier.width(6.dp))
            }
            val cwd = session.cwdOf(addr)?.let { herdr.core.folderName(it, if (machineOf(addr ?: "") == null && space.machine == null) session.home else null) }
            Text(
                listOfNotNull(paneTitle(session, pool, pane), cwd).distinct().joinToString(" · "),
                color = if (focused) Theme.text else Theme.dim, fontSize = 12.sp, fontFamily = FontFamily.Monospace,
                maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f),
            )
            PaneButton(Icons.Default.ViewColumn, "Split right") { session.split(id, Axis.Row) }
            PaneButton(Icons.Default.ViewStream, "Split down") { session.split(id, Axis.Column) }
            PaneButton(if (zoomed) Icons.Default.FullscreenExit else Icons.Default.Fullscreen, "Zoom") { session.toggleZoom(tab.id, id) }
            Box {
                PaneButton(Icons.Default.MoreVert, "More") { menu = true }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(text = { Text("Close pane") }, onClick = { menu = false; session.closePane(id) })
                    if (addr != null) DropdownMenuItem(text = { Text("Copy selection") }, onClick = {
                        menu = false
                        pool.get(addr)?.copySelection { session.copied = it }
                    })
                    DropdownMenuItem(text = { Text("New tab here") }, onClick = { menu = false; session.newTab(space.id, null) })
                }
            }
        }
        Box(Modifier.fillMaxSize()) {
            if (addr != null) {
                TerminalBox(pool, addr) { session.focusPane(tab.id, id) }
            } else {
                Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("Mod pane: ${pane.modName}", color = Theme.text)
                    Text("Mods are drawn by the desktop app. Use the Files and Changes panels here.", color = Theme.dim, fontSize = 12.sp)
                }
            }
        }
    }
}

@Composable
private fun PaneButton(icon: ImageVector, label: String, onClick: () -> Unit) {
    IconButton(onClick = onClick, modifier = Modifier.size(28.dp)) { Icon(icon, label, tint = Theme.dim, modifier = Modifier.size(16.dp)) }
}

/** The terminal's WebView, moved into whichever box shows it. */
@Composable
fun TerminalBox(pool: TerminalPool, address: String, onTouch: () -> Unit) {
    val ctl = remember(address) { pool.acquire(address) }
    DisposableEffect(address) { onDispose { pool.release(address) } }
    Box(Modifier.fillMaxSize()) {
        AndroidView(
            factory = { ctx -> FrameLayout(ctx) },
            update = { frame ->
                if (ctl.web.parent !== frame) {
                    (ctl.web.parent as? ViewGroup)?.removeView(ctl.web)
                    frame.addView(ctl.web, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
                }
                ctl.web.setOnFocusChangeListener { _, has -> if (has) onTouch() }
            },
            modifier = Modifier.fillMaxSize(),
        )
    }
}

/** A worker without a pane, shown over the tab. */
@Composable
private fun Peek(session: Session, pool: TerminalPool, address: String) {
    val agent = session.agents().firstOrNull { it.address == address }
    Dialog(onDismissRequest = { session.peek = null }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(Modifier.fillMaxSize().padding(16.dp).background(Theme.bg, RoundedCornerShape(10.dp)).border(1.dp, Theme.accent.copy(alpha = 0.5f), RoundedCornerShape(10.dp)).imePadding()) {
            Row(Modifier.fillMaxWidth().padding(start = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Dot(agent?.state)
                Spacer(Modifier.width(8.dp))
                Text("Peek · ${agent?.app ?: address}", color = Theme.text, modifier = Modifier.weight(1f))
                androidx.compose.material3.TextButton(onClick = { session.keepAsTab(address) }) { Text("Keep as tab") }
                IconButton(onClick = { session.peek = null }) { Icon(Icons.Default.Close, "Close", tint = Theme.dim) }
            }
            Box(Modifier.weight(1f)) { TerminalBox(pool, address) {} }
            KeyBar(session, pool)
        }
    }
}

/** Keys a phone keyboard lacks, for the focused terminal. */
@Composable
fun KeyBar(session: Session, pool: TerminalPool) {
    val clipboard = LocalClipboardManager.current
    fun target() = pool.get(session.peek ?: session.focusedTerminal())
    @Composable
    fun K(label: String, active: Boolean = false, onClick: () -> Unit) {
        Text(
            label, color = if (active) Theme.bg else Theme.text, fontSize = 13.sp, fontFamily = FontFamily.Monospace,
            modifier = Modifier.padding(horizontal = 2.dp).background(if (active) Theme.accent else Theme.raised, RoundedCornerShape(5.dp))
                .clickable(onClick = onClick).padding(horizontal = 11.dp, vertical = 8.dp),
        )
    }
    Row(Modifier.fillMaxWidth().background(Theme.panel).horizontalScroll(rememberScrollState()).padding(4.dp)) {
        K("esc") { target()?.key("esc") }
        K("tab") { target()?.key("tab") }
        K("ctrl", session.ctrl) { session.ctrl = !session.ctrl; target()?.setMods(session.ctrl, session.alt) }
        K("alt", session.alt) { session.alt = !session.alt; target()?.setMods(session.ctrl, session.alt) }
        K("←") { target()?.key("left") }
        K("↑") { target()?.key("up") }
        K("↓") { target()?.key("down") }
        K("→") { target()?.key("right") }
        for (c in listOf("|", "~", "/", "-", "`")) K(c) { target()?.type(c) }
        K("⇧tab") { target()?.key("shift-tab") }
        K("home") { target()?.key("home") }
        K("end") { target()?.key("end") }
        K("pgup") { target()?.key("pgup") }
        K("pgdn") { target()?.key("pgdn") }
        K("paste") { clipboard.getText()?.text?.let { target()?.paste(it) } }
        K("^C") { target()?.type("\u0003") }
        K("A−") { pool.fontSize = (pool.fontSize - 1).coerceAtLeast(8); session.saveFont(pool.fontSize) }
        K("A+") { pool.fontSize = (pool.fontSize + 1).coerceAtMost(28); session.saveFont(pool.fontSize) }
    }
    LaunchedEffect(session.copied) {
        session.copied?.let {
            clipboard.setText(androidx.compose.ui.text.AnnotatedString(it))
            session.notice = if (it.isEmpty()) "Nothing selected: long-press text in the terminal first." else "Copied."
            session.copied = null
        }
    }
}
