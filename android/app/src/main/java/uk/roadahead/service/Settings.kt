package uk.roadahead.service

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import uk.roadahead.core.METRES_PER_MILE
import uk.roadahead.core.AlertSettings

/** All user settings, stored in SharedPreferences, editable from the phone UI. */
class Settings(ctx: Context) {
    private val p = ctx.getSharedPreferences("settings", Context.MODE_PRIVATE)

    companion object {
        val DEFAULTS: Map<String, Any> = mapOf(
            "horizonMiles" to 10, "voice" to true, "cameras" to true, "minor" to false, "turnByTurn" to true,
            "demo" to true, "tfl" to true, "tflKey" to "",
            "osrmUrl" to "https://router.project-osrm.org",
            "nominatimUrl" to "https://nominatim.openstreetmap.org",
            "overpassUrl" to "https://overpass-api.de/api/interpreter",
            "osmApiUrl" to "https://api.openstreetmap.org",
            "nhSituationsUrl" to "", "nhPlannedUrl" to "", "nhVmsTableUrl" to "", "nhVmsStatusUrl" to "",
            "nhSignalTableUrl" to "", "nhSignalStatusUrl" to "", "nhUser" to "", "nhPass" to "", "nhKey" to "",
            "extraDatexUrls" to "",
        )
    }

    fun str(k: String): String = p.getString(k, DEFAULTS[k] as? String ?: "") ?: ""
    fun bool(k: String): Boolean = p.getBoolean(k, DEFAULTS[k] as? Boolean ?: false)
    fun int(k: String): Int = p.getInt(k, DEFAULTS[k] as? Int ?: 0)

    val alertSettings get() = AlertSettings(int("horizonMiles") * METRES_PER_MILE, bool("cameras"), bool("minor"), bool("turnByTurn"))

    fun toJson(): JSONObject = JSONObject().also { o ->
        DEFAULTS.forEach { (k, v) -> when (v) { is Boolean -> o.put(k, bool(k)); is Int -> o.put(k, int(k)); else -> o.put(k, str(k)) } }
    }

    fun save(o: JSONObject) {
        val e = p.edit()
        DEFAULTS.forEach { (k, v) ->
            if (!o.has(k)) return@forEach
            when (v) {
                is Boolean -> e.putBoolean(k, o.getBoolean(k))
                is Int -> e.putInt(k, o.optInt(k, v))
                else -> e.putString(k, o.optString(k).trim())
            }
        }
        e.apply()
    }

    /** Recent destinations (for the car's search screen). */
    fun recents(): List<Pair<String, uk.roadahead.core.Pt>> = runCatching {
        val a = JSONArray(p.getString("recents", "[]"))
        (0 until a.length()).map { val o = a.getJSONObject(it); o.getString("name") to uk.roadahead.core.Pt(o.getDouble("lon"), o.getDouble("lat")) }
    }.getOrDefault(emptyList())

    fun addRecent(name: String, pt: uk.roadahead.core.Pt) {
        val list = (listOf(name to pt) + recents().filter { it.first != name }).take(8)
        p.edit().putString("recents", JSONArray().also { a -> list.forEach { a.put(JSONObject().put("name", it.first).put("lon", it.second.lon).put("lat", it.second.lat)) } }.toString()).apply()
    }

    fun hasLiveSignalFeed() = str("nhVmsStatusUrl").isNotBlank() || str("nhSignalStatusUrl").isNotBlank()

    fun nhHeaders(): Map<String, String> {
        val h = HashMap<String, String>()
        if (str("nhUser").isNotBlank()) h["Authorization"] = "Basic " + android.util.Base64.encodeToString("${str("nhUser")}:${str("nhPass")}".toByteArray(), android.util.Base64.NO_WRAP)
        if (str("nhKey").isNotBlank()) h["Ocp-Apim-Subscription-Key"] = str("nhKey")
        return h
    }
}
