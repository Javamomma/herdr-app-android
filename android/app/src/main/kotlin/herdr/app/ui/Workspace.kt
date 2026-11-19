package herdr.app.ui

import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.activity.compose.BackHandler
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.MenuOpen
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.FullscreenExit
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.SmartToy
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
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
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
import herdr.core.LeftPanel
import herdr.core.PanelToggle
import herdr.core.SendOutcome
import herdr.core.draftAfter
import herdr.term.Clip
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
fun HerdrRoot(session: Session, pool: TerminalPool, locked: Boolean = false, onUnlock: () -> Unit = {}) {
    var editing by remember { mutableStateOf(false) }
    LaunchedEffect(session.generation, session.terminals) { pool.reattach() }
    val showConnect = editing || (session.conn as? Conn.Failed)?.hostKey != null || (!session.demo && (!session.profile.ready ||
        (session.spaces.isEmpty() && session.conn !is Conn.Connected)))
    androidx.compose.material3.MaterialTheme(colorScheme = androidx.compose.material3.darkColorScheme(
        primary = Theme.accent, background = Theme.bg, surface = Theme.panel, onSurface = Theme.text, onBackground = Theme.text,
        surfaceVariant = Theme.raised, outline = Theme.border,
    )) {
        if (locked) {
            LockScreen(onUnlock)
        } else if (showConnect) {
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
    var snippetsOpen by remember { mutableStateOf(false) }
    var agentsHome by remember { mutableStateOf(false) }
    // Asked from a notification, the widget, the key bar or the demo.
    LaunchedEffect(session.requested) {
        when (session.requested) {
            "files", "changes" -> panel = session.requested
            "agents" -> { panel = null; agentsHome = true }
            "work" -> { panel = null; agentsHome = false }
            "settings" -> settings = true
            "snippets" -> { panel = null; snippetsOpen = true }
            null -> {}
            else -> if (session.requested!!.startsWith("file:")) {
                session.fileToOpen = session.requested!!.removePrefix("file:")
                panel = "files"
            }
        }
        session.requested = null
    }

    // Agents that need you (and, on the agents list, all of them): their
    // screens, for previews and the answers their questions offer.
    LaunchedEffect(session.generation) {
        while (true) {
            val agents = session.agents()
            val wanted = agents.filter { it.state == "blocked" || (agentsHome && it.state == "working") }.map { it.address }
            if (wanted.isNotEmpty()) session.refreshScreens(wanted)
            kotlinx.coroutines.delay(if (agentsHome) 3_000 else 5_000)
        }
    }

    val space = session.spaces.firstOrNull { it.id == session.activeSpace }
    val tab = space?.tabs?.firstOrNull { it.id == session.activeTab[space.id] }
    // Nothing on this screen once it leaves (connect screen, app lock): no one is looking.
    androidx.compose.runtime.DisposableEffect(Unit) { onDispose { session.visible = emptySet() } }

    BoxWithConstraints(Modifier.fillMaxSize().background(Theme.bg).safeDrawingPadding()) {
        val wide = maxWidth >= WIDE
        val wider = maxWidth >= WIDER
        // Folded: open to the agents list when there are agents to look at.
        LaunchedEffect(wide) {
            agentsHome = !wide && session.agentsWhenFolded && session.agents().any { it.state == "blocked" || it.state == "done" }
        }
        LaunchedEffect(session.peek, session.activeSpace) { if (session.peek != null) agentsHome = false }
        val drawer = rememberDrawerState(DrawerValue.Closed)
        val scope = rememberCoroutineScope()
        val drawerOpen = drawer.currentValue == DrawerValue.Open
        // What is really drawn counts as looked at (shared with the desktop as herdr.seen; see herdr.core.Looking).
        val looking = herdr.core.Looking.panes(herdr.core.Looking.Screen(
            foreground = true, covered = false, dialogOver = settings || snippetsOpen, wide = wide, agentsHome = agentsHome, drawerOpen = drawerOpen,
            panelOver = panel != null && !wider,
            panes = tab?.root?.panes()?.mapNotNull { it.terminal }.orEmpty(),
            zoomed = tab?.let { t -> session.zoomed[t.id]?.let { z -> t.root.find(z)?.terminal } },
            focused = tab?.let { t -> t.root.panes().firstOrNull { it.nodeId == session.focused[t.id] }?.terminal },
            peek = session.peek,
        ))
        LaunchedEffect(looking) { session.visible = looking }
        // Back closes the panel first when it is on screen: wide hides it (saved, like the button), folded closes the drawer.
        BackHandler(enabled = LeftPanel.backCloses(wide, session.sidebarHidden, drawerOpen)) {
            if (wide) session.updateSidebarHidden(true) else scope.launch { drawer.close() }
        }
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
                    IconButton(onClick = {
                        when (LeftPanel.toggle(wide, drawerOpen)) {
                            PanelToggle.FlipSaved -> session.updateSidebarHidden(!session.sidebarHidden)
                            PanelToggle.OpenDrawer -> scope.launch { drawer.open() }
                            PanelToggle.CloseDrawer -> scope.launch { drawer.close() }
                        }
                    }) {
                        val icon = if (LeftPanel.open(wide, session.sidebarHidden, drawerOpen)) Icons.AutoMirrored.Filled.MenuOpen else Icons.Default.Menu
                        Icon(icon, "Show or hide the left panel", tint = Theme.text)
                    }
                    if (!wide) IconButton(onClick = { agentsHome = !agentsHome }) {
                        Icon(Icons.Default.SmartToy, "Agents", tint = if (agentsHome) Theme.accent else Theme.dim)
                    }
                    Box(Modifier.weight(1f)) {
                        TabBar(session, space, wide, onRename = { dialog = DialogKind.RenameTab(it) }, onCustom = { dialog = DialogKind.Command(space?.id) })
                    }
                    IconButton(onClick = { panel = if (panel == "files") null else "files" }) { Icon(Icons.Default.Folder, "Files", tint = if (panel == "files") Theme.accent else Theme.dim) }
                    IconButton(onClick = { panel = if (panel == "changes") null else "changes" }) { Icon(Icons.Default.SwapVert, "Changes", tint = if (panel == "changes") Theme.accent else Theme.dim) }
                }
                ConnectionBanner(session)
                Row(Modifier.weight(1f)) {
                    Box(Modifier.weight(1f).fillMaxHeight()) {
                        if (agentsHome && !wide) {
                            AgentsHome(session, onOpen = { a -> session.showAgent(a); agentsHome = false })
                        } else if (space == null || tab == null) {
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
                if (LeftPanel.besideShown(wide, session.sidebarHidden)) {
                    Box(Modifier.width(if (wider) 280.dp else 240.dp).fillMaxHeight().background(Theme.panel)) { sidebar() }
                    Box(Modifier.width(1.dp).fillMaxHeight().background(Theme.border))
                }
                Box(Modifier.weight(1f)) { main() }
            }
        } else {
            // Swipe only while open (to swipe it shut): sideways drags that start on the terminal are its own (selection, scrolling).
            ModalNavigationDrawer(drawerState = drawer, gesturesEnabled = LeftPanel.drawerGestures(drawerOpen), drawerContent = {
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
    session.closing?.let { c ->
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { session.closing = null },
            title = { Text(c.title) },
            text = { Text(c.text) },
            confirmButton = { androidx.compose.material3.TextButton(onClick = { session.closing = null; c.run() }) { Text(c.confirm, color = Theme.stateColor("blocked")) } },
            dismissButton = { androidx.compose.material3.TextButton(onClick = { session.closing = null }) { Text("Cancel") } },
        )
    }
    if (snippetsOpen) SnippetsDialog(session, pool, onDismiss = { snippetsOpen = false })
    if (settings) SettingsDialog(session, pool, onDismiss = { settings = false }, onEditConnection = { settings = false; onEditConnection() })
}

@Composable
private fun ConnectionBanner(session: Session) {
    val c = session.conn
    val prefix = if (session.stale) "Showing what was there last time. " else ""
    val text = prefix + when (c) {
        is Conn.Connecting -> c.step
        is Conn.Failed -> c.message + if (c.retrying) " Reconnecting…" else ""
        Conn.Idle -> "Not connected."
        Conn.Connected -> if (prefix.isEmpty()) return else ""
    }
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
                IconButton(onClick = onSettings) {
                    Icon(Icons.Default.Settings, "Settings", tint = if (session.update != null) Theme.accent else Theme.dim)
                }
            }
        }
        for (machine in groups) {
            val spaces = session.spaces.filter { it.machine == machine }
            item(key = "m:$machine") {
                Row(Modifier.fillMaxWidth().padding(start = 16.dp, top = 10.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(session.machineLabel(machine).uppercase(), color = Theme.dim, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                    IconButton(onClick = { onNewSpace(machine) }, modifier = Modifier.size(40.dp)) { Icon(Icons.Default.Add, "New space", tint = Theme.dim, modifier = Modifier.size(20.dp)) }
                }
                session.layoutErrors[machine ?: ""]?.let { Text(it, color = Theme.stateColor("blocked"), fontSize = 11.sp, modifier = Modifier.padding(horizontal = 16.dp)) }
            }
            items(spaces, key = { "s:${it.id}" }) { s ->
                var menu by remember { mutableStateOf(false) }
                val selected = s.id == session.activeSpace
                Box {
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 8.dp).background(if (selected) Theme.raised else Color.Transparent, RoundedCornerShape(6.dp))
                            .combinedClickable(onClick = { session.selectSpace(s.id); session.peek = null; onPicked() }, onLongClick = { menu = true })
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

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun AgentRow(session: Session, a: Agent, onPicked: () -> Unit) {
    val space = session.spaces.firstOrNull { it.id == a.spaceId }
    var menu by remember { mutableStateOf(false) }
    SwipeReveal(session, a, background = Theme.panel) {
        Box {
            Row(
                Modifier.fillMaxWidth().combinedClickable(onClick = { session.showAgent(a); onPicked() }, onLongClick = { menu = true })
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Dot(a.state)
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            listOfNotNull(a.app, if (a.parent != null) "worker" else null).joinToString(" · "),
                            color = Theme.text, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false),
                        )
                        if (a.pinned) Icon(Icons.Default.PushPin, "Pinned", tint = Theme.dim, modifier = Modifier.padding(start = 4.dp).size(12.dp))
                    }
                    Text(
                        listOfNotNull(a.state, space?.let { session.spaceName(it) }, a.title.ifBlank { null }).joinToString(" · "),
                        color = Theme.dim, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                    if (a.state == "blocked") Answers(session, a, compact = true)
                }
            }
            AgentMenu(session, a, menu) { menu = false }
        }
    }
}

/**
 * The answers an agent's question offers, read from its screen, as buttons.
 * Risky ones (can't be undone, or stop future questions) need a press-and-hold.
 */
@OptIn(ExperimentalFoundationApi::class, androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
fun Answers(session: Session, a: Agent, compact: Boolean) {
    val screen = session.screens[a.address] ?: return
    val q = remember(screen) { herdr.core.Prompts.question(screen) }
    val choices = q.choices
    if (choices.isEmpty()) return
    androidx.compose.foundation.layout.FlowRow(
        Modifier.padding(top = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        for (c in choices) {
            val color = if (c.risky || q.dangerous) Theme.stateColor("blocked") else Theme.accent
            Text(
                (c.number?.let { "$it. " } ?: "") + c.label + if (c.risky || q.dangerous) "  (hold)" else "",
                color = color, fontSize = if (compact) 12.sp else 14.sp, maxLines = 2, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.border(1.dp, color.copy(alpha = 0.6f), RoundedCornerShape(8.dp))
                    .combinedClickable(
                        onClick = { if (c.risky || q.dangerous) session.notice = "Press and hold to choose \"${c.label}\"" else session.choose(a.address, c.label, q.mark, held = false) },
                        onLongClick = { session.choose(a.address, c.label, q.mark, held = true) },
                    )
                    .padding(horizontal = 10.dp, vertical = if (compact) 5.dp else 8.dp),
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
                    Text(session.tabName(space, t), color = if (selected) Theme.text else Theme.dim, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 160.dp))
                }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(text = { Text("Rename tab") }, onClick = { menu = false; onRename(t) })
                    DropdownMenuItem(text = { Text("Close tab") }, onClick = { menu = false; session.requestCloseTab(t.id) })
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
        IconButton(onClick = { open = true }, modifier = Modifier.size(40.dp)) { Icon(Icons.Default.Add, "New tab", tint = Theme.text) }
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
    val clipboard = LocalClipboardManager.current
    val state = addr?.let { a -> session.agents().firstOrNull { it.address == a }?.state }
    Column(
        Modifier.fillMaxSize().padding(2.dp)
            .border(1.dp, if (framed && focused) Theme.accent.copy(alpha = 0.7f) else Theme.border, RoundedCornerShape(6.dp))
            .background(Theme.bg, RoundedCornerShape(6.dp)),
    ) {
        Row(
            Modifier.fillMaxWidth().height(36.dp).background(Theme.panel, RoundedCornerShape(topStart = 6.dp, topEnd = 6.dp))
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
                    DropdownMenuItem(text = { Text("Close pane") }, onClick = { menu = false; session.requestClosePane(id) })
                    if (addr != null) DropdownMenuItem(text = { Text("Copy selection") }, onClick = {
                        menu = false
                        copyPicked(clipboard, session, pool.get(addr)?.selection)
                    })
                    DropdownMenuItem(text = { Text("New tab here") }, onClick = { menu = false; session.newTab(space.id, null) })
                }
            }
        }
        Box(Modifier.fillMaxSize()) {
            if (addr != null) {
                TerminalBox(session, pool, addr) { session.focusPane(tab.id, id) }
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
    IconButton(onClick = onClick, modifier = Modifier.size(36.dp)) { Icon(icon, label, tint = Theme.dim, modifier = Modifier.size(18.dp)) }
}

/** The terminal's native view, moved into whichever box shows it. */
@Composable
fun TerminalBox(session: Session, pool: TerminalPool, address: String, onTouch: () -> Unit) {
    val ctl = remember(address) { pool.acquire(address) }
    DisposableEffect(address) { onDispose { pool.release(address) } }
    Box(Modifier.fillMaxSize()) {
        AndroidView(
            factory = { ctx -> FrameLayout(ctx) },
            update = { frame ->
                if (ctl.view.parent !== frame) {
                    (ctl.view.parent as? ViewGroup)?.removeView(ctl.view)
                    frame.addView(ctl.view, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
                }
                ctl.view.setOnFocusChangeListener { _, has -> if (has) onTouch() }
            },
            modifier = Modifier.fillMaxSize(),
        )
        val picked = ctl.selection
        // The label and the selection bar share the top-right corner: the bar replaces the label.
        if (ctl.watching && picked == null) {
            // Not clickable, so touches reach the terminal under it.
            Text(
                "Watching · tap to type",
                color = Theme.dim, fontSize = 11.sp,
                modifier = Modifier.align(Alignment.TopEnd).padding(6.dp)
                    .background(Theme.raised.copy(alpha = 0.85f), RoundedCornerShape(6.dp)).padding(horizontal = 6.dp, vertical = 2.dp),
            )
        }
        if (picked != null) {
            val clipboard = LocalClipboardManager.current
            // Copy keeps the text; Select all takes everything; Paste does what the key row's Paste does
            // (typing: while watching it takes control and the text is held until then). Copy, Select all, Clear never do.
            Row(
                Modifier.align(Alignment.TopEnd).padding(8.dp).background(Theme.raised, RoundedCornerShape(8.dp)).border(1.dp, Theme.border, RoundedCornerShape(8.dp))
                    .horizontalScroll(rememberScrollState()),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                androidx.compose.material3.TextButton(onClick = {
                    copyPicked(clipboard, session, picked)
                    ctl.clearSelection()
                }) { Text("Copy") }
                androidx.compose.material3.TextButton(onClick = { ctl.selectAll() }) { Text("Select all") }
                androidx.compose.material3.TextButton(onClick = {
                    clipboardText(clipboard, session)?.let { ctl.paste(it); ctl.clearSelection() }
                }) { Text("Paste") }
                androidx.compose.material3.TextButton(onClick = { ctl.clearSelection() }) { Text("Clear", color = Theme.dim) }
            }
        }
    }
}

/** The clipboard's text for a paste into a terminal or the message box; says "Nothing to paste" and gives null when there is none. */
private fun clipboardText(clipboard: androidx.compose.ui.platform.ClipboardManager, session: Session): String? {
    val text = clipboard.getText()?.text
    if (text.isNullOrEmpty()) { session.notice = "Nothing to paste"; return null }
    return text
}

/** Puts a terminal selection on the clipboard (an empty one leaves it alone) and says what happened. */
private fun copyPicked(clipboard: androidx.compose.ui.platform.ClipboardManager, session: Session, picked: herdr.term.Picked?) {
    val p = picked ?: herdr.term.Picked("")
    // An empty selection must not overwrite what is already on the clipboard.
    if (Clip.shouldWrite(p.text)) clipboard.setText(androidx.compose.ui.text.AnnotatedString(p.text))
    session.notice = Clip.copyNotice(p.text, p.cut)
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
            Box(Modifier.weight(1f)) { TerminalBox(session, pool, address) {} }
            KeyBar(session, pool)
        }
    }
}

/**
 * Keys a phone keyboard lacks, for the focused terminal, and the Type
 * composer: a normal text field (autocorrect, swipe, voice) whose message is
 * pasted and sent in one go. It opens by itself for agents.
 */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
fun KeyBar(session: Session, pool: TerminalPool) {
    val clipboard = LocalClipboardManager.current
    val address = session.peek ?: session.focusedTerminal()
    fun target() = pool.get(address)
    val isAgent = address != null && session.terminals[address]?.get("status")?.let { it !is kotlinx.serialization.json.JsonNull } == true
    val composing = address != null && (session.composer[address] ?: isAgent)
    val imeVisible = WindowInsets.isImeVisible
    @Composable
    fun K(label: String, active: Boolean = false, onClick: () -> Unit) {
        Text(
            label, color = if (active) Theme.bg else Theme.text, fontSize = 13.sp, fontFamily = FontFamily.Monospace,
            modifier = Modifier.padding(horizontal = 2.dp).background(if (active) Theme.accent else Theme.raised, RoundedCornerShape(5.dp))
                .clickable(onClick = onClick).padding(horizontal = 12.dp, vertical = 11.dp),
        )
    }
    // The message box's text and cursor live here so Paste can insert at the cursor.
    val draftText = address?.let { session.drafts[it] } ?: ""
    var typedField by remember(address) { mutableStateOf(TextFieldValue(draftText, TextRange(draftText.length))) }
    val field = if (typedField.text == draftText) typedField else TextFieldValue(draftText, TextRange(draftText.length))
    var boxFocused by remember(address) { mutableStateOf(false) }
    fun pasteIntoDraft(text: String) {
        val a = address ?: return
        val (t, pos) = Clip.insertAt(field.text, field.selection.start, field.selection.end, text)
        typedField = TextFieldValue(t, TextRange(pos))
        session.drafts[a] = t
    }
    fun pasteFromClipboard(toDraft: Boolean) {
        val text = clipboardText(clipboard, session) ?: return
        // With the message box in use the text goes into the draft and nothing reaches the terminal.
        if (toDraft) pasteIntoDraft(text) else target()?.paste(text)
    }
    if (composing && address != null) {
        Composer(
            session, address, field, onField = { typedField = it; session.drafts[address] = it.text },
            onFocus = { boxFocused = it }, onPaste = { pasteFromClipboard(true) },
        ) { target() }
    }
    Row(Modifier.fillMaxWidth().background(Theme.panel).horizontalScroll(rememberScrollState()).padding(4.dp)) {
        K("⌨", imeVisible) { target()?.toggleKeyboard(imeVisible) }
        K("esc") { target()?.key("esc") }
        K("paste") { pasteFromClipboard(composing && boxFocused) }
        K("^C") { target()?.type("\u0003") }
        K("tab") { target()?.key("tab") }
        K("ctrl", session.ctrl) { session.ctrl = !session.ctrl }
        K("alt", session.alt) { session.alt = !session.alt }
        K("←") { target()?.key("left") }
        K("↑") { target()?.key("up") }
        K("↓") { target()?.key("down") }
        K("→") { target()?.key("right") }
        K("⏎") { target()?.key("enter") }
        for (c in listOf("|", "~", "/", "-", "`")) K(c) { target()?.type(c) }
        K("⇧tab") { target()?.key("shift-tab") }
        K("home") { target()?.key("home") }
        K("end") { target()?.key("end") }
        K("pgup") { target()?.key("pgup") }
        K("pgdn") { target()?.key("pgdn") }
        K("⚡") { session.requested = "snippets" }
        K("type", composing) { if (address != null) session.composer[address] = !composing }
        K("A−") { pool.fontSize = (pool.fontSize - 1).coerceAtLeast(7); session.saveFont(pool.fontSize) }
        K("A+") { pool.fontSize = (pool.fontSize + 1).coerceAtMost(32); session.saveFont(pool.fontSize) }
    }
}

/** A draft for an agent, kept per terminal until sent, with quick phrases and slash commands. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun Composer(
    session: Session, address: String, field: TextFieldValue, onField: (TextFieldValue) -> Unit,
    onFocus: (Boolean) -> Unit, onPaste: () -> Unit, target: () -> herdr.app.term.TerminalController?,
) {
    DisposableEffect(address) { onDispose { onFocus(false) } }
    var sending by remember(address) { mutableStateOf(false) }
    val draft = session.drafts[address] ?: ""
    val app = session.agents().firstOrNull { it.address == address }?.app ?: ""
    val slash = if (app.contains("codex", ignoreCase = true)) listOf("/new", "/model", "/approvals", "/diff", "/status", "/compact")
        else listOf("/clear", "/compact", "/model", "/review", "/cost", "/resume", "/init", "/help")
    // Phrases, or the agent's slash commands once the draft starts with "/".
    val chips = if (draft.startsWith("/")) slash.filter { it.startsWith(draft.trim()) } else session.phrases
    Row(
        Modifier.fillMaxWidth().background(Theme.panel).horizontalScroll(rememberScrollState()).padding(start = 6.dp, end = 6.dp, top = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        @Composable
        fun Chip(text: String, onLong: (() -> Unit)? = null, onClick: () -> Unit) {
            Text(
                text, color = Theme.text, fontSize = 13.sp, maxLines = 1,
                modifier = Modifier.background(Theme.raised, RoundedCornerShape(14.dp))
                    .combinedClickable(onClick = onClick, onLongClick = onLong).padding(horizontal = 12.dp, vertical = 6.dp),
            )
        }
        if (!draft.startsWith("/")) Chip("/") { session.drafts[address] = "/" }
        for (c in chips) Chip(c, onLong = if (draft.startsWith("/")) null else ({ session.savePhrases(session.phrases - c); session.notice = "Removed \"$c\"" })) {
            session.drafts[address] = if (c.startsWith("/")) c else (if (draft.isBlank()) c else "$draft $c")
        }
        if (draft.isNotBlank() && !draft.startsWith("/") && draft !in session.phrases) Chip("+ save phrase") { session.savePhrases(session.phrases + draft.trim()) }
    }
    Row(Modifier.fillMaxWidth().background(Theme.panel).padding(horizontal = 6.dp, vertical = 4.dp), verticalAlignment = Alignment.Bottom) {
        androidx.compose.material3.OutlinedTextField(
            value = field, onValueChange = onField,
            placeholder = { Text("Message the agent…", fontSize = 14.sp) },
            maxLines = 6, modifier = Modifier.weight(1f).onFocusChanged { onFocus(it.isFocused) },
            keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(capitalization = androidx.compose.ui.text.input.KeyboardCapitalization.Sentences),
        )
        androidx.compose.material3.TextButton(onClick = onPaste, modifier = Modifier.padding(bottom = 4.dp)) { Text("Paste") }
        androidx.compose.material3.Button(
            enabled = !sending,
            onClick = {
                val sent = draft
                val t = target()
                if (t == null) {
                    session.notice = SendOutcome.Rejected.notice
                } else {
                    sending = true
                    t.submit(sent) { outcome ->
                        sending = false
                        // The draft stays unless the message was delivered; nothing is resent.
                        if (draftAfter(session.drafts[address] ?: "", sent, outcome).isEmpty()) session.drafts.remove(address)
                        outcome.notice?.let { session.notice = it }
                    }
                }
            },
            modifier = Modifier.padding(bottom = 4.dp),
        ) { Text("Send") }
    }
}


/** The cover screen's front page: every agent, the ones that need you first. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun AgentsHome(session: Session, onOpen: (Agent) -> Unit) {
    val agents = session.agents()
    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 12.dp, vertical = 8.dp)) {
        item {
            val blocked = agents.count { it.state == "blocked" }
            Text(
                when {
                    agents.isEmpty() -> "No agents running"
                    blocked > 0 -> "$blocked waiting for you"
                    else -> "${agents.size} agents"
                },
                color = Theme.text, fontSize = 20.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(4.dp, 4.dp, 4.dp, 12.dp),
            )
        }
        items(agents, key = { it.address }) { a ->
            val space = session.spaces.firstOrNull { it.id == a.spaceId }
            var menu by remember { mutableStateOf(false) }
            SwipeReveal(session, a, background = Theme.bg, inset = 4.dp) {
            Box {
            Row(
                Modifier.fillMaxWidth().padding(vertical = 4.dp).background(Theme.panel, RoundedCornerShape(10.dp))
                    .border(1.dp, if (a.state == "blocked") Theme.stateColor("blocked").copy(alpha = 0.6f) else Theme.border, RoundedCornerShape(10.dp))
                    .combinedClickable(onClick = { onOpen(a) }, onLongClick = { menu = true }).padding(14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Dot(a.state, 10.dp)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(a.app + if (a.parent != null) " · worker" else "", color = Theme.text, fontSize = 15.sp, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f, fill = false))
                        if (a.pinned) Icon(Icons.Default.PushPin, "Pinned", tint = Theme.dim, modifier = Modifier.padding(start = 6.dp).size(14.dp))
                    }
                    Text(a.title.ifBlank { a.state }, color = Theme.dim, fontSize = 13.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text(listOfNotNull(a.state, space?.let { session.spaceName(it) }).joinToString(" · "), color = Theme.stateColor(a.state), fontSize = 12.sp)
                    session.screens[a.address]?.let { screen ->
                        val tail = remember(screen) { herdr.core.Prompts.tail(screen, 3) }
                        if (tail.isNotEmpty() && herdr.core.Prompts.choices(screen).isEmpty()) {
                            Text(
                                tail.joinToString("\n"), color = Theme.dim, fontSize = 11.sp, fontFamily = FontFamily.Monospace,
                                maxLines = 3, overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.padding(top = 6.dp).fillMaxWidth().background(Theme.bg, RoundedCornerShape(6.dp)).padding(8.dp),
                            )
                        }
                    }
                    if (a.state == "blocked") Answers(session, a, compact = false)
                }
                // A question without options: Allow is Enter, sent only while this same question shows.
                val shown = session.screens[a.address]
                if (a.state == "blocked" && shown != null) {
                    val q = remember(shown) { herdr.core.Prompts.question(shown) }
                    if (q.choices.isEmpty() && herdr.core.Prompts.allowKeys(q) != null) {
                        androidx.compose.material3.TextButton(onClick = { session.allowShown(a.address, q.mark) }) { Text("Allow") }
                    }
                }
            }
            AgentMenu(session, a, menu) { menu = false }
            }
            }
        }
    }
}

/** The app lock: nothing shows until the phone's owner unlocks it. */
@Composable
private fun LockScreen(onUnlock: () -> Unit) {
    LaunchedEffect(Unit) { onUnlock() }
    Column(
        Modifier.fillMaxSize().background(Theme.bg).safeDrawingPadding(),
        verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("Herdr is locked", color = Theme.text, fontSize = 20.sp)
        Spacer(Modifier.height(16.dp))
        androidx.compose.material3.Button(onClick = onUnlock) { Text("Unlock") }
    }
}
