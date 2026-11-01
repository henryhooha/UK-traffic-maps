package uk.roadahead.core

import kotlin.math.max

enum class AlertKind { HAZARD, CAMERA, TURN, SPEEDING }

data class Alert(
    val kind: AlertKind, val key: String, val title: String, val subtitle: String, val spoken: String,
    val severity: Severity, val distance: Double, val itemId: String? = null, val icon: String = "⚠️",
)

data class AlertSettings(val horizonMetres: Double = 10 * METRES_PER_MILE, val cameras: Boolean = true, val minor: Boolean = false, val turnByTurn: Boolean = true)

/**
 * Decides what to say and when. Pure logic so it can be unit-tested; the
 * DriveService feeds it position updates and speaks/shows whatever it returns.
 */
class AlertEngine(var settings: AlertSettings = AlertSettings()) {
    private val fired = HashMap<String, MutableSet<Double>>()
    private var speedingSince = 0L
    private var lastSpeedingWarn = 0L

    fun reset() { fired.clear() }

    private fun fire(key: String, thresholds: List<Double>, d: Double): Boolean {
        val set = fired.getOrPut(key) { mutableSetOf() }
        val t = thresholds.firstOrNull { d <= it && it !in set } ?: return false
        thresholds.filter { it >= t }.forEach { set.add(it) }
        return true
    }

    fun exitText(it: Item, along: Double): String? {
        val ex = it.exitsBefore.firstOrNull { x -> x.along > along + 100 } ?: return if (it.exitBefore != null && along > 0) "No exits left before this" else null
        return "Last exit before it: ${ex.ref ?: "next exit"}${ex.name?.let { " $it" } ?: ""} in ${fmtMiles(ex.along - along)}"
    }

    private fun spokenRoad(r: String?) = r?.let { " on the " + it.toCharArray().joinToString(" ") } ?: ""

    fun hazardSpeech(it: Item, d: Double, along: Double): String {
        val parts = mutableListOf<String>()
        if (it.kind == ItemKind.ZONE) {
            if (it.lanesClosed.isNotEmpty()) {
                val where = sayDistance(max(0.0, (it.closureAt ?: it.start) - along))
                parts.add("Heads up. ${if (it.lanesClosed.size > 1) "Lanes" else "Lane"} ${it.lanesClosed.joinToString(" and ")} closed in $where${spokenRoad(it.road)}.")
                it.speedLimit?.let { s -> parts.add("Speed limit drops to $s from ${sayDistance(d)}.") }
            } else parts.add("Heads up. ${it.speedLimit} mile an hour limit in ${sayDistance(d)}${spokenRoad(it.road)}.")
            it.cause?.let { c -> parts.add("Cause: ${c.substringBefore('·').trim()}.") }
        } else {
            val what = if (it.isUserReport) "${it.type.spoken}, from your earlier report," else it.type.spoken
            val lanes = if (it.lanesClosed.isNotEmpty()) ", ${if (it.lanesClosed.size > 1) "lanes" else "lane"} ${it.lanesClosed.joinToString(" and ")} blocked" else ""
            parts.add("Heads up. $what in ${sayDistance(d)}${spokenRoad(it.road)}$lanes.")
        }
        if (it.delayMinutes >= 3) parts.add("About ${Math.round(it.delayMinutes)} minutes delay.")
        it.exitsBefore.firstOrNull { x -> x.along > along + 100 }?.let { ex ->
            parts.add("Last exit before it${ex.ref?.let { r -> ", junction ${r.removePrefix("J")}," } ?: ""} is in ${sayDistance(ex.along - along)}.")
        }
        return parts.joinToString(" ")
    }

