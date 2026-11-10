package herdr.app

import android.Manifest
import android.app.KeyguardManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.widget.RemoteViews
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.RemoteInput
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import herdr.core.Prompts
import herdr.core.Question

/**
 * Phone notifications for agents: one per agent that is blocked or just
 * finished. A blocked one shows the question read from the agent's screen,
 * with Allow, Deny and an inline Reply that only work on an unlocked phone
 * and only while the agent still shows that question. On the lock screen,
 * and whenever the app lock is on, a notification says no more than that an
 * agent needs you. Tapping opens the app on that agent.
 */
class Notifier(private val context: Context) {
    private val manager = NotificationManagerCompat.from(context)

    /** What an agent's notification shows, to put it back as it was. */
    private class Shown(val kind: Alert, val agent: Agent, val where: String, val question: Question?)
    private val shown = HashMap<String, Shown>()

    init {
        val nm = context.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(AGENTS, "Agents that need you", NotificationManager.IMPORTANCE_HIGH).apply {
            description = "An agent is waiting for an answer, or finished while you were away."
        })
        nm.createNotificationChannel(NotificationChannel(WATCH, "Connection to your Mac", NotificationManager.IMPORTANCE_MIN).apply {
            description = "Shown while Herdr stays connected in the background to watch your agents."
            setShowBadge(false)
        })
    }

    private fun id(address: String) = address.hashCode()

    private fun open(address: String?): PendingIntent {
        val intent = Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        if (address != null) intent.putExtra(EXTRA_AGENT, address)
        return PendingIntent.getActivity(context, address?.hashCode() ?: 0, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }

    /** A button on the notification. Android asks for the phone to be unlocked before it runs (12 and later). */
    private fun button(label: String, address: String, what: String, mark: String, input: RemoteInput? = null): NotificationCompat.Action {
        val intent = Intent(context, AgentActionReceiver::class.java).setAction(what).putExtra(EXTRA_AGENT, address).putExtra(EXTRA_MARK, mark)
        // The reply's text is added to the intent by Android, so that one must be mutable.
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or if (input != null) PendingIntent.FLAG_MUTABLE else PendingIntent.FLAG_IMMUTABLE
        val pending = PendingIntent.getBroadcast(context, (address + what).hashCode(), intent, flags)
        val b = NotificationCompat.Action.Builder(0, label, pending).setAuthenticationRequired(true)
        // No "Yes" / "No" chips made up by Android: a reply is only what the person typed.
        if (input != null) b.addRemoteInput(input).setAllowGeneratedReplies(false)
        return b.build()
    }

    /** All a locked phone, or an app with its lock on, says. */
    private fun discreet(kind: Alert, address: String) = NotificationCompat.Builder(context, AGENTS)
        .setSmallIcon(R.drawable.ic_stat_agent)
        .setContentTitle(if (kind == Alert.BLOCKED) "An agent needs you" else "An agent finished")
        .setContentText("Open Herdr to see")
        .setCategory(NotificationCompat.CATEGORY_MESSAGE)
        .setPriority(NotificationCompat.PRIORITY_HIGH)
        .setContentIntent(open(address))
        .setAutoCancel(true)

    /**
     * Shows an agent's notification. [question] is what its screen asks (a
     * blocked agent); [hidden] when the app lock is on; [quiet] to change
     * one already showing without sounding again.
     */
    fun alert(kind: Alert, agent: Agent, where: String, question: Question?, hidden: Boolean, quiet: Boolean = false) {
        if (!allowed(context)) return
        shown[agent.address] = Shown(kind, agent, where, question)
        val b = discreet(kind, agent.address).setOnlyAlertOnce(quiet)
        if (!hidden) {
            b.setContentTitle(if (kind == Alert.BLOCKED) "${agent.app} needs you · $where" else "${agent.app} finished · $where")
            b.setContentText(agent.title.ifBlank { if (kind == Alert.BLOCKED) "Waiting for your answer" else "Ready for you" })
            b.setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            b.setPublicVersion(discreet(kind, agent.address).build())
            if (kind == Alert.BLOCKED && question != null) {
                val lines = question.text.takeLast(5) + question.choices.map { "${if (it.selected) "❯" else "  "} ${it.number}. ${it.label}" }
                if (lines.isNotEmpty()) b.setStyle(NotificationCompat.BigTextStyle().bigText(lines.joinToString("\n")))
                val address = agent.address
                if (Prompts.allowKeys(question) != null) {
                    val label = Prompts.allowed(question)?.label?.let { if (it.length > 18) it.take(17) + "…" else it } ?: "Allow"
                    b.addAction(button(label, address, ACTION_ALLOW, question.mark))
                } else {
                    b.setSubText("Answer in the app")
                }
                b.addAction(button("Deny", address, ACTION_DENY, question.mark))
                val input = RemoteInput.Builder(KEY_REPLY).setLabel("Reply to ${agent.app}").build()
                b.addAction(button("Reply", address, ACTION_REPLY, question.mark, input))
            }
        }
        runCatching { manager.notify(id(agent.address), b.build()) }
    }

    /** Puts an agent's notification back as it was (an answer tapped that couldn't be taken). */
    fun restore(address: String, hidden: Boolean) {
        shown[address]?.let { alert(it.kind, it.agent, it.where, it.question, hidden, quiet = true) }
    }

    fun showing(address: String) = address in shown

    /** An answer that was not sent: says so, in the place of the agent's notification. */
    fun problem(address: String, title: String, text: String) {
        if (!allowed(context)) return
        shown.remove(address)
        val b = NotificationCompat.Builder(context, AGENTS)
            .setSmallIcon(R.drawable.ic_stat_agent)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setCategory(NotificationCompat.CATEGORY_ERROR)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setContentIntent(open(address))
            .setAutoCancel(true)
        runCatching { manager.notify(id(address), b.build()) }
    }

    fun settle(address: String) {
        shown.remove(address)
        manager.cancel(id(address))
    }

    /** Takes down every agent notification (the app lock was turned on or off: they'd show the wrong amount). */
    fun settleAll() {
        for (address in shown.keys.toList()) settle(address)
    }

    /** The quiet ongoing notification a foreground service needs: what it says is whether it is really watching. */
    fun watching(host: String, agents: List<Agent>, conn: Conn): Notification {
        val blocked = agents.count { it.state == "blocked" }
        val working = agents.count { it.state == "working" }
        val counts = listOfNotNull(
            if (blocked > 0) "$blocked blocked" else null,
            if (working > 0) "$working working" else null,
        ).joinToString(" · ").ifEmpty { "No agents running" }
        val (title, text) = when (conn) {
            Conn.Connected -> "Watching agents on $host" to counts
            is Conn.Connecting -> "Connecting to $host…" to "Agents can't reach you until it connects."
            else -> "Not connected to $host" to "Agents can't reach you. Tap to open Herdr."
        }
        return NotificationCompat.Builder(context, WATCH)
            .setSmallIcon(R.drawable.ic_stat_agent)
            .setContentTitle(title)
            .setContentText(text)
            .setOngoing(true)
            .setSilent(true)
            .setContentIntent(open(null))
            .build()
    }

    companion object {
        const val AGENTS = "agents"
        const val WATCH = "watch"
        const val EXTRA_AGENT = "herdr.agent"
        const val EXTRA_MARK = "herdr.mark"
        const val ACTION_ALLOW = "herdr.allow"
        const val ACTION_DENY = "herdr.deny"
        const val ACTION_REPLY = "herdr.reply"
        const val KEY_REPLY = "reply"
        const val WATCH_ID = 1

        fun allowed(context: Context) = Build.VERSION.SDK_INT < 33 ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
    }
}

