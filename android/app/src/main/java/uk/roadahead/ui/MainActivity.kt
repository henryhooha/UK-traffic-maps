package uk.roadahead.ui

import android.Manifest
import android.annotation.SuppressLint
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import uk.roadahead.core.*
import uk.roadahead.service.DriveMode
import uk.roadahead.service.NavController
import uk.roadahead.service.NavState
import java.io.File

private const val AA_PKG = "com.google.android.projection.gearhead"

class MainActivity : AppCompatActivity() {
    private lateinit var web: WebView
    private var pageReady = false
    private var sentRoutes = -1
    private var sentAnalysis = -1
    private var insetsJs = ""

    private val permLauncher = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { res ->
        if (res[Manifest.permission.ACCESS_FINE_LOCATION] == true) NavController.locateOnce()
        else if (res.containsKey(Manifest.permission.ACCESS_FINE_LOCATION))
            Toast.makeText(this, "Precise location is needed for driving alerts", Toast.LENGTH_LONG).show()
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        web = WebView(this)
        setContentView(web)
        // Android 15 draws apps edge-to-edge (behind the status and navigation
        // bars). Keep the map full-screen but tell the page how big the bars
        // are so the controls sit clear of them.
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowCompat.getInsetsController(window, web).isAppearanceLightStatusBars = false
        ViewCompat.setOnApplyWindowInsetsListener(web) { _, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            val ime = insets.getInsets(WindowInsetsCompat.Type.ime())
            val d = resources.displayMetrics.density
            fun px(v: Int) = "${(v / d).toInt()}px"
            insetsJs = "(function(s){s.setProperty('--sat','${px(bars.top)}');s.setProperty('--sab','${px(maxOf(bars.bottom, ime.bottom))}');" +
                "s.setProperty('--sal','${px(bars.left)}');s.setProperty('--sar','${px(bars.right)}');})(document.documentElement.style)"
            js(insetsJs)
            insets
        }
        web.settings.javaScriptEnabled = true
        web.settings.domStorageEnabled = true
        web.settings.userAgentString = web.settings.userAgentString + " RoadAheadUK/0.1"
        web.webChromeClient = WebChromeClient()
        web.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, url: String?) { pageReady = true; if (insetsJs.isNotEmpty()) web.evaluateJavascript(insetsJs, null); sentRoutes = -1; sentAnalysis = -1; push(NavController.state.value) }
        }
        web.addJavascriptInterface(Bridge(), "Android")
        web.loadUrl("file:///android_asset/web/index.html")

        askPermissions()

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                NavController.state.collect { s ->
                    push(s)
                    if (s.mode != DriveMode.NONE) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                    else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                    delay(250)
                }
            }
        }
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                NavController.alerts.collect { a -> js("RA.onAlert(${alertJson(a)})") }
            }
        }
    }

    private fun askPermissions() {
        val need = mutableListOf<String>()
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            need += Manifest.permission.ACCESS_FINE_LOCATION; need += Manifest.permission.ACCESS_COARSE_LOCATION
        }
        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
            need += Manifest.permission.POST_NOTIFICATIONS
        if (need.isNotEmpty()) permLauncher.launch(need.toTypedArray()) else NavController.locateOnce()
    }

    private fun js(code: String) = runOnUiThread { if (pageReady) web.evaluateJavascript(code, null) }
    private fun q(s: String) = JSONObject.quote(s)

    private fun alertJson(a: Alert) = JSONObject().put("kind", a.kind.name.lowercase()).put("title", a.title).put("subtitle", a.subtitle)
        .put("severity", a.severity.name.lowercase()).put("distance", a.distance).putOpt("itemId", a.itemId).put("icon", a.icon).put("key", a.key)

    private fun push(s: NavState) {
        if (!pageReady) return
        if (s.routesVersion != sentRoutes) {
            sentRoutes = s.routesVersion
            val arr = JSONArray(); s.routes.forEach { arr.put(it.toJson(true)) }
            js("RA.onRoutes(${arr})")
            sentAnalysis = -1
        }
        if (s.analysisVersion != sentAnalysis) {
            sentAnalysis = s.analysisVersion
            val arr = JSONArray(); s.routes.forEach { arr.put(it.toJson(false)) }
            js("RA.onAnalysis(${JSONObject().putOpt("activeId", s.active?.id).put("routes", arr)})")
        }
        val o = JSONObject()
            .put("along", s.along).putOpt("position", s.position?.toJson()).putOpt("heading", s.heading).putOpt("speedMs", s.speedMs)
            .putOpt("accuracy", s.accuracy?.toDouble()).put("mode", s.mode.name.lowercase()).putOpt("limit", s.limit)
            .putOpt("activeId", s.active?.id).putOpt("destination", s.destination?.name)
            .putOpt("busy", s.busy).putOpt("error", s.error).put("demo", s.demo)
            .putOpt("lastAlert", s.lastAlert?.let { alertJson(it) })
            .put("reports", Report.listToJson(s.reports))
            .put("sources", JSONArray().also { a -> s.sources.forEach { a.put(JSONObject().put("name", it.name).put("count", it.count).putOpt("error", it.error)) } })
        s.nextStep?.let { st -> o.put("nextStep", JSONObject().put("text", Instructions.text(st)).put("arrow", Instructions.arrow(st)).put("distance", st.from - s.along)) }
        s.active?.let { r -> o.put("remaining", r.distance - s.along).put("remainingMinutes", r.duration / 60 * (1 - s.along / r.distance) + (r.analysis?.items?.filter { (it.kind == ItemKind.ZONE || it.kind == ItemKind.EVENT) && it.end > s.along }?.sumOf { it.delayMinutes } ?: 0.0))
            o.putOpt("road", r.roadAt(s.along)?.let { st -> listOfNotNull(st.ref, st.name).joinToString(" · ") }) }
        s.diversion?.let { d ->
            o.put("diversion", JSONObject().put("hazard", d.hazard.headline).put("stayMinutes", d.stayMinutes).put("stayDelay", d.stayDelay)
                .put("options", JSONArray().also { a -> d.options.forEach { r -> a.put(r.toJson(true).put("deltaMinutes", Math.round(r.score - d.stayMinutes))) } }))
        }
        js("RA.onState($o)")
    }

    /** Methods callable from the page as Android.xxx(). */
    inner class Bridge {
        @JavascriptInterface fun search(q: String, cb: String) {
            lifecycleScope.launch {
                val res = runCatching { NavController.search(q) }
                val arr = JSONArray()
                res.getOrNull()?.forEach { arr.put(JSONObject().put("name", it.name).put("location", it.location.toJson())) }
                js("RA.onResult(${q(cb)}, ${JSONObject().put("results", arr).putOpt("error", res.exceptionOrNull()?.message)})")
            }
        }
        @JavascriptInterface fun plan(json: String) {
            val o = JSONObject(json)
            val from = o.optJSONArray("from")?.let { Pt(it.getDouble(0), it.getDouble(1)) }
            val to = o.getJSONObject("to")
            val t = to.getJSONArray("location")
            NavController.plan(from, Place(to.getString("name"), Pt(t.getDouble(0), t.getDouble(1))), o.optBoolean("go"))
        }
        @JavascriptInterface fun selectRoute(id: String) = NavController.select(id)
        @JavascriptInterface fun startDrive(mode: String, simSpeed: Double) {
            NavController.simSpeed = simSpeed
            runOnUiThread {
                if (mode == "gps" && !NavController.hasLocationPermission()) askPermissions()
                else NavController.startDrive(if (mode == "sim") DriveMode.SIM else DriveMode.GPS)
            }
        }
        @JavascriptInterface fun setSimSpeed(v: Double) { NavController.simSpeed = v }
        @JavascriptInterface fun stopDrive() = NavController.stopDrive()
        @JavascriptInterface fun report(type: String) { ReportType.parse(type)?.let { NavController.report(it) } }
        @JavascriptInterface fun deleteReport(id: String) = NavController.deleteReport(id)
        @JavascriptInterface fun divert(itemId: String) = NavController.divert(itemId)
        @JavascriptInterface fun chooseDiversion(i: Int) = NavController.chooseDiversion(i)
        @JavascriptInterface fun cancelDiversion() = NavController.cancelDiversion()
        @JavascriptInterface fun dismissAlert() = NavController.dismissAlert()
        @JavascriptInterface fun clearError() = NavController.clearError()
        @JavascriptInterface fun refresh() = NavController.refresh(true)
        @JavascriptInterface fun locate() = runOnUiThread { if (NavController.hasLocationPermission()) NavController.locateOnce() else askPermissions() }
        @JavascriptInterface fun getSettings(): String = NavController.settings.toJson().toString()
        @JavascriptInterface fun saveSettings(json: String) { NavController.settings.save(JSONObject(json)); NavController.settingsChanged() }
        @JavascriptInterface fun shareToOsm(id: String) = NavController.shareReportToOsm(id) { msg -> runOnUiThread { Toast.makeText(this@MainActivity, msg, Toast.LENGTH_LONG).show() } }
        @JavascriptInterface fun openInWaze(lon: Double, lat: Double) = runOnUiThread {
            // Waze has no public reporting API: open Waze at the spot so you can report it there yourself.
            val i = Intent(Intent.ACTION_VIEW, Uri.parse("https://waze.com/ul?ll=$lat,$lon&z=16"))
            runCatching { startActivity(i) }
        }
        /** Diagnostics for "the app doesn't appear in Android Auto". */
        @JavascriptInterface fun autoStatus(): String {
            val pm = packageManager
            val aa = runCatching { pm.getPackageInfo(AA_PKG, 0) }.getOrNull()
            val svc = pm.queryIntentServices(Intent("androidx.car.app.CarAppService").setPackage(packageName), 0)
            val installer = runCatching {
                if (Build.VERSION.SDK_INT >= 30) pm.getInstallSourceInfo(packageName).installingPackageName
                else @Suppress("DEPRECATION") pm.getInstallerPackageName(packageName)
            }.getOrNull()
            return JSONObject().put("aaInstalled", aa != null).putOpt("aaVersion", aa?.versionName)
                .put("serviceOk", svc.isNotEmpty()).putOpt("installer", installer)
                .put("fromPlay", installer == "com.android.vending")
                .put("location", NavController.hasLocationPermission()).toString()
        }
        @JavascriptInterface fun openAutoSettings() = runOnUiThread {
            // Android Auto has no public settings intent; try its own entry points, then its app-info page.
            val tries = listOf(
                Intent().setClassName(AA_PKG, "com.google.android.apps.auto.carservice.settings.SettingsActivity"),
                Intent().setClassName(AA_PKG, "com.google.android.projection.gearhead.companion.settings.SettingsActivity"),
                packageManager.getLaunchIntentForPackage(AA_PKG),
                Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$AA_PKG")),
            )
            for (i in tries.filterNotNull()) if (runCatching { startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }.isSuccess) return@runOnUiThread
            Toast.makeText(this@MainActivity, "Open Settings and search for \"Android Auto\"", Toast.LENGTH_LONG).show()
        }
        @JavascriptInterface fun exportReports() = runOnUiThread {
            val f = File(cacheDir, "road-ahead-reports.gpx")
            f.writeText(Report.toGpx(NavController.state.value.reports))
            val uri = FileProvider.getUriForFile(this@MainActivity, "$packageName.files", f)
            startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("application/gpx+xml").putExtra(Intent.EXTRA_STREAM, uri)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION), "Export reports"))
        }
    }
}
