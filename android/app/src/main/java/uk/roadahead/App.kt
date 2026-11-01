package uk.roadahead

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import uk.roadahead.core.AlertKind
import uk.roadahead.service.NavController
import uk.roadahead.service.Speaker

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CH_DRIVE, "Driving", NotificationManager.IMPORTANCE_LOW).apply {
            description = "Shown while Road Ahead is tracking your drive"
        })
        nm.createNotificationChannel(NotificationChannel(CH_ALERTS, "Road alerts", NotificationManager.IMPORTANCE_HIGH).apply {
            description = "Hazards, lane closures, cameras ahead"
            enableVibration(true)
        })
        NavController.init(this)
        // Voice lives for the whole process so it works from the phone, the
        // background service and Android Auto alike.
        val speaker = Speaker(this)
        CoroutineScope(SupervisorJob() + Dispatchers.Main).launch {
            NavController.alerts.collect { a ->
                if (NavController.settings.bool("voice")) speaker.speak(a.spoken, urgent = a.kind != AlertKind.TURN)
            }
        }
    }

    companion object {
        const val CH_DRIVE = "drive"
        const val CH_ALERTS = "alerts"
    }
}
