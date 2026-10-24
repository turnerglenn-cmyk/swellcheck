package io.github.swellcheck

import java.time.Instant
import java.time.ZoneId

/**
 * Turns the hourly forecast into plain-English planning: the paddle call for
 * each hour of the next few days, the best window in daylight, the main reason
 * behind today's call, and sunrise/sunset. All worked out on the phone.
 */
object Plan {

    data class Daylight(val rise: Long?, val set: Long?)

    data class Day(
        val start: Long,                // local midnight, unix seconds
        val label: String,              // "Today", "Tomorrow", "Fri 23"
        val ratings: List<Paddle?>,     // one per local hour 0..23, null = no data
        val light: List<Float>,         // 0 night .. 1 day, per hour
        val best: Pair<Long, Long>?,    // longest "Go" run in daylight
        val verdict: String,            // "Go 07–11", "Marginal at best", "No go"
        val verdictColor: Int,
    )

    private val zone: ZoneId get() = ZoneId.systemDefault()

    fun dayStart(sec: Long): Long =
        Instant.ofEpochSecond(sec).atZone(zone).toLocalDate().atStartOfDay(zone).toEpochSecond()

    /** Sunrise and sunset on the local day containing [sec]; null when the sun doesn't rise or set. */
    fun daylight(lat: Double, lon: Double, sec: Long): Daylight {
        val start = dayStart(sec)
        var rise: Long? = null
        var set: Long? = null
        var prev = Sun.elevation(lat, lon, start)
        var t = start
        while (t < start + 86400) {
            t += 120
            val e = Sun.elevation(lat, lon, t)
            if (prev < -0.833 && e >= -0.833 && rise == null) rise = t
            if (prev >= -0.833 && e < -0.833) set = t
            prev = e
        }
        return Daylight(rise, set)
    }

    /** 0 = night, 1 = day. Without a location, assume 07:00–19:00. */
    fun light(lat: Double?, lon: Double?, sec: Long): Float =
        if (lat != null && lon != null) Sun.light(lat, lon, sec)
        else if (Fmt.hourOfDay(sec) in 7..18) 1f else 0f

    fun days(
        hours: List<Hour>, facing: Int?, limits: Limits, lat: Double?, lon: Double?,
        nowSec: Long, count: Int = 3,
    ): List<Day> {
        val byTime = hours.associateBy { it.time }
        val today = dayStart(nowSec)
        return (0 until count).map { k ->
            // Step to the next local midnight (handles the clocks changing).
            val start = dayStart(today + k * 86400L + 3 * 3600L)
            val label = when (k) {
                0 -> "Today"
                1 -> "Tomorrow"
                else -> Fmt.dayShort(start)
            }
            val times = (0 until 24).map { start + it * 3600L }
            val ratings = times.map { t -> byTime[t]?.let { Rating.paddle(it, facing, limits).rating } }
            val light = times.map { light(lat, lon, it + 1800) }

            // Longest run of "Go" in daylight that hasn't already passed.
            var best: Pair<Long, Long>? = null
            var runStart = -1
            for (i in 0..24) {
                val ok = i < 24 && ratings[i] == Paddle.GO && light[i] > 0.3f && times[i] + 3600 > nowSec
                if (ok && runStart < 0) runStart = i
                if (!ok && runStart >= 0) {
                    val len = i - runStart
                    if (best == null || len * 3600L > best.second - best.first) best = times[runStart] to times[i - 1] + 3600L
                    runStart = -1
                }
            }
            val lit = ratings.filterIndexed { i, _ -> light[i] > 0.3f }.filterNotNull()
            val (verdict, color) = when {
                best != null -> "Go ${hh(best.first)}–${hh(best.second)}" to Paddle.GO.color
                lit.isEmpty() -> "No data" to Paddle.UNKNOWN.color
                lit.any { it == Paddle.MARGINAL } -> "Marginal at best" to Paddle.MARGINAL.color
                else -> "No go" to Paddle.NO_GO.color
            }
            Day(start, label, ratings, light, best, verdict, color)
        }
    }

