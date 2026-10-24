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
    // Weather, for the live scene. Missing in forecasts saved by older versions.
    val cloudPct: Double? = null,      // 0..100
    val precipMm: Double? = null,      // mm in the hour
    val weatherCode: Int? = null,      // WMO code (61 rain, 71 snow, 95 thunder...)
    val visibilityM: Double? = null,
    // Extra sea detail: a second swell train, and which way the wind waves run.
    val swell2M: Double? = null,
    val swell2Dir: Double? = null,
    val swell2Period: Double? = null,
    val windWaveDir: Double? = null,
    val windWavePeriod: Double? = null,
)

/** A 15-minute step of the weather model (real 15-minute data where the model has it). */
data class Quarter(
    val time: Long,
    val windKn: Double?, val windDir: Double?, val gustKn: Double?,
    val precipMm: Double?,     // mm in the 15 minutes
    val visibilityM: Double?,
)

data class Forecast(
    val fetchedAt: Long,       // unix millis
    val now: Hour,
    val hours: List<Hour>,
    val quarters: List<Quarter> = emptyList(),
) {
    val hasSea: Boolean get() = now.waveM != null || hours.any { it.waveM != null }

    /** Hours from the current hour onwards. */
    fun upcoming(nowSec: Long = System.currentTimeMillis() / 1000): List<Hour> =
        hours.filter { it.time + 3600 > nowSec }

    /**
     * Conditions at an exact moment rather than on the hour: values are blended between the
     * hours either side (directions the short way round the compass), and wind, gusts, rain and
     * visibility come from the 15-minute data where there is some. Weather type and cloud are
     * taken from the nearer hour. Used by the live scene so it changes smoothly through the day.
     */
    fun at(sec: Long): Hour {
        val i = hours.indexOfLast { it.time <= sec }
        if (i < 0) return hours.firstOrNull() ?: now
        val a = hours[i]
        val b = hours.getOrNull(i + 1) ?: return a
        val f = ((sec - a.time).toDouble() / (b.time - a.time)).coerceIn(0.0, 1.0)
        fun lin(x: Double?, y: Double?) = if (x == null) y else if (y == null) x else x + (y - x) * f
        fun ang(x: Double?, y: Double?): Double? {
            if (x == null || y == null) return x ?: y
            var dd = (y - x) % 360.0; if (dd > 180) dd -= 360.0; if (dd < -180) dd += 360.0
            return ((x + dd * f) % 360.0 + 360.0) % 360.0
        }
        val near = if (f < 0.5) a else b
        // 15-minute weather, blended the same way, when it covers this moment
        val qi = quarters.indexOfLast { it.time <= sec }
        val qa = quarters.getOrNull(qi); val qb = quarters.getOrNull(qi + 1)
        val qf = if (qa != null && qb != null) ((sec - qa.time).toDouble() / (qb.time - qa.time)).coerceIn(0.0, 1.0) else 0.0
        fun qlin(x: Double?, y: Double?) = if (x == null) y else if (y == null) x else x + (y - x) * qf
        val q = qa != null && qb != null && sec - qa.time < 1800
        return Hour(
            time = sec,
            windKn = if (q) qlin(qa!!.windKn, qb!!.windKn) ?: lin(a.windKn, b.windKn) else lin(a.windKn, b.windKn),
            windDir = if (q) ang(qa!!.windDir, qb!!.windDir) ?: ang(a.windDir, b.windDir) else ang(a.windDir, b.windDir),
            gustKn = if (q) qlin(qa!!.gustKn, qb!!.gustKn) ?: lin(a.gustKn, b.gustKn) else lin(a.gustKn, b.gustKn),
            airC = lin(a.airC, b.airC),
            waveM = lin(a.waveM, b.waveM), waveDir = ang(a.waveDir, b.waveDir), wavePeriod = lin(a.wavePeriod, b.wavePeriod),
            swellM = lin(a.swellM, b.swellM), swellDir = ang(a.swellDir, b.swellDir), swellPeriod = lin(a.swellPeriod, b.swellPeriod),
            windWaveM = lin(a.windWaveM, b.windWaveM), seaLevelM = lin(a.seaLevelM, b.seaLevelM), seaTempC = lin(a.seaTempC, b.seaTempC),
            cloudPct = lin(a.cloudPct, b.cloudPct),
            // rain as an hourly rate: from the 15-minute total when we have it
            precipMm = if (q) qlin(qa!!.precipMm, qb!!.precipMm)?.let { it * 4 } ?: near.precipMm else near.precipMm,
            weatherCode = near.weatherCode,
            visibilityM = if (q) qlin(qa!!.visibilityM, qb!!.visibilityM) ?: lin(a.visibilityM, b.visibilityM) else lin(a.visibilityM, b.visibilityM),
            swell2M = lin(a.swell2M, b.swell2M), swell2Dir = ang(a.swell2Dir, b.swell2Dir), swell2Period = lin(a.swell2Period, b.swell2Period),
            windWaveDir = ang(a.windWaveDir, b.windWaveDir), windWavePeriod = lin(a.windWavePeriod, b.windWavePeriod),
        )
    }
}

