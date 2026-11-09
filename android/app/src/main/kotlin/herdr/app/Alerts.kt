package herdr.app

import android.Manifest
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

/**
 * Phone notifications for agents: one per agent that is blocked or just
 * finished, with Allow / Deny (Enter / Esc) and an inline Reply for a
 * blocked one. Tapping opens the app on that agent.
 */
class Notifier(private val context: Context) {
    private val manager = NotificationManagerCompat.from(context)

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

    private fun action(address: String, what: String, mutable: Boolean = false): PendingIntent {
        val intent = Intent(context, AgentActionReceiver::class.java).setAction(what).putExtra(EXTRA_AGENT, address)
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or if (mutable) PendingIntent.FLAG_MUTABLE else PendingIntent.FLAG_IMMUTABLE
        return PendingIntent.getBroadcast(context, (address + what).hashCode(), intent, flags)
    }

    fun alert(kind: Alert, agent: Agent, where: String) {
        if (!allowed(context)) return
        val title = when (kind) {
            Alert.BLOCKED -> "${agent.app} needs you · $where"
            Alert.DONE -> "${agent.app} finished · $where"
        }
        val b = NotificationCompat.Builder(context, AGENTS)
            .setSmallIcon(R.drawable.ic_stat_agent)
            .setContentTitle(title)
            .setContentText(agent.title.ifBlank { if (kind == Alert.BLOCKED) "Waiting for your answer" else "Ready for you" })
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setContentIntent(open(agent.address))
            .setAutoCancel(true)
            .setOnlyAlertOnce(false)
        if (kind == Alert.BLOCKED) {
            b.addAction(0, "Allow", action(agent.address, ACTION_ALLOW))
            b.addAction(0, "Deny", action(agent.address, ACTION_DENY))
            val input = RemoteInput.Builder(KEY_REPLY).setLabel("Reply to ${agent.app}").build()
            b.addAction(NotificationCompat.Action.Builder(0, "Reply", action(agent.address, ACTION_REPLY, mutable = true)).addRemoteInput(input).build())
        }
        runCatching { manager.notify(id(agent.address), b.build()) }
    }

    fun settle(address: String) = manager.cancel(id(address))

    /** The quiet ongoing notification a foreground service needs. */
    fun watching(host: String, agents: List<Agent>): Notification {
        val blocked = agents.count { it.state == "blocked" }
        val working = agents.count { it.state == "working" }
        val text = listOfNotNull(
            if (blocked > 0) "$blocked blocked" else null,
            if (working > 0) "$working working" else null,
        ).joinToString(" · ").ifEmpty { "No agents running" }
        return NotificationCompat.Builder(context, WATCH)
            .setSmallIcon(R.drawable.ic_stat_agent)
            .setContentTitle("Watching agents on $host")
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
        const val ACTION_ALLOW = "herdr.allow"
        const val ACTION_DENY = "herdr.deny"
        const val ACTION_REPLY = "herdr.reply"
        const val KEY_REPLY = "reply"
        const val WATCH_ID = 1

        fun allowed(context: Context) = Build.VERSION.SDK_INT < 33 ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
    }
}

/** Allow (Enter), Deny (Esc) and Reply, straight from the notification. */
class AgentActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val app = context.applicationContext as HerdrApplication
        val address = intent.getStringExtra(Notifier.EXTRA_AGENT) ?: return
        when (intent.action) {
            Notifier.ACTION_ALLOW -> app.session.answerKeys(address, listOf("enter"))
            Notifier.ACTION_DENY -> app.session.answerKeys(address, listOf("esc"))
            Notifier.ACTION_REPLY -> {
                val text = RemoteInput.getResultsFromIntent(intent)?.getCharSequence(Notifier.KEY_REPLY)?.toString()
                if (!text.isNullOrBlank()) app.session.answerText(address, text)
            }
        }
        app.notifier.settle(address)
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
        val note = app.notifier.watching(app.session.machineLabel(null), app.session.agents())
        val type = if (Build.VERSION.SDK_INT >= 34) ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0
        runCatching { ServiceCompat.startForeground(this, Notifier.WATCH_ID, note, type) }.onFailure { stopSelf() }
        return START_STICKY
    }

    companion object {
        fun start(context: Context) {
            runCatching { ContextCompat.startForegroundService(context, Intent(context, WatchService::class.java)) }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, WatchService::class.java))
        }

        fun update(context: Context, host: String, agents: List<Agent>, notifier: Notifier) {
            if (!Notifier.allowed(context)) return
            runCatching { NotificationManagerCompat.from(context).notify(Notifier.WATCH_ID, notifier.watching(host, agents)) }
        }
    }
}

/** Home-screen widget: how many agents are blocked, done and working. Tap to open. */
class AgentsWidget : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        val app = context.applicationContext as HerdrApplication
        render(context, app.session.agents(), app.session.conn is Conn.Connected)
    }

    companion object {
        fun render(context: Context, agents: List<Agent>, connected: Boolean) {
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
