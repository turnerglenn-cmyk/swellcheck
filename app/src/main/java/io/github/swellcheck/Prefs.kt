package io.github.swellcheck

import android.content.Context

/** Everything the app remembers. Stored only on this phone. */
class Prefs(context: Context) {
    private val sp = context.applicationContext.getSharedPreferences("swellcheck", Context.MODE_PRIVATE)

    private fun getD(key: String): Double? = sp.getString(key, null)?.toDoubleOrNull()
    private fun putD(key: String, v: Double?) = sp.edit().putString(key, v?.toString()).apply()

    /** true = follow the phone's location, false = a fixed place chosen in Settings. */
    var useGps: Boolean
        get() = sp.getBoolean("use_gps", true)
        set(v) = sp.edit().putBoolean("use_gps", v).apply()

    var lat: Double?
        get() = getD("lat")
        set(v) = putD("lat", v)

    var lon: Double?
        get() = getD("lon")
        set(v) = putD("lon", v)

    /** Name of a fixed place; null when following GPS. */
    var placeName: String?
        get() = sp.getString("place_name", null)
        set(v) = sp.edit().putString("place_name", v).apply()

    /** Compass bearing the beach faces (looking out to sea), or -1 if not set. */
    var beachFacing: Int
        get() = sp.getInt("beach_facing", -1)
        set(v) = sp.edit().putInt("beach_facing", v).apply()

    var useFeet: Boolean
        get() = sp.getBoolean("use_feet", true)
        set(v) = sp.edit().putBoolean("use_feet", v).apply()

    var windUnit: WindUnit
        get() = runCatching { WindUnit.valueOf(sp.getString("wind_unit", null) ?: "MPH") }.getOrDefault(WindUnit.MPH)
        set(v) = sp.edit().putString("wind_unit", v.name).apply()

    val units: Units get() = Units(windUnit, useFeet)

    var maxWind: Double
        get() = getD("max_wind") ?: 15.0
        set(v) = putD("max_wind", v)

    var maxGust: Double
        get() = getD("max_gust") ?: 22.0
        set(v) = putD("max_gust", v)

    var maxWave: Double
        get() = getD("max_wave") ?: 1.2
        set(v) = putD("max_wave", v)

    val limits: Limits get() = Limits(maxWind, maxGust, maxWave)

    /** Colour theme id (see Themes). */
    var theme: String
        get() = sp.getString("theme", null) ?: Themes.DEFAULT
        set(v) = sp.edit().putString("theme", v).apply()

    /** How the main screen is laid out. */
    var layout: ScreenLayout
        get() = runCatching { ScreenLayout.valueOf(sp.getString("layout", null) ?: "CLASSIC") }.getOrDefault(ScreenLayout.CLASSIC)
        set(v) = sp.edit().putString("layout", v.name).apply()
    val facingOrNull: Int? get() = beachFacing.takeIf { it in 0..359 }

    // ---- cached forecast -------------------------------------------------

    val weatherJson: String? get() = sp.getString("cache_weather", null)
    val marineJson: String? get() = sp.getString("cache_marine", null)
    val fetchedAt: Long get() = sp.getLong("cache_time", 0L)
    val cacheKey: String? get() = sp.getString("cache_key", null)

    fun saveCache(weather: String, marine: String?, time: Long, key: String) {
        sp.edit()
            .putString("cache_weather", weather)
            .putString("cache_marine", marine)
            .putLong("cache_time", time)
            .putString("cache_key", key)
            .apply()
    }
}
