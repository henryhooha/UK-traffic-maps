package uk.roadahead.core

import org.json.JSONObject

/**
 * OpenStreetMap via Overpass:
 *  - fixed speed cameras (highway=speed_camera) and average-speed zones
 *    (type=enforcement + enforcement=average_speed relations) along a route
 *  - junction numbers (highway=motorway_junction) for OSRM's exits
 */
object Overpass {
    private val junctionCache = HashMap<String, Pair<String?, String?>?>()

    /** Bounding boxes covering the route in ~25 km chunks. */
    fun routeBoxes(line: RouteLine, chunk: Double = 25000.0, pad: Double = 0.006): List<DoubleArray> {
        val boxes = mutableListOf<DoubleArray>()
        var a = 0.0
        while (a < line.length) {
            val pts = line.slice(a, minOf(line.length, a + chunk))
            boxes.add(doubleArrayOf(pts.minOf { it.lat } - pad, pts.minOf { it.lon } - pad, pts.maxOf { it.lat } + pad, pts.maxOf { it.lon } + pad))
            a += chunk
        }
        return boxes
    }

    fun cameras(url: String, line: RouteLine): List<Event> {
        val boxes = routeBoxes(line)
        if (boxes.isEmpty()) return emptyList()
        val parts = boxes.joinToString("") { b ->
            val bb = String.format(java.util.Locale.US, "%.5f,%.5f,%.5f,%.5f", b[0], b[1], b[2], b[3])
            "node[\"highway\"=\"speed_camera\"]($bb);relation[\"type\"=\"enforcement\"][\"enforcement\"=\"average_speed\"]($bb);"
        }
        val q = "[out:json][timeout:25];($parts);out body;>;out skel qt;"
        return parseCameras(Http.postForm(url, "data=" + Http.enc(q), timeoutMs = 30000))
    }

    private fun mph(tag: String?): Int? {
        if (tag == null) return null
        val n = Regex("(\\d+)").find(tag)?.value?.toIntOrNull() ?: return null
        return if (tag.contains("mph")) n else (n / 1.609344 / 10).let { Math.round(it).toInt() * 10 }
    }

    fun parseCameras(json: String): List<Event> {
        val els = JSONObject(json).optJSONArray("elements") ?: return emptyList()
        val nodes = HashMap<Long, Pt>()
        for (i in 0 until els.length()) { val e = els.getJSONObject(i); if (e.optString("type") == "node" && e.has("lat")) nodes[e.getLong("id")] = Pt(e.getDouble("lon"), e.getDouble("lat")) }
        val out = mutableListOf<Event>()
        for (i in 0 until els.length()) {
            val e = els.getJSONObject(i)
            val tags = e.optJSONObject("tags") ?: continue
            if (e.optString("type") == "node" && tags.optString("highway") == "speed_camera") {
                val limit = mph(tags.optString("maxspeed").ifEmpty { null })
                val brg = tags.optString("direction").toDoubleOrNull()
                out.add(Event(
                    id = "osm:cam:${e.getLong("id")}", source = "osm", type = EventType.SPEED_CAMERA, severity = Severity.MEDIUM,
                    location = Pt(e.getDouble("lon"), e.getDouble("lat")), bearing = brg,
                    title = "Speed camera${limit?.let { " · $it mph" } ?: ""}", speedLimit = limit,
                    description = tags.optString("name").ifEmpty { null },
                ))
            } else if (e.optString("type") == "relation" && tags.optString("enforcement") == "average_speed") {
                val members = e.optJSONArray("members") ?: continue
                var from: Pt? = null; var to: Pt? = null
                for (k in 0 until members.length()) {
                    val m = members.getJSONObject(k)
                    if (m.optString("type") != "node") continue
                    val p = nodes[m.getLong("ref")] ?: continue
                    when (m.optString("role")) { "from" -> if (from == null) from = p; "to" -> to = p }
                }
                if (from == null) continue
                val limit = mph(tags.optString("maxspeed").ifEmpty { null })
                out.add(Event(
                    id = "osm:avg:${e.getLong("id")}", source = "osm", type = EventType.AVERAGE_SPEED, severity = Severity.MEDIUM,
                    location = from, end = to, bearing = to?.let { bearing(from, it) },
                    title = "Average speed check${limit?.let { " · $it mph" } ?: ""}", speedLimit = limit,
                    description = tags.optString("name").ifEmpty { null },
                ))
            }
        }
        return out
    }

    fun nameJunctions(url: String, exits: List<Exit>): List<Exit> {
        if (exits.isEmpty()) return exits
        fun key(p: Pt) = String.format(java.util.Locale.US, "%.3f,%.3f", p.lon, p.lat)
        val todo = exits.filter { !junctionCache.containsKey(key(it.location)) }.take(150)
        if (todo.isNotEmpty()) runCatching {
            val q = "[out:json][timeout:20];(" + todo.joinToString("") { "node(around:900,${it.location.lat},${it.location.lon})[highway=motorway_junction];" } + ");out;"
            val els = JSONObject(Http.postForm(url, "data=" + Http.enc(q))).optJSONArray("elements")
            val nodes = (0 until (els?.length() ?: 0)).map { els!!.getJSONObject(it) }
            for (e in todo) {
                val best = nodes.map { n -> n to haversine(e.location, Pt(n.getDouble("lon"), n.getDouble("lat"))) }.filter { it.second < 900 }.minByOrNull { it.second }
                junctionCache[key(e.location)] = best?.first?.optJSONObject("tags")?.let { t -> t.optString("ref").ifEmpty { null } to t.optString("name").ifEmpty { null } }
            }
        }
        return exits.map { e ->
            junctionCache[key(e.location)]?.let { (ref, name) -> e.copy(ref = ref?.let { "J" + it.removePrefix("J") }, name = name) } ?: e
        }
    }
}