    private fun hh(sec: Long) = Fmt.time(sec).substringBefore(":")

    /**
     * One or two sentences on what decides today's paddle call, looking at the
     * daylight hours still to come (or tomorrow's, late in the evening).
     */
    fun reason(hours: List<Hour>, facing: Int?, limits: Limits, units: Units, lat: Double?, lon: Double?, nowSec: Long): String {
        var window = hours.filter { it.time + 3600 > nowSec && Fmt.sameDay(it.time, nowSec) && light(lat, lon, it.time + 1800) > 0.3f }
        var when_ = "today"
        if (window.isEmpty()) {
            window = hours.filter { it.time > nowSec && !Fmt.sameDay(it.time, nowSec) && light(lat, lon, it.time + 1800) > 0.3f }.take(14)
            when_ = "tomorrow"
        }
        if (window.isEmpty()) return "No forecast for the daylight hours yet."

        fun at(h: Hour) = Fmt.time(h.time)
        val gust = window.filter { it.gustKn != null }.maxByOrNull { it.gustKn!! }
        val wind = window.filter { it.windKn != null }.maxByOrNull { it.windKn!! }
        val wave = window.filter { it.waveM != null }.maxByOrNull { it.waveM!! }
        fun offshoreOver(kn: Double) = if (facing == null) null else window.firstOrNull {
            val wd = it.windDir; val ws = it.windKn
            wd != null && ws != null && ws > kn && Rating.windKind(wd, facing.toDouble()) == WindKind.OFFSHORE
        }
        val offshoreStrong = offshoreOver(10.0)   // same thresholds as Rating.paddle
        val offshoreBreeze = offshoreOver(6.0)
        val parts = mutableListOf<String>()
        fun over(v: Double, lim: Double) = v > lim
        fun near(v: Double, lim: Double) = v > 0.75 * lim
        when {
            gust != null && over(gust.gustKn!!, limits.maxGust) ->
                parts += "Gusts reach ${Fmt.speed(gust.gustKn, units)} around ${at(gust)}, over your ${Fmt.speed(limits.maxGust, units)} limit."
            wind != null && over(wind.windKn!!, limits.maxWind) ->
                parts += "Wind reaches ${Fmt.speed(wind.windKn, units)} around ${at(wind)}, over your ${Fmt.speed(limits.maxWind, units)} limit."
            wave != null && over(wave.waveM!!, limits.maxWave) ->
                parts += "Waves build to ${Fmt.height(wave.waveM, units.feet)} around ${at(wave)}, over your limit."
            offshoreStrong != null ->
                parts += "Offshore wind of ${Fmt.speed(offshoreStrong.windKn, units)} from ${at(offshoreStrong)}: risk of being blown out to sea."
            gust != null && near(gust.gustKn!!, limits.maxGust) ->
                parts += "Gusts up to ${Fmt.speed(gust.gustKn, units)} around ${at(gust)}, close to your ${Fmt.speed(limits.maxGust, units)} limit."
            wind != null && near(wind.windKn!!, limits.maxWind) ->
                parts += "Wind up to ${Fmt.speed(wind.windKn, units)} around ${at(wind)}, close to your limit."
            wave != null && near(wave.waveM!!, limits.maxWave) ->
                parts += "Waves up to ${Fmt.height(wave.waveM, units.feet)} around ${at(wave)}, close to your limit."
            else -> parts += "Within your limits all $when_" +
                (wind?.let { ": wind no more than ${Fmt.speed(it.windKn, units)}." } ?: ".")
        }
        if (offshoreStrong == null && offshoreBreeze != null) parts += "Offshore breeze from ${at(offshoreBreeze)}: stay close in."
        return parts.joinToString(" ")
    }
}
