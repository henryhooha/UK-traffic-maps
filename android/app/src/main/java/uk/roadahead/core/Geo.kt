package uk.roadahead.core

import kotlin.math.*

/** A point in GeoJSON order. */
data class Pt(val lon: Double, val lat: Double)

const val METRES_PER_MILE = 1609.344
private const val R = 6371008.8

fun Double.toRad() = this * PI / 180
fun Double.toDeg() = this * 180 / PI
fun mphToMs(mph: Double) = mph * 0.44704

fun haversine(a: Pt, b: Pt): Double {
    val dLat = (b.lat - a.lat).toRad()
    val dLon = (b.lon - a.lon).toRad()
    val h = sin(dLat / 2).pow(2) + cos(a.lat.toRad()) * cos(b.lat.toRad()) * sin(dLon / 2).pow(2)
    return 2 * R * asin(min(1.0, sqrt(h)))
}

fun bearing(a: Pt, b: Pt): Double {
    val p1 = a.lat.toRad(); val p2 = b.lat.toRad(); val dl = (b.lon - a.lon).toRad()
    val y = sin(dl) * cos(p2)
    val x = cos(p1) * sin(p2) - sin(p1) * cos(p2) * cos(dl)
    return (atan2(y, x).toDeg() + 360) % 360
}

fun bearingDiff(a: Double, b: Double): Double {
    val d = abs(a - b) % 360
    return if (d > 180) 360 - d else d
}

fun destination(p: Pt, brg: Double, dist: Double): Pt {
    val d = dist / R; val t = brg.toRad(); val p1 = p.lat.toRad(); val l1 = p.lon.toRad()
    val p2 = asin(sin(p1) * cos(d) + cos(p1) * sin(d) * cos(t))
    val l2 = l1 + atan2(sin(t) * sin(d) * cos(p1), cos(d) - sin(p1) * sin(p2))
    return Pt(((l2.toDeg() + 540) % 360) - 180, p2.toDeg())
}

fun directionToBearing(dir: String?): Double? {
    if (dir == null) return null
    return when (dir.lowercase().replace(Regex("[^a-z]"), "")) {
        "n", "nb", "north", "northbound" -> 0.0
        "ne", "northeast", "northeastbound" -> 45.0
        "e", "eb", "east", "eastbound" -> 90.0
        "se", "southeast", "southeastbound" -> 135.0
        "s", "sb", "south", "southbound" -> 180.0
        "sw", "southwest", "southwestbound" -> 225.0
        "w", "wb", "west", "westbound" -> 270.0
        "nw", "northwest", "northwestbound" -> 315.0
        else -> null
    }
}

fun fmtMiles(m: Double): String {
    val mi = m / METRES_PER_MILE
    return when {
        mi < 0.1 -> "${m.roundToInt()} m"
        mi < 10 -> String.format(java.util.Locale.UK, "%.1f mi", mi)
        else -> "${mi.roundToInt()} mi"
    }
}

/** Spoken distance, UK style. */
fun sayDistance(m: Double): String {
    val mi = m / METRES_PER_MILE
    return when {
        mi < 0.3 -> "${(m / 50).roundToInt() * 50} metres"
        mi < 0.4 -> "a quarter of a mile"
        mi < 0.75 -> "half a mile"
        mi < 1.25 -> "one mile"
        else -> "${mi.roundToInt()} miles"
    }
}

data class Snap(val along: Double, val offset: Double, val segment: Int, val bearing: Double)

/** Route polyline prepared for fast repeated snapping (grid index). */
class RouteLine(val coords: List<Pt>, private val cellDeg: Double = 0.01) {
    val cum = DoubleArray(coords.size)
    val bearings = DoubleArray(max(0, coords.size - 1))
    private val grid = HashMap<Long, MutableList<Int>>()
    val length: Double
    val minLon: Double; val minLat: Double; val maxLon: Double; val maxLat: Double

