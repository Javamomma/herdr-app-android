package herdr.app

import androidx.compose.ui.graphics.Color
import herdr.core.obj
import kotlinx.serialization.json.JsonObject

/** Herdr's dark look, shared by the Compose chrome and the terminals. */
object Theme {
    const val BG_INT = 0xFF16171B.toInt()
    val bg = Color(0xFF16171B)
    val panel = Color(0xFF1D1F24)
    val raised = Color(0xFF262930)
    val border = Color(0xFF30333B)
    val text = Color(0xFFD8DAE0)
    val dim = Color(0xFF8A8F9C)
    val accent = Color(0xFFE8A04C)

    fun stateColor(state: String?): Color = when (state) {
        "blocked" -> Color(0xFFE06C75)
        "error" -> Color(0xFFE06C75)
        "done" -> Color(0xFF61AFEF)
        "working" -> Color(0xFFE5C07B)
        "idle" -> Color(0xFF5C6370)
        else -> Color.Transparent
    }

    /** What this viewer's terminal looks like (the core answers color and size queries with it). */
    fun display(cell: IntArray? = null): JsonObject = obj(
        "fg" to "#d8dae0", "bg" to "#16171b", "cursor" to "#e8a04c",
        "cell" to cell?.takeIf { it.size == 2 && it[0] > 0 && it[1] > 0 }?.toList(),
    )
}
