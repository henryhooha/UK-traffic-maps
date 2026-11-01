package uk.roadahead.core

import org.json.JSONArray

data class Place(val name: String, val location: Pt)

/** Nominatim (OpenStreetMap) search, GB only. Max 1 request/second. */
object Geocoder {
    private val cache = HashMap<String, List<Place>>()
    private var last = 0L

    @Synchronized
    fun search(baseUrl: String, q: String, near: Pt? = null): List<Place> {
        val key = q.trim().lowercase()
        cache[key]?.let { return it }
        val wait = 1100 - (System.currentTimeMillis() - last)
        if (wait > 0) Thread.sleep(wait)
        last = System.currentTimeMillis()
        val bias = near?.let { "&viewbox=${it.lon - 1},${it.lat + 1},${it.lon + 1},${it.lat - 1}" } ?: ""
        val arr = JSONArray(Http.get("${baseUrl.trimEnd('/')}/search?format=jsonv2&limit=8&countrycodes=gb&q=${Http.enc(q)}$bias"))
        val out = (0 until arr.length()).map { val o = arr.getJSONObject(it); Place(o.getString("display_name"), Pt(o.getString("lon").toDouble(), o.getString("lat").toDouble())) }
        cache[key] = out
        return out
    }
}
