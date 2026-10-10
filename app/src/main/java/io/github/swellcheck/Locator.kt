package io.github.swellcheck

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper

/**
 * Gets the phone's position using Android's own LocationManager, so it works
 * without Google Play services (important for F-Droid users).
 */
object Locator {
    private const val MAX_AGE_MS = 30 * 60 * 1000L
    private const val TIMEOUT_MS = 20 * 1000L

    fun hasPermission(ctx: Context): Boolean =
        ctx.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
            ctx.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED

    @SuppressLint("MissingPermission")
    fun locate(ctx: Context, callback: (Location?) -> Unit) {
        val lm = ctx.getSystemService(LocationManager::class.java)
        if (lm == null || !hasPermission(ctx)) { callback(null); return }

        val candidates = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= 31) candidates += LocationManager.FUSED_PROVIDER
        candidates += LocationManager.NETWORK_PROVIDER
        candidates += LocationManager.GPS_PROVIDER
        candidates += LocationManager.PASSIVE_PROVIDER
        val providers = candidates.filter { runCatching { lm.isProviderEnabled(it) }.getOrDefault(false) }

        var best: Location? = null
        for (p in providers) {
            val l = try { lm.getLastKnownLocation(p) } catch (e: Exception) { null } ?: continue
            val b = best
            if (b == null || l.time > b.time) best = l
        }
        val known = best
        if (known != null && System.currentTimeMillis() - known.time < MAX_AGE_MS) {
            callback(known); return
        }

        val active = providers.firstOrNull { it != LocationManager.PASSIVE_PROVIDER }
        if (active == null) { callback(known); return }

        var delivered = false
        fun deliver(l: Location?) {
            if (delivered) return
            delivered = true
            callback(l ?: known)
        }

        // Never leave the caller waiting forever (e.g. indoors with GPS only).
        var listenerToCancel: LocationListener? = null
        Handler(Looper.getMainLooper()).postDelayed({
            listenerToCancel?.let { runCatching { lm.removeUpdates(it) } }
            deliver(null)
        }, TIMEOUT_MS)

        try {
            if (Build.VERSION.SDK_INT >= 30) {
                lm.getCurrentLocation(active, null, ctx.mainExecutor) { loc -> deliver(loc) }
            } else {
                val listener = object : LocationListener {
                    override fun onLocationChanged(location: Location) {
                        lm.removeUpdates(this)
                        deliver(location)
                    }
                    @Deprecated("Deprecated in Java")
                    override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
                    override fun onProviderEnabled(provider: String) {}
                    override fun onProviderDisabled(provider: String) {
                        lm.removeUpdates(this)
                        deliver(null)
                    }
                }
                listenerToCancel = listener
                lm.requestLocationUpdates(active, 0L, 0f, listener, Looper.getMainLooper())
            }
        } catch (e: Exception) {
            deliver(null)
        }
    }
}
