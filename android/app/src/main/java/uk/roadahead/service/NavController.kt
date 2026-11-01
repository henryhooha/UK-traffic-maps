package uk.roadahead.service

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Location
import androidx.core.content.ContextCompat
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import uk.roadahead.core.*
import java.io.File
import kotlin.math.min

enum class DriveMode { NONE, GPS, SIM }

data class Diversion(val hazard: Item, val stayMinutes: Int, val stayDelay: Int, val options: List<Route>)

data class SourceStatus(val name: String, val count: Int, val error: String?)

data class NavState(
    val routes: List<Route> = emptyList(),
    val active: Route? = null,
    val destination: Place? = null,
    val along: Double = 0.0,
    val position: Pt? = null,
    val heading: Double? = null,
    val speedMs: Double? = null,
    val accuracy: Float? = null,
    val mode: DriveMode = DriveMode.NONE,
    val limit: Int? = null,
    val nextStep: Step? = null,
    val lastAlert: Alert? = null,
    val busy: String? = null,
    val error: String? = null,
    val diversion: Diversion? = null,
    val reports: List<Report> = emptyList(),
    val sources: List<SourceStatus> = emptyList(),
    val demo: Boolean = true,
    val analysisVersion: Int = 0,
    val routesVersion: Int = 0,
)

/**
 * Single source of truth shared by the phone UI, the drive service and the
 * Android Auto screens. Holds the plan, live events and drive progress.
 */
object NavController {
    private lateinit var app: Context
    lateinit var settings: Settings; private set
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val mutex = Mutex()

    private val _state = MutableStateFlow(NavState())
    val state: StateFlow<NavState> = _state
    private val _alerts = MutableSharedFlow<Alert>(extraBufferCapacity = 16)
    val alerts: SharedFlow<Alert> = _alerts

    private val demo = DemoProvider()
    private val datexTable = HashMap<String, Datex2.SignMeta>()
    private var liveEvents: List<Event> = emptyList()
    private var lastLiveFetch = 0L
    private val cameraCache = HashMap<String, List<Event>>() // route id → cameras
    private val engine = AlertEngine()
    private var tracker: LocationTracker? = null
    private var simJob: Job? = null
    private var refreshJob: Job? = null
    private var offRoute = 0
    private var seq = 0
    private var replanning = false

    fun init(ctx: Context) {
        app = ctx.applicationContext
        settings = Settings(app)
        _state.value = _state.value.copy(reports = loadReports(), demo = demoOn())
        tracker = LocationTracker(app, ::onFix)
    }

