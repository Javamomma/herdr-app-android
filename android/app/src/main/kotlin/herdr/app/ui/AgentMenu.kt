package herdr.app.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitHorizontalTouchSlopOrCancellation
import androidx.compose.foundation.gestures.horizontalDrag
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import herdr.app.Agent
import herdr.app.Session
import herdr.app.Theme
import herdr.core.Mark
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

private fun readLabel(a: Agent) = if (a.mark == Mark.READ) "Mark read" else "Mark unread"

/** Long-press menu of an agent (list row or card): Pin / Unpin and Mark unread / Mark read. */
@Composable
fun AgentMenu(session: Session, a: Agent, expanded: Boolean, onDismiss: () -> Unit) {
    DropdownMenu(expanded = expanded, onDismissRequest = onDismiss) {
        DropdownMenuItem(text = { Text(if (a.pinned) "Unpin" else "Pin") }, onClick = { onDismiss(); session.setPinned(a.address, !a.pinned) })
        if (a.mark != Mark.NONE) DropdownMenuItem(text = { Text(readLabel(a)) }, onClick = { onDismiss(); session.toggleRead(a.address) })
    }
}

private val ACTION_WIDTH = 76.dp

/**
 * Swipe right to show two actions (Pin / Unpin and Mark read / unread) behind
 * the row; tap one to do it. Past halfway it stays open; tap the row or swipe
 * back to close. There is no swipe-left action: a left drag does nothing when
 * closed (it is left to the drawer and the list), and when open only closes.
 * [inset]: vertical space the content keeps around its card, so the actions line up with it.
 */
@Composable
fun SwipeReveal(session: Session, a: Agent, background: Color, inset: Dp = 0.dp, content: @Composable () -> Unit) {
    val count = if (a.mark == Mark.NONE) 1 else 2
    val full = with(LocalDensity.current) { (ACTION_WIDTH * count).toPx() }
    val x = remember { Animatable(0f) }
    val scope = rememberCoroutineScope()
    var open by remember { mutableStateOf(false) }
    fun settle(to: Boolean) { open = to; scope.launch { x.animateTo(if (to) full else 0f) } }
    Box(Modifier.fillMaxWidth()) {
        if (x.value > 0f) Row(Modifier.matchParentSize().padding(vertical = inset)) {
            @Composable
            fun action(text: String, color: Color, run: () -> Unit) = Box(
                Modifier.width(ACTION_WIDTH).fillMaxHeight().background(color).clickable { settle(false); run() },
                contentAlignment = Alignment.Center,
            ) { Text(text, color = Theme.bg, fontSize = 12.sp, textAlign = TextAlign.Center, modifier = Modifier.padding(horizontal = 4.dp)) }
            action(if (a.pinned) "Unpin" else "Pin", Theme.accent) { session.setPinned(a.address, !a.pinned) }
            if (a.mark != Mark.NONE) action(if (a.mark == Mark.READ) "Read" else "Unread", Theme.dim) { session.toggleRead(a.address) }
        }
        Box(
            Modifier.offset { IntOffset(x.value.roundToInt(), 0) }.background(background)
                .pointerInput(full, a.address) {
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        val startedOpen = open
                        var pos = x.value
                        val slop = awaitHorizontalTouchSlopOrCancellation(down.id) { change, over ->
                            // Closed: only rightward drags are ours. Open: either way (to close it).
                            if (over > 0f || startedOpen) {
                                change.consume()
                                pos = (pos + over).coerceIn(0f, full)
                                scope.launch { x.snapTo(pos) }
                            }
                        }
                        if (slop != null) {
                            horizontalDrag(slop.id) { change ->
                                pos = (pos + change.positionChange().x).coerceIn(0f, full)
                                scope.launch { x.snapTo(pos) }
                                change.consume()
                            }
                            settle(pos > full / 2)
                        }
                    }
                },
        ) {
            content()
            // Open: a tap on the row closes it instead of opening the agent.
            if (open) Box(Modifier.matchParentSize().clickable(interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }, indication = null) { settle(false) })
        }
    }
}
