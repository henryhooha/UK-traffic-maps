package uk.roadahead.core

import org.json.JSONArray
import org.json.JSONObject

/** Things you can report from the car, Waze-style. */
enum class ReportType(val eventType: EventType, val label: String, val ttlMinutes: Long, val severity: Severity, val lanes: List<Int> = emptyList()) {
    ACCIDENT(EventType.ACCIDENT, "Accident", 120, Severity.HIGH),
    STOPPED_VEHICLE(EventType.STOPPED_VEHICLE, "Car stopped", 60, Severity.MEDIUM),
    LANE_BLOCKED(EventType.LANE_CLOSURE, "Lane blocked", 90, Severity.HIGH, listOf(1)),
    OBJECT(EventType.OBJECT, "Object on road", 60, Severity.MEDIUM),
    TRAFFIC(EventType.CONGESTION, "Traffic jam", 45, Severity.MEDIUM),
    POLICE(EventType.POLICE, "Police", 60, Severity.LOW),
    MOBILE_CAMERA(EventType.MOBILE_CAMERA, "Mobile camera", 240, Severity.MEDIUM),
    SPEED_CAMERA(EventType.SPEED_CAMERA, "Fixed camera", Long.MAX_VALUE / 60000, Severity.MEDIUM),
    ROADWORKS(EventType.ROADWORKS, "Roadworks", 7 * 24 * 60, Severity.MEDIUM),
    WEATHER(EventType.WEATHER, "Weather hazard", 120, Severity.MEDIUM),
    CLOSURE(EventType.CLOSURE, "Road closed", 240, Severity.SEVERE);

    val icon get() = eventType.icon
    companion object {
        fun parse(s: String?) = entries.firstOrNull { it.name.equals(s, true) }

        /** Map a spoken reply ("broken down car in lane one") to a report type. */
        fun fromSpeech(text: String?): ReportType? {
            val t = (text ?: "").lowercase()
            return when {
                Regex("mobile|speed trap|van").containsMatchIn(t) -> MOBILE_CAMERA
                Regex("camera").containsMatchIn(t) -> SPEED_CAMERA
                Regex("police|cop").containsMatchIn(t) -> POLICE
                Regex("accident|crash|collision|smash").containsMatchIn(t) -> ACCIDENT
                Regex("broke|breakdown|stopped|stationary|car on|vehicle").containsMatchIn(t) -> STOPPED_VEHICLE
                Regex("lane").containsMatchIn(t) -> LANE_BLOCKED
                Regex("object|debris|tyre|tire|animal|something in").containsMatchIn(t) -> OBJECT
                Regex("traffic|jam|queue|slow|standstill").containsMatchIn(t) -> TRAFFIC
                Regex("road ?works|works|cones").containsMatchIn(t) -> ROADWORKS
                Regex("fog|ice|icy|flood|snow|weather|spray").containsMatchIn(t) -> WEATHER
                Regex("closed|closure").containsMatchIn(t) -> CLOSURE
                else -> null
            }
        }
    }
}

data class Report(
    val id: String, val type: ReportType, val location: Pt, val bearing: Double?, val road: String?,
    val createdAt: Long, val note: String? = null, val sharedToOsm: Boolean = false,
) {
    val expiresAt get() = if (type.ttlMinutes >= Long.MAX_VALUE / 120000) Long.MAX_VALUE else createdAt + type.ttlMinutes * 60000
    fun active(now: Long) = now < expiresAt

    fun toEvent(): Event = Event(
        id = "report:$id", source = "you", type = type.eventType, severity = type.severity, location = location,
        road = road, bearing = bearing, title = "${type.label} (your report)${road?.let { " · $it" } ?: ""}",
        description = note, lanesClosed = type.lanes, userReported = true,
        updated = java.time.Instant.ofEpochMilli(createdAt).toString(),
    )

    fun toJson(): JSONObject = JSONObject().put("id", id).put("type", type.name).put("lon", location.lon).put("lat", location.lat)
        .putOpt("bearing", bearing).putOpt("road", road).put("createdAt", createdAt).putOpt("note", note).put("sharedToOsm", sharedToOsm)
        .put("icon", type.icon).put("label", type.label)

    companion object {
        fun fromJson(o: JSONObject) = Report(
            o.getString("id"), ReportType.parse(o.getString("type")) ?: ReportType.ACCIDENT,
            Pt(o.getDouble("lon"), o.getDouble("lat")), if (o.has("bearing")) o.getDouble("bearing") else null,
            o.optString("road").ifEmpty { null }, o.getLong("createdAt"), o.optString("note").ifEmpty { null }, o.optBoolean("sharedToOsm"),
        )
        fun listToJson(l: List<Report>) = JSONArray().also { a -> l.forEach { a.put(it.toJson()) } }
        fun listFromJson(s: String): List<Report> = runCatching { val a = JSONArray(s); (0 until a.length()).map { fromJson(a.getJSONObject(it)) } }.getOrDefault(emptyList())

        /** GPX waypoints — import into other tools, or keep as a log. */
        fun toGpx(l: List<Report>): String = buildString {
            append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<gpx version=\"1.1\" creator=\"Road Ahead UK\" xmlns=\"http://www.topografix.com/GPX/1/1\">\n")
            for (r in l) append("  <wpt lat=\"${r.location.lat}\" lon=\"${r.location.lon}\"><time>${java.time.Instant.ofEpochMilli(r.createdAt)}</time><name>${r.type.label}</name>${r.road?.let { "<desc>$it</desc>" } ?: ""}</wpt>\n")
            append("</gpx>\n")
        }
    }
}

/**
 * Share a report with OpenStreetMap as a public map note (anonymous). OSM
 * mappers review notes and add e.g. missing fixed cameras to the map, which
 * feeds every OSM-based app. There is no public API to submit to Waze or
 * Google Maps.
 */
object OsmNotes {
    fun create(baseUrl: String, r: Report): String {
        val text = "Fixed speed camera observed here (direction of travel ${r.bearing?.let { "%.0f°".format(it) } ?: "unknown"}${r.road?.let { ", road $it" } ?: ""}). Reported from a car via Road Ahead UK — please verify before mapping."
        val url = "${baseUrl.trimEnd('/')}/api/0.6/notes.json?lat=${r.location.lat}&lon=${r.location.lon}&text=${Http.enc(text)}"
        return Http.request("POST", url, "", emptyMap(), 15000)
    }
}
