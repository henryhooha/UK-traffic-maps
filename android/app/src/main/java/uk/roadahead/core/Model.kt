package uk.roadahead.core

import org.json.JSONArray
import org.json.JSONObject

enum class Severity(val rank: Int) { LOW(0), MEDIUM(1), HIGH(2), SEVERE(3);
    companion object {
        fun max(vararg s: Severity?) = s.filterNotNull().maxByOrNull { it.rank } ?: LOW
        fun parse(s: String?) = entries.firstOrNull { it.name.equals(s, true) }
    }
}

/**
 * Event types. The Waze-style ones (STOPPED_VEHICLE, OBJECT, POLICE, cameras)
 * come from official feeds where available, from OpenStreetMap (fixed cameras)
 * and from your own reports.
 */
enum class EventType(val label: String, val icon: String, val spoken: String) {
    ACCIDENT("Accident", "💥", "Accident"),
    STOPPED_VEHICLE("Vehicle stopped on road", "🚙", "Vehicle stopped on the road"),
    LANE_CLOSURE("Lane closed", "🚧", "Lane closed"),
    ROADWORKS("Roadworks", "🚧", "Roadworks"),
    CLOSURE("Road closed", "⛔", "Road closed"),
    OBJECT("Object on road", "📦", "Object on the road"),
    CONGESTION("Traffic jam", "🚗", "Traffic jam"),
    WEATHER("Weather hazard", "🌧", "Weather hazard"),
    SPEED_RESTRICTION("Speed restriction", "⏱", "Speed restriction"),
    INCIDENT("Incident", "⚠️", "Incident"),
    SPEED_CAMERA("Speed camera", "📷", "Speed camera"),
    MOBILE_CAMERA("Mobile speed camera", "📸", "Mobile speed camera reported"),
    AVERAGE_SPEED("Average speed check", "📏", "Average speed check"),
    POLICE("Police", "🚓", "Police reported"),
    SIGNAL("Gantry", "▦", "Gantry"),
    VMS("Message sign", "💬", "Message sign");

    val isCamera get() = this == SPEED_CAMERA || this == MOBILE_CAMERA || this == AVERAGE_SPEED
    companion object { fun parse(s: String?) = entries.firstOrNull { it.name.equals(s, true) } }
}

enum class Aspect { SPEED, CLOSED, MERGE_LEFT, MERGE_RIGHT, END, BLANK }

data class SignalLane(val lane: Int, val aspect: Aspect, val speed: Int? = null)

data class Event(
    val id: String,
    val source: String,
    val type: EventType,
    val severity: Severity,
    val location: Pt,
    val road: String? = null,
    val direction: String? = null,
    val bearing: Double? = null,
    val end: Pt? = null,
    val title: String = type.label,
    val description: String? = null,
    val subType: String? = null,
    val lanesClosed: List<Int> = emptyList(),
    val lanesTotal: Int? = null,
    val speedLimit: Int? = null,
    val delayMinutes: Int? = null,
    val signal: List<SignalLane>? = null,
    val vmsText: List<String>? = null,
    val startTime: String? = null,
    val endTime: String? = null,
    val updated: String? = null,
    val userReported: Boolean = false,
)

data class Gantry(
    val id: String, val along: Double, val location: Pt, val signal: List<SignalLane>?,
    val speedLimit: Int?, val lanesClosed: List<Int>, val road: String?, val source: String,
)

data class Exit(val ref: String?, val name: String?, val location: Pt, val along: Double = 0.0, val road: String? = null)

enum class ItemKind { ZONE, EVENT, VMS, CAMERA }

data class Item(
    val id: String,
    val kind: ItemKind,
    val type: EventType,
    var severity: Severity,
    val road: String?,
    val start: Double,
    var end: Double,
    val speedLimit: Int?,
    val lanesClosed: List<Int>,
    val headline: String,
    val details: List<String>,
    var delayMinutes: Double,
    val delayIsEstimate: Boolean = false,
    val gantries: List<Gantry> = emptyList(),
    val events: MutableList<Event> = mutableListOf(),
    val location: Pt,
    val closureAt: Double? = null,
    var cause: String? = null,
    var exitBefore: Exit? = null,
    var exitsBefore: List<Exit> = emptyList(),
    val vmsText: List<String>? = null,
) {
    val isDemo get() = events.any { it.source == "demo" } || gantries.any { it.source == "demo" }
    val isUserReport get() = events.any { it.userReported }
}