    /**
     * @param speedMs current speed in m/s (null if unknown)
     * @param limitMph current posted/gantry limit if known
     */
    fun update(route: Route, along: Double, speedMs: Double?, limitMph: Int?, nowMs: Long = System.currentTimeMillis()): List<Alert> {
        val out = mutableListOf<Alert>()
        val analysis = route.analysis ?: return out
        val motorway = isMotorwayRef(route.roadAt(along)?.ref)

        // Hazards: horizon, 2 miles, half a mile.
        val hzTh = listOf(settings.horizonMetres, 2 * METRES_PER_MILE, 0.5 * METRES_PER_MILE).distinct().filterIndexed { i, x -> i == 0 || x < settings.horizonMetres }
        for (it in analysis.items) {
            if (it.kind != ItemKind.ZONE && it.kind != ItemKind.EVENT) continue
            if (it.severity.rank < Severity.MEDIUM.rank && !settings.minor && !it.isUserReport) continue
            val d = it.start - along
            if (d < -50) continue
            if (!fire(it.id, hzTh, d)) continue
            out.add(Alert(AlertKind.HAZARD, it.id, it.headline,
                listOfNotNull(it.cause, if (it.delayMinutes > 0) "+${Math.round(it.delayMinutes)} min" else null, exitText(it, along)).joinToString(" · "),
                hazardSpeech(it, d, along), it.severity, d, it.id, it.type.icon))
            break // one hazard announcement at a time
        }

        // Cameras.
        if (settings.cameras) for (it in analysis.items) {
            if (it.kind != ItemKind.CAMERA) continue
            val d = it.start - along
            if (d < -30) continue
            val th = if (motorway) listOf(0.5 * METRES_PER_MILE) else listOf(300.0)
            if (!fire(it.id, th, d)) continue
            val lim = it.speedLimit ?: limitMph
            val over = lim != null && speedMs != null && speedMs > mphToMs(lim + 2.0)
            out.add(Alert(AlertKind.CAMERA, it.id, it.headline, "in ${fmtMiles(d)}${lim?.let { " · $it mph" } ?: ""}",
                "${it.type.spoken} ahead${lim?.let { ", $it limit" } ?: ""}.${if (over) " Check your speed." else ""}", Severity.MEDIUM, d, it.id, it.type.icon))
        }

        // Turn-by-turn.
        if (settings.turnByTurn) {
            val next = route.steps.withIndex().firstOrNull { (_, s) -> s.from > along + 5 && Instructions.announce(s) }
            if (next != null) {
                val (i, s) = next
                val d = s.from - along
                val fast = (speedMs ?: 0.0) > 20 || motorway
                val th = if (fast) listOf(1 * METRES_PER_MILE, 0.5 * METRES_PER_MILE, 250.0) else listOf(500.0, 150.0, 40.0)
                if (fire("turn:$i", th, d)) {
                    val txt = Instructions.text(s)
                    val spoken = if (d < (if (fast) 300.0 else 60.0)) "$txt." else "In ${sayDistance(d)}, ${txt.replaceFirstChar { it.lowercase() }}."
                    out.add(Alert(AlertKind.TURN, "turn:$i", txt, fmtMiles(d), spoken, Severity.LOW, d, icon = Instructions.arrow(s)))
                }
            }
        }

        // Speeding vs gantry/camera limit (only when we actually know the limit).
        if (limitMph != null && speedMs != null && speedMs > mphToMs(limitMph + 3.0)) {
            if (speedingSince == 0L) speedingSince = nowMs
            if (nowMs - speedingSince > 4000 && nowMs - lastSpeedingWarn > 60000) {
                lastSpeedingWarn = nowMs
                out.add(Alert(AlertKind.SPEEDING, "speeding", "Limit $limitMph mph", "You're above the signed limit", "Speed limit $limitMph.", Severity.MEDIUM, 0.0, icon = "⚠️"))
            }
        } else speedingSince = 0L
        return out
    }

    /** Current limit from the last gantry passed (valid ~2.5 km), else null. */
    fun currentGantryLimit(route: Route, along: Double): Int? {
        val last = route.analysis?.gantries?.lastOrNull { it.along <= along + 30 } ?: return null
        if (along - last.along > 2500) return null
        if (last.signal?.none { it.aspect != Aspect.BLANK && it.aspect != Aspect.END } != false) return null
        return last.speedLimit
    }
}