    init {
        for (i in 1 until coords.size) cum[i] = cum[i - 1] + haversine(coords[i - 1], coords[i])
        length = if (coords.isEmpty()) 0.0 else cum.last()
        for (i in 0 until coords.size - 1) {
            bearings[i] = bearing(coords[i], coords[i + 1])
            val a = coords[i]; val b = coords[i + 1]
            val x0 = floor(min(a.lon, b.lon) / cellDeg).toInt(); val x1 = floor(max(a.lon, b.lon) / cellDeg).toInt()
            val y0 = floor(min(a.lat, b.lat) / cellDeg).toInt(); val y1 = floor(max(a.lat, b.lat) / cellDeg).toInt()
            for (x in x0..x1) for (y in y0..y1) grid.getOrPut(key(x, y)) { mutableListOf() }.add(i)
        }
        minLon = coords.minOfOrNull { it.lon } ?: 0.0; maxLon = coords.maxOfOrNull { it.lon } ?: 0.0
        minLat = coords.minOfOrNull { it.lat } ?: 0.0; maxLat = coords.maxOfOrNull { it.lat } ?: 0.0
    }

    private fun key(x: Int, y: Int) = (x.toLong() shl 32) or (y.toLong() and 0xffffffffL)

    fun snap(
        p: Pt, maxDist: Double = 50.0, brg: Double? = null, maxBearingDiff: Double = 50.0,
        minAlong: Double = Double.NEGATIVE_INFINITY, maxAlong: Double = Double.POSITIVE_INFINITY,
    ): Snap? {
        val cx = floor(p.lon / cellDeg).toInt(); val cy = floor(p.lat / cellDeg).toInt()
        val seen = HashSet<Int>()
        var best: Snap? = null
        val k = cos(p.lat.toRad())
        for (x in cx - 1..cx + 1) for (y in cy - 1..cy + 1) {
            val segs = grid[key(x, y)] ?: continue
            for (i in segs) {
                if (!seen.add(i)) continue
                if (brg != null && bearingDiff(brg, bearings[i]) > maxBearingDiff) continue
                val a = coords[i]; val b = coords[i + 1]
                val ax = (a.lon - p.lon).toRad() * R * k; val ay = (a.lat - p.lat).toRad() * R
                val bx = (b.lon - p.lon).toRad() * R * k; val by = (b.lat - p.lat).toRad() * R
                val dx = bx - ax; val dy = by - ay
                val len2 = dx * dx + dy * dy
                val t = if (len2 == 0.0) 0.0 else ((-ax) * dx + (-ay) * dy) / len2
                val tc = t.coerceIn(0.0, 1.0)
                val dist = hypot(ax + tc * dx, ay + tc * dy)
                if (dist > maxDist) continue
                val along = cum[i] + tc * (cum[i + 1] - cum[i])
                if (along < minAlong || along > maxAlong) continue
                if (best == null || dist < best.offset - 1e-6) best = Snap(along, dist, i, bearings[i])
            }
        }
        return best
    }

    private fun indexAt(along: Double): Int {
        var lo = 0; var hi = cum.size - 1
        while (hi - lo > 1) { val mid = (lo + hi) ushr 1; if (cum[mid] <= along) lo = mid else hi = mid }
        return lo
    }

    fun pointAt(along: Double): Pt {
        if (coords.isEmpty()) return Pt(0.0, 0.0)
        if (along <= 0) return coords.first()
        if (along >= length) return coords.last()
        val lo = indexAt(along); val hi = lo + 1
        val seg = cum[hi] - cum[lo]
        val t = if (seg == 0.0) 0.0 else (along - cum[lo]) / seg
        val a = coords[lo]; val b = coords[hi]
        return Pt(a.lon + (b.lon - a.lon) * t, a.lat + (b.lat - a.lat) * t)
    }

    fun bearingAt(along: Double): Double = if (bearings.isEmpty()) 0.0 else bearings[min(indexAt(along), bearings.size - 1)]

    fun slice(from: Double, to: Double): List<Pt> {
        val out = mutableListOf(pointAt(from))
        for (i in coords.indices) if (cum[i] > from && cum[i] < to) out.add(coords[i])
        out.add(pointAt(to))
        return out
    }
}
