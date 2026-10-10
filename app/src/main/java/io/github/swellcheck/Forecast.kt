package io.github.swellcheck

import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI
import java.net.URLEncoder
import java.util.Locale

/** One point in time. Any value can be missing (null), e.g. no sea data inland. */
data class Hour(
    val time: Long,            // unix seconds, UTC
    val windKn: Double?,
    val windDir: Double?,      // degrees the wind comes FROM
    val gustKn: Double?,
    val airC: Double?,
    val waveM: Double?,
    val waveDir: Double?,
    val wavePeriod: Double?,
    val swellM: Double?,
    val swellDir: Double?,     // degrees the swell comes FROM
    val swellPeriod: Double?,
    val windWaveM: Double?,
    val seaLevelM: Double?,    // modelled sea level incl. tide (approximate)
    val seaTempC: Double?,
)

data class Forecast(
    val fetchedAt: Long,       // unix millis
    val now: Hour,
    val hours: List<Hour>,
) {
    val hasSea: Boolean get() = now.waveM != null || hours.any { it.waveM != null }

    /** Hours from the current hour onwards. */
    fun upcoming(nowSec: Long = System.currentTimeMillis() / 1000): List<Hour> =
        hours.filter { it.time + 3600 > nowSec }
}

data class Place(val name: String, val lat: Double, val lon: Double)

/** Talks to Open-Meteo directly. No accounts, no keys, no third parties. */
object Api {
    private const val WEATHER = "https://api.open-meteo.com/v1/forecast"
    private const val MARINE = "https://marine-api.open-meteo.com/v1/marine"
    private const val GEOCODE = "https://geocoding-api.open-meteo.com/v1/search"

    private const val WEATHER_VARS =
        "temperature_2m,wind_speed_10m,wind_direction_10m,wind_gusts_10m"
    private const val MARINE_VARS =
        "wave_height,wave_direction,wave_period," +
            "swell_wave_height,swell_wave_direction,swell_wave_period," +
            "wind_wave_height,sea_surface_temperature,sea_level_height_msl"

    private fun coord(v: Double) = String.format(Locale.US, "%.2f", v)

    fun weatherUrl(lat: Double, lon: Double) =
        "$WEATHER?latitude=${coord(lat)}&longitude=${coord(lon)}" +
            "&current=$WEATHER_VARS&hourly=$WEATHER_VARS" +
            "&wind_speed_unit=kn&timeformat=unixtime&forecast_days=4"

    fun marineUrl(lat: Double, lon: Double) =
        "$MARINE?latitude=${coord(lat)}&longitude=${coord(lon)}" +
            "&current=$MARINE_VARS&hourly=$MARINE_VARS" +
            "&timeformat=unixtime&forecast_days=4&cell_selection=sea"

    fun get(url: String): String {
        val c = URI(url).toURL().openConnection() as HttpURLConnection
        c.connectTimeout = 5000
        c.readTimeout = 7000
        c.setRequestProperty("User-Agent", "SwellCheck (open source)")
        try {
            val code = c.responseCode
            val stream = if (code in 200..299) c.inputStream else c.errorStream
            val body = stream?.bufferedReader()?.use { it.readText() } ?: ""
            if (code !in 200..299) throw IOException("HTTP $code ${body.take(200)}")
            return body
        } finally {
            c.disconnect()
        }
    }

    fun search(name: String): List<Place> {
        val q = URLEncoder.encode(name.trim(), "UTF-8")
        val json = JSONObject(get("$GEOCODE?name=$q&count=8&language=en&format=json"))
        val arr = json.optJSONArray("results") ?: return emptyList()
        return (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            val label = listOf(o.optString("name"), o.optString("admin1"), o.optString("country_code"))
                .filter { it.isNotBlank() }
                .joinToString(", ")
            Place(label, o.getDouble("latitude"), o.getDouble("longitude"))
        }
    }

    // ---- parsing -------------------------------------------------------

    private fun JSONArray?.num(i: Int?): Double? {
        if (this == null || i == null || i < 0 || i >= length() || isNull(i)) return null
        val v = optDouble(i)
        return if (v.isNaN()) null else v
    }

    private fun JSONObject?.num(key: String): Double? {
        if (this == null || !has(key) || isNull(key)) return null
        val v = optDouble(key)
        return if (v.isNaN()) null else v
    }

    fun parse(weatherJson: String, marineJson: String?, fetchedAt: Long): Forecast {
        val w = JSONObject(weatherJson)
        val m = marineJson?.let { runCatching { JSONObject(it) }.getOrNull() }

        val wh = w.getJSONObject("hourly")
        val times = wh.getJSONArray("time")
        val wTemp = wh.optJSONArray("temperature_2m")
        val wSpeed = wh.optJSONArray("wind_speed_10m")
        val wDir = wh.optJSONArray("wind_direction_10m")
        val wGust = wh.optJSONArray("wind_gusts_10m")

        val mh = m?.optJSONObject("hourly")
        val mIndex = HashMap<Long, Int>()
        mh?.optJSONArray("time")?.let { t -> for (i in 0 until t.length()) mIndex[t.getLong(i)] = i }
        val mWave = mh?.optJSONArray("wave_height")
        val mWaveDir = mh?.optJSONArray("wave_direction")
        val mWavePer = mh?.optJSONArray("wave_period")
        val mSwell = mh?.optJSONArray("swell_wave_height")
        val mSwellDir = mh?.optJSONArray("swell_wave_direction")
        val mSwellPer = mh?.optJSONArray("swell_wave_period")
        val mWindWave = mh?.optJSONArray("wind_wave_height")
        val mSst = mh?.optJSONArray("sea_surface_temperature")
        val mLevel = mh?.optJSONArray("sea_level_height_msl")

        val hours = (0 until times.length()).map { i ->
            val t = times.getLong(i)
            val mi = mIndex[t]
            Hour(
                time = t,
                windKn = wSpeed.num(i), windDir = wDir.num(i), gustKn = wGust.num(i),
                airC = wTemp.num(i),
                waveM = mWave.num(mi), waveDir = mWaveDir.num(mi), wavePeriod = mWavePer.num(mi),
                swellM = mSwell.num(mi), swellDir = mSwellDir.num(mi), swellPeriod = mSwellPer.num(mi),
                windWaveM = mWindWave.num(mi), seaLevelM = mLevel.num(mi), seaTempC = mSst.num(mi),
            )
        }

        val wc = w.optJSONObject("current")
        val mc = m?.optJSONObject("current")
        val nowTime = wc?.optLong("time", fetchedAt / 1000) ?: (fetchedAt / 1000)
        val now = Hour(
            time = nowTime,
            windKn = wc.num("wind_speed_10m"), windDir = wc.num("wind_direction_10m"),
            gustKn = wc.num("wind_gusts_10m"), airC = wc.num("temperature_2m"),
            waveM = mc.num("wave_height"), waveDir = mc.num("wave_direction"),
            wavePeriod = mc.num("wave_period"),
            swellM = mc.num("swell_wave_height"), swellDir = mc.num("swell_wave_direction"),
            swellPeriod = mc.num("swell_wave_period"),
            windWaveM = mc.num("wind_wave_height"), seaLevelM = mc.num("sea_level_height_msl"),
            seaTempC = mc.num("sea_surface_temperature"),
        )
        return Forecast(fetchedAt, now, hours)
    }
}
