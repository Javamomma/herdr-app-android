package herdr.app.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import herdr.app.Session
import herdr.app.Theme
import herdr.core.Find
import herdr.core.FindItem
import herdr.core.folderName

/**
 * Find any terminal of the core, agent or not, in any space or tab: type words (all must match name, title, folder
 * or machine), tap a result to jump to it. Back or the clear button closes it.
 */
@Composable
fun FindPanel(session: Session, onClose: () -> Unit, onOpened: () -> Unit) {
    var query by rememberSaveable { mutableStateOf("") }
    val focus = remember { FocusRequester() }
    BackHandler(onBack = onClose)
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    val results = Find.search(query, session.findItems())
    val states = session.agents().associate { it.address to it.state }
    Column(Modifier.fillMaxSize().padding(top = 8.dp)) {
        Row(Modifier.fillMaxWidth().padding(start = 12.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = query, onValueChange = { query = it }, singleLine = true,
                placeholder = { Text("Find a terminal…", fontSize = 14.sp) },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { results.firstOrNull()?.let { session.showTerminal(it.address); onOpened() } }),
                modifier = Modifier.weight(1f).focusRequester(focus),
            )
            IconButton(onClick = { if (query.isEmpty()) onClose() else query = "" }) {
                Icon(Icons.Default.Close, if (query.isEmpty()) "Close find" else "Clear search", tint = Theme.dim)
            }
        }
        if (results.isEmpty()) Text("No terminal matches.", color = Theme.dim, fontSize = 13.sp, modifier = Modifier.padding(16.dp))
        LazyColumn(Modifier.fillMaxSize(), verticalArrangement = Arrangement.Top) {
            items(results, key = { it.address }) { r -> FindRow(r, states[r.address]) { session.showTerminal(r.address); onOpened() } }
        }
    }
}

@Composable
private fun FindRow(r: FindItem, state: String?, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).semantics { contentDescription = "${r.name}, ${Find.where(r)}" }
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Dot(state)
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(r.name + if (r.pinned) " · pinned" else "", color = Theme.text, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                listOfNotNull(r.title.ifBlank { null }, folderName(r.folder, null).ifEmpty { null }).joinToString(" · "),
                color = Theme.dim, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
            Text(Find.where(r) + " · " + r.machine, color = Theme.accent, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}