    private fun demoOn() = settings.bool("demo") && !settings.hasLiveSignalFeed()
    private fun update(f: (NavState) -> NavState) { _state.value = f(_state.value) }
    private fun bumpAnalysis() = update { it.copy(analysisVersion = it.analysisVersion + 1) }
    fun hasLocationPermission() = ContextCompat.checkSelfPermission(app, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED

    // ------------------------------------------------------------ search & planning
    suspend fun search(q: String): List<Place> = withContext(Dispatchers.IO) {
        Geocoder.search(settings.str("nominatimUrl"), q, _state.value.position)
    }

    /** Ask for a fresh GPS position (for "from: my location" and passive map). */
    fun locateOnce(cb: (Pt?) -> Unit = {}) {
        if (!hasLocationPermission()) { cb(null); return }
        tracker?.lastKnown { loc ->
            val p = loc?.let { Pt(it.longitude, it.latitude) }
            if (p != null) update { it.copy(position = p, heading = loc.bearing.toDouble().takeIf { loc.hasBearing() }) }
            cb(p)
        }
    }

    fun plan(from: Pt?, to: Place, startNavigating: Boolean = false) {
        scope.launch {
            update { it.copy(busy = "Checking the road ahead…", error = null, diversion = null) }
            try {
                val start = from ?: _state.value.position ?: suspendCancellableCoroutine<Pt?> { c -> locateOnce { c.resumeWith(Result.success(it)) } }
                    ?: throw Exception("No GPS fix yet — choose a start point")
                val routes = withContext(Dispatchers.IO) { Osrm.route(settings.str("osrmUrl"), listOf(start, to.location), 2) }
                routes.forEachIndexed { i, r -> r.id = "r${seq++}"; r.label = if (i == 0) "Fastest" else "Alternative $i" }
                settings.addRecent(to.name.split(",").take(2).joinToString(","), to.location)
                mutex.withLock {
                    refreshLive(force = false)
                    for (r in routes) analyse(r)
                }
                val sorted = routes.sortedBy { it.score }
                sorted.first().label = "Recommended"
                engine.reset()
                update { it.copy(routes = sorted, active = sorted.first(), destination = to, along = 0.0, busy = null, routesVersion = it.routesVersion + 1, nextStep = sorted.first().steps.firstOrNull { s -> Instructions.announce(s) }) }
                bumpAnalysis()
                if (startNavigating) startDrive(DriveMode.GPS)
            } catch (e: Exception) {
                update { it.copy(busy = null, error = e.message ?: "Routing failed") }
            }
        }
    }

    fun select(routeId: String) {
        val r = _state.value.routes.firstOrNull { it.id == routeId } ?: return
        engine.reset()
        update { it.copy(active = r, along = 0.0) }
        bumpAnalysis()
    }

    // ------------------------------------------------------------ live data
    private suspend fun refreshLive(force: Boolean) = withContext(Dispatchers.IO) {
        if (!force && System.currentTimeMillis() - lastLiveFetch < 55_000 && liveEvents.isNotEmpty()) return@withContext
        val status = mutableListOf<SourceStatus>()
        val all = mutableListOf<Event>()
        if (settings.bool("tfl")) runCatching { Tfl.fetch(settings.str("tflKey")) }
            .onSuccess { all += it; status += SourceStatus("TfL", it.size, null) }
            .onFailure { status += SourceStatus("TfL", 0, it.message) }
        val nh = listOf("nhSituationsUrl", "nhPlannedUrl", "nhVmsTableUrl", "nhVmsStatusUrl", "nhSignalTableUrl", "nhSignalStatusUrl").map { settings.str(it) }.filter { it.isNotBlank() }
        if (nh.isNotEmpty()) {
            var n = 0; var err: String? = null
            // Tables first so status messages can be placed.
            for (u in nh.sortedBy { if (it.contains("table", true)) 0 else 1 }) runCatching { Datex2.parse(Http.get(u, settings.nhHeaders()), "national-highways", datexTable) }
                .onSuccess { all += it; n += it.size }.onFailure { err = it.message }
            status += SourceStatus("National Highways", n, err)
        }
        val extra = settings.str("extraDatexUrls").split(Regex("[\\s,]+")).filter { it.startsWith("http") }
        for (u in extra) runCatching { Datex2.parse(Http.get(u), "datex2", datexTable) }
            .onSuccess { all += it; status += SourceStatus(java.net.URL(u).host, it.size, null) }
            .onFailure { status += SourceStatus(u, 0, it.message) }
        liveEvents = all
        lastLiveFetch = System.currentTimeMillis()
        update { it.copy(sources = status, demo = demoOn()) }
    }

    private suspend fun analyse(r: Route) = withContext(Dispatchers.IO) {
        if (r.junctions == null) r.junctions = runCatching { Overpass.nameJunctions(settings.str("overpassUrl"), r.exits) }.getOrDefault(r.exits)
        val cams = if (settings.bool("cameras")) cameraCache.getOrPut(r.id) {
            runCatching { Overpass.cameras(settings.str("overpassUrl"), r.line) }.getOrDefault(emptyList())
        } else emptyList()
        val events = ArrayList<Event>(liveEvents)
        if (demoOn()) { demo.prepareRoute(r); events += demo.events() }
        events += cams
        val now = System.currentTimeMillis()
        events += _state.value.reports.filter { it.active(now) }.map { it.toEvent() }
        r.analysis = Analyser.analyse(r, events)
    }

    fun refresh(force: Boolean = true) {
        scope.launch {
            mutex.withLock {
                refreshLive(force)
                _state.value.active?.let { analyse(it) }
            }
            update { it.copy(sources = it.sources) }
            bumpAnalysis()
        }
    }

    // ------------------------------------------------------------ driving
    fun startDrive(mode: DriveMode) {
        val r = _state.value.active ?: return
        if (mode == DriveMode.GPS && !hasLocationPermission()) { update { it.copy(error = "Location permission needed — open the app on your phone") }; return }
        stopTracking()
        engine.settings = settings.alertSettings
        update { it.copy(mode = mode, error = null) }
        DriveService.start(app)
        if (mode == DriveMode.GPS) tracker?.start()
        else simJob = scope.launch {
            var last = System.currentTimeMillis()
            while (isActive) {
                delay(500)
                val now = System.currentTimeMillis()
                val dt = (now - last) / 1000.0; last = now
                val s = _state.value
                val route = s.active ?: break
                val roadMph = if (isMotorwayRef(route.roadAt(s.along)?.ref)) 70.0 else 40.0
                val mph = min(roadMph, (s.limit ?: 70).toDouble())
                val along = min(route.distance, s.along + mphToMs(mph) * dt * simSpeed)
                val p = route.line.pointAt(along)
                progress(route, along, p, route.line.bearingAt(along), mphToMs(mph))
                if (along >= route.distance - 5) { stopDrive(); break }
            }
        }
        refreshJob = scope.launch { while (isActive) { delay(60_000); refresh(force = true) } }
        _state.value.active?.let { progress(it, _state.value.along, _state.value.position ?: r.coords.first(), null, null) }
    }

    @Volatile var simSpeed = 10.0

    private fun stopTracking() { tracker?.stop(); simJob?.cancel(); refreshJob?.cancel(); simJob = null; refreshJob = null }

    fun stopDrive() {
        stopTracking()
        update { it.copy(mode = DriveMode.NONE) }
        DriveService.stop(app)
    }

    private fun onFix(loc: Location) {
        val p = Pt(loc.longitude, loc.latitude)
        val heading = if (loc.hasBearing() && loc.hasSpeed() && loc.speed > 3) loc.bearing.toDouble() else null
        val s = _state.value
        update { it.copy(position = p, heading = heading ?: it.heading, speedMs = if (loc.hasSpeed()) loc.speed.toDouble() else null, accuracy = loc.accuracy) }
        val route = s.active ?: return
        if (s.mode != DriveMode.GPS) return
        val hit = route.line.snap(p, 60.0 + loc.accuracy, heading, 70.0, minAlong = s.along - 300)
            ?: route.line.snap(p, 40.0 + loc.accuracy, minAlong = s.along - 300)
        if (hit == null) {
            if (loc.accuracy < 50 && ++offRoute >= 5) { offRoute = 0; replanFrom(p) }
            return
        }
        offRoute = 0
        progress(route, maxOf(s.along - 50, hit.along), p, heading, if (loc.hasSpeed()) loc.speed.toDouble() else null)
    }

    private fun progress(route: Route, along: Double, p: Pt, heading: Double?, speed: Double?) {
        val limit = engine.currentGantryLimit(route, along)
        val alerts = engine.update(route, along, speed, limit)
        val next = route.steps.firstOrNull { it.from > along + 5 && Instructions.announce(it) }
        update { it.copy(along = along, position = p, heading = heading ?: route.line.bearingAt(along), speedMs = speed ?: it.speedMs, limit = limit, nextStep = next, lastAlert = alerts.firstOrNull { a -> a.kind != AlertKind.TURN } ?: it.lastAlert) }
        alerts.forEach { _alerts.tryEmit(it) }
        val la = _state.value.lastAlert
        if (la?.itemId != null) {
            val item = route.analysis?.items?.firstOrNull { it.id == la.itemId }
            if (item == null || item.end < along) update { it.copy(lastAlert = null) }
        }
        if (along >= route.distance - 30 && _state.value.mode == DriveMode.GPS) {
            _alerts.tryEmit(Alert(AlertKind.TURN, "arrive", "You have arrived", "", "You have arrived.", Severity.LOW, 0.0, icon = "🏁"))
            stopDrive()
        }
    }

    fun dismissAlert() = update { it.copy(lastAlert = null) }

    private fun replanFrom(p: Pt) {
        val dest = _state.value.destination ?: return
        if (replanning) return
        replanning = true
        scope.launch {
            try {
                _alerts.tryEmit(Alert(AlertKind.TURN, "reroute", "Re-routing", "", "Re-routing.", Severity.LOW, 0.0, icon = "↻"))
                val routes = withContext(Dispatchers.IO) { Osrm.route(settings.str("osrmUrl"), listOf(p, dest.location), 1) }
                routes.forEach { it.id = "r${seq++}"; it.label = "Re-route" }
                mutex.withLock { routes.forEach { analyse(it) } }
                engine.reset()
                val best = routes.minBy { it.score }
                update { it.copy(routes = listOf(best), active = best, along = 0.0, routesVersion = it.routesVersion + 1) }
                bumpAnalysis()
            } catch (e: Exception) { update { it.copy(error = "Re-route failed: ${e.message}") } } finally { replanning = false }
        }
    }

    // ------------------------------------------------------------ diversions
    fun divert(itemId: String) {
        val s = _state.value
        val cur = s.active ?: return
        val hazard = cur.analysis?.items?.firstOrNull { it.id == itemId } ?: return
        scope.launch {
            update { it.copy(busy = "Finding a way round…", diversion = null) }
            try {
                val from = if (s.along > 0) cur.line.pointAt(s.along) else cur.coords.first()
                val to = s.destination?.location ?: cur.coords.last()
                val remainingDelay = cur.analysis!!.items.filter { (it.kind == ItemKind.ZONE || it.kind == ItemKind.EVENT) && it.end > s.along }.sumOf { it.delayMinutes }
                val stay = (cur.duration / 60 * (1 - s.along / cur.distance) + remainingDelay)
                val cands = mutableListOf<Route>()
                val osrm = settings.str("osrmUrl")
                withContext(Dispatchers.IO) {
                    runCatching { cands += Osrm.route(osrm, listOf(from, to), 2).onEach { it.label = "Alternative from here" } }
                    val brg = cur.line.bearingAt((hazard.start + hazard.end) / 2)
                    for (off in listOf(5000.0, 10000.0)) for (side in listOf(90.0, -90.0)) runCatching {
                        val via = destination(hazard.location, brg + side, off)
                        cands += Osrm.route(osrm, listOf(from, via, to), 0).onEach { it.label = "Detour ${if (side > 0) "right" else "left"} of the ${hazard.road ?: "hazard"}" }
                    }
                }
                val opts = mutableListOf<Route>()
                mutex.withLock {
                    for (r in cands) {
                        if (opts.any { o -> o.summary == r.summary && kotlin.math.abs(o.distance - r.distance) < r.distance * 0.08 }) continue
                        r.id = "r${seq++}"
                        analyse(r)
                        if (r.analysis!!.items.any { Analyser.sameHazard(hazard, it) }) continue
                        if (haversine(r.coords.first(), from) > 3000) continue
                        opts += r
                    }
                }
                opts.sortBy { it.score }
                update { it.copy(busy = null, diversion = Diversion(hazard, Math.round(stay).toInt(), Math.round(remainingDelay).toInt(), opts.take(3))) }
            } catch (e: Exception) { update { it.copy(busy = null, error = "Diversion failed: ${e.message}") } }
        }
    }

    fun chooseDiversion(index: Int) {
        val d = _state.value.diversion ?: return
        val r = d.options.getOrNull(index) ?: return
        r.label = "Diversion"
        engine.reset()
        update { it.copy(routes = listOf(r), active = r, along = 0.0, diversion = null, lastAlert = null, routesVersion = it.routesVersion + 1) }
        bumpAnalysis()
    }

    fun cancelDiversion() = update { it.copy(diversion = null) }

    // ------------------------------------------------------------ reports
    private val reportsFile get() = File(app.filesDir, "reports.json")
    private fun loadReports() = runCatching { Report.listFromJson(reportsFile.readText()) }.getOrDefault(emptyList())
    private fun saveReports(l: List<Report>) = runCatching { reportsFile.writeText(Report.listToJson(l).toString()) }

    /** Report something at your current position, in your direction of travel. */
    fun report(type: ReportType): Report? {
        val s = _state.value
        val route = s.active
        val p = s.position ?: route?.line?.pointAt(s.along) ?: return null.also { update { it.copy(error = "No position yet — can't place the report") } }
        val brg = s.heading ?: route?.line?.bearingAt(s.along)
        val road = route?.roadAt(s.along)?.ref?.split(';')?.first()
        val r = Report("${System.currentTimeMillis()}", type, p, brg, road, System.currentTimeMillis())
        val list = (s.reports.filter { it.active(System.currentTimeMillis()) } + r)
        saveReports(list)
        update { it.copy(reports = list) }
        _alerts.tryEmit(Alert(AlertKind.TURN, "report:${r.id}", "Reported: ${type.label}", "Thanks — saved on your map", "${type.label} reported.", Severity.LOW, 0.0, icon = type.icon))
        refresh(force = false)
        return r
    }

    fun deleteReport(id: String) {
        val list = _state.value.reports.filter { it.id != id }
        saveReports(list); update { it.copy(reports = list) }; refresh(false)
    }

    fun shareReportToOsm(id: String, cb: (String) -> Unit) {
        val r = _state.value.reports.firstOrNull { it.id == id } ?: return
        scope.launch(Dispatchers.IO) {
            val msg = runCatching { OsmNotes.create(settings.str("osmApiUrl"), r); "Sent to OpenStreetMap as a public note" }.getOrElse { "OSM note failed: ${it.message}" }
            if (msg.startsWith("Sent")) {
                val list = _state.value.reports.map { if (it.id == id) it.copy(sharedToOsm = true) else it }
                saveReports(list); update { it.copy(reports = list) }
            }
            cb(msg)
        }
    }

    fun clearError() = update { it.copy(error = null) }

    fun settingsChanged() {
        engine.settings = settings.alertSettings
        cameraCache.clear()
        lastLiveFetch = 0
        update { it.copy(demo = demoOn()) }
        refresh(true)
    }
}
