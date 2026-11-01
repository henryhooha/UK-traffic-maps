package uk.roadahead.core

import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Demo smart-motorway simulator (port of server/providers/demo.js). Creates
 * plausible gantry settings, message signs and incidents on the motorways a
 * route uses. Everything is source = "demo" and badged DEMO in the UI.
 */
class DemoProvider(private val now: () -> Long = System::currentTimeMillis) {
    private data class Entry(val event: Event, val expires: Long)
    private data class Def(val kind: String, val weight: Int, val title: String, val type: EventType, val lanes: (Int) -> List<Int>, val vms: List<String>, val delay: Int, val severity: Severity)
    private class Scenario(val id: String, val ref: String, val def: Def, val lanesTotal: Int, val lanes: List<Int>, val location: Pt, val bearing: Double)

    private val store = LinkedHashMap<String, Entry>()
    private val scenarios = HashMap<String, Scenario?>()
    private val TTL = 3 * 3600 * 1000L
    private val SPACING = 1100.0

    private val defs = listOf(
        Def("accident", 3, "Accident", EventType.ACCIDENT, { listOf(1, 2) }, listOf("ACCIDENT", "2 LANES CLOSED", "5 MILES"), 18, Severity.HIGH),
        Def("breakdown", 3, "Broken down vehicle", EventType.STOPPED_VEHICLE, { listOf(1) }, listOf("BROKEN DOWN", "VEHICLE", "LANE 1 CLOSED"), 6, Severity.MEDIUM),
        Def("roadworks", 2, "Roadworks", EventType.ROADWORKS, { listOf(1) }, listOf("ROADWORKS", "LANE 1 CLOSED", "50 MPH"), 5, Severity.MEDIUM),
        Def("queue", 2, "Queue — congestion", EventType.CONGESTION, { emptyList() }, listOf("QUEUE", "AHEAD"), 9, Severity.MEDIUM),
        Def("debris", 1, "Debris in road", EventType.OBJECT, { n -> listOf(n) }, listOf("DEBRIS", "IN ROAD", "OFFSIDE LANE CLOSED"), 4, Severity.MEDIUM),
    )

    private fun hash(s: String): Int { var h = -2128831035; for (c in s) { h = h xor c.code; h *= 16777619 }; return h }
    private fun dir(b: Double) = arrayOf("N", "E", "S", "W")[((b / 90).roundToInt()) % 4]

    fun events(): List<Event> { val t = now(); store.entries.removeAll { it.value.expires < t }; return store.values.map { it.event } }
    private fun put(e: Event) { store[e.id] = Entry(e, now() + TTL) }

    private data class Run(val ref: String, val from: Double, var to: Double)

    fun prepareRoute(route: Route) {
        val line = route.line
        val runs = mutableListOf<Run>()
        var cur: Run? = null
        for (s in route.steps) {
            val ref = s.ref?.split(';')?.first()?.trim()
            if (ref != null && isMotorwayRef(ref)) {
                if (cur != null && cur.ref == ref && s.from - cur.to < 50) cur.to = s.to
                else { cur?.let { runs.add(it) }; cur = Run(ref, s.from, s.to) }
            } else { cur?.let { runs.add(it) }; cur = null }
        }
        cur?.let { runs.add(it) }
        for (run in runs.filter { it.to - it.from > 9000 }) {
            run.to = min(run.to, line.length)
            val sc = scenarioFor(run, line)
            val hz = sc?.let { line.snap(it.location, 40.0, it.bearing, 45.0) }
            var a = run.from + 400
            while (a < run.to - 200) {
                val loc = line.pointAt(a); val brg = line.bearingAt(a)
                val id = "demo:g:${run.ref}:${dir(brg)}:${(loc.lon / 0.006).roundToInt()}:${(loc.lat / 0.006).roundToInt()}"
                val near = store.values.any { (e) -> e.type == EventType.SIGNAL && e.road == run.ref && bearingDiff(e.bearing ?: 0.0, brg) < 45 &&
                    kotlin.math.abs(e.location.lon - loc.lon) < 0.008 && kotlin.math.abs(e.location.lat - loc.lat) < 0.005 }
                if (!store.containsKey(id) && !near) {
                    val n = sc?.lanesTotal ?: 3
                    val lanes = (if (sc != null && hz != null) gantryFor(sc, hz.along - a) else null) ?: (1..n).map { SignalLane(it, Aspect.BLANK) }
                    val speed = lanes.mapNotNull { it.speed }.minOrNull()
                    val closed = lanes.filter { it.aspect == Aspect.CLOSED }.map { it.lane }
                    val g = Gantry(id, a, loc, lanes, speed, closed, run.ref, "demo")
                    put(Event(id, "demo", EventType.SIGNAL, Analyser.gantrySeverity(g), loc, road = run.ref, bearing = brg,
                        title = Datex2.signalTitle(speed, closed), lanesClosed = closed, speedLimit = speed, signal = lanes))
                }
                a += SPACING
            }
            if (sc != null && hz != null && hz.along - run.from > 8500) {
                val va = hz.along - 8000
                val id = "demo:vms:${sc.id}"
                if (!store.containsKey(id)) put(Event(id, "demo", EventType.VMS, Severity.MEDIUM, line.pointAt(va), road = run.ref,
                    bearing = line.bearingAt(va), title = "Sign: ${sc.def.vms.joinToString(" / ")}", description = sc.def.vms.joinToString(" / "), vmsText = sc.def.vms))
            }
        }
    }

