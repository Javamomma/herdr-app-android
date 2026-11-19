package herdr.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Column
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
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
import herdr.core.Reading
import herdr.core.Reply
import herdr.term.Clip

/** The start of an agent's last reply on its card, with "Show all" when there is more. Plain text only. */
@Composable
fun ReplyExcerpt(session: Session, a: Agent, maxLines: Int, fontSp: Int, modifier: Modifier = Modifier) {
    if (a.reply.isEmpty()) return
    val e = remember(a.reply, maxLines) { Reply.excerpt(a.reply, maxLines, maxLines * 100) }
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
 * (it is not "looking at" the agent); the text is the agent's output and stays plain.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReplySheet(session: Session) {
    val address = session.replyOpen ?: return
    val agent = session.agents().firstOrNull { it.address == address }
    val text = agent?.reply.orEmpty()
    val clipboard = LocalClipboardManager.current
    var copied by remember(address) { mutableStateOf(false) }
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
                SelectionContainer {
                    Text(
                        text, color = Theme.text, fontSize = 14.sp, lineHeight = 20.sp,
                        modifier = Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()).padding(bottom = 8.dp),
                    )
                }
                Reply.cutNotice(text)?.let { Text(it, color = Theme.dim, fontSize = 12.sp, modifier = Modifier.padding(vertical = 8.dp)) }
            }
            androidx.compose.foundation.layout.Spacer(Modifier.padding(bottom = 12.dp))
        }
    }
}

/** Wrap or No wrap, remembered on this phone; and an optional "Copy all". */
@Composable
fun ViewBar(session: Session, onCopyAll: (() -> Unit)?, copied: Boolean) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
        TextButton(
            onClick = { session.updateWrapLines(!session.wrapLines) },
            modifier = Modifier.semantics { contentDescription = if (session.wrapLines) "Long lines wrap. Tap for no wrap" else "Long lines scroll sideways. Tap to wrap" },
        ) { Text(if (session.wrapLines) "Wrap" else "No wrap") }
        if (onCopyAll != null) TextButton(onClick = onCopyAll) { Text(if (copied) "Copied" else "Copy all") }
    }
}

/**
 * [text] in blocks of lines (`Chunks`), monospace, long lines wrapped or scrolling sideways, one selection across the
 * whole text. Up to `Reading.EAGER_CHARS` every block is composed, so Select all reaches everything; past that the
 * blocks are lazy (only those scrolled into view exist, so a big file does not freeze) and a drag-selection or Select all
 * only reaches what is composed: "Copy all" in the bar copies the full text.
 */
@Composable
fun TextViewer(text: String, wrap: Boolean, fontSp: Int, modifier: Modifier = Modifier, colorOf: ((String) -> Color)? = null) {
    val blocks = remember(text) { Chunks.split(text) }
    val lazy = Reading.lazy(text)
    val base = modifier.fillMaxSize().background(Theme.bg)
    val h = rememberScrollState()
    SelectionContainer(Modifier.fillMaxSize()) {
        val scrollSideways = if (wrap) base else base.horizontalScroll(h)
        if (lazy) {
            LazyColumn(scrollSideways, contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = 24.dp)) {
                items(blocks) { Block(it, wrap, fontSp, colorOf) }
            }
        } else {
            Column(scrollSideways.verticalScroll(rememberScrollState()).padding(bottom = 24.dp)) { blocks.forEach { Block(it, wrap, fontSp, colorOf) } }
        }
    }
}

@Composable
private fun Block(chunk: Chunk, wrap: Boolean, fontSp: Int, colorOf: ((String) -> Color)?) {
    val shown = remember(chunk, colorOf == null) {
        if (colorOf == null) AnnotatedString(chunk.text.ifEmpty { " " })
        else buildAnnotatedString {
            val lines = chunk.text.split('\n')
            lines.forEachIndexed { i, line ->
                withStyle(SpanStyle(color = colorOf(line))) { append(line) }
                if (i < lines.lastIndex) append('\n')
            }
            if (chunk.text.isEmpty()) append(" ")
        }
    }
    Text(
        shown, color = Theme.text, fontSize = fontSp.sp, lineHeight = (fontSp * 1.35f).sp, fontFamily = FontFamily.Monospace,
        softWrap = wrap, modifier = Modifier.padding(horizontal = 10.dp),
    )
}
