package io.github.swellcheck

import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

/**
 * Where the sun is, worked out on the phone (no data needed). Accurate to a
 * fraction of a degree, i.e. sunrise/sunset within a minute or two.
 */
object Sun {
    private const val RAD = Math.PI / 180.0

    /** Sun's height above the horizon in degrees at [unixSec] for this place. */
    fun elevation(lat: Double, lon: Double, unixSec: Long): Double {
        val n = unixSec / 86400.0 - 10957.5                       // days since J2000.0
        val g = (357.529 + 0.98560028 * n) * RAD                  // mean anomaly
        val q = 280.459 + 0.98564736 * n                          // mean longitude
        val l = (q + 1.915 * sin(g) + 0.020 * sin(2 * g)) * RAD   // ecliptic longitude
        val e = (23.439 - 0.00000036 * n) * RAD                   // obliquity
        val ra = atan2(cos(e) * sin(l), cos(l))                   // right ascension
        val dec = asin(sin(e) * sin(l))                           // declination
        val gmst = (18.697374558 + 24.06570982441908 * n) * 15.0  // sidereal time, degrees
        val ha = (gmst + lon) * RAD - ra                          // hour angle
        val la = lat * RAD
        return asin(sin(la) * sin(dec) + cos(la) * cos(dec) * cos(ha)) / RAD
    }

    /** Compass bearing of the sun (0 = north, 90 = east) at [unixSec] for this place. */
    fun azimuth(lat: Double, lon: Double, unixSec: Long): Double {
        val n = unixSec / 86400.0 - 10957.5
        val g = (357.529 + 0.98560028 * n) * RAD
        val q = 280.459 + 0.98564736 * n
        val l = (q + 1.915 * sin(g) + 0.020 * sin(2 * g)) * RAD
        val e = (23.439 - 0.00000036 * n) * RAD
        val ra = atan2(cos(e) * sin(l), cos(l))
        val dec = asin(sin(e) * sin(l))
        val gmst = (18.697374558 + 24.06570982441908 * n) * 15.0
        val ha = (gmst + lon) * RAD - ra
        val la = lat * RAD
        val az = atan2(sin(ha), cos(ha) * sin(la) - kotlin.math.tan(dec) * cos(la)) / RAD + 180.0
        return ((az % 360.0) + 360.0) % 360.0
    }

    /**
     * 0 = night, 1 = full daylight, smooth through dawn and dusk
     * (from about -8° below the horizon to 8° above, roughly an hour and a half in the UK).
     */
    fun light(lat: Double, lon: Double, unixSec: Long): Float {
        val t = ((elevation(lat, lon, unixSec) + 8.0) / 16.0).coerceIn(0.0, 1.0)
        return (t * t * (3 - 2 * t)).toFloat()                    // smoothstep
    }
}
