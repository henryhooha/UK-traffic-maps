package uk.roadahead.car

import android.os.Handler
import android.os.Looper
import androidx.car.app.AppManager
import androidx.car.app.CarContext
import androidx.car.app.CarToast
import androidx.car.app.Screen
import androidx.car.app.model.*
import androidx.car.app.navigation.NavigationManager
import androidx.car.app.navigation.NavigationManagerCallback
import androidx.car.app.navigation.model.*
import androidx.car.app.model.Distance
import androidx.car.app.model.DateTimeWithZone
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import uk.roadahead.core.*
import uk.roadahead.core.Step as RouteStep
import uk.roadahead.service.DriveMode
import uk.roadahead.service.NavController
import java.util.TimeZone

/**
 * The main Android Auto screen: NavigationTemplate over our own map drawing.
 * Shows the next turn, ETA, and pops up look-ahead alerts with an "Avoid" action.
 */
class NavScreen(ctx: CarContext) : Screen(ctx) {
    private val renderer = MapRenderer(ctx)
    private val nav = ctx.getCarService(NavigationManager::class.java)
    private val handler = Handler(Looper.getMainLooper())
    private var lastInvalidate = 0L
    private var navigating = false
    private var jobs = mutableListOf<Job>()

    init {
        nav.setNavigationManagerCallback(object : NavigationManagerCallback {
            override fun onStopNavigation() { NavController.stopDrive() }
            override fun onAutoDriveEnabled() {
                // Desktop Head Unit "auto drive": simulate along the route.
                NavController.simSpeed = 4.0
                NavController.startDrive(DriveMode.SIM)
            }
        })
        ctx.getCarService(AppManager::class.java).setSurfaceCallback(renderer)

        lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onCreate(owner: LifecycleOwner) {
                jobs += lifecycleScope.launch {
                    NavController.state.collect { s ->
                        renderer.update(s)
                        val nowNav = s.mode != DriveMode.NONE
                        if (nowNav != navigating) { navigating = nowNav; if (nowNav) nav.navigationStarted() else nav.navigationEnded() }
                        throttledInvalidate()
                    }
                }
                jobs += lifecycleScope.launch { NavController.alerts.collect { showCarAlert(it) } }
            }
            override fun onDestroy(owner: LifecycleOwner) {
                if (navigating) nav.navigationEnded()
                nav.clearNavigationManagerCallback()
                jobs.forEach { it.cancel() }
            }
        })
    }

    private fun throttledInvalidate() {
        val now = System.currentTimeMillis()
        val wait = 1000 - (now - lastInvalidate)
        handler.removeCallbacksAndMessages(null)
        if (wait <= 0) { lastInvalidate = now; invalidate() } else handler.postDelayed({ lastInvalidate = System.currentTimeMillis(); invalidate() }, wait)
    }

    private var alertId = 0
    private fun showCarAlert(a: uk.roadahead.core.Alert) {
        if (a.kind == AlertKind.TURN && !a.key.startsWith("report") && a.key != "reroute") return // turns are in the routing card
        if (carContext.carAppApiLevel >= 5) {
            val b = androidx.car.app.model.Alert.Builder(++alertId, CarText.create("${a.title}"), if (a.kind == AlertKind.HAZARD) 12000 else 6000)
                .setIcon(CarIcons.glyph(a.icon))
            if (a.subtitle.isNotBlank()) b.setSubtitle(CarText.create(a.subtitle))
            if (a.kind == AlertKind.HAZARD && a.itemId != null) {
                b.addAction(Action.Builder().setTitle("Avoid").setOnClickListener { openDivert(a.itemId) }.build())
                b.addAction(Action.Builder().setTitle("Stay").setOnClickListener { carContext.getCarService(AppManager::class.java).dismissAlert(alertId) }.build())
            }
            runCatching { carContext.getCarService(AppManager::class.java).showAlert(b.build()) }
                .onFailure { CarToast.makeText(carContext, a.title, CarToast.LENGTH_LONG).show() }
        } else CarToast.makeText(carContext, "${a.icon} ${a.title}", CarToast.LENGTH_LONG).show()
    }

    private fun openDivert(itemId: String) {
        NavController.divert(itemId)
        screenManager.push(DivertScreen(carContext))
    }

    private fun icon(res: Int) = CarIcon.Builder(androidx.core.graphics.drawable.IconCompat.createWithResource(carContext, res)).build()

    private fun distance(m: Double): Distance {
        val mi = m / METRES_PER_MILE
        return if (mi >= 0.25) Distance.create(if (mi < 10) Math.round(mi * 10) / 10.0 else Math.round(mi).toDouble(), Distance.UNIT_MILES)
        else Distance.create((Math.round(m * 1.09361 / 10) * 10).toDouble(), Distance.UNIT_YARDS)
    }

    private fun maneuver(s: RouteStep): Maneuver {
        val left = s.modifier?.contains("left") == true
        val right = s.modifier?.contains("right") == true
        val slight = s.modifier?.contains("slight") == true
        val sharp = s.modifier?.contains("sharp") == true
        val type = when (s.maneuver) {
            "arrive" -> Maneuver.TYPE_DESTINATION
            "roundabout", "rotary" -> Maneuver.TYPE_ROUNDABOUT_ENTER_AND_EXIT_CW
            "off ramp" -> if (left) Maneuver.TYPE_OFF_RAMP_NORMAL_LEFT else Maneuver.TYPE_OFF_RAMP_NORMAL_RIGHT
            "on ramp" -> if (right) Maneuver.TYPE_ON_RAMP_NORMAL_RIGHT else Maneuver.TYPE_ON_RAMP_NORMAL_LEFT
            "fork" -> if (left) Maneuver.TYPE_FORK_LEFT else Maneuver.TYPE_FORK_RIGHT
            "merge" -> if (left) Maneuver.TYPE_MERGE_LEFT else if (right) Maneuver.TYPE_MERGE_RIGHT else Maneuver.TYPE_MERGE_SIDE_UNSPECIFIED
            else -> when {
                s.modifier == "uturn" -> Maneuver.TYPE_U_TURN_LEFT
                left && slight -> Maneuver.TYPE_TURN_SLIGHT_LEFT
                left && sharp -> Maneuver.TYPE_TURN_SHARP_LEFT
                left -> Maneuver.TYPE_TURN_NORMAL_LEFT
                right && slight -> Maneuver.TYPE_TURN_SLIGHT_RIGHT
                right && sharp -> Maneuver.TYPE_TURN_SHARP_RIGHT
                right -> Maneuver.TYPE_TURN_NORMAL_RIGHT
                else -> Maneuver.TYPE_STRAIGHT
            }
        }
        val b = Maneuver.Builder(type).setIcon(CarIcons.glyph(Instructions.arrow(s)))
        if (type == Maneuver.TYPE_ROUNDABOUT_ENTER_AND_EXIT_CW) b.setRoundaboutExitNumber(s.exit ?: 1)
        return b.build()
    }

    override fun onGetTemplate(): Template {
        val s = NavController.state.value
        if (!NavController.hasLocationPermission())
            return MessageTemplate.Builder("Open Road Ahead on your phone once and allow precise location.").setTitle("Location needed").setHeaderAction(Action.APP_ICON).build()

        val strip = ActionStrip.Builder()
        if (s.mode == DriveMode.NONE) {
            strip.addAction(Action.Builder().setTitle("Where to?").setIcon(icon(uk.roadahead.R.drawable.ic_search)).setOnClickListener { screenManager.push(SearchScreen(carContext)) }.build())
            if (s.active != null) strip.addAction(Action.Builder().setTitle("Go").setOnClickListener { NavController.startDrive(DriveMode.GPS) }.build())
        } else {
            strip.addAction(Action.Builder().setIcon(icon(uk.roadahead.R.drawable.ic_report)).setOnClickListener { screenManager.push(ReportScreen(carContext)) }.build())
            val nextHazard = s.active?.analysis?.items?.firstOrNull { (it.kind == ItemKind.ZONE || it.kind == ItemKind.EVENT) && it.start > s.along + 300 }
            if (nextHazard != null) strip.addAction(Action.Builder().setTitle("Avoid").setOnClickListener { openDivert(nextHazard.id) }.build())
            strip.addAction(Action.Builder().setIcon(icon(uk.roadahead.R.drawable.ic_stop)).setOnClickListener { NavController.stopDrive() }.build())
        }
        if (s.mode == DriveMode.NONE && s.active == null)
            strip.addAction(Action.Builder().setIcon(icon(uk.roadahead.R.drawable.ic_report)).setOnClickListener { screenManager.push(ReportScreen(carContext)) }.build())

        val mapStrip = ActionStrip.Builder()
            .addAction(Action.Builder().setIcon(icon(uk.roadahead.R.drawable.ic_zoom_in)).setOnClickListener { renderer.zoom(-1) }.build())
            .addAction(Action.Builder().setIcon(icon(uk.roadahead.R.drawable.ic_zoom_out)).setOnClickListener { renderer.zoom(1) }.build())
            .build()

        val b = NavigationTemplate.Builder().setActionStrip(strip.build()).setMapActionStrip(mapStrip)
            .setBackgroundColor(CarColor.createCustom(0xFF0B1D2E.toInt(), 0xFF0B1D2E.toInt()))
        val route = s.active
        when {
            s.busy != null -> b.setNavigationInfo(MessageInfo.Builder(s.busy).build())
            route == null -> b.setNavigationInfo(MessageInfo.Builder("Where to?").setText("Tap the search button. Hazards, gantry signs and cameras on your route will be announced before you reach them.").build())
            s.mode == DriveMode.NONE -> {
                val a = route.analysis
                b.setNavigationInfo(MessageInfo.Builder("${Math.round(route.score)} min · ${fmtMiles(route.distance)}")
                    .setText(if (a == null || a.items.none { it.kind == ItemKind.ZONE || it.kind == ItemKind.EVENT }) "Road ahead clear" else "${a.items.count { it.kind == ItemKind.ZONE || it.kind == ItemKind.EVENT }} items · +${a.totalDelayMinutes} min. Tap Go to start.").build())
            }
            else -> {
                val step = s.nextStep
                if (step != null) {
                    val cue = Instructions.text(step)
                    val st = Step.Builder(cue).setManeuver(maneuver(step))
                    listOfNotNull(step.ref?.split(';')?.first(), step.name).firstOrNull()?.let { st.setRoad(it) }
                    b.setNavigationInfo(RoutingInfo.Builder().setCurrentStep(st.build(), distance(step.from - s.along)).build())
                } else b.setNavigationInfo(MessageInfo.Builder("Follow the route").build())
                val remaining = route.distance - s.along
                val delays = route.analysis?.items?.filter { (it.kind == ItemKind.ZONE || it.kind == ItemKind.EVENT) && it.end > s.along }?.sumOf { it.delayMinutes } ?: 0.0
                val secs = (route.duration * (1 - s.along / route.distance) + delays * 60).toLong()
                val arrive = DateTimeWithZone.create(System.currentTimeMillis() + secs * 1000, TimeZone.getDefault())
                b.setDestinationTravelEstimate(TravelEstimate.Builder(distance(remaining), arrive).setRemainingTimeSeconds(secs)
                    .setRemainingTimeColor(if (delays >= 5) CarColor.RED else if (delays > 0) CarColor.YELLOW else CarColor.GREEN).build())
            }
        }
        return b.build()
    }
}