data class Place(val name: String, val lat: Double, val lon: Double)

/** Talks to Open-Meteo directly. No accounts, no keys, no third parties. */
object Api {
    private const val WEATHER = "https://api.open-meteo.com/v1/forecast"
    private const val MARINE = "https://marine-api.open-meteo.com/v1/marine"
    private const val GEOCODE = "https://geocoding-api.open-meteo.com/v1/search"

    private const val WEATHER_VARS =
        "temperature_2m,wind_speed_10m,wind_direction_10m,wind_gusts_10m,cloud_cover,precipitation,weather_code"
    private const val MARINE_VARS =
        "wave_height,wave_direction,wave_period," +
            "swell_wave_height,swell_wave_direction,swell_wave_period," +
            "wind_wave_height,wind_wave_direction,wind_wave_period," +
            "secondary_swell_wave_height,secondary_swell_wave_direction,secondary_swell_wave_period," +
            "sea_surface_temperature,sea_level_height_msl"
    // 15-minute steps: real 15-minute model output in parts of Europe and North America,
    // interpolated from hourly elsewhere. Next 6 hours.
    private const val QUARTER_VARS = "wind_speed_10m,wind_direction_10m,wind_gusts_10m,precipitation,visibility"

    private fun coord(v: Double) = String.format(Locale.US, "%.2f", v)

    fun weatherUrl(lat: Double, lon: Double) =
        "$WEATHER?latitude=${coord(lat)}&longitude=${coord(lon)}" +
            "&current=$WEATHER_VARS&hourly=$WEATHER_VARS,visibility" +
            "&minutely_15=$QUARTER_VARS&past_minutely_15=4&forecast_minutely_15=24" +
            // best_match: the highest-resolution model for the spot (e.g. Met Office UK 2 km in the UK
            // and Ireland, AROME 1.5 km in France, ICON-D2 in central Europe), blended into global ones.
            "&models=best_match" +
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
        val wCloud = wh.optJSONArray("cloud_cover")
        val wRain = wh.optJSONArray("precipitation")
        val wCode = wh.optJSONArray("weather_code")
        val wVis = wh.optJSONArray("visibility")

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
        val mWindWaveDir = mh?.optJSONArray("wind_wave_direction")
        val mWindWavePer = mh?.optJSONArray("wind_wave_period")
        val m2 = mh?.optJSONArray("secondary_swell_wave_height")
        val m2Dir = mh?.optJSONArray("secondary_swell_wave_direction")
        val m2Per = mh?.optJSONArray("secondary_swell_wave_period")
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
                cloudPct = wCloud.num(i), precipMm = wRain.num(i), weatherCode = wCode.num(i)?.toInt(),
                visibilityM = wVis.num(i),
                swell2M = m2.num(mi), swell2Dir = m2Dir.num(mi), swell2Period = m2Per.num(mi),
                windWaveDir = mWindWaveDir.num(mi), windWavePeriod = mWindWavePer.num(mi),
            )
        }

        val q = w.optJSONObject("minutely_15")
        val quarters = q?.optJSONArray("time")?.let { qt ->
            val qs = q.optJSONArray("wind_speed_10m"); val qd = q.optJSONArray("wind_direction_10m")
            val qg = q.optJSONArray("wind_gusts_10m"); val qp = q.optJSONArray("precipitation")
            val qv = q.optJSONArray("visibility")
            (0 until qt.length()).map { i -> Quarter(qt.getLong(i), qs.num(i), qd.num(i), qg.num(i), qp.num(i), qv.num(i)) }
        } ?: emptyList()

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
            cloudPct = wc.num("cloud_cover"), precipMm = wc.num("precipitation"),
            weatherCode = wc.num("weather_code")?.toInt(),
            // Visibility only comes hourly: take the hour we're in.
            visibilityM = hours.lastOrNull { it.time <= nowTime }?.visibilityM,
            swell2M = mc.num("secondary_swell_wave_height"), swell2Dir = mc.num("secondary_swell_wave_direction"),
            swell2Period = mc.num("secondary_swell_wave_period"),
            windWaveDir = mc.num("wind_wave_direction"), windWavePeriod = mc.num("wind_wave_period"),
        )
        return Forecast(fetchedAt, now, hours, quarters)
    }
}
