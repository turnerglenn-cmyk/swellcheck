package io.github.swellcheck

import kotlin.math.abs
import kotlin.math.max

enum class Surf(val label: String, val color: Int) {
    FLAT("Flat", 0xFF5C6B7A.toInt()),
    POOR("Poor", 0xFFFF6B6B.toInt()),
    FAIR("Fair", 0xFFFFC857.toInt()),
    GOOD("Good", 0xFF3DDC97.toInt()),
    EPIC("Epic", 0xFF4FD1E8.toInt()),
}

enum class Paddle(val label: String, val color: Int) {
    GO("Go", 0xFF3DDC97.toInt()),
    MARGINAL("Marginal", 0xFFFFC857.toInt()),
    NO_GO("No go", 0xFFFF6B6B.toInt()),
    UNKNOWN("No data", 0xFF5C6B7A.toInt()),
}

data class SurfResult(val rating: Surf, val notes: List<String>)
data class PaddleResult(val rating: Paddle, val reasons: List<String>)
/** Your paddling limits (knots, metres). [maxOffshore]: the most offshore wind you'll paddle in, blowing you away from the beach. */
data class Limits(val maxWind: Double, val maxGust: Double, val maxWave: Double, val maxOffshore: Double = 10.0)

enum class WindKind { OFFSHORE, CROSS, ONSHORE }

/**
 * Simple, transparent rules a surfer or sea kayaker would recognise.
 * Not a safety system: it only summarises a model forecast.
 */
object Rating {
    /** Your paddling wind limits, set when a screen, widget or wallpaper loads the settings. Too windy to paddle = too windy to surf. */
    @Volatile var windLimits: Limits? = null

    /** Smallest angle between two bearings, 0..180. */
    fun angDiff(a: Double, b: Double): Double {
        val d = abs(a - b) % 360.0
        return if (d > 180.0) 360.0 - d else d
    }

    /** facing = bearing you look along from the beach out to sea. */
    fun windKind(windFrom: Double, facing: Double): WindKind {
        val fromLand = (facing + 180.0) % 360.0
        val d = angDiff(windFrom, fromLand)
        return when {
            d <= 45.0 -> WindKind.OFFSHORE
            d <= 110.0 -> WindKind.CROSS
            else -> WindKind.ONSHORE
        }
    }

    fun surf(h: Hour, facing: Int?): SurfResult {
        val height = h.swellM ?: h.waveM ?: return SurfResult(Surf.FLAT, listOf("No sea data at this spot"))
        val period = h.swellPeriod ?: h.wavePeriod ?: 0.0
        val dir = h.swellDir ?: h.waveDir
        val notes = mutableListOf<String>()

        if (height < 0.3) return SurfResult(Surf.FLAT, listOf("Less than 0.3 m of swell"))
        windLimits?.let { lim ->
            val w = h.windKn; val g = h.gustKn
            if ((w != null && w > lim.maxWind) || (g != null && g > lim.maxGust))
                return SurfResult(Surf.POOR, listOf("Too windy: over your wind limits"))
        }
        // Under about 5 s it's wind chop: it closes out and has no push. Nobody surfs it.
        if (period in 0.1..4.99) return SurfResult(Surf.POOR, listOf("Under 5 s period: wind chop, not surfable"))

        var s = when {
            height < 0.6 -> 1.0
            height < 1.0 -> 2.0
            height < 2.5 -> 3.0
            height < 3.5 -> 2.5
            else -> 1.5
        }
        if (height >= 3.5) notes += "Big and heavy: experienced surfers only"

        s += when {
            period < 6.0 -> -1.0
            period < 9.0 -> 0.0
            period < 12.0 -> 0.5
            else -> 1.0
        }
        if (period < 6.0) notes += "Short-period wind swell, likely messy"
        if (period >= 12.0) notes += "Long-period groundswell"

        if (facing != null && dir != null) {
            val d = angDiff(dir, facing.toDouble())
            when {
                d <= 30.0 -> Unit
                d <= 60.0 -> s -= 0.5
                d <= 90.0 -> { s -= 1.5; notes += "Swell angle is off for this beach" }
                else -> { s = 0.0; notes += "Swell is heading away from this beach" }
            }
        }

        val ws = h.windKn
        val wd = h.windDir
        if (ws != null) {
            if (facing != null && wd != null) {
                when (windKind(wd, facing.toDouble())) {
                    WindKind.OFFSHORE ->
                        if (ws <= 20.0) { s += 1.0; notes += "Offshore wind: clean faces" }
                        else { s -= 0.5; notes += "Strong offshore: hard work paddling out" }
                    WindKind.CROSS ->
                        if (ws > 12.0) { s -= 1.0; notes += "Cross-shore wind" }
                    WindKind.ONSHORE ->
                        if (ws >= 10.0) { s -= 2.0; notes += "Onshore wind: choppy" }
                        else if (ws >= 5.0) { s -= 1.0; notes += "Light onshore breeze" }
                }
            } else {
                s += when {
                    ws < 5.0 -> 0.5
                    ws > 15.0 -> -1.5
                    ws > 8.0 -> -0.5
                    else -> 0.0
                }
            }
            if (ws < 5.0) notes += "Light winds"
        }
        if (facing == null) notes += "Set the beach direction in Settings for a better rating"

        var rating = when {
            s < 1.5 -> Surf.POOR
            s < 3.0 -> Surf.FAIR
            s < 4.0 -> Surf.GOOD
            else -> Surf.EPIC
        }
        // Floor: 2 ft (0.6 m) or more at 5 s or more is always worth a look, whatever the wind
        // and direction do to the score.
        if (rating == Surf.POOR && height >= 0.6 && period >= 5.0) {
            rating = Surf.FAIR
            notes += "2 ft+ at 5 s+: rideable"
        }
        return SurfResult(rating, notes)
    }