/** Allow, Deny and Reply, straight from the notification. */
class AgentActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val app = context.applicationContext as HerdrApplication
        val address = intent.getStringExtra(Notifier.EXTRA_AGENT) ?: return
        val mark = intent.getStringExtra(Notifier.EXTRA_MARK) ?: return
        val what = intent.action ?: return
        val reply = if (what == Notifier.ACTION_REPLY) RemoteInput.getResultsFromIntent(intent)?.getCharSequence(Notifier.KEY_REPLY)?.toString() else null
        // Never from a locked phone (Android 12 and later ask to unlock first;
        // this covers the phones before it), and never past the app's own lock.
        val locked = context.getSystemService(KeyguardManager::class.java)?.isDeviceLocked == true
        if (locked || app.session.lockEnabled || (what == Notifier.ACTION_REPLY && reply.isNullOrBlank())) {
            app.notifier.restore(address, app.session.lockEnabled)
            return
        }
        // The answer is checked against the agent's screen and sent while this broadcast is held open.
        val pending = goAsync()
        app.answer(address, mark, deny = what == Notifier.ACTION_DENY, reply = reply) { pending.finish() }
    }
}

/**
 * Keeps the app (and its SSH connection) alive in the background while
 * agents may need you, with a quiet ongoing notification, as Android requires.
 */
