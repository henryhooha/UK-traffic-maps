package uk.roadahead.car

import android.content.Intent
import androidx.car.app.CarAppService
import androidx.car.app.Screen
import androidx.car.app.Session
import androidx.car.app.validation.HostValidator

/** Entry point Android Auto binds to (navigation category). */
class RoadAheadCarService : CarAppService() {
    // Personal sideloaded build: accept any host (Android Auto, Desktop Head Unit).
    override fun createHostValidator(): HostValidator = HostValidator.ALLOW_ALL_HOSTS_VALIDATOR
    override fun onCreateSession(): Session = NavSession()
}

class NavSession : Session() {
    override fun onCreateScreen(intent: Intent): Screen = NavScreen(carContext)
}