    fun paddle(h: Hour, facing: Int?, lim: Limits): PaddleResult {
        val ws = h.windKn ?: return PaddleResult(Paddle.UNKNOWN, listOf("No wind data"))
        val reasons = mutableListOf<String>()
        var level = 0 // 0 go, 1 marginal, 2 no go

        fun check(v: Double?, limit: Double, what: String) {
            if (v == null) return
            if (v > limit) { level = 2; reasons += "$what over your limit" }
            else if (v > 0.75 * limit) { level = max(level, 1); reasons += "$what near your limit" }
        }
        check(ws, lim.maxWind, "Wind")
        check(h.gustKn, lim.maxGust, "Gusts")
        check(h.waveM, lim.maxWave, "Waves")

        val wd = h.windDir
        if (facing != null && wd != null && windKind(wd, facing.toDouble()) == WindKind.OFFSHORE) {
            if (ws > lim.maxOffshore) { level = 2; reasons += "Offshore wind over your limit: risk of being blown out to sea" }
            else if (ws > 0.6 * lim.maxOffshore) { level = max(level, 1); reasons += "Offshore breeze near your limit: stay close in" }
        }
        if (h.waveM == null) reasons += "No wave data at this spot"

        val rating = when (level) {
            0 -> Paddle.GO
            1 -> Paddle.MARGINAL
            else -> Paddle.NO_GO
        }
        return PaddleResult(rating, reasons)
    }
}

data class TideEvent(val time: Long, val high: Boolean)

/** Finds approximate high/low water from the hourly modelled sea level. */
object Tide {
    fun events(hours: List<Hour>): List<TideEvent> {
        val out = mutableListOf<TideEvent>()
        for (i in 1 until hours.size - 1) {
            val y0 = hours[i - 1].seaLevelM ?: continue
            val y1 = hours[i].seaLevelM ?: continue
            val y2 = hours[i + 1].seaLevelM ?: continue
            val high = y1 > y0 && y1 >= y2
            val low = y1 < y0 && y1 <= y2
            if (!high && !low) continue
            // Fit a parabola through the three points to estimate the turning time.
            val denom = y0 - 2 * y1 + y2
            val offsetH = if (denom != 0.0) (0.5 * (y0 - y2) / denom).coerceIn(-0.5, 0.5) else 0.0
            val step = hours[i + 1].time - hours[i].time
            out += TideEvent(hours[i].time + (offsetH * step).toLong(), high)
        }
        return out
    }

    /** e.g. "Rising, high about 14:20" */
    fun summary(hours: List<Hour>, nowSec: Long = System.currentTimeMillis() / 1000): String? {
        val next = events(hours).firstOrNull { it.time > nowSec } ?: return null
        val trend = if (next.high) "Rising" else "Falling"
        val kind = if (next.high) "high" else "low"
        return "$trend, $kind about ${Fmt.dayTime(next.time, nowSec)}"
    }
}
