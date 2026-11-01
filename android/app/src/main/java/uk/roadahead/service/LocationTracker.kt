package uk.roadahead.service

import android.annotation.SuppressLint
import android.content.Context
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Looper
import com.google.android.gms.common.ConnectionResult
import com.google.android.gms.common.GoogleApiAvailability
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority

/**
 * High-accuracy location, 1 Hz — the same fused GPS/Wi-Fi/sensor provider Waze
 * and Google Maps use, falling back to raw GPS if Play services are missing.
 */
class LocationTracker(private val ctx: Context, private val onFix: (Location) -> Unit) {
    private var fusedCb: LocationCallback? = null
    private var gpsListener: LocationListener? = null

    @SuppressLint("MissingPermission")
    fun start() {
        if (fusedCb != null || gpsListener != null) return
        val gms = GoogleApiAvailability.getInstance().isGooglePlayServicesAvailable(ctx) == ConnectionResult.SUCCESS
        if (gms) {
            val req = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 1000)
                .setMinUpdateIntervalMillis(500).setWaitForAccurateLocation(false).build()
            val cb = object : LocationCallback() { override fun onLocationResult(r: LocationResult) { r.lastLocation?.let(onFix) } }
            fusedCb = cb
            LocationServices.getFusedLocationProviderClient(ctx).requestLocationUpdates(req, cb, Looper.getMainLooper())
        } else {
            val lm = ctx.getSystemService(LocationManager::class.java)
            val l = LocationListener { onFix(it) }
            gpsListener = l
            lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1000L, 0f, l, Looper.getMainLooper())
        }
    }

    fun stop() {
        fusedCb?.let { LocationServices.getFusedLocationProviderClient(ctx).removeLocationUpdates(it) }
        gpsListener?.let { ctx.getSystemService(LocationManager::class.java).removeUpdates(it) }
        fusedCb = null; gpsListener = null
    }

    @SuppressLint("MissingPermission")
    fun lastKnown(cb: (Location?) -> Unit) {
        runCatching {
            LocationServices.getFusedLocationProviderClient(ctx).getCurrentLocation(Priority.PRIORITY_HIGH_ACCURACY, null)
                .addOnSuccessListener { cb(it) }.addOnFailureListener {
                    cb(ctx.getSystemService(LocationManager::class.java).getLastKnownLocation(LocationManager.GPS_PROVIDER))
                }
        }.onFailure { cb(null) }
    }
}
