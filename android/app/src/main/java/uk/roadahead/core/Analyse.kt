package uk.roadahead.core

import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * "Road ahead" analysis (Kotlin port of server/lib/analyse.js plus cameras and
 * user reports): snap each event onto *your* carriageway, group restricted
 * gantries into zones, estimate delays and find the last exit before each item.
 */
object Analyser {
    private const val FREE_FLOW_MPH = 70.0
    private const val ZONE_GAP_M = 3000.0
    private const val SIGNAL_TAIL_M = 800.0

    private data class SnapCfg(val maxDist: Double, val withBearing: Double, val withoutBearing: Double)
    private fun cfg(t: EventType) = when (t) {
        EventType.SIGNAL -> SnapCfg(45.0, 50.0, 20.0)
        EventType.VMS -> SnapCfg(60.0, 50.0, 25.0)
        EventType.SPEED_CAMERA, EventType.AVERAGE_SPEED -> SnapCfg(45.0, 60.0, 35.0)
        else -> SnapCfg(120.0, 70.0, 60.0)
    }

    private class Hit(val ev: Event, val along: Double, val endAlong: Double?, val directionKnown: Boolean)

    private fun snapEvent(route: Route, ev: Event): Hit? {
        val line = route.line
        val c = cfg(ev.type)
        val hasB = ev.bearing != null
        val hit = (if (hasB) line.snap(ev.location, c.maxDist, ev.bearing, c.withBearing) else line.snap(ev.location, c.withoutBearing)) ?: return null
        val evRoad = normRoad(ev.road)
        val step = route.roadAt(hit.along)
        if (evRoad != null && step?.ref != null && hit.offset > 12) {
            val refs = step.ref.split(';', ',').map { normRoad(it) }
            if (evRoad !in refs) return null
        }
        var endAlong: Double? = null
        if (ev.end != null) {
            val maxD = (if (hasB) c.maxDist else c.withoutBearing) * 1.5
            endAlong = (if (hasB) line.snap(ev.end, maxD, ev.bearing, c.withBearing, minAlong = hit.along) else line.snap(ev.end, maxD, minAlong = hit.along))?.along
        }
        return Hit(ev, hit.along, endAlong, hasB)
    }

    fun heuristicDelay(ev: Event): Double {
        ev.delayMinutes?.let { return it.toDouble() }
        return when (ev.type) {
            EventType.CLOSURE -> 20.0
            EventType.ACCIDENT -> when (ev.severity) { Severity.SEVERE -> 25.0; Severity.HIGH -> 12.0; else -> 6.0 }
            EventType.LANE_CLOSURE -> 3.0 + 3 * max(1, ev.lanesClosed.size)
            EventType.CONGESTION -> if (ev.severity == Severity.SEVERE) 20.0 else 8.0
            EventType.STOPPED_VEHICLE -> if (ev.lanesClosed.isNotEmpty()) 5.0 else 2.0
            EventType.OBJECT -> 2.0
            EventType.INCIDENT -> if (ev.severity == Severity.HIGH) 8.0 else 3.0
            EventType.ROADWORKS -> if (ev.lanesClosed.isNotEmpty()) 4.0 else 2.0
            EventType.WEATHER -> 3.0
            else -> 0.0
        }
    }

    private fun slowdown(metres: Double, mph: Int?): Double =
        if (mph == null || mph >= FREE_FLOW_MPH) 0.0 else (metres / METRES_PER_MILE) * (60.0 / mph - 60.0 / FREE_FLOW_MPH)

    private fun restricted(g: Gantry) = (g.speedLimit != null && g.speedLimit < FREE_FLOW_MPH) || g.lanesClosed.isNotEmpty()

    fun fmt(m: Double): String = if (m / METRES_PER_MILE < 0.2) "${m.roundToInt()} m" else String.format(java.util.Locale.UK, "%.1f mi", m / METRES_PER_MILE)

    private fun lanesWord(l: List<Int>) = "${if (l.size > 1) "Lanes" else "Lane"} ${l.joinToString(" & ")}"