    private fun scenarioFor(run: Run, line: RouteLine): Scenario? {
        val key = "${run.ref}:${dir(line.bearingAt((run.from + run.to) / 2))}"
        if (scenarios.containsKey(key)) return scenarios[key]
        val bucket = now() / (3 * 3600 * 1000L)
        val rnd = java.util.Random(hash("$key$bucket").toLong())
        var sc: Scenario? = null
        if (rnd.nextDouble() < 0.8) {
            var pick = rnd.nextDouble() * defs.sumOf { it.weight }
            val def = defs.firstOrNull { pick -= it.weight; pick < 0 } ?: defs[0]
            val lo = run.from + min(9000.0, (run.to - run.from) * 0.45)
            val hi = run.to - 1500
            if (hi > lo) {
                val along = lo + rnd.nextDouble() * (hi - lo)
                val total = if (Regex("^M(1|6|25|4|5|62|60)$").matches(run.ref)) 4 else 3
                sc = Scenario("demo:$key:$bucket", run.ref, def, total, def.lanes(total), line.pointAt(along), line.bearingAt(along))
            }
        }
        scenarios[key] = sc
        sc?.let {
            put(Event(it.id, "demo", it.def.type, it.def.severity, it.location, road = it.ref, bearing = it.bearing,
                direction = mapOf("N" to "northbound", "E" to "eastbound", "S" to "southbound", "W" to "westbound")[dir(it.bearing)],
                title = "${it.def.title} · ${it.ref}",
                description = "${it.def.title} on the ${it.ref}.${if (it.lanes.isNotEmpty()) " Lane${if (it.lanes.size > 1) "s" else ""} ${it.lanes.joinToString(" & ")} of ${it.lanesTotal} closed." else ""} (Simulated)",
                lanesClosed = it.lanes, lanesTotal = it.lanesTotal, delayMinutes = it.def.delay))
        }
        return sc
    }

    private fun gantryFor(sc: Scenario, d: Double): List<SignalLane>? {
        val n = sc.lanesTotal
        val closed = sc.lanes.toSet()
        fun all(f: (Int) -> SignalLane) = (1..n).map(f)
        if (d < -300) return null
        if (d < 0) return all { SignalLane(it, Aspect.END) }
        return when (sc.def.kind) {
            "roadworks" -> when {
                d > 7500 -> null
                d > 5000 -> all { SignalLane(it, Aspect.SPEED, 60) }
                else -> all { l -> if (l in closed && d < 1500) SignalLane(l, Aspect.CLOSED) else if (l in closed && d < 2700) SignalLane(l, Aspect.MERGE_RIGHT) else SignalLane(l, Aspect.SPEED, 50) }
            }
            "queue" -> if (d > 5500) null else all { SignalLane(it, Aspect.SPEED, if (d < 1500) 30 else if (d < 3200) 40 else 50) }
            else -> if (d > 6500) null else {
                val speed = if (d < 2500) 40 else if (d < 4500) 50 else 60
                val merge = if (sc.lanes.minOrNull() == 1) Aspect.MERGE_RIGHT else Aspect.MERGE_LEFT
                all { l -> if (l in closed && d < 1300) SignalLane(l, Aspect.CLOSED) else if (l in closed && d < 2500) SignalLane(l, merge) else SignalLane(l, Aspect.SPEED, speed) }
            }
        }
    }
}