class WatchService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val app = application as HerdrApplication
        val note = app.notifier.watching(app.session.machineLabel(null), app.session.agents(), app.session.conn)
        val type = if (Build.VERSION.SDK_INT >= 34) ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0
        try {
            ServiceCompat.startForeground(this, Notifier.WATCH_ID, note, type)
        } catch (e: Exception) {
            stopSelf()
            return START_NOT_STICKY
        }
        app.watching(true)
        // Android may have ended the app and started only this service again:
        // nothing is connected then, so connect, or the notification would lie.
        app.session.resume()
        return START_STICKY
    }

    override fun onDestroy() {
        (application as HerdrApplication).watching(false)
        super.onDestroy()
    }

    companion object {
        /** Asks for the service; it reports back itself once it runs (Android may refuse from the background). */
        fun start(context: Context) {
            runCatching { ContextCompat.startForegroundService(context, Intent(context, WatchService::class.java)) }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, WatchService::class.java))
        }

        fun update(context: Context, host: String, agents: List<Agent>, conn: Conn, notifier: Notifier) {
            if (!Notifier.allowed(context)) return
            runCatching { NotificationManagerCompat.from(context).notify(Notifier.WATCH_ID, notifier.watching(host, agents, conn)) }
        }
    }
}

/** Home-screen widget: how many agents are blocked, done and working. Tap to open. */
class AgentsWidget : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        val app = context.applicationContext as HerdrApplication
        render(context, app.session.agents(), app.session.conn is Conn.Connected, app.session.lockEnabled)
    }

    companion object {
        /** [hidden]: the app lock is on, so counts only, no agent's name or title. */
        fun render(context: Context, agents: List<Agent>, connected: Boolean, hidden: Boolean) {
            val manager = AppWidgetManager.getInstance(context)
            val ids = manager.getAppWidgetIds(ComponentName(context, AgentsWidget::class.java))
            if (ids.isEmpty()) return
            val views = RemoteViews(context.packageName, R.layout.widget_agents)
            fun count(state: String) = agents.count { it.state == state }.toString()
            views.setTextViewText(R.id.blocked, count("blocked"))
            views.setTextViewText(R.id.done, count("done"))
            views.setTextViewText(R.id.working, count("working"))
            val top = agents.firstOrNull { it.state == "blocked" } ?: agents.firstOrNull { it.state == "done" }
            views.setTextViewText(R.id.detail, when {
                !connected -> "Not connected"
                top != null && hidden -> "Open Herdr to see"
                top != null -> "${top.app}: ${top.title.ifBlank { top.state }}"
                else -> "All quiet"
            })
            val intent = Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                .putExtra(MainActivity.EXTRA_SCREEN, "agents")
            views.setOnClickPendingIntent(R.id.root, PendingIntent.getActivity(context, 7, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))
            manager.updateAppWidget(ids, views)
        }
    }
}
