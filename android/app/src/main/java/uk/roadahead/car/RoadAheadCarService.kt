package uk.roadahead.car

import android.content.Intent
import androidx.car.app.CarAppService
import androidx.car.app.Screen
import androidx.car.app.Session
import androidx.car.app.validation.HostValidator

/** Entry point Android Auto binds to (navigation category). */
class RoadAheadCarService : CarAppService() {
    // Debug builds accept any host (e.g. the Desktop Head Unit); release builds —
    // what goes to Google Play — only accept Google's official Android Auto hosts.
    override fun createHostValidator(): HostValidator =
        if (applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE != 0) HostValidator.ALLOW_ALL_HOSTS_VALIDATOR
        else HostValidator.Builder(applicationContext).addAllowedHosts(androidx.car.app.R.array.hosts_allowlist_sample).build()
    override fun onCreateSession(): Session = NavSession()
}

class NavSession : Session() {
    override fun onCreateScreen(intent: Intent): Screen = NavScreen(carContext)
}
