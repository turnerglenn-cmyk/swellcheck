package io.github.swellcheck

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.roundToInt

/**
 * Moon phase and spring/neap tide strength, worked out on the phone with plain
 * maths. No data, no network.
 *
 * Spring tides (biggest range, strongest streams) come about 1–2 days after new
 * and full moon; neaps about 1–2 days after the quarters. The exact lag varies
 * along the coast, so this is a guide, not a tide table.
 */
object Moon {
    const val SYNODIC_DAYS = 29.530588853
    // A known new moon: 6 Jan 2000, 18:14 UTC.
    private const val REF_NEW_MOON_SEC = 947182440L
    // Typical delay between moon phase and the tide responding (UK/Ireland ~1.5 days).
    const val TIDE_LAG_DAYS = 1.5

    /** Days since the last new moon, 0 until 29.53. */
    fun age(sec: Long): Double {
        val days = (sec - REF_NEW_MOON_SEC) / 86400.0
        return ((days % SYNODIC_DAYS) + SYNODIC_DAYS) % SYNODIC_DAYS
    }

    /** 0 = new, 0.5 = full (fraction through the cycle). */
    fun phase(sec: Long): Double = age(sec) / SYNODIC_DAYS

    /** Fraction of the disc that is lit, 0..1. */
    fun illumination(sec: Long): Double = (1 - cos(2 * PI * phase(sec))) / 2

    fun name(sec: Long): String {
        val a = age(sec)
        return when {
            a < 1.0 || a > SYNODIC_DAYS - 1.0 -> "New moon"
            a < 6.4 -> "Waxing crescent"
            a < 8.4 -> "First quarter"
            a < 13.8 -> "Waxing gibbous"
            a < 15.8 -> "Full moon"
            a < 21.1 -> "Waning gibbous"
            a < 23.1 -> "Last quarter"
            else -> "Waning crescent"
        }
    }

    /**
     * Tide strength 0..1: 1 at springs, 0 at neaps. Based on the moon phase
     * (twice per cycle) shifted by the typical tide lag.
     */
    fun tideStrength(sec: Long): Double {
        val a = age(sec) - TIDE_LAG_DAYS
        return (1 + cos(4 * PI * a / SYNODIC_DAYS)) / 2
    }

    fun tideLabel(strength: Double): String = when {
        strength >= 0.75 -> "Springs"
        strength <= 0.25 -> "Neaps"
        else -> if (strength >= 0.5) "Towards springs" else "Towards neaps"
    }

    /** Whether strength is rising (heading to springs) at this moment. */
    fun buildingToSprings(sec: Long): Boolean = tideStrength(sec + 6 * 3600) > tideStrength(sec)

    /** Time of the next spring-tide peak after [sec]. */
    fun nextSprings(sec: Long): Long {
        val half = SYNODIC_DAYS / 2
        val a = age(sec) - TIDE_LAG_DAYS
        val since = ((a % half) + half) % half         // days since the last springs peak
        return sec + ((half - since) * 86400).toLong()
    }

    /** Time of the next neap-tide low after [sec]. */
    fun nextNeaps(sec: Long): Long {
        val half = SYNODIC_DAYS / 2
        val a = age(sec) - TIDE_LAG_DAYS - half / 2
        val since = ((a % half) + half) % half
        return sec + ((half - since) * 86400).toLong()
    }

    /** Text bar for monospaced readouts, e.g. "▰▰▰▰▰▰▱▱▱▱". */
    fun bar(strength: Double, cells: Int = 10): String {
        val n = (strength * cells).roundToInt().coerceIn(0, cells)
        return "▰".repeat(n) + "▱".repeat(cells - n)
    }
}

/** Range between neighbouring high and low water in the modelled sea level. */
object TideRange {
    /** Largest high-to-low difference (metres) found in [hours], or null without sea data. */
    fun today(hours: List<Hour>, fromSec: Long, toSec: Long): Double? {
        val pts = hours.filter { it.time in fromSec..toSec }.mapNotNull { h -> h.seaLevelM?.let { h.time to it } }
        if (pts.size < 3) return null
        val ext = mutableListOf<Double>()
        for (i in 1 until pts.size - 1) {
            val (_, y0) = pts[i - 1]; val (_, y1) = pts[i]; val (_, y2) = pts[i + 1]
            if ((y1 > y0 && y1 >= y2) || (y1 < y0 && y1 <= y2)) ext += y1
        }
        if (ext.size < 2) return null
        return ext.zipWithNext { a, b -> abs(a - b) }.maxOrNull()
    }
}
