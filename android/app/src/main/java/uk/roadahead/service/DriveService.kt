package uk.roadahead.service

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.Person
import androidx.core.app.RemoteInput
import androidx.core.graphics.drawable.IconCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import uk.roadahead.App
import uk.roadahead.R
import uk.roadahead.core.AlertKind
import uk.roadahead.core.ItemKind
import uk.roadahead.core.fmtMiles
import uk.roadahead.ui.MainActivity

/**
 * Foreground service that keeps GPS, voice and alerts running while the screen
 * is off or another app (or Android Auto) is in front.
 */
class DriveService : LifecycleService() {
    private val nm by lazy { getSystemService(NotificationManager::class.java) }

    override fun onCreate() {
        super.onCreate()
        val type = if (Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION else 0
        try {
            ServiceCompat.startForeground(this, NOTIF_DRIVE, ongoing("Starting…"), type)
        } catch (e: Exception) {
            // Android may refuse a location FGS when started from the background;
            // tracking still runs while the app or Android Auto is on screen.
            stopSelf(); return
        }

        lifecycleScope.launch {
            NavController.alerts.collect { a ->
                if (a.kind == AlertKind.HAZARD || a.kind == AlertKind.CAMERA) heads_up(a.icon + " " + a.title, a.subtitle, a.itemId)
            }
        }
        lifecycleScope.launch {
            NavController.state.map { s ->
                val r = s.active
                val next = r?.analysis?.items?.firstOrNull { (it.kind == ItemKind.ZONE || it.kind == ItemKind.EVENT) && it.end > s.along }
                when {
                    r == null -> "No route"
                    next != null -> "${next.type.icon} ${next.headline} in ${fmtMiles((next.start - s.along).coerceAtLeast(0.0))}"
                    else -> "Road ahead clear · ${fmtMiles(r.distance - s.along)} to go"
                }
            }.distinctUntilChanged().collectLatest { nm.notify(NOTIF_DRIVE, ongoing(it)) }
        }
    }

    private fun openApp(): PendingIntent = PendingIntent.getActivity(this, 0,
        Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP), PendingIntent.FLAG_IMMUTABLE)

    private fun ongoing(text: String): Notification = NotificationCompat.Builder(this, App.CH_DRIVE)
        .setSmallIcon(R.drawable.ic_notify).setContentTitle("Road Ahead — driving").setContentText(text)
        .setOngoing(true).setOnlyAlertOnce(true).setCategory(NotificationCompat.CATEGORY_NAVIGATION)
        .setContentIntent(openApp())
        .addAction(0, "Stop", PendingIntent.getService(this, 1, Intent(this, DriveService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE))
        .build()

    /**
     * Hazard alerts are posted as a messaging-style conversation from
     * "Road Ahead". Android Auto (17.9+) hides sideloaded car apps, but still
     * shows and reads aloud messaging notifications — so alerts reach the car
     * screen even while you navigate with Waze or Google Maps. Replying by
     * voice ("accident", "car stopped", "mobile camera"…) files a report.
     */
    @Suppress("FunctionName")
    private fun heads_up(title: String, text: String, itemId: String?) {
        val now = System.currentTimeMillis()
        recent.addLast(Triple("$title${if (text.isNotBlank()) " — $text" else ""}", now, itemId))
        while (recent.size > 5) recent.removeFirst()
        nm.notify(NOTIF_ALERT, alertNotification(this, recent.toList()))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        if (intent?.action == ACTION_STOP) { NavController.stopDrive(); stopSelf() }
        return START_NOT_STICKY
    }


    private val recent = ArrayDeque<Triple<String, Long, String?>>()

    companion object {
        private const val NOTIF_DRIVE = 1
        const val NOTIF_ALERT = 2

        fun alertNotification(ctx: Context, msgs: List<Triple<String, Long, String?>>): Notification {
            val bot = Person.Builder().setName("Road Ahead").setKey("road-ahead").setBot(true)
                .setIcon(IconCompat.createWithResource(ctx, R.drawable.ic_notify)).build()
            val me = Person.Builder().setName("You").setKey("me").build()
            val style = NotificationCompat.MessagingStyle(me).setConversationTitle("Road Ahead alerts").setGroupConversation(false)
            msgs.forEach { (t, time, _) -> style.addMessage(t, time, bot) }
            val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
            val reply = NotificationCompat.Action.Builder(R.drawable.ic_report, "Report",
                PendingIntent.getBroadcast(ctx, 10, Intent(ctx, AlertActionReceiver::class.java).setAction(AlertActionReceiver.ACTION_REPLY), flags))
                .addRemoteInput(RemoteInput.Builder(AlertActionReceiver.KEY_TEXT).setLabel("Report (e.g. \"car stopped\")").build())
                .setSemanticAction(NotificationCompat.Action.SEMANTIC_ACTION_REPLY).setShowsUserInterface(false).build()
            val read = NotificationCompat.Action.Builder(R.drawable.ic_stop, "Got it",
                PendingIntent.getBroadcast(ctx, 11, Intent(ctx, AlertActionReceiver::class.java).setAction(AlertActionReceiver.ACTION_READ), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))
                .setSemanticAction(NotificationCompat.Action.SEMANTIC_ACTION_MARK_AS_READ).setShowsUserInterface(false).build()
            return NotificationCompat.Builder(ctx, App.CH_ALERTS)
                .setSmallIcon(R.drawable.ic_notify).setStyle(style)
                .setContentTitle("Road Ahead").setContentText(msgs.lastOrNull()?.first ?: "")
                .setPriority(NotificationCompat.PRIORITY_HIGH).setCategory(NotificationCompat.CATEGORY_MESSAGE)
                .setOnlyAlertOnce(false).setAutoCancel(true).setTimeoutAfter(10 * 60_000)
                .setContentIntent(PendingIntent.getActivity(ctx, 0, Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP), PendingIntent.FLAG_IMMUTABLE))
                .addAction(reply).addInvisibleAction(read)
                .build()
        }

        private const val ACTION_STOP = "stop"
        fun start(ctx: Context) = runCatching { ContextCompat.startForegroundService(ctx, Intent(ctx, DriveService::class.java)) }
        fun stop(ctx: Context) { ctx.stopService(Intent(ctx, DriveService::class.java)) }
    }
}
