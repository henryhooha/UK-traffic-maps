package uk.roadahead.core

import org.json.JSONArray

/** Transport for London road disruptions — public API. */
object Tfl {
    private val SEV = mapOf("No impact" to Severity.LOW, "Minimal" to Severity.LOW, "Moderate" to Severity.MEDIUM, "Serious" to Severity.HIGH, "Severe" to Severity.SEVERE)
    private val CAT = mapOf("Works" to EventType.ROADWORKS, "Collisions" to EventType.ACCIDENT, "Asset issues" to EventType.INCIDENT,
        "Planned events" to EventType.INCIDENT, "Incidents" to EventType.INCIDENT, "Traffic incidents" to EventType.INCIDENT,
        "Traffic volume" to EventType.CONGESTION, "Weather" to EventType.WEATHER, "Breakdowns" to EventType.STOPPED_VEHICLE)

    fun fetch(appKey: String?): List<Event> =
        parse(Http.get("https://api.tfl.gov.uk/Road/all/Disruption" + (appKey?.takeIf { it.isNotBlank() }?.let { "?app_key=${Http.enc(it)}" } ?: "")))

    fun parse(json: String): List<Event> {
        val arr = JSONArray(json)
        val out = mutableListOf<Event>()
        for (i in 0 until arr.length()) {
            val d = arr.getJSONObject(i)
            val pt = runCatching { JSONArray(d.optString("point")) }.getOrNull() ?: continue
            if (pt.length() < 2) continue
            val comments = d.optString("comments")
            val text = "$comments ${d.optString("currentUpdate")}"
            val lt = lanesFromText(text)
            val dir = Regex("\\((north|south|east|west)bound\\)", RegexOption.IGNORE_CASE).find(comments)?.groupValues?.get(1)?.lowercase()
            val closed = d.optBoolean("hasClosures") || lt.fullClosure
            val cat = CAT[d.optString("category")]
            val sub = d.optString("subCategory").ifEmpty { null }
            var type = when {
                closed -> EventType.CLOSURE
                sub?.contains("breakdown", true) == true || text.contains("broken down", true) -> EventType.STOPPED_VEHICLE
                sub?.contains("collision", true) == true -> EventType.ACCIDENT
                lt.lanesClosed.isNotEmpty() && cat != EventType.ROADWORKS -> EventType.LANE_CLOSURE
                else -> cat ?: EventType.INCIDENT
            }
            val location = d.optString("location").ifEmpty { null }
            out.add(Event(
                id = "tfl:${d.optString("id")}", source = "tfl", type = type,
                severity = if (closed) Severity.SEVERE else SEV[d.optString("severity")] ?: Severity.MEDIUM,
                location = Pt(pt.getDouble(0), pt.getDouble(1)), road = roadFromText(location ?: comments),
                direction = dir?.let { "${it}bound" }, bearing = dir?.let { directionToBearing(it) },
                title = "${sub ?: d.optString("category")}${location?.let { " · $it" } ?: ""}",
                description = listOf(comments, d.optString("currentUpdate")).filter { it.isNotBlank() }.joinToString(" — "),
                subType = sub, lanesClosed = lt.lanesClosed, lanesTotal = lt.lanesTotal,
                startTime = d.optString("startDateTime").ifEmpty { null }, endTime = d.optString("endDateTime").ifEmpty { null },
                updated = d.optString("lastModifiedTime").ifEmpty { null },
            ))
        }
        return out
    }
}