data class Analysis(
    val items: List<Item>,
    val exits: List<Exit>,
    val gantries: List<Gantry>,
    val matchedCount: Int,
    val totalDelayMinutes: Int,
    val worstSeverity: Severity,
)

data class Step(
    val from: Double, val to: Double, val ref: String?, val name: String?,
    val maneuver: String?, val modifier: String?, val location: Pt, val exit: Int? = null,
    val destinations: String? = null,
    val junction: String? = null,
)

class Route(
    val coords: List<Pt>,
    val distance: Double,
    val duration: Double,
    val steps: List<Step>,
    val exits: List<Exit>,
    val summary: String,
) {
    var id: String = ""
    var label: String = ""
    var junctions: List<Exit>? = null
    var analysis: Analysis? = null
    val line: RouteLine by lazy { RouteLine(coords) }
    val score get() = duration / 60 + (analysis?.totalDelayMinutes ?: 0)
    fun roadAt(along: Double): Step? = steps.firstOrNull { along >= it.from && along <= it.to }
}

// ------------------------------------------------------------------ JSON (for the WebView UI)

fun Pt.toJson() = JSONArray().put(lon).put(lat)

fun List<SignalLane>.toJson() = JSONArray().also { a ->
    forEach { a.put(JSONObject().put("lane", it.lane).put("aspect", it.aspect.name.lowercase()).putOpt("speed", it.speed)) }
}

fun Event.toJson(): JSONObject = JSONObject()
    .put("id", id).put("source", source).put("type", type.name.lowercase()).put("severity", severity.name.lowercase())
    .put("location", location.toJson()).putOpt("road", road).putOpt("direction", direction).put("title", title)
    .putOpt("description", description).put("lanesClosed", JSONArray(lanesClosed)).putOpt("speedLimit", speedLimit)
    .putOpt("delayMinutes", delayMinutes).putOpt("signal", signal?.let { JSONObject().put("lanes", it.toJson()) })
    .putOpt("vmsText", vmsText?.let { JSONArray(it) }).putOpt("updated", updated).put("userReported", userReported)
    .put("icon", type.icon)

fun Gantry.toJson(): JSONObject = JSONObject().put("id", id).put("along", along).put("location", location.toJson())
    .putOpt("signal", signal?.let { JSONObject().put("lanes", it.toJson()) }).putOpt("speedLimit", speedLimit)
    .put("lanesClosed", JSONArray(lanesClosed)).putOpt("road", road).put("source", source)

fun Exit.toJson(): JSONObject = JSONObject().putOpt("ref", ref).putOpt("name", name).put("along", along).put("location", location.toJson())

fun Item.toJson(): JSONObject = JSONObject()
    .put("id", id).put("kind", kind.name.lowercase()).put("type", type.name.lowercase()).put("icon", type.icon)
    .put("severity", severity.name.lowercase()).putOpt("road", road).put("start", start).put("end", end)
    .putOpt("speedLimit", speedLimit).put("lanesClosed", JSONArray(lanesClosed)).put("headline", headline)
    .put("details", JSONArray(details)).put("delayMinutes", delayMinutes).put("delayIsEstimate", delayIsEstimate)
    .put("gantries", JSONArray().also { a -> gantries.forEach { a.put(it.toJson()) } })
    .put("events", JSONArray().also { a -> events.forEach { a.put(it.toJson()) } })
    .put("location", location.toJson()).putOpt("closureAt", closureAt).putOpt("cause", cause)
    .putOpt("exitBefore", exitBefore?.toJson())
    .put("exitsBefore", JSONArray().also { a -> exitsBefore.forEach { a.put(it.toJson()) } })
    .putOpt("vmsText", vmsText?.let { JSONArray(it) }).put("demo", isDemo).put("userReport", isUserReport)

fun Analysis.toJson(): JSONObject = JSONObject()
    .put("items", JSONArray().also { a -> items.forEach { a.put(it.toJson()) } })
    .put("gantries", JSONArray().also { a -> gantries.forEach { a.put(it.toJson()) } })
    .put("totalDelayMinutes", totalDelayMinutes).put("worstSeverity", worstSeverity.name.lowercase())

fun Route.toJson(withCoords: Boolean = true): JSONObject = JSONObject()
    .put("id", id).put("label", label).put("distance", distance).put("duration", duration).put("summary", summary)
    .put("etaMinutes", Math.round(score)).putOpt("analysis", analysis?.toJson())
    .apply { if (withCoords) put("coords", JSONArray().also { a -> coords.forEach { a.put(it.toJson()) } }) }
