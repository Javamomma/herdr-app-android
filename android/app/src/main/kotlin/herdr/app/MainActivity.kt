package herdr.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import herdr.app.term.TerminalPool
import herdr.app.ui.HerdrRoot

class MainActivity : ComponentActivity() {
    private lateinit var pool: TerminalPool
    private val session get() = (application as HerdrApplication).session

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        pool = TerminalPool(this, session)
        pool.fontSize = getSharedPreferences("view", MODE_PRIVATE).getInt("font", 13)
        pool.onFontChanged = { session.saveFont(it) }
        setContent { HerdrRoot(session, pool) }
        if (session.profile.ready) session.connect()
    }

    override fun onStart() {
        super.onStart()
        session.setForeground(true)
    }

    override fun onStop() {
        super.onStop()
        session.setForeground(false)
    }

    override fun onDestroy() {
        pool.disposeAll()
        super.onDestroy()
    }
}
