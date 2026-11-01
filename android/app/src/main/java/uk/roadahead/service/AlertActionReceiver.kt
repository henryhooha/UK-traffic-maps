package uk.roadahead.service

import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.RemoteInput
import uk.roadahead.core.ReportType

/** Handles "Report" (voice reply) and "Got it" (mark as read) from alert notifications, incl. on Android Auto. */
class AlertActionReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        when (intent.action) {
            ACTION_REPLY -> {
                val text = RemoteInput.getResultsFromIntent(intent)?.getCharSequence(KEY_TEXT)?.toString()
                val type = ReportType.fromSpeech(text)
                val msg = if (type != null && NavController.report(type) != null) "${type.icon} ${type.label} reported — thanks"
                    else "Didn't catch that. Say e.g. \"accident\", \"car stopped\", \"lane blocked\", \"police\" or \"mobile camera\"."
                nm.notify(DriveService.NOTIF_ALERT, DriveService.alertNotification(ctx, listOf(Triple(msg, System.currentTimeMillis(), null))))
            }
            ACTION_READ -> nm.cancel(DriveService.NOTIF_ALERT)
        }
    }

    companion object {
        const val ACTION_REPLY = "uk.roadahead.REPLY"
        const val ACTION_READ = "uk.roadahead.READ"
        const val KEY_TEXT = "text"
    }
}