    private fun buildZones(gs: List<Gantry>, line: RouteLine): MutableList<Item> {
        class Z(val start: Double, val road: String?) { val g = mutableListOf<Gantry>(); var end = 0.0 }
        val zones = mutableListOf<Z>()
        var cur: Z? = null
        for (g in gs) {
            if (!restricted(g)) { cur?.let { it.end = g.along; zones.add(it) }; cur = null; continue }
            val c = cur
            if (c != null && (g.along - c.g.last().along > ZONE_GAP_M || normRoad(g.road) != normRoad(c.road))) {
                c.end = c.g.last().along + SIGNAL_TAIL_M; zones.add(c); cur = null
            }
            if (cur == null) cur = Z(g.along, g.road)
            cur!!.g.add(g)
        }
        cur?.let { it.end = min(line.length, it.g.last().along + SIGNAL_TAIL_M); zones.add(it) }

        return zones.mapIndexed { idx, z ->
            val speeds = z.g.mapNotNull { it.speedLimit }
            val minSpeed = speeds.minOrNull()
            val closed = z.g.flatMap { it.lanesClosed }.toSortedSet().toList()
            val firstClosure = z.g.firstOrNull { it.lanesClosed.isNotEmpty() }
            val firstMerge = z.g.firstOrNull { g -> g.signal?.any { it.aspect == Aspect.MERGE_LEFT || it.aspect == Aspect.MERGE_RIGHT } == true }
            var delay = 0.0
            z.g.forEachIndexed { i, g -> delay += slowdown((if (i + 1 < z.g.size) z.g[i + 1].along else z.end) - g.along, g.speedLimit) }
            if (closed.isNotEmpty()) delay += 1.5 * closed.size
            val details = mutableListOf<String>()
            if (minSpeed != null) details.add("Speed limits down to $minSpeed mph (${speeds.joinToString(" → ")})")
            if (firstMerge != null && (firstClosure == null || firstMerge.along < firstClosure.along)) details.add("Merge arrows from ${fmt(firstMerge.along - z.start)} into the zone")
            if (firstClosure != null) details.add("${lanesWord(closed)} closed (red X) — ${fmt(firstClosure.along - z.start)} into the zone")
            details.add("${z.g.size} ${if (z.g.size == 1) "gantry" else "gantries"} set over ${fmt(z.end - z.start)}")
            val r = z.road?.let { "$it: " } ?: ""
            val headline = when {
                closed.isNotEmpty() && minSpeed != null -> "$r${lanesWord(closed)} closed, $minSpeed mph limit"
                closed.isNotEmpty() -> "$r${lanesWord(closed)} closed"
                else -> "$r$minSpeed mph limit on the gantries"
            }
            Item(
                id = "zone:${normRoad(z.road) ?: "road"}:${z.start.roundToInt()}:$idx", kind = ItemKind.ZONE,
                type = if (closed.isNotEmpty()) EventType.LANE_CLOSURE else EventType.SPEED_RESTRICTION,
                severity = z.g.fold(Severity.LOW) { s, g -> Severity.max(s, gantrySeverity(g)) },
                road = z.road, start = z.start, end = z.end, speedLimit = minSpeed, lanesClosed = closed,
                headline = headline, details = details, delayMinutes = (delay * 10).roundToInt() / 10.0,
                gantries = z.g.toList(), location = line.pointAt(z.start), closureAt = firstClosure?.along,
            )
        }.toMutableList()
    }

    fun gantrySeverity(g: Gantry): Severity = when {
        g.lanesClosed.size >= 2 -> Severity.SEVERE
        g.lanesClosed.isNotEmpty() -> Severity.HIGH
        g.speedLimit != null && g.speedLimit <= 40 -> Severity.HIGH
        g.speedLimit != null && g.speedLimit <= 50 -> Severity.MEDIUM
        else -> Severity.LOW
    }

