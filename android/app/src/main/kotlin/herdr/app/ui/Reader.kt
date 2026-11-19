package herdr.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.layout.layout
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import herdr.app.Agent
import herdr.app.Session
import herdr.app.Theme
import herdr.core.Chunk
import herdr.core.Chunks
import herdr.core.ReadDoc
import herdr.core.Reading
import herdr.core.Reply
import herdr.term.Clip
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** The start of an agent's last reply on its card, with "Show all" when there is more. Plain text only; only for an agent that has stopped. */
@Composable
fun ReplyExcerpt(session: Session, a: Agent, maxLines: Int, fontSp: Int, modifier: Modifier = Modifier) {
    if (a.reply.isEmpty() || !Reply.showsOnCard(a.state)) return
    val e = remember(a.reply, maxLines, a.replyMaybeCut) { Reply.excerpt(a.reply, maxLines, maxLines * 100, a.replyMaybeCut) }
    if (e.text.isEmpty()) return
    var overflow by remember(e.text) { mutableStateOf(false) }
    Column(modifier) {
        Text(
            e.text, color = Theme.dim, fontSize = fontSp.sp, maxLines = maxLines, overflow = TextOverflow.Ellipsis,
            onTextLayout = { overflow = it.hasVisualOverflow },
        )
        if (e.more || overflow) {
            Text(
                "Show all", color = Theme.accent, fontSize = 12.sp, fontWeight = FontWeight.Medium,
                modifier = Modifier.clickable { session.replyOpen = a.address }
                    .semantics { contentDescription = "Show all of the last reply" }
                    .heightIn(min = 32.dp).padding(top = 6.dp, end = 12.dp),
            )
        }
    }
}

/**
 * The whole last reply in a sheet: wrapped, selectable, with Copy. It opens no terminal and attaches to nothing
 * (it is not "looking" at the agent; while it is open nothing under it counts as looked at); the text is the agent's output and stays plain.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReplySheet(session: Session) {
    val address = session.replyOpen ?: return
    val agent = session.agents().firstOrNull { it.address == address }
    val text = agent?.reply.orEmpty()
    val clipboard = LocalClipboardManager.current
    var copied by remember(address, text) { mutableStateOf(false) }
    ModalBottomSheet(onDismissRequest = { session.replyOpen = null }, containerColor = Theme.panel) {
        Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 16.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                Text(
                    listOfNotNull(agent?.app, agent?.title?.ifBlank { null }).joinToString(" · ").ifEmpty { "Last reply" },
                    color = Theme.text, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f),
                )
                if (text.isNotEmpty()) TextButton(onClick = { clipboard.setText(AnnotatedString(Clip.limit(text).text)); copied = true }) { Text(if (copied) "Copied" else "Copy") }
            }
            if (text.isEmpty()) {
                Text("No reply to show.", color = Theme.dim, fontSize = 13.sp, modifier = Modifier.padding(vertical = 16.dp))
            } else {
                // The weight goes on the container (a weight on the Text inside it would do nothing), so a tall reply scrolls and the notice below keeps its room.
                SelectionContainer(Modifier.weight(1f, fill = false)) {
                    Text(text, color = Theme.text, fontSize = 14.sp, lineHeight = 20.sp, modifier = Modifier.verticalScroll(rememberScrollState()).padding(bottom = 8.dp))
                }
                Reply.cutNotice(agent?.replyMaybeCut == true)?.let { Text(it, color = Theme.dim, fontSize = 12.sp, modifier = Modifier.padding(vertical = 8.dp)) }
            }
            Spacer(Modifier.padding(bottom = 12.dp))
        }
    }
}

/** Wrap or No wrap, remembered on this phone; "Copy all" with what it did. */
@Composable
fun ViewBar(session: Session, onCopyAll: (() -> Unit)?, note: String?) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
        TextButton(
            onClick = { session.updateWrapLines(!session.wrapLines) },
            modifier = Modifier.semantics { contentDescription = if (session.wrapLines) "Long lines wrap. Tap for no wrap" else "Long lines scroll sideways. Tap to wrap" },
        ) { Text(if (session.wrapLines) "Wrap" else "No wrap") }
        if (onCopyAll != null) TextButton(onClick = onCopyAll) { Text("Copy all") }
        if (note != null) Text(note, color = Theme.dim, fontSize = 11.sp, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f).padding(horizontal = 4.dp))
    }
}

/**
 * A loaded file or diff: the bar (wrap, Copy all), one-line notices (hidden characters replaced, large file), the text,
 * and [footer] under it (the cut notice and "Load more"). Copy all runs off the main thread and keeps the START of a very big file.
 */
