package io.github.swellcheck

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.roundToInt

/** Speeds are stored in knots internally (the ratings use knots). */
enum class WindUnit(val label: String, val perKnot: Double) {
    MPH("mph", 1.150779),
    KN("kn", 1.0),
    KMH("km/h", 1.852),
}

data class Units(val wind: WindUnit, val feet: Boolean) {
    fun speed(kn: Double): Double = kn * wind.perKnot
    fun len(m: Double): Double = if (feet) m * 3.28084 else m
    val lenLabel: String get() = if (feet) "ft" else "m"
}

object Fmt {
    private val points = arrayOf(
        "N", "NNE", "NE", "ENE", "E", "ESE", "SE", "SSE",
        "S", "SSW", "SW", "WSW", "W", "WNW", "NW", "NNW",
    )
    private val hm = DateTimeFormatter.ofPattern("HH:mm", Locale.UK)
    private val dayHm = DateTimeFormatter.ofPattern("EEE HH:mm", Locale.UK)
    private val day = DateTimeFormatter.ofPattern("EEEE d MMM", Locale.UK)

    fun compass(deg: Double?): String {
        if (deg == null) return "–"
        val norm = ((deg % 360.0) + 360.0) % 360.0
        return points[((norm / 22.5) + 0.5).toInt() % 16]
    }

    fun compassName(index: Int): String = points[index % 16]

    fun height(m: Double?, feet: Boolean): String = when {
        m == null -> "–"
        feet -> String.format(Locale.UK, "%.1f ft", m * 3.28084)
        else -> String.format(Locale.UK, "%.1f m", m)
    }

    fun knots(v: Double?): String = if (v == null) "–" else "${v.roundToInt()} kn"
    fun seconds(v: Double?): String = if (v == null) "–" else "${v.roundToInt()} s"
    fun temp(v: Double?): String = if (v == null) "–" else "${v.roundToInt()}°C"

    private fun zoned(sec: Long) = Instant.ofEpochSecond(sec).atZone(ZoneId.systemDefault())

    fun time(sec: Long): String = hm.format(zoned(sec))
    fun dayName(sec: Long): String = day.format(zoned(sec))
    fun sameDay(a: Long, b: Long): Boolean = zoned(a).toLocalDate() == zoned(b).toLocalDate()

    /** "14:20" today, "Sat 14:20" otherwise. */
    fun dayTime(sec: Long, nowSec: Long): String =
        if (sameDay(sec, nowSec)) time(sec) else dayHm.format(zoned(sec))

    fun speed(kn: Double?, u: Units): String =
        if (kn == null) "–" else "${u.speed(kn).roundToInt()} ${u.wind.label}"

    fun wind(h: Hour, u: Units): String =
        "${speed(h.windKn, u)} ${compass(h.windDir)}" +
            (h.gustKn?.let { ", gusts ${speed(it, u)}" } ?: "")

    fun hourOfDay(sec: Long): Int = zoned(sec).hour
    fun dayShort(sec: Long): String = DateTimeFormatter.ofPattern("EEE d", Locale.UK).format(zoned(sec))

    fun swell(h: Hour, feet: Boolean): String {
        val metres = h.swellM ?: h.waveM ?: return "No sea data here"
        val period = h.swellPeriod ?: h.wavePeriod
        val dir = h.swellDir ?: h.waveDir
        return "${height(metres, feet)} @ ${seconds(period)} from ${compass(dir)}"
    }

    fun coords(lat: Double, lon: Double): String {
        val ns = if (lat >= 0) "N" else "S"
        val ew = if (lon >= 0) "E" else "W"
        return String.format(Locale.UK, "%.2f°%s %.2f°%s", kotlin.math.abs(lat), ns, kotlin.math.abs(lon), ew)
    }
}