    fun analyse(route: Route, events: List<Event>, junctions: List<Exit> = route.junctions ?: route.exits): Analysis {
        val line = route.line
        val pad = 0.02
        val matched = events.asSequence()
            .filter { it.location.lon in (line.minLon - pad)..(line.maxLon + pad) && it.location.lat in (line.minLat - pad)..(line.maxLat + pad) }
            .mapNotNull { snapEvent(route, it) }
            .sortedBy { it.along }.toList()

        val gantries = matched.filter { it.ev.type == EventType.SIGNAL }.map {
            Gantry(it.ev.id, it.along, it.ev.location, it.ev.signal, it.ev.speedLimit, it.ev.lanesClosed, it.ev.road, it.ev.source)
        }
        val vms = matched.filter { it.ev.type == EventType.VMS }
        val cameras = matched.filter { it.ev.type.isCamera }
        val others = matched.filter { it.ev.type != EventType.SIGNAL && it.ev.type != EventType.VMS && !it.ev.type.isCamera }

        val zones = buildZones(gantries, line)
        val standalone = mutableListOf<Hit>()
        for (h in others) {
            val z = zones.firstOrNull { h.along >= it.start - 300 && h.along <= it.end + 2500 }
            if (z != null) {
                z.events.add(h.ev.copy())
                z.severity = Severity.max(z.severity, h.ev.severity)
                z.end = max(z.end, h.endAlong ?: h.along)
                h.ev.delayMinutes?.let { if (it > z.delayMinutes) z.delayMinutes = it.toDouble() }
                if (z.cause == null) z.cause = h.ev.title
            } else standalone.add(h)
        }
        val attachedVms = HashSet<String>()
        for (v in vms) {
            val z = zones.firstOrNull { v.along <= it.end && it.start - v.along < 16000 }
            if (z != null) { z.events.add(v.ev); attachedVms.add(v.ev.id) }
        }

        val eventItems = standalone.map { h ->
            val ev = h.ev
            Item(
                id = ev.id, kind = ItemKind.EVENT, type = ev.type, severity = ev.severity, road = ev.road,
                start = h.along, end = h.endAlong ?: h.along, speedLimit = ev.speedLimit, lanesClosed = ev.lanesClosed,
                headline = ev.title,
                details = listOfNotNull(
                    ev.description,
                    if (ev.lanesClosed.isNotEmpty()) "Lanes closed: ${ev.lanesClosed.joinToString(", ") { if (it == 0) "hard shoulder" else "$it" }}${ev.lanesTotal?.let { " of $it" } ?: ""}" else null,
                    if (ev.userReported) "Your report" else null,
                    if (!h.directionKnown) "Direction not given — may be on the opposite carriageway" else null,
                ),
                delayMinutes = heuristicDelay(ev), delayIsEstimate = ev.delayMinutes == null,
                events = mutableListOf(ev), location = ev.location,
            )
        }
        val vmsItems = vms.filter { it.ev.id !in attachedVms }.map { v ->
            Item(v.ev.id, ItemKind.VMS, EventType.VMS, v.ev.severity, v.ev.road, v.along, v.along, null, emptyList(),
                v.ev.vmsText?.joinToString(" · ") ?: v.ev.title, emptyList(), 0.0, events = mutableListOf(v.ev), location = v.ev.location, vmsText = v.ev.vmsText)
        }
        val camItems = cameras.map { c ->
            val ev = c.ev
            Item(ev.id, ItemKind.CAMERA, ev.type, Severity.MEDIUM, ev.road, c.along, c.endAlong ?: c.along, ev.speedLimit, emptyList(),
                ev.title, listOfNotNull(ev.description, if (ev.userReported) "Your report" else null), 0.0,
                events = mutableListOf(ev), location = ev.location)
        }

        val items = (zones + eventItems + vmsItems + camItems).sortedBy { it.start }

        val exits = junctions.mapNotNull { j -> line.snap(j.location, 35.0)?.let { j.copy(along = it.along) } }.sortedBy { it.along }
        for (it in items) {
            val before = exits.filter { x -> x.along < it.start - 250 }
            it.exitBefore = before.lastOrNull()
            it.exitsBefore = before.takeLast(3).reversed()
        }
        val hazards = items.filter { it.kind == ItemKind.ZONE || it.kind == ItemKind.EVENT }
        return Analysis(
            items = items, exits = exits, gantries = gantries, matchedCount = matched.size,
            totalDelayMinutes = hazards.sumOf { it.delayMinutes }.roundToInt(),
            worstSeverity = hazards.fold(Severity.LOW) { s, i -> Severity.max(s, i.severity) },
        )
    }

    /** Is hazard [a] (from one analysis) the same as [b] (from another)? */
    fun sameHazard(a: Item, b: Item): Boolean {
        if (a.id == b.id) return true
        val ids = (a.events.map { it.id } + a.gantries.map { it.id }).toSet()
        if (b.events.any { it.id in ids } || b.gantries.any { it.id in ids }) return true
        return haversine(a.location, b.location) < 400 && normRoad(a.road) == normRoad(b.road)
    }
}
