package io.github.swellcheck

import android.content.Context
import java.util.Locale

/** Fetches and caches the forecast. Call [refresh] off the main thread. */
object Repo {
    private const val FRESH_MS = 20 * 60 * 1000L

    // Coordinates are rounded to ~1 km before leaving the phone. The forecast
    // grids are coarser than that anyway, so nothing is lost.
    private fun key(lat: Double, lon: Double) = String.format(Locale.US, "%.2f,%.2f", lat, lon)

    fun cached(ctx: Context): Forecast? {
        val p = Prefs(ctx)
        val w = p.weatherJson ?: return null
        return runCatching { Api.parse(w, p.marineJson, p.fetchedAt) }.getOrNull()
    }

    @Synchronized
    fun refresh(ctx: Context, force: Boolean = false): Forecast? {
        val p = Prefs(ctx)
        val lat = p.lat ?: return null
        val lon = p.lon ?: return null
        val k = key(lat, lon)
        val age = System.currentTimeMillis() - p.fetchedAt
        if (!force && k == p.cacheKey && age < FRESH_MS) return cached(ctx)

        val weather = Api.get(Api.weatherUrl(lat, lon))
        // Inland there is no sea data; keep going with just the weather.
        val marine = runCatching { Api.get(Api.marineUrl(lat, lon)) }.getOrNull()
        val now = System.currentTimeMillis()
        p.saveCache(weather, marine, now, k)
        return Api.parse(weather, marine, now)
    }
}
