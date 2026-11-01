package uk.roadahead.core

import org.json.JSONObject

/** Routing via OSRM, plus motorway diverge points mined from step intersections. */
object Osrm {
    fun route(baseUrl: String, waypoints: List<Pt>, alternatives: Int = 2): List<Route> {
        val coords = waypoints.joinToString(";") { String.format(java.util.Locale.US, "%.6f,%.6f", it.lon, it.lat) }
        val alt = if (waypoints.size == 2 && alternatives > 0) "&alternatives=$alternatives" else ""
        val json = Http.get("${baseUrl.trimEnd('/')}/route/v1/driving/$coords?overview=full&geometries=geojson&steps=true$alt", timeoutMs = 25000)
        return parse(json)
    }

    fun parse(json: String): List<Route> {
        val data = JSONObject(json)
        if (data.optString("code") != "Ok") throw Exception("Routing failed: ${data.optString("message", data.optString("code"))}")
        val routes = data.getJSONArray("routes")
        return (0 until routes.length()).map { normalise(routes.getJSONObject(it)) }
    }

    private fun normalise(r: JSONObject): Route {
        val g = r.getJSONObject("geometry").getJSONArray("coordinates")
        val coords = (0 until g.length()).map { val c = g.getJSONArray(it); Pt(c.getDouble(0), c.getDouble(1)) }
        val steps = mutableListOf<Step>()
        val exits = mutableListOf<Exit>()
        var along = 0.0
        val legs = r.getJSONArray("legs")
        for (li in 0 until legs.length()) {
            val ss = legs.getJSONObject(li).getJSONArray("steps")
            for (si in 0 until ss.length()) {
                val s = ss.getJSONObject(si)
                val from = along
                along += s.getDouble("distance")
                val m = s.getJSONObject("maneuver")
                val loc = m.getJSONArray("location")
                val ref = s.optString("ref").ifEmpty { null }
                steps.add(Step(from, along, ref, s.optString("name").ifEmpty { null }, m.optString("type").ifEmpty { null },
                    m.optString("modifier").ifEmpty { null }, Pt(loc.getDouble(0), loc.getDouble(1)),
                    if (m.has("exit")) m.getInt("exit") else null, s.optString("destinations").ifEmpty { null },
                    s.optString("exits").ifEmpty { null }))
                val ints = s.optJSONArray("intersections") ?: continue
                var motorway = isMotorwayRef(ref)
                for (ii in 0 until ints.length()) ints.getJSONObject(ii).optJSONArray("classes")?.let { c -> for (k in 0 until c.length()) if (c.getString(k) == "motorway") motorway = true }
                if (!motorway) continue
                for (ii in 1 until ints.length()) {
                    val inter = ints.getJSONObject(ii)
                    val entry = inter.optJSONArray("entry") ?: continue
                    var options = 0
                    for (k in 0 until entry.length()) if (entry.getBoolean(k)) options++
                    if (options >= 2) { val l = inter.getJSONArray("location"); exits.add(Exit(null, null, Pt(l.getDouble(0), l.getDouble(1)), road = ref)) }
                }
            }
        }
        val byRef = LinkedHashMap<String, Double>()
        for (s in steps) { val ref = s.ref?.split(';')?.first()?.trim() ?: continue; byRef[ref] = (byRef[ref] ?: 0.0) + (s.to - s.from) }
        val summary = byRef.entries.sortedByDescending { it.value }.take(3).joinToString(", ") { it.key }
        return Route(coords, r.getDouble("distance"), r.getDouble("duration"), steps, exits, summary)
    }
}