@Composable
fun DocPane(session: Session, doc: ReadDoc, colorOf: ((String) -> Color)? = null, footer: @Composable () -> Unit = {}) {
    val clipboard = LocalClipboardManager.current
    val scope = rememberCoroutineScope()
    var note by remember(doc) { mutableStateOf<String?>(null) }
    Column(Modifier.fillMaxSize()) {
        ViewBar(session, onCopyAll = if (doc.chunks.isEmpty()) null else ({
            scope.launch {
                val c = withContext(Dispatchers.Default) { Reading.copyAll(doc, Clip.MAX_CHARS) }
                clipboard.setText(AnnotatedString(c.text))
                note = Reading.copyNotice(c)
            }
        }), note = note)
        if (doc.replaced) Text(Reading.REPLACED_NOTICE, color = Theme.dim, fontSize = 11.sp, modifier = Modifier.padding(horizontal = 12.dp, vertical = 2.dp))
        if (Reading.lazy(doc.chars)) Text(Reading.LAZY_HINT, color = Theme.dim, fontSize = 11.sp, modifier = Modifier.padding(horizontal = 12.dp, vertical = 2.dp))
        Box(Modifier.weight(1f)) {
            if (doc.chunks.isEmpty()) Text(if (doc.lines == 0) "Empty file." else "", color = Theme.dim, fontSize = 13.sp, modifier = Modifier.padding(12.dp))
            else TextViewer(doc, session.wrapLines, session.readerFontSp(), colorOf = colorOf)
        }
        footer()
    }
}

/**
 * [doc]'s blocks, monospace, long lines wrapped or scrolling sideways, one selection across the whole text. Every block's
 * text ends with its own line break (the last one aside), so a copy across a border keeps the lines apart. Up to
 * `Reading.EAGER_CHARS` it is one block (Select all reaches everything); past that the blocks are lazy (only those scrolled
 * into view exist, so a big file does not freeze) and a selection only reaches what is composed: "Copy all" has it all.
 */
@Composable
fun TextViewer(doc: ReadDoc, wrap: Boolean, fontSp: Int, modifier: Modifier = Modifier, colorOf: ((String) -> Color)? = null) {
    val lazy = Reading.lazy(doc.chars)
    val base = modifier.fillMaxSize().background(Theme.bg)
    val h = rememberScrollState()
    SelectionContainer(Modifier.fillMaxSize()) {
        val sideways = if (wrap) base else base.horizontalScroll(h)
        if (lazy) {
            LazyColumn(sideways, contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = 24.dp)) {
                items(doc.chunks) { Block(it, wrap, fontSp, colorOf) }
            }
        } else {
            Column(sideways.verticalScroll(rememberScrollState()).padding(bottom = 24.dp)) { doc.chunks.forEach { Block(it, wrap, fontSp, colorOf) } }
        }
    }
}

/** Drops the empty last line a trailing line break adds to a block, so blocks stack without blank lines between them. */
private fun Modifier.withoutTrailingBlankLine(trim: Boolean, lineSp: Float): Modifier = if (!trim) this else layout { m, c ->
    val p = m.measure(c)
    val cut = lineSp.sp.roundToPx()
    layout(p.width, (p.height - cut).coerceAtLeast(0)) { p.place(0, 0) }
}

@Composable
private fun Block(chunk: Chunk, wrap: Boolean, fontSp: Int, colorOf: ((String) -> Color)?) {
    val lineSp = fontSp * 1.35f
    if (chunk.text.isEmpty()) {
        // A blank last line: room, nothing to copy.
        Spacer(Modifier.height(with(androidx.compose.ui.platform.LocalDensity.current) { lineSp.sp.toDp() }))
        return
    }
    val shown = remember(chunk, colorOf == null) {
        if (colorOf == null) AnnotatedString(chunk.text)
        else buildAnnotatedString {
            for (seg in Chunks.segments(chunk)) withStyle(SpanStyle(color = colorOf(seg.lead))) { append(seg.text) }
        }
    }
    Text(
        shown, color = Theme.text, fontSize = fontSp.sp, fontFamily = FontFamily.Monospace, softWrap = wrap,
        style = TextStyle(lineHeight = lineSp.sp, lineHeightStyle = LineHeightStyle(LineHeightStyle.Alignment.Center, LineHeightStyle.Trim.None)),
        modifier = Modifier.padding(horizontal = 10.dp).withoutTrailingBlankLine(chunk.text.endsWith('\n'), lineSp),
    )
}
