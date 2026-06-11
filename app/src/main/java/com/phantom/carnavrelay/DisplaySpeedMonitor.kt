package com.phantom.carnavrelay

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Looper
import androidx.core.content.ContextCompat
import kotlin.math.max
import kotlin.math.roundToInt

data class SpeedSnapshot(
    val kmh: Int?,
    val hasFix: Boolean,
    val updatedAt: Long,
    val accuracyMeters: Float? = null,
    val provider: String? = null,
    val source: String = "UNKNOWN",
    val lat: Double? = null,
    val lng: Double? = null,
    val locationTime: Long? = null
)

class DisplaySpeedMonitor(
    private val context: Context,
    private val onSnapshot: (SpeedSnapshot) -> Unit
) {

    companion object {
        fun hasLocationPermission(context: Context): Boolean {
            return ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.ACCESS_FINE_LOCATION
            ) == PackageManager.PERMISSION_GRANTED || ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.ACCESS_COARSE_LOCATION
            ) == PackageManager.PERMISSION_GRANTED
        }

        fun hasBackgroundLocationPermission(context: Context): Boolean {
            return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                ContextCompat.checkSelfPermission(
                    context,
                    Manifest.permission.ACCESS_BACKGROUND_LOCATION
                ) == PackageManager.PERMISSION_GRANTED
            } else {
                true
            }
        }

        fun isLocationEnabled(context: Context): Boolean {
            val manager = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager ?: return false
            return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                manager.isLocationEnabled
            } else {
                manager.isProviderEnabled(LocationManager.GPS_PROVIDER) ||
                    manager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)
            }
        }
    }

    private val locationManager =
        context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
    private var lastLocation: Location? = null
    private var started = false

    private val listener = LocationListener { location ->
        val snapshot = buildSnapshot(location)
        onSnapshot(snapshot)
        lastLocation = location
    }

    @SuppressLint("MissingPermission")
    fun start() {
        if (started) return
        if (!hasLocationPermission(context)) {
            onSnapshot(SpeedSnapshot(null, false, System.currentTimeMillis(), source = "NO_PERMISSION"))
            return
        }

        val manager = locationManager ?: run {
            onSnapshot(SpeedSnapshot(null, false, System.currentTimeMillis(), source = "NO_MANAGER"))
            return
        }

        started = true
        val providers = listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)
            .filter { provider ->
                try {
                    manager.isProviderEnabled(provider)
                } catch (_: Exception) {
                    false
                }
            }

        if (providers.isEmpty()) {
            onSnapshot(SpeedSnapshot(null, false, System.currentTimeMillis(), source = "LOCATION_DISABLED"))
            return
        }

        providers.forEach { provider ->
            try {
                manager.requestLocationUpdates(provider, 1000L, 0f, listener, Looper.getMainLooper())
                manager.getLastKnownLocation(provider)?.let {
                    val snapshot = buildSnapshot(it)
                    onSnapshot(snapshot)
                    lastLocation = it
                }
            } catch (_: SecurityException) {
                onSnapshot(SpeedSnapshot(null, false, System.currentTimeMillis(), source = "NO_PERMISSION"))
            } catch (_: Exception) {
                // Keep other providers alive if one fails.
            }
        }
    }

    fun stop() {
        if (!started) return
        started = false
        try {
            locationManager?.removeUpdates(listener)
        } catch (_: Exception) {
        }
    }

    private fun buildSnapshot(location: Location): SpeedSnapshot {
        val speedMps = when {
            location.hasSpeed() -> max(location.speed, 0f)
            lastLocation != null && location.time > lastLocation!!.time -> {
                val distance = lastLocation!!.distanceTo(location)
                val deltaSeconds = (location.time - lastLocation!!.time).coerceAtLeast(1L) / 1000f
                if (deltaSeconds > 0f) max(distance / deltaSeconds, 0f) else 0f
            }
            else -> 0f
        }
        val kmh = (speedMps * 3.6f).roundToInt()
        val hasFix = location.accuracy <= 80f || location.provider == LocationManager.GPS_PROVIDER
        return SpeedSnapshot(
            kmh = kmh,
            hasFix = hasFix,
            updatedAt = System.currentTimeMillis(),
            accuracyMeters = if (location.hasAccuracy()) location.accuracy else null,
            provider = location.provider,
            source = if (location.hasSpeed()) "GPS_SPEED" else "DISTANCE_DELTA",
            lat = location.latitude,
            lng = location.longitude,
            locationTime = location.time
        )
    }
}
