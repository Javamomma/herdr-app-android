package herdr.app

import android.Manifest
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_STRONG
import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_WEAK
import androidx.biometric.BiometricManager.Authenticators.DEVICE_CREDENTIAL
import androidx.biometric.BiometricPrompt
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import herdr.app.term.TerminalPool
import herdr.app.ui.HerdrRoot

class MainActivity : FragmentActivity() {
    private lateinit var pool: TerminalPool
    private val app get() = application as HerdrApplication
    private val session get() = app.session
    /** The app lock is showing (fingerprint, face or PIN to get in). */
    private var locked by mutableStateOf(false)
    private var backgroundedAt = 0L
    private var prompting = false
    private val askNotifications = registerForActivityResult(ActivityResultContracts.RequestPermission()) {}

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        pool = TerminalPool(this, session)
        pool.fontSize = getSharedPreferences("view", MODE_PRIVATE).getInt("font", 13)
        pool.onFontChanged = { session.saveFont(it) }
        locked = session.lockEnabled
        setContent { HerdrRoot(session, pool, locked = locked, onUnlock = ::unlock) }
        if (!handle(intent) && session.profile.ready) session.connect()
        if (Build.VERSION.SDK_INT >= 33 && session.alerts &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            askNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handle(intent)
    }

    /** A notification or widget tap, or the demo: returns true when it started a connection itself. */
    private fun handle(intent: Intent?): Boolean {
        intent ?: return false
        var connected = false
        // Only on a phone with no Mac set up: another app can't knock you off yours.
        if (intent.getBooleanExtra(EXTRA_DEMO, false) && (!session.profile.ready || session.demo)) {
            session.connectDemo()
            connected = true
        }
        intent.getStringExtra(Notifier.EXTRA_AGENT)?.let { address ->
            session.agents().firstOrNull { it.address == address }?.let { session.showAgent(it) }
            app.notifier.settle(address)
        }
        intent.getStringExtra(EXTRA_SCREEN)?.let { session.requested = it }
        return connected
    }

    override fun onStart() {
        super.onStart()
        session.setForeground(true)
        val away = SystemClock.elapsedRealtime() - backgroundedAt
        if (session.lockEnabled && backgroundedAt > 0 && away > session.lockAfterMinutes * 60_000L) locked = true
        if (locked) unlock()
        app.keepWatching()
    }

    override fun onStop() {
        super.onStop()
        backgroundedAt = SystemClock.elapsedRealtime()
        session.setForeground(false)
    }

    /** Asks for a fingerprint, face or the phone's PIN, then shows the app. */
    private fun unlock() {
        if (!locked || prompting) return
        prompting = true
        val who = if (Build.VERSION.SDK_INT >= 30) BIOMETRIC_STRONG or DEVICE_CREDENTIAL else BIOMETRIC_WEAK or DEVICE_CREDENTIAL
        val prompt = BiometricPrompt(this, ContextCompat.getMainExecutor(this), object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                prompting = false
                locked = false
            }

            override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                prompting = false
                // No lock screen set up on the phone: the app lock can't work, don't trap the person.
                if (errorCode == BiometricPrompt.ERROR_NO_DEVICE_CREDENTIAL || errorCode == BiometricPrompt.ERROR_HW_NOT_PRESENT) locked = false
            }
        })
        val info = BiometricPrompt.PromptInfo.Builder()
            .setTitle("Unlock Herdr")
            .setSubtitle("Your Mac's terminals are behind this")
            .setAllowedAuthenticators(who)
            .build()
        runCatching { prompt.authenticate(info) }.onFailure { prompting = false }
    }

    override fun onDestroy() {
        pool.disposeAll()
        super.onDestroy()
    }

    companion object {
        /** Start with the built-in demo instead of a Mac (also used by CI screenshots). */
        const val EXTRA_DEMO = "demo"
        /** Open on a screen: `agents`, `files`, `changes`, `settings`, `snippets`. */
        const val EXTRA_SCREEN = "screen"
    }
}
