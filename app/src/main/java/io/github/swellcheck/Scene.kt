package io.github.swellcheck

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.view.View
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sign
import kotlin.math.sin
import kotlin.math.tan

/**
 * Everything the live scene shows. Each value drives something you can see;
 * see [SceneArt] for what means what.
 */
data class SceneInput(
    val facing: Double,          // compass bearing the camera looks along (out to sea)
    val windKn: Double,
    val gustKn: Double,
    val windFrom: Double?,
    val waveM: Double,           // swell (or total wave) height
    val periodS: Double,
    val swellFrom: Double?,
    val chopM: Double,           // wind-wave height
    val chopPeriod: Double? = null,      // wind-wave period, s
    val chopFrom: Double? = null,        // which way the wind waves come from
    val swell2M: Double? = null,         // a second swell train
    val swell2Period: Double? = null,
    val swell2From: Double? = null,
    val tide: Double,            // -1 low water .. +1 high water
    val tideRising: Boolean,
    val tideSign: String,        // e.g. "HIGH 14:20"
    val springs: Double,         // 0 neaps .. 1 springs
    val seaTempC: Double?,
    val cloud: Double,           // 0..1
    val rainMm: Double,
    val code: Int,               // WMO weather code
    val visibilityM: Double,
    val sunElev: Double,
    val sunAz: Double,
    val moonPhase: Double,
    val moonElev: Double = -90.0,
    val moonAz: Double = 0.0,
    val surf: Surf,
    val paddle: Paddle,
    val feet: Boolean,
    val title: String,           // top bar, e.g. "FILEY · THU 22 OCT · 10:20"
    val subtitles: List<String>, // bottom bar, shown in turn
    val launches: List<Long> = emptyList(),   // lifeboat launch times today (epoch seconds), on really bad days
    val heliTimes: List<Long> = emptyList(),  // rescue helicopter visits today (rare)
    val heliCallOut: Boolean = false,         // a real call-out rather than training
) {
    companion object {
        fun from(f: Forecast, now: Hour, place: String, facing: Int?, limits: Limits, units: Units,
                 lat: Double?, lon: Double?, nowSec: Long): SceneInput {
            val i = f.hours.indexOfLast { it.time <= nowSec }
            val lvl = now.seaLevelM ?: f.hours.getOrNull(i)?.seaLevelM     // blended to this minute
            val nextLvl = f.hours.getOrNull(i + 1)?.seaLevelM
            val near = f.hours.filter { abs(it.time - nowSec) < 13 * 3600 }.mapNotNull { it.seaLevelM }
            val tide = if (lvl != null && near.size > 2 && near.max() - near.min() > 0.05)
                (lvl - near.min()) / (near.max() - near.min()) * 2 - 1 else 0.0
            val next = Tide.events(f.hours).firstOrNull { it.time > nowSec }
            val sign = next?.let { (if (it.high) "HIGH " else "LOW ") + Fmt.time(it.time) } ?: "TIDE"
            val wave = now.swellM ?: now.waveM ?: 0.0
            val period = now.swellPeriod ?: now.wavePeriod ?: 8.0
            val surf = Rating.surf(now, facing).rating
            val paddle = Rating.paddle(now, facing, limits).rating
            val cloud = (now.cloudPct ?: 30.0) / 100.0
            val rain = now.precipMm ?: 0.0
            val code = now.weatherCode ?: 0
            val springs = Moon.tideStrength(nowSec)
            val subs = mutableListOf<String>()
            subs += "Wind ${Fmt.speed(now.windKn, units)}" + (now.windDir?.let { " from the ${Fmt.compass(it)}" } ?: "") +
                (now.gustKn?.let { ", gusting ${Fmt.speed(it, units)}" } ?: "")
            if (f.hasSea) subs += "Swell ${Fmt.height(wave, units.feet)} every ${Fmt.seconds(period)}" +
                ((now.swellDir ?: now.waveDir)?.let { " from the ${Fmt.compass(it)}" } ?: "") +
                // a second swell train, when there's a real one
                (now.swell2M?.takeIf { it >= 0.3 && (now.swell2Period ?: 0.0) >= 5 }?.let { h2 ->
                    " + ${Fmt.height(h2, units.feet)} at ${Fmt.seconds(now.swell2Period)}" + (now.swell2Dir?.let { " from the ${Fmt.compass(it)}" } ?: "")
                } ?: "")
            if (f.hasSea && wave > 0.2) {
                val b = Surfzone.breaker(max(wave, (now.windWaveM ?: 0.0) * 0.5), period, tide)
                val far = (kotlin.math.round(b.distanceM / 10.0) * 10).toInt()
                subs += if (far < 10) "Waves ${b.type.words}, breaking right at the water's edge at this tide"
                    else "Waves ${b.type.words}, breaking about $far m out at this tide"
            }
            Tide.summary(f.hours, nowSec)?.let { subs += "Tide " + it.replaceFirstChar { c -> c.lowercase() } + " · " + Moon.tideLabel(springs).lowercase() }
            subs += weatherWords(code, cloud, rain) + (now.seaTempC?.let { " · sea ${Fmt.temp(it)}" } ?: "") +
                " · paddle: ${paddle.label.lowercase()}"
            val day = java.time.format.DateTimeFormatter.ofPattern("EEE d MMM", java.util.Locale.UK)
                .format(java.time.Instant.ofEpochSecond(nowSec).atZone(java.time.ZoneId.systemDefault()))
            return SceneInput(
                facing = (facing ?: 90).toDouble(),
                windKn = now.windKn ?: 0.0, gustKn = now.gustKn ?: (now.windKn ?: 0.0), windFrom = now.windDir,
                waveM = if (f.hasSea) wave else 0.0, periodS = period, swellFrom = now.swellDir ?: now.waveDir,
                chopM = now.windWaveM ?: 0.0,
                chopPeriod = now.windWavePeriod?.takeIf { it > 0.5 }, chopFrom = now.windWaveDir,
                swell2M = now.swell2M, swell2Period = now.swell2Period, swell2From = now.swell2Dir,
                tide = tide, tideRising = lvl != null && nextLvl != null && nextLvl > lvl, tideSign = sign,
                springs = springs, seaTempC = now.seaTempC,
                cloud = cloud.coerceIn(0.0, 1.0), rainMm = rain, code = code, visibilityM = now.visibilityM ?: 30000.0,
                sunElev = if (lat != null && lon != null) Sun.elevation(lat, lon, nowSec) else 20.0,
                sunAz = if (lat != null && lon != null) Sun.azimuth(lat, lon, nowSec) else 180.0,
                moonPhase = Moon.phase(nowSec),
                // The moon crosses the sky about phase × 24h50m behind the sun; close enough to place it.
                moonElev = if (lat != null && lon != null) Sun.elevation(lat, lon, nowSec - (Moon.phase(nowSec) * 89400).toLong()) else -90.0,
                moonAz = if (lat != null && lon != null) Sun.azimuth(lat, lon, nowSec - (Moon.phase(nowSec) * 89400).toLong()) else 0.0,
                surf = surf, paddle = paddle, feet = units.feet,
                title = "${place.substringBefore(",").uppercase()} · ${day.uppercase()} · ${Fmt.time(nowSec)}",
                subtitles = subs,
                launches = launches(f, nowSec, facing, limits),
                heliTimes = heliTimes(nowSec, paddle == Paddle.NO_GO),
                heliCallOut = paddle == Paddle.NO_GO,
            )
        }

        /**
         * The conditions to show, matching the cards: the latest 15-minute reading while it's
         * fresh (under half an hour old), and the forecast blended to this minute after that.
         */
        fun moment(f: Forecast, sec: Long): Hour = if (sec - f.now.time in 0..1799) f.now else f.at(sec)

        /** Build from the saved forecast and settings; null until the app has a forecast. Used by the wallpaper. */
        fun fromCache(ctx: Context): SceneInput? {
            val f = Repo.cached(ctx) ?: return null
            val p = Prefs(ctx)
            Rating.windLimits = p.limits
            val nowSec = System.currentTimeMillis() / 1000
            val now = moment(f, nowSec)
            val place = if (!p.useGps && p.placeName != null) p.placeName!! else "Near you"
            return from(f, now, place, p.facingOrNull, p.limits, p.units, p.lat, p.lon, nowSec)
        }

        /** Weather bad enough for a lifeboat call-out: a no-go paddle call in a strong wind, big sea or thunder. */
        fun lifeboatWeather(h: Hour, facing: Int?, limits: Limits): Boolean {
            val w = h.windKn ?: 0.0; val g = h.gustKn ?: 0.0; val wave = h.waveM ?: 0.0
            return Rating.paddle(h, facing, limits).rating == Paddle.NO_GO &&
                (w >= 25 || g >= 38 || wave >= 2.5 || (h.weatherCode ?: 0) >= 95)
        }

        /**
         * Today's lifeboat launches, worked out from the hourly forecast so they follow the weather
         * through the day: the first goes out within an hour or so of it turning rough, and more
         * follow (up to three a day) the longer and worse it stays. Only between 06:00 and 22:00.
         * The times are fixed for the day so the scene, the wallpaper and the widget agree.
         */
        fun launches(f: Forecast, nowSec: Long, facing: Int?, limits: Limits): List<Long> {
            val start = Plan.dayStart(nowSec)
            val bad = f.hours.filter { it.time >= start + 6 * 3600L && it.time < start + 22 * 3600L && lifeboatWeather(it, facing, limits) }
            if (bad.isEmpty()) return emptyList()
            val seed = start / 86400
            fun rnd(i: Int): Double { val x = sin((seed * 31 + i) * 12.9898) * 43758.5453; return x - floor(x) }
            val onset = bad.first().time
            val end = bad.last().time + 3600L
            val worstWind = bad.maxOf { it.windKn ?: 0.0 }; val worstGust = bad.maxOf { it.gustKn ?: 0.0 }
            val worstWave = bad.maxOf { it.waveM ?: 0.0 }; val thunder = bad.any { (it.weatherCode ?: 0) >= 95 }
            val severity = 1 + (if (worstWind >= 33 || worstGust >= 45 || worstWave >= 3.0) 1 else 0) +
                (if (thunder || worstGust >= 55 || worstWind >= 40) 1 else 0)
            val duration = kotlin.math.ceil(bad.size / 5.0).toInt()     // another call-out about every 5 rough hours
            val n = max(severity, duration).coerceIn(1, 3)
            val out = mutableListOf(onset + (30 + rnd(0) * 50).toLong() * 60)
            if (n > 1) {
                val from = out[0] + (2.5 * 3600).toLong()
                val to = end - 3600L
                for (k in 1 until n) {
                    if (to <= from) break
                    val tk = from + ((to - from) * ((k - 1 + 0.3 + 0.4 * rnd(k)) / (n - 1))).toLong()
                    // only while it's still rough at that hour
                    if (bad.any { tk >= it.time && tk < it.time + 3600 }) out += tk
                }
            }
            return out
        }

        private fun rel(a: Double, b: Double): Double { var d = (a - b) % 360.0; if (d > 180) d -= 360.0; if (d < -180) d += 360.0; return d }

        /**
         * The air-sea rescue helicopter is a rare sight: on about one day in five it comes by on
         * training, more often (and sometimes twice) on rough days when it's more likely a call-out.
         * Visits fall between 09:00 and 19:00 and are fixed for each day.
         */
        fun heliTimes(nowSec: Long, rough: Boolean): List<Long> {
            val start = Plan.dayStart(nowSec)
            val seed = (start / 86400).toInt()
            fun rnd(i: Int): Double { val x = sin((seed * 53 + i) * 7.123) * 43758.5453; return x - floor(x) }
            val out = mutableListOf<Long>()
            if (rnd(0) < (if (rough) 0.55 else 0.2)) out += start + 9 * 3600L + (rnd(1) * 5 * 3600).toLong()
            if (rough && rnd(2) < 0.35) out += start + 14 * 3600L + (rnd(3) * 5 * 3600).toLong()
            return out.sorted()
        }

        fun weatherWords(code: Int, cloud: Double, rain: Double): String = when {
            code >= 95 -> "Thunderstorms"
            code in 71..77 || code in 85..86 -> "Snow"
            code in 80..82 || rain >= 2.5 -> "Heavy showers"
            code in 61..67 || rain >= 0.5 -> "Rain"
            code in 51..57 || rain > 0.0 -> "Drizzle"
            code in 45..48 -> "Fog"
            cloud > 0.85 -> "Overcast"
            cloud > 0.5 -> "Mostly cloudy"
            cloud > 0.2 -> "Some cloud"
            else -> "Clear skies"
        }
    }
}

/**
 * How swell turns into breaking waves on a beach, from standard coastal-engineering rules:
 *  - breaker height Hb = 0.56 H0 (H0/L0)^-1/5 (waves grow as they feel the bottom);
 *  - they break in water about Hb/0.78 deep (the breaker depth index);
 *  - the Iribarren number xi = slope / sqrt(H0/L0) sets the type: < 0.5 spilling,
 *    0.5-3.3 plunging, > 3.3 surging;
 *  - inside the surf zone the height is limited by the depth, so waves shrink as they run in.
 * The beach is flatter at low tide (a wide surf zone) and steeper higher up (a shore break).
 */
object Surfzone {
    enum class Type(val words: String) { SPILLING("spilling"), PLUNGING("plunging"), SURGING("surging") }

    data class Breaker(val hb: Double, val depth: Double, val xi: Double, val type: Type, val distanceM: Double)

    /** Share of the swell that reaches a beach, by how far off its heading the swell comes from. */
    fun exposure(offDeg: Double): Double {
        val a = abs(offDeg)
        return when {
            a <= 30 -> 1.0
            a <= 60 -> 1.0 - 0.3 * (a - 30) / 30
            a <= 100 -> 0.7 - 0.55 * (a - 60) / 40
            else -> 0.15 - 0.1 * ((a - 100) / 80).coerceIn(0.0, 1.0)
        }
    }

    /** Beach slope (rise over run): about 1:65 at low water to 1:20 at high water. */
    fun slope(tide: Double) = 0.015 + 0.035 * ((tide + 1) / 2).coerceIn(0.0, 1.0)

    /**
     * [windOn]: onshore (+) or offshore (-) wind, 0..1 by strength. Offshore wind holds the faces
     * up so waves break later, in shallower water, and plunge; onshore wind knocks them over early,
     * further out, as crumbly spilling breakers (Douglass 1990; King & Baker 1996).
     */
    fun breaker(h0: Double, periodS: Double, tide: Double, windOn: Double = 0.0): Breaker {
        val h = max(h0, 0.05)
        val l0 = 9.81 * periodS * periodS / (2 * PI)
        val hb = (0.56 * h * (h / l0).pow(-0.2)).coerceAtMost(2.2 * h)
        val gamma = 0.78 * (1 - 0.14 * windOn.coerceIn(0.0, 1.0) + 0.12 * (-windOn).coerceIn(0.0, 1.0))
        val db = hb / gamma
        val m = slope(tide)
        val xi = m / kotlin.math.sqrt(h / l0) * (1 + 0.4 * (-windOn).coerceIn(0.0, 1.0) - 0.35 * windOn.coerceIn(0.0, 1.0))
        val type = when { xi < 0.5 -> Type.SPILLING; xi < 3.3 -> Type.PLUNGING; else -> Type.SURGING }
        return Breaker(hb, db, xi, type, db / m)
    }
}

/**
 * The live scene: a camera on the beach looking out to sea along the way the
 * beach faces. Nothing is decoration; each thing on screen reads off the forecast.
 *
 *  - Sun and moon: where they really are in the sky relative to the beach; the moon's
 *    shape is the real phase (full and new moons bring spring tides).
 *  - Sky colour: time of day, cloud cover and rain. Clouds: how many = cloud cover,
 *    how dark = rain, which way and how fast they drift = the wind. They pass in front of the sun.
 *  - Rain, snow, fog and lightning: the weather code, rain rate and visibility.
 *    Fog hazes out the horizon, the buoy a mile out and the far waves.
 *  - Wave lines: the swell. Their angle is the swell direction, the gap between
 *    them and how fast they roll in is the period, their height is the wave height.
 *    The striped post in the water is a gauge: waves climb it to the real height.
 *    White horses out to sea = wind chop.
 *  - Water's edge: the tide. The weed line is the high-water mark; the sign says the next turn.
 *  - Windsock: points where the wind is going, stands out with strength, flicks with gusts.
 *  - Beach flag: your paddle call (green go, yellow marginal, red no go).
 *  - Surfer: the surf rating. Riding = good or epic, waiting on the board = fair,
 *    board stuck in the sand = poor or flat.
 *  - Kayak: paddle call again. Out at sea = go, close in = marginal, upside down on the sand = no go.
 *  - Buoy: bobs with the real swell; its tag is the sea temperature.
 *  - Gulls: soar in light wind, fight it in strong wind, sit it out on the sand in a gale or heavy rain.
 */
object SceneArt {
    private fun p(color: Int) = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color }
    private fun stroke(color: Int, w: Float) = p(color).apply {
        style = Paint.Style.STROKE; strokeWidth = w; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND
    }
    private fun mix(a: Int, b: Int, f: Double) = Draw.mix(a, b, f.toFloat().coerceIn(0f, 1f))
    private fun alpha(c: Int, a: Double) = (c and 0x00FFFFFF) or (((a.coerceIn(0.0, 1.0)) * 255).toInt() shl 24)
    private fun hash(i: Int): Double { val x = sin(i * 12.9898 + 78.233) * 43758.5453; return x - floor(x) }
    private fun rad(deg: Double) = deg * PI / 180.0
    /** Signed angle from b to a, -180..180. */
    private fun rel(a: Double, b: Double): Double { var d = (a - b) % 360.0; if (d > 180) d -= 360.0; if (d < -180) d += 360.0; return d }

    // ---- layering: things on the water are drawn in depth order with the waves ----------

    /** For previews only: every surfed wave ends in a capsize and swim. */
    internal var testSwim = false
    /** For previews only: told where each kayak is, every frame. */
    internal var poseLog: ((Int, Double, Double, String) -> Unit)? = null
    internal var runLog: ((String) -> Unit)? = null
    private val planCache = HashMap<String, Any>()

    /** Something on the water waiting to be drawn, and how far out it is. */
    private class Layered(val x: Double, val z: Double, val draw: () -> Unit)
    private var layerQueue: MutableList<Layered>? = null

    /** Draw now, or (while the sea is being drawn) queue it to go in between the waves at its distance. */
    private fun later(x: Double, z: Double, draw: () -> Unit) {
        val q = layerQueue
        if (q == null) draw() else q += Layered(x, z, draw)
    }

    /** Draw (furthest first) everything queued that isn't to be kept for later. */
    private fun flushLayers(keep: (Layered) -> Boolean) {
        val q = layerQueue ?: return
        val go = q.filter { !keep(it) }.sortedByDescending { it.z }
        if (go.isEmpty()) return
        q.removeAll { it in go }
        layerQueue = null                 // so anything drawn now draws straight away
        go.forEach { it.draw() }
        layerQueue = q
    }

    /** A sideways position as a share of the visible half-width at distance [z] (-1 left edge .. 1 right edge). */
    private fun across(v: View3, z: Double, frac: Double) = (v.w / 2.0) / v.f * z * frac

    /** Crests are drawn out to this far (metres); beyond it the distant swell lines take over. */
    private const val FAR_CREST = 260.0
    /** Horizontal distances on the water are drawn at this fraction of real life (heights are true). */
    private const val SCALE = 0.25

    /** Below this (about a foot) the sea is drawn flat: no swell lines, breakers or white horses. */
    private const val FLAT_M = 0.3

    private const val CAM_H = 2.5       // camera up on the dune, metres above the sand

    private class View3(val w: Float, val top: Float, val bottom: Float, val horizon: Float, val f: Float, val camH: Double = CAM_H,
                        val skyTop: Float = top) {   // where the scene's own sky starts (clouds, sun and birds go below it)
        fun y(z: Double, height: Double = 0.0) = (horizon + f * (camH - height) / z).toFloat()
        fun x(xm: Double, z: Double) = (w / 2f + f * xm / z).toFloat()
        fun size(m: Double, z: Double) = (f * m / z).toFloat()
    }

    /** Widescreen, with letterbox bars, title and subtitles: the in-app view. */
    fun draw(c: Canvas, w: Float, h: Float, d: Float, t: Float, s: SceneInput, wall: Double = System.currentTimeMillis() / 1000.0) {
        val barTop = 26f * d
        val barBottom = 40f * d
        val top = barTop; val bottom = h - barBottom
        val horizon = top + (bottom - top) * 0.42f
        scene(c, View3(w, top, bottom, horizon, w * 0.8f), d, t, s, wall)
        bars(c, w, h, d, t, s, barTop, barBottom, wall)
    }

    /**
     * Full-screen portrait: the phone wallpaper. The camera goes higher up the dune
     * so the beach things sit in the lower part of the screen, under your app icons
     * rather than behind the clock. The readings show in a small caption.
     */
    fun drawTall(c: Canvas, w: Float, h: Float, d: Float, t: Float, s: SceneInput, wall: Double = System.currentTimeMillis() / 1000.0) {
        // Frame it like the in-app view, in a band from 3/8 of the way down to 1/8 from the bottom:
        // the sky carries on above it (behind the clock) and the sand below (under the dock).
        val bandTop = h * 3f / 8f
        val bandBottom = h * 7f / 8f
        // Exactly the app's camera, scaled up to fill the band: the same proportions (nothing
        // stretched), just a narrower slice of the view because a phone is narrower than the panorama.
        val horizon = bandTop + (bandBottom - bandTop) * 0.42f
        val f = (bandBottom - bandTop) * (0.8f * 390f / 234f)    // the app view's focal length : scene height
        // ...then stretched 20% top to bottom below the horizon (a slightly higher camera)
        scene(c, View3(w, 0f, h, horizon, f, CAM_H * 1.2, skyTop = bandTop), d, t, s, wall)
        // (the readings scroll on the amber display under the tide board)
    }

    private fun scene(c: Canvas, v: View3, d: Float, t: Float, s: SceneInput, wall: Double) {
        val w = v.w; val top = v.top; val bottom = v.bottom

        val day = smooth((s.sunElev + 6) / 12)                              // 0 night .. 1 day
        val golden = (1 - smooth(abs(s.sunElev - 3) / 9)) * day.coerceAtLeast(0.3)   // low sun glow
        val overcast = s.cloud.pow(1.6)
        val fog = ((8000 - s.visibilityM) / 7000).coerceIn(0.0, 1.0)
        val storm = s.code >= 95
        val flash = storm && (t % 5.7f) < 0.12f
        val wind = WindView(s)

        c.save()
        c.clipRect(0f, top, w, bottom)
        sky(c, v, s, day, golden, overcast, fog)
        stars(c, v, t, s, day, overcast, fog)
        sunAndMoon(c, v, d, s, day, overcast)
        clouds(c, v, d, t, s, wind, day, overcast)
        sea(c, v, d, t, s, wind, day, golden, wall, overcast, fog)
        beach(c, v, d, t, s, wind, day, overcast, wall)
        gulls(c, v, d, t, s, wind, day)
        weather(c, v, d, t, s, wind, fog, flash)
        vignette(c, w, top, bottom, d)
        c.restore()
    }

    private fun smooth(x: Double): Double { val t = x.coerceIn(0.0, 1.0); return t * t * (3 - 2 * t) }

    /** Wind as the camera sees it: sideways part (+ = to the right) and strength. */
    private class WindView(s: SceneInput) {
        val towards = (s.windFrom ?: s.facing) + 180.0
        val r = Math.toRadians(((towards - s.facing) % 360.0 + 540.0) % 360.0 - 180.0)
        val side = sin(r)                 // +1 blowing left-to-right across the view
        val away = cos(r)                 // +1 blowing out to sea (offshore)
        val strength = (s.windKn / 30.0).coerceIn(0.0, 1.0)
        val gustiness = ((s.gustKn - s.windKn) / max(s.windKn, 4.0)).coerceIn(0.0, 1.5)
        /** 0..1 gust pulse: more often and stronger the gustier it is. */
        fun gust(t: Float): Double {
            val cycle = 6.0 - 3.0 * min(gustiness, 1.0)
            val ph = (t % cycle) / cycle
            return if (ph < 0.25) sin(ph / 0.25 * PI) * gustiness.coerceAtMost(1.0) else 0.0
        }
    }

    // ---- sky -------------------------------------------------------------------------------

    private fun sky(c: Canvas, v: View3, s: SceneInput, day: Double, golden: Double, overcast: Double, fog: Double) {
        var top = mix(0xFF050B1E.toInt(), 0xFF3E86CF.toInt(), day)
        var low = mix(0xFF16223F.toInt(), 0xFFBFE1F4.toInt(), day)
        top = mix(top, 0xFF3D5A8A.toInt(), golden * 0.5)
        low = mix(low, 0xFFF4A261.toInt(), golden)
        val grey = mix(0xFF1E232B.toInt(), 0xFF8D99A6.toInt(), day)
        top = mix(top, grey, overcast * 0.85)
        low = mix(low, mix(grey, 0xFFFFFFFF.toInt(), 0.15), overcast * 0.8)
        low = mix(low, mix(0xFF39424E.toInt(), 0xFFD5DBE1.toInt(), day), fog)
        val bands = 28
        val fill = p(0)
        for (i in 0 until bands) {
            val f = i / (bands - 1.0)
            fill.color = mix(top, low, f.pow(1.4))
            c.drawRect(0f, v.top + (v.horizon - v.top) * i / bands, v.w, v.top + (v.horizon - v.top) * (i + 1) / bands + 1f, fill)
        }
    }

    private fun stars(c: Canvas, v: View3, t: Float, s: SceneInput, day: Double, overcast: Double, fog: Double) {
        val vis = (1 - day) * (1 - overcast) * (1 - fog)
        if (vis < 0.05) return
        val star = p(0)
        for (i in 0 until 50) {
            val tw = 0.5 + 0.5 * sin(t * (1.3 + hash(i) * 2) + i)
            star.color = alpha(0xFFFFFFFF.toInt(), vis * (0.35 + 0.65 * tw))
            c.drawCircle((hash(i * 3) * v.w).toFloat(), (v.top + hash(i * 7) * (v.horizon - v.top) * 0.9).toFloat(), (0.6 + hash(i * 5) * 1.4).toFloat() * 1.6f, star)
        }
    }

    /** Where a compass bearing at a given elevation lands on screen (null if outside the view). */
    private fun skyPoint(v: View3, s: SceneInput, az: Double, elev: Double): Pair<Float, Float>? {
        val r = rel(az, s.facing)
        if (abs(r) > 60) return null
        // Sky angles are squeezed a little so a wide slice of sky fits the frame, like a wide lens.
        return Pair((v.w / 2f + (v.w / 2f + 20f) * (r / 55.0)).toFloat(), (v.horizon - (v.horizon - v.skyTop) * (elev / 42.0)).toFloat())
    }

    private fun moonPoint(v: View3, s: SceneInput) = if (s.moonElev > 0) skyPoint(v, s, s.moonAz, s.moonElev) else null

    private fun sunAndMoon(c: Canvas, v: View3, d: Float, s: SceneInput, day: Double, overcast: Double) {
        if (s.sunElev > -1.5) {
            val pt = skyPoint(v, s, s.sunAz, s.sunElev)
            if (pt != null) {
                val (x, y) = pt
                val bright = 1 - overcast * 0.85
                val low = smooth(1 - s.sunElev / 20)
                val col = mix(0xFFFFF3C4.toInt(), 0xFFFF8C42.toInt(), low)
                for (k in 4 downTo 1) c.drawCircle(x, y, (14f + k * 12f) * d, p(alpha(col, 0.07 * bright)))
                c.drawCircle(x, y, 13f * d, p(alpha(col, 0.35 + 0.65 * bright)))
            }
        } else {
            // Where the moon really is; its shape is the real phase.
            val pt = moonPoint(v, s) ?: return
            val x = pt.first; val y = pt.second; val r = 10f * d
            val dim = 1 - overcast * 0.8
            c.drawCircle(x, y, r * 2.2f, p(alpha(0xFFE8EEF9.toInt(), 0.10 * dim)))
            val dark = p(alpha(0xFF2A3245.toInt(), dim)); val lit = p(alpha(0xFFF4F1E6.toInt(), dim))
            val waxing = s.moonPhase < 0.5
            c.drawCircle(x, y, r, dark)
            c.drawArc(RectF(x - r, y - r, x + r, y + r), if (waxing) -90f else 90f, 180f, true, lit)
            val k = cos(2 * PI * s.moonPhase).toFloat()
            val rx = abs(k) * r
            c.drawOval(RectF(x - rx, y - r, x + rx, y + r), if (k > 0) dark else lit)
        }
    }

    private fun clouds(c: Canvas, v: View3, d: Float, t: Float, s: SceneInput, wind: WindView, day: Double, overcast: Double) {
        val n = (s.cloud * 10).toInt().coerceIn(0, 10)
        if (n == 0) return
        val rainy = (s.rainMm / 2.0).coerceIn(0.0, 1.0)
        val lit = mix(mix(0xFF2B3140.toInt(), 0xFFFFFFFF.toInt(), day), 0xFF8E98A3.toInt(), rainy * 0.8)
        val shadow = mix(lit, 0xFF4A5260.toInt(), 0.35 + rainy * 0.4)
        val speed = (6 + 50 * wind.strength) * d * wind.side
        for (i in 0 until n) {
            val span = v.w + 240f * d
            var x = ((hash(i + 11) * span + t * speed * (0.6 + 0.12 * (i % 4))) % span).toFloat()
            if (x < 0) x += span
            x -= 120f * d
            val y = (v.skyTop + (v.horizon - v.skyTop) * (0.12 + 0.62 * hash(i + 31))).toFloat()
            val sc = (0.7 + 0.8 * hash(i + 41) + overcast * 0.6).toFloat() * d
            val base = p(alpha(shadow, 0.95))
            val top = p(alpha(lit, 0.95))
            c.drawOval(RectF(x - 46 * sc, y - 6 * sc, x + 46 * sc, y + 12 * sc), base)
            c.drawCircle(x - 20 * sc, y - 4 * sc, 16 * sc, top)
            c.drawCircle(x + 6 * sc, y - 12 * sc, 22 * sc, top)
            c.drawCircle(x + 28 * sc, y - 2 * sc, 14 * sc, top)
            c.drawOval(RectF(x - 44 * sc, y + 2 * sc, x + 44 * sc, y + 12 * sc), base)
        }
    }

    // ---- the sea -------------------------------------------------------------------------

    private fun shoreZ(s: SceneInput) = LOW_Z - (s.tide + 1) / 2 * (LOW_Z - HIGH_Z)
    private const val LOW_Z = 19.0      // water's edge at low tide, metres from camera
    private const val HIGH_Z = 9.5      // ...and at high tide
    private const val PROP_Z = 7.0
    private const val SIGN_Z = PROP_Z + 2.6
    /** Chance a planned run goes wrong and ends in a swim. Some swims don't fit before a session
     *  ends and are swapped for an ordinary run, so this is set a bit above 1/6 to land on 1 in 6. */
    private const val SWIM_P = 0.24      // where things on the beach stand

    /** Sky colours at the top of the scene's sky and at the horizon (the sea reflects these). */
    private fun skyCols(day: Double, golden: Double, overcast: Double, fog: Double): Pair<Int, Int> {
        var top = mix(0xFF050B1E.toInt(), 0xFF3E86CF.toInt(), day)
        var low = mix(0xFF16223F.toInt(), 0xFFBFE1F4.toInt(), day)
        top = mix(top, 0xFF3D5A8A.toInt(), golden * 0.5)
        low = mix(low, 0xFFF4A261.toInt(), golden)
        val grey = mix(0xFF1E232B.toInt(), 0xFF8D99A6.toInt(), day)
        top = mix(top, grey, overcast * 0.85)
        low = mix(low, mix(grey, 0xFFFFFFFF.toInt(), 0.15), overcast * 0.8)
        low = mix(low, mix(0xFF39424E.toInt(), 0xFFD5DBE1.toInt(), day), fog)
        return Pair(top, low)
    }

    /** Smooth value noise in 3D (x, y, time), -1..1. */
    private fun vnoise(x: Double, y: Double, z: Double): Double {
        val xi = kotlin.math.floor(x); val yi = kotlin.math.floor(y); val zi = kotlin.math.floor(z)
        val fx = x - xi; val fy = y - yi; val fz = z - zi
        val ux = fx * fx * (3 - 2 * fx); val uy = fy * fy * (3 - 2 * fy); val uz = fz * fz * (3 - 2 * fz)
        fun h(a: Double, b: Double, cc: Double) = hash((a * 73856093 + b * 19349663 + cc * 83492791).toInt() and 0x7fffffff) * 2 - 1
        fun lerp(a: Double, b: Double, f: Double) = a + (b - a) * f
        val x0 = lerp(lerp(h(xi, yi, zi), h(xi + 1, yi, zi), ux), lerp(h(xi, yi + 1, zi), h(xi + 1, yi + 1, zi), ux), uy)
        val x1 = lerp(lerp(h(xi, yi, zi + 1), h(xi + 1, yi, zi + 1), ux), lerp(h(xi, yi + 1, zi + 1), h(xi + 1, yi + 1, zi + 1), ux), uy)
        return lerp(x0, x1, uz)
    }

    /**
     * The sea surface: a mesh over the water from the horizon to the water's edge, each point
     * lifted by the simulated waves and coloured the way real water looks:
     *  - Fresnel: at a glancing angle (far off, and the backs of waves) water mirrors the sky; looking
     *    into it (wave faces turned towards us) you see the water itself, dark teal;
     *  - thin crests glow see-through green, more so with the sun behind them;
     *  - the sun or moon makes a glint path, tight on a calm sea, broad and broken in a wind;
     *  - wind roughens the surface (cat's paws in light airs, a matt texture in a breeze), glassy when calm;
     *  - the surf zone is clouded with sand and streaked with foam;
     *  - distance softens everything into the haze at the horizon.
     * It's drawn far to near in strips so nearer waves hide what's behind them; [between] is called
     * before each strip with its distance so boats and breakers can be slotted in at the right depth.
     */
    private fun surface(c: Canvas, v: View3, d: Float, t: Float, s: SceneInput, sw: Swell, day: Double, golden: Double,
                        overcast: Double, fog: Double, between: (Double) -> Unit) {
        val zs = sw.zs
        val (skyTop, skyLow) = skyCols(day, golden, overcast, fog)
        val yShore = v.y(zs)
        val top = v.horizon + 0.6f
        if (yShore <= top + 2) return
        val rowPx = 3.2f * d
        val rows = ((yShore - top) / rowPx).toInt().coerceIn(24, 180)
        val colPx = 10f * d
        val cols = (v.w / colPx).toInt() + 3
        val nV = (rows + 1) * cols
        val verts = FloatArray(nV * 2); val colors = IntArray(nV); val zRow = DoubleArray(rows + 1)
        // base colours
        fun r(cc: Int) = ((cc shr 16) and 255) / 255.0
        fun g(cc: Int) = ((cc shr 8) and 255) / 255.0
        fun b(cc: Int) = (cc and 255) / 255.0
        val light = (0.18 + 0.82 * day) * (1 - 0.25 * overcast)
        val deep = mix(0xFF0B2B3A.toInt(), 0xFF123E4C.toInt(), 0.5)
        val shallow = 0xFF4A7468.toInt()                        // sandy green where the surf stirs the bottom up
        val sss = 0xFF2E9C8E.toInt()                             // light through a thin crest
        val foamLit = mix(0xFFE9EEF0.toInt(), 0xFFFFFFFF.toInt(), day * (1 - overcast))
        val foamShade = 0xFFB4C4CC.toInt()
        val haze = mix(skyLow, 0xFFFFFFFF.toInt(), 0.05)
        // the sun (or moon) as a direction in the camera's frame: x right, y up, z out to sea
        val sunUp = s.sunElev > -2
        val az = rad(rel(if (sunUp) s.sunAz else s.moonAz, s.facing)); val el = rad(max(if (sunUp) s.sunElev else s.moonElev, 0.5))
        val Sx = sin(az) * cos(el); val Sy = sin(el); val Sz = cos(az) * cos(el)
        val lamp = if (sunUp) (1 - 0.92 * overcast) * smooth((s.sunElev + 2) / 6) else if (s.moonElev > 0) 0.35 * (1 - overcast) * (1 - abs(s.moonPhase - 0.5) * 2) else 0.0
        val lampCol = if (sunUp) mix(0xFFFFF4DA.toInt(), 0xFFFFB46A.toInt(), smooth(1 - s.sunElev / 15)) else 0xFFDDE6F5.toInt()
        val windRough = (s.windKn / 22.0).coerceIn(0.0, 1.0)
        val xbm = sw.breakXMean
        val smp = Swell.Sample()
        val td = t.toDouble()
        for (ri in 0..rows) {
            val yb = top + (yShore - top) * (ri.toFloat() / rows).pow(1.15f)
            val z = max(zs + 0.01, v.f * v.camH / max(0.3f, yb - v.horizon))
            zRow[ri] = z
            val q = z - zs
            val cellZ = z * z / (v.f * v.camH) * rowPx
            for (ci in 0 until cols) {
                val xs = (ci - 1) * colPx
                val x = (xs - v.w / 2.0) * z / v.f
                val cellX = z * colPx / v.f
                sw.sample(td, x, q, max(cellZ * 0.9, cellX * 0.6), smp)
                val vi = ri * cols + ci
                verts[vi * 2] = xs; verts[vi * 2 + 1] = v.y(z, smp.eta)
                // surface roughness from the wind: patchy cat's paws in light airs, all over in a breeze
                val patch = 0.55 + 0.45 * vnoise(x * 0.04 + td * 0.05, q * 0.05, td * 0.08)
                val rough = windRough * (if (s.windKn < 10) patch else 1.0)
                val nn = rough * 0.35 * (if (q > 1) 1.0 else 0.3) * smooth(3.0 / (2.2 * max(cellZ, cellX)) - 1.0)
                val sx = smp.dx * 0.6 + nn * vnoise(x * 1.3, q * 1.1, td * 1.7)
                val sz = smp.dq * 0.6 + nn * vnoise(x * 1.1 + 7, q * 1.3, td * 1.9)
                // normal and the direction back to the eye
                var Nx = -sx; var Ny = 1.0; var Nz = -sz
                val nl = kotlin.math.sqrt(Nx * Nx + Ny * Ny + Nz * Nz); Nx /= nl; Ny /= nl; Nz /= nl
                var Vx = -x; var Vy = v.camH - smp.eta; var Vz = -z
                val vl = kotlin.math.sqrt(Vx * Vx + Vy * Vy + Vz * Vz); Vx /= vl; Vy /= vl; Vz /= vl
                val ndv = (Nx * Vx + Ny * Vy + Nz * Vz).coerceIn(0.0, 1.0)
                val fres = (0.02 + 0.98 * (1 - ndv).pow(5.0)) * (1 - 0.3 * rough)
                val Rx = 2 * ndv * Nx - Vx; val Ry = 2 * ndv * Ny - Vy; val Rz = 2 * ndv * Nz - Vz
                val skyC = if (Ry <= 0) mix(skyLow, deep, 0.4) else mix(skyLow, skyTop, smooth(Ry / 0.45))
                // the water itself: deep teal, sandy green in the surf zone, glowing through thin crests
                var body = deep
                if (q < xbm * 1.8 + 2) body = mix(body, shallow, 0.55 * smooth(1 - q / (xbm * 1.8 + 2)))
                val glow = smooth(smp.eta / (sw.h * 0.45 + 0.05)) * (sz * 2.5).coerceIn(0.0, 1.0) * (0.45 + 0.55 * max(0.0, Sz)) * day
                body = mix(body, sss, glow * 0.6)
                var cr = r(body) * light; var cg = g(body) * light; var cb = b(body) * light
                cr += (r(skyC) - cr) * fres; cg += (g(skyC) - cg) * fres; cb += (b(skyC) - cb) * fres
                // glint: tight on glassy water, a broad scatter of sparkle when it's ruffled
                if (lamp > 0) {
                    val rs = max(0.0, Rx * Sx + Ry * Sy + Rz * Sz)
                    val spec = lamp * (rs.pow(600.0 - 520 * rough) * 1.6 + rs.pow(18.0) * 0.18 * rough)
                    cr += r(lampCol) * spec; cg += g(lampCol) * spec; cb += b(lampCol) * spec
                }
                // foam: whitewater rollers and trails in the surf zone
                if (smp.foam > 0.01) {
                    val lit = mix(foamShade, foamLit, (0.4 + 0.6 * ndv).coerceIn(0.0, 1.0))
                    val fo = smp.foam * (0.75 + 0.25 * vnoise(x * 0.9, q * 1.4, td * 0.6))
                    cr += (r(lit) * light - cr) * fo; cg += (g(lit) * light - cg) * fo; cb += (b(lit) * light - cb) * fo
                }
                // haze with distance
                val hz = (smooth((z - zs - 40) / 2600) * 0.75 + fog * smooth((z - zs) / 120) * 0.6).coerceIn(0.0, 0.92)
                cr += (r(haze) - cr) * hz; cg += (g(haze) - cg) * hz; cb += (b(haze) - cb) * hz
                colors[vi] = (0xFF shl 24) or ((cr.coerceIn(0.0, 1.0) * 255).toInt() shl 16) or
                    ((cg.coerceIn(0.0, 1.0) * 255).toInt() shl 8) or (cb.coerceIn(0.0, 1.0) * 255).toInt()
            }
        }
        // under-paint, so nothing shows through where waves lift the mesh
        c.drawRect(0f, v.horizon, v.w, yShore + 1f, p(colors[cols / 2]))
        val paint = Paint()
        val chunk = 3
        var r0 = 0
        while (r0 < rows) {
            val r1 = min(rows, r0 + chunk)
            between(zRow[r0])
            val tris = (r1 - r0) * (cols - 1) * 6
            val idx = ShortArray(tris)
            var k = 0
            val base = r0 * cols
            for (ri in r0 until r1) for (ci in 0 until cols - 1) {
                val a = (ri * cols + ci - base).toShort(); val b2 = (ri * cols + ci + 1 - base).toShort()
                val cc = ((ri + 1) * cols + ci - base).toShort(); val dd = ((ri + 1) * cols + ci + 1 - base).toShort()
                idx[k++] = a; idx[k++] = b2; idx[k++] = cc
                idx[k++] = b2; idx[k++] = dd; idx[k++] = cc
            }
            val count = (r1 - r0 + 1) * cols
            // Each strip gets its own arrays from offset 0. Android's native drawVertices insists the
            // colour array is as long as the float array (2 per vertex), so it's padded to match.
            val vChunk = verts.copyOfRange(base * 2, (base + count) * 2)
            val cChunk = IntArray(count * 2); System.arraycopy(colors, base, cChunk, 0, count)
            c.drawVertices(Canvas.VertexMode.TRIANGLES, count * 2, vChunk, 0, null, 0, cChunk, 0, idx, 0, tris, paint)
            r0 = r1
        }
    }

    private fun sea(c: Canvas, v: View3, d: Float, t: Float, s: SceneInput, wind: WindView, day: Double, golden: Double, wall: Double,
                    overcast: Double, fog: Double) {
        val zs = shoreZ(s)
        val sw = Swell(s, zs, v.camH)
        val hw = sw.h
        // Boats, people, breakers, white horses and ships are queued by distance and slotted in
        // between the strips of sea, so nearer water hides what's behind it.
        layerQueue = mutableListOf()
        later(0.0, 5000.0) { ships(c, v, d, s, day, fog, wall) }
        buoy(c, v, d, t, s, zs, sw, day)
        boats(c, v, d, t, s, zs, sw, day, onSand = false)
        lifeboat(c, v, d, t, s, zs, sw, day, wall)
        rib(c, v, d, t, s, zs, sw, day, wall)

        // Breaking waves: the lip of a plunging wave, the tumbling roller of whitewater and, in an
        // offshore wind, spray blown back off the top. (The faces, troughs and foam trails are part
        // of the sea surface itself.)
        val crests = 24
        val glass = mix(mix(0xFF1B4E63.toInt(), 0xFF4FB3C4.toInt(), day), 0xFF7A8C96.toInt(), overcast * 0.5)
        val offshoreSpray = (-sw.windOn).coerceIn(0.0, 1.0) * smooth((s.windKn - 8) / 10)
        if (hw >= FLAT_M) for (k in crests - 1 downTo 0) {
            val dist = sw.crestDepth(k, t)
            if (dist <= 0.05) continue
            val id = sw.crestId(k, t)
            val br = sw.breaker(id)
            val xb = sw.breakX(id)
            if (dist > xb * 1.6 + 4 || br.hb < 0.25) continue
            later(0.0, zs + dist) {
                val pts = sw.crestLine(v, dist)
                val base = mutableListOf<Pair<Float, Float>>()
                val topL = mutableListOf<Pair<Float, Float>>()
                val brk = mutableListOf<Boolean>()
                val prog = mutableListOf<Double>()
                val qs = mutableListOf<Double>()
                for ((xm, zm) in pts) {
                    val q = zm - zs
                    val hh = min(sw.waveHeight(id, q), v.camH * 0.92) * smooth(q / 1.0)
                    base += Pair(v.x(xm, zm), v.y(zm)); topL += Pair(v.x(xm, zm), v.y(zm, hh * 0.85))
                    val xbl = xb * sw.breakScale(xm)
                    brk += q < xbl; prog += ((xbl - q) / xbl).coerceIn(0.0, 1.0); qs += q
                }
                if (base.size < 2) return@later
                val fadeFar = 1 - fog * 0.8
                // Offshore wind: spray streaming back off the crest just before and as it breaks
                if (offshoreSpray > 0.05) {
                    val mist = p(alpha(0xFFF2F7F9.toInt(), 0.5 * offshoreSpray * fadeFar))
                    for ((j, pt) in topL.withIndex()) if (j % 2 == 0) {
                        val q = qs[j]; val xbl = xb * sw.breakScale(0.0)
                        val near = smooth((q - xbl * 0.8) / (xbl * 0.2)) * (1 - smooth((q - xbl * 1.5) / (xbl * 0.3)))
                        if (near <= 0.02) continue
                        val zm = zs + q
                        for (m in 0 until 3) {
                            val life = ((t * 1.3 + hash(j * 3 + m + k * 17)) % 1.0).toFloat()
                            val up = v.size(0.3 + 0.9 * life, zm) * near.toFloat()
                            val back = v.size(0.4 + 1.6 * life, zm) * 0.25f
                            c.drawCircle(pt.first + (m - 1) * back, pt.second - up, max(1f * d, v.size(0.18 * (1 - life), zm)), mist)
                        }
                    }
                }
                if (!brk.any { it }) return@later
                // Plunging: the lip throws forward over a hollow face before it lands
                val curlEnd = 0.2
                if (br.type == Surfzone.Type.PLUNGING) {
                    var j = 0
                    while (j < topL.size) {
                        if (!(brk[j] && prog[j] < curlEnd)) { j++; continue }
                        val start = j
                        while (j < topL.size && brk[j] && prog[j] < curlEnd) j++
                        if (j - start < 2) continue
                        // tapered at both ends: it pitches where the peak is and fades along the shoulder
                        val e = FloatArray(j - start) { i -> smooth(min(i, j - 1 - start - i) / 5.0).toFloat() }
                        val face = Path(); val lip = Path(); val edge = Path()
                        fun span(m: Int) = base[m].second - topL[m].second
                        fun curl(m: Int) = (sin(prog[m] / curlEnd * PI / 2) * e[m - start]).toFloat()
                        // the steep face in shadow under the lip
                        for (m in start until j) if (m == start) face.moveTo(topL[m].first, topL[m].second) else face.lineTo(topL[m].first, topL[m].second)
                        for (m in j - 1 downTo start) face.lineTo(topL[m].first, topL[m].second + span(m) * 0.95f * curl(m))
                        face.close()
                        c.drawPath(face, p(alpha(mix(0xFF0E3A40.toInt(), 0xFF1F5E5A.toInt(), day), 0.35 * fadeFar)))
                        // the lip: thin water, lit green from behind, throwing forward
                        for (m in start until j) {
                            val y = topL[m].second - span(m) * 0.08f * e[m - start]
                            if (m == start) lip.moveTo(topL[m].first, y) else lip.lineTo(topL[m].first, y)
                        }
                        for (m in j - 1 downTo start) lip.lineTo(topL[m].first, topL[m].second + span(m) * (0.06f + 0.22f * curl(m)))
                        lip.close()
                        c.drawPath(lip, p(alpha(mix(glass, 0xFF6FBFB4.toInt(), 0.25 * day), 0.6 * fadeFar)))
                        for (m in start until j) {
                            val y = topL[m].second + span(m) * (0.06f + 0.22f * curl(m))
                            if (m == start) edge.moveTo(topL[m].first, y) else edge.lineTo(topL[m].first, y)
                        }
                        c.drawPath(edge, stroke(alpha(0xFFFFFFFF.toInt(), 0.5 * fadeFar), 1.1f * d))
                    }
                }
                // The tumbling roller: a ragged, churning crown of white along the broken front.
                // One continuous band (not blobs), torn and lumpy, taller where it has just broken.
                val froth = p(alpha(mix(0xFFE3ECEF.toInt(), 0xFFFAFDFE.toInt(), day), 0.92 * fadeFar))
                val shade = p(alpha(mix(0xFF8FA7B1.toInt(), 0xFFC3D5DC.toInt(), day), 0.85 * fadeFar))
                val hb = min(br.hb, 3.5)
                val grow = DoubleArray(topL.size) { j ->
                    if (!brk[j]) 0.0 else {
                        val pr = prog[j]
                        when (br.type) {
                            Surfzone.Type.SPILLING -> smooth(pr * 5) * (0.4 + 0.6 * smooth(pr * 2.5))
                            Surfzone.Type.PLUNGING -> if (pr < curlEnd) 0.0 else smooth((pr - curlEnd) * 6)
                            Surfzone.Type.SURGING -> 0.3 * smooth(pr * 3)
                        } * (1 - 0.55 * smooth((pr - 0.75) / 0.25))     // the bore shrinks as it runs up the sand
                    }
                }
                var j = 0
                while (j < topL.size) {
                    if (grow[j] <= 0.02) { j++; continue }
                    val st = j
                    while (j < topL.size && grow[j] > 0.02) j++
                    if (j - st < 2) continue
                    val crown = Path(); val under = Path()
                    val gE = FloatArray(j - st) { i -> (grow[st + i] * smooth(min(i, j - 1 - st - i) / 4.0)).toFloat() }
                    for (m in st until j) {
                        val zm = zs + qs[m]
                        val xm = pts[m].first
                        val n1 = vnoise(xm * 0.9, t * 0.9, k * 3.1)
                        val n2 = vnoise(xm * 3.1, t * 1.7, k * 1.3)
                        val g = gE[m - st]
                        val tall = v.size(hb * 0.22 * g * (0.55 + 0.55 * n1 + 0.25 * n2), zm)
                        val y = topL[m].second - tall + (base[m].second - topL[m].second) * 0.15f
                        if (m == st) crown.moveTo(topL[m].first, y) else crown.lineTo(topL[m].first, y)
                    }
                    for (m in j - 1 downTo st) {
                        val y = topL[m].second + (base[m].second - topL[m].second) * (0.15f + 0.45f * gE[m - st])
                        crown.lineTo(topL[m].first, y)
                    }
                    crown.close()
                    for (m in st until j) {
                        val y = topL[m].second + (base[m].second - topL[m].second) * 0.3f
                        if (m == st) under.moveTo(topL[m].first, y) else under.lineTo(topL[m].first, y)
                    }
                    for (m in j - 1 downTo st) under.lineTo(topL[m].first, topL[m].second + (base[m].second - topL[m].second) * (0.3f + 0.7f * gE[m - st]))
                    under.close()
                    c.drawPath(under, shade)
                    c.drawPath(crown, froth)
                }
            }
        }

        // White horses. Whitecap cover grows with about the 3.4th power of the wind speed
        // (Monahan & O'Muircheartaigh): scattered at force 3, many at force 5, everywhere in a gale.
        val U = s.windKn * 0.5144
        val cover = 3.84e-6 * U.pow(3.41)
        val caps = (cover * 5000).toInt().coerceIn(0, 160)
        if (caps > 0 && hw >= 0.05) {
            val tw = (s.chopPeriod ?: (3.86 * kotlin.math.sqrt(max(s.chopM, 0.1)))).coerceIn(1.6, 8.0)
            val capLen = SCALE * 1.56 * tw * tw * 0.3 + 0.2
            val z0 = zs + sw.breakXMean * 1.5 + 3; val z1 = zs + 450
            for (i in 0 until caps) {
                val cyc = tw * (1.6 + hash(i + 705))
                val gen = kotlin.math.floor(t / cyc + hash(i + 700) * 10)
                val life = ((t / cyc + hash(i + 700) * 10) - gen)
                val seed = i * 31 + gen.toInt() * 7
                val zm = z0 + (z1 - z0) * kotlin.math.sqrt(hash(seed + 710))
                val xm = (hash(seed + 720) - 0.5) * 2 * zm * (v.w / v.f) * 0.55 + wind.side * life * 3 * wind.strength
                later(xm, zm) {
                    val x = v.x(xm, zm); val y = v.y(zm, sw.height(t, xm, zm)) - 0.4f * d
                    val L = max(2f * d, v.size(capLen, zm)) * (0.7f + 0.6f * hash(seed + 730).toFloat())
                    val rise = smooth(life / 0.15)
                    val fade = 1 - smooth((life - 0.3) / 0.55)
                    if (fade <= 0.02) return@later
                    val spread = 1f + 1.4f * smooth((life - 0.15) / 0.6).toFloat()
                    val drift = (wind.side * L * 0.5 * smooth(life / 0.8)).toFloat()
                    val a = fade * rise * (1 - fog * 0.7)
                    for (m in 0 until 3) {
                        val ox = drift + (m - 1f) * L * 0.25f * spread
                        val rr = L * (0.14f + 0.06f * hash(seed * 5 + m).toFloat()) * (1.1f - 0.3f * (spread - 1f) / 1.4f)
                        c.drawOval(RectF(x + ox - rr * 1.3f, y - rr * 0.35f, x + ox + rr * 1.3f, y + rr * 0.45f), p(alpha(0xFFC4D9E2.toInt(), a * 0.75)))
                        c.drawOval(RectF(x + ox - rr * 1.15f, y - rr * 0.5f, x + ox + rr * 1.15f, y + rr * 0.3f), p(alpha(0xFFF7FBFC.toInt(), a * 0.9)))
                    }
                }
            }
        }
        // Force 7 and up: foam blown off the crests lies in streaks along the wind
        if (s.windKn >= 27) {
            val n = ((s.windKn - 26) * 6).toInt().coerceAtMost(120)
            val (ux, uq) = Pair(wind.side, wind.away)
            for (i in 0 until n) {
                val zm = zs + sw.breakXMean * 1.3 + 4 + 300 * kotlin.math.sqrt(hash(i + 1800))
                val xm = (hash(i + 1810) - 0.5) * 2 * zm * (v.w / v.f) * 0.6
                later(xm, zm) {
                    val len = 6.0 + 10 * hash(i + 1820)
                    val e = sw.height(t, xm, zm)
                    c.drawLine(v.x(xm, zm), v.y(zm, e), v.x(xm + ux * len, zm + uq * len), v.y(zm + uq * len, e),
                        stroke(alpha(0xFFE8F0F2.toInt(), 0.35 * (1 - fog * 0.7)), max(0.8f * d, v.size(0.25, zm))))
                }
            }
        }

        // The sea itself, far to near, with everything above slotted in by distance
        surface(c, v, d, t, s, sw, day, golden, overcast, fog) { zFar -> flushLayers { it.z <= zFar } }

        // Whatever's left is nearer than all the water
        flushLayers { false }
        layerQueue = null
        heli(c, v, d, t, s, zs, sw, day, wall)
    }

    /**
     * The sea as seen from this beach. Built from real wave physics, then scaled to fit the picture:
     *  - dispersion: wavelength L0 = gT²/2π offshore, shortening in shallow water (Eckart's formula);
     *  - refraction by Snell's law: the along-shore wavenumber is constant, so crests swing round to
     *    meet the beach more square-on as they slow down;
     *  - shoaling (energy flux), then depth-limited breaking where the water is about 1.3x the wave
     *    height deep, with the breaker type from the beach slope (Iribarren number) and the wind;
     *  - sets (wave groups), a spread of swell directions (wide and confused for short periods,
     *    tidy lines for long groundswell), a second swell, and the local wind sea.
     * Horizontal distances on the water are drawn at [SCALE] of real life so a whole surf zone fits
     * in view; heights are true, so a 4 ft forecast draws 4 ft waves.
     */
    private class Swell(val s: SceneInput, val zs: Double, camH: Double = CAM_H) {
        private val g = 9.81
        /** How far off the beach's heading the swell comes from, degrees (-180..180). */
        private val off = rel(s.swellFrom ?: s.facing, s.facing)
        /** Crest angle far out (0 = parallel to the beach); swell from behind only wraps in steeply. */
        val angle: Double = off.coerceIn(-75.0, 75.0)
        /** Which way the break peels along the wave across the screen (+1 left to right, 0 = A-frame). */
        val peel: Double = if (abs(angle) < 4) 0.0 else if (angle > 0) -1.0 else 1.0
        /** Swell height (wind chop still makes small waves on a flat day). */
        val h: Double = max(s.waveM, s.chopM * 0.5)
        val tv = max(s.periodS, 2.0)
        private val L0 = g * tv * tv / (2 * PI)
        /** Offshore wavelength in the picture. */
        val lambda = SCALE * L0
        val speed: Double get() = lambda / tv
        private val k0 = 2 * PI / lambda
        private val kx = k0 * sin(rad(angle))
        private val slopeReal = Surfzone.slope(s.tide)
        /** Real water depth (m) at [q] picture-metres out from the water's edge. */
        fun depth(q: Double) = max(q, 0.0) / SCALE * slopeReal
        /** Onshore (+) or offshore (-) wind, 0..1 by strength: it moves and changes the break. */
        val windOn: Double = run {
            val wf = s.windFrom ?: return@run 0.0
            cos(rad(rel(wf, s.facing))) * (s.windKn / 25.0).coerceIn(0.0, 1.0)
        }

        /** Local wavelength (picture metres) at [q] out, for a wave of period [T]. */
        private fun waveLen(q: Double, T: Double): Double {
            val l0 = g * T * T / (2 * PI)
            return SCALE * l0 * kotlin.math.sqrt(kotlin.math.tanh(4 * PI * PI * depth(q) / (g * T * T))).coerceAtLeast(0.04)
        }

        // Phase along the shore-normal: K(q) = integral of the onshore wavenumber, so the phase at
        // (x, q) is K(q) + kx·x. Crests are where that (plus 2πt/T) is a whole number of turns.
        private val step = 0.2
        private val n = 6000
        private val K = DoubleArray(n + 1).also { a ->
            for (i in 1..n) {
                val k = 2 * PI / waveLen((i - 0.5) * step, tv)
                a[i] = a[i - 1] + step * kotlin.math.sqrt(max(k * k - kx * kx, 0.05 * k * k))
            }
        }
        private val kqDeep = kotlin.math.sqrt(max(k0 * k0 - kx * kx, 0.05 * k0 * k0))
        private fun Kq(q: Double): Double {
            if (q <= 0) return q * (K[1] / step)
            val i = (q / step).toInt()
            if (i >= n) return K[n] + (q - n * step) * kqDeep
            val f = q / step - i
            return K[i] + (K[i + 1] - K[i]) * f
        }
        private fun KqInv(v: Double): Double {
            if (v <= 0) return v / (K[1] / step)
            if (v >= K[n]) return n * step + (v - K[n]) / kqDeep
            var lo = 0; var hi = n
            while (hi - lo > 1) { val mid = (lo + hi) / 2; if (K[mid] < v) lo = mid else hi = mid }
            return (lo + (v - K[lo]) / (K[hi] - K[lo])) * step
        }
        /** "Phase distance" along the middle of the view: crests move through it at a steady [speed]. */
        fun sAt(q: Double): Double = Kq(q) * lambda / (2 * PI)
        fun xAt(sv: Double): Double = KqInv(sv * 2 * PI / lambda)
        fun travel(x0: Double, dt: Double) = xAt(sAt(x0) - speed * dt)
        fun timeBetween(x1: Double, x2: Double) = (sAt(x1) - sAt(x2)) / speed
        /** Distance out where the crest through the middle at [qc] is, at sideways position [x]. */
        fun qOnCrest(qc: Double, x: Double): Double = max(0.01, KqInv(Kq(qc) - kx * x))
        fun depthAtCentre(x: Double, z: Double): Double = KqInv(Kq(z - zs) + kx * x)

        /** Sets: every 8th wave the biggest, a lull between, no two quite alike. */
        fun setFactor(id: Int): Double = 1.0 + 0.28 * sin(2 * PI * id / 8.0) + 0.08 * (hash(id * 13 + 7) - 0.5)
        /** Where along the crest it breaks first: a peak on the sandbar upwave of the middle. */
        private val peakX = -peel * 9.0
        fun breakScale(xm: Double): Double = 0.7 + 0.45 * kotlin.math.exp(-((xm - peakX) / 16.0).pow(2.0))
        private val cache = HashMap<Int, Surfzone.Breaker>()
        fun breaker(id: Int) = cache.getOrPut(id) { Surfzone.breaker(h * setFactor(id), tv, s.tide, windOn) }
        val average = Surfzone.breaker(h, tv, s.tide, windOn)
        /** Where wave [id] starts to break, picture-metres out from the water's edge. */
        fun breakX(id: Int) = SCALE * breaker(id).depth / slopeReal
        val breakXMean: Double get() = SCALE * average.depth / slopeReal

        // Shoaling coefficient sqrt(cg0/cg) along the profile (real units)
        private val ks = DoubleArray(n + 1).also { a ->
            for (i in 0..n) {
                val dd = max(depth(i * step), 0.05)
                val L = waveLen(i * step, tv) / SCALE; val k = 2 * PI / L
                val kh = min(k * dd, 20.0)
                val nn = 0.5 * (1 + 2 * kh / kotlin.math.sinh(2 * kh))
                val cg = nn * L / tv
                a[i] = kotlin.math.sqrt((0.5 * L0 / tv) / cg).coerceIn(0.85, 1.6)
            }
        }
        private fun shoal(q: Double): Double { val i = (q / step).toInt(); return if (i >= n) 1.0 else if (i < 0) ks[0] else ks[i] }

        /** Height of wave [id] at [q] out: shoaling up to the break, then a bore shrinking with the depth. */
        fun waveHeight(id: Int, q: Double): Double {
            val b = breaker(id); val h0 = h * setFactor(id); val xb = SCALE * b.depth / slopeReal
            val hb = min(b.hb, 1.35 * h0)
            return if (q >= xb) min(h0 * shoal(q), hb) else hb * (q / xb) * (0.55 + 0.45 * q / xb)
        }

        /** Distance out where crest [k] crosses the middle of the view, and which wave it is. */
        fun crestDepth(k: Int, t: Float): Double {
            val ph = ((t / tv) % 1.0 + 1.0) % 1.0
            return xAt(lambda * (k - ph))
        }
        fun crestId(k: Int, t: Float): Int = k + kotlin.math.floor(t / tv).toInt()
        fun idAt(q: Double, t: Double): Int = kotlin.math.round(sAt(q) / lambda + t / tv).toInt()
        fun timeOf(id: Int, q: Double): Double = (id - sAt(q) / lambda) * tv

        /** Points (x, z) along the crest that crosses the middle at [depth], left to right. */
        fun crestLine(v: View3, depth: Double): List<Pair<Double, Double>> {
            val out = mutableListOf<Pair<Double, Double>>()
            val z0 = zs + depth
            val half = (v.w / 2.0 + 40) / v.f
            var x = -half * z0 * 2.5
            val end = half * z0 * 2.5
            val dx = max(0.4, z0 * 0.012)
            while (x <= end) {
                val q = qOnCrest(depth, x); val z = zs + q
                val xs = v.x(x, z)
                if (xs > -40 && xs < v.w + 40 && q > 0.02) out += Pair(x, z)
                x += dx
            }
            return out
        }

        // ---- the whole surface: swell + spread + second swell + wind sea ------------------

        /** A train of waves that doesn't refract (spread swell, second swell, wind sea). */
        private class Comp(val a: Double, val T: Double, val fromDeg: Double, val ph: Double, val nearFade: Double)
        private val comps: List<Comp> = run {
            val list = mutableListOf<Comp>()
            // tidy lines for long groundswell, a confused spread of directions for short wind swell
            val coherent = smooth((tv - 4.0) / 6.0)
            val spreadDeg = 8.0 + 30.0 * (1 - coherent)
            val sideA = h * (0.12 + 0.28 * (1 - coherent))
            list += Comp(sideA, tv * 1.07, off + spreadDeg, 1.3, 1.0)
            list += Comp(sideA * 0.85, tv * 0.93, off - spreadDeg * 0.8, 4.1, 1.0)
            // a second swell from somewhere else
            val h2 = s.swell2M ?: 0.0
            if (h2 > 0.1) list += Comp(h2 / 2, max(s.swell2Period ?: tv, 3.0), rel(s.swell2From ?: s.facing, s.facing).coerceIn(-80.0, 80.0), 2.2, 1.0)
            // the local wind sea: a spread of short waves running downwind
            val hw = s.chopM
            if (hw > 0.03) {
                val tw = (s.chopPeriod ?: (3.86 * kotlin.math.sqrt(hw))).coerceIn(1.6, 8.0)
                val fromW = rel(s.chopFrom ?: s.windFrom ?: s.facing, s.facing)
                val per = doubleArrayOf(1.0, 0.8, 0.66, 1.15, 0.55, 0.9)
                val ang = doubleArrayOf(0.0, -35.0, 30.0, -60.0, 55.0, 15.0)
                val w = doubleArrayOf(0.42, 0.3, 0.25, 0.18, 0.15, 0.22)
                val norm = kotlin.math.sqrt(w.sumOf { it * it })
                for (i in per.indices) list += Comp(hw * 0.35 * w[i] / norm * 2, tw * per[i], fromW + ang[i], i * 1.7 + 0.4, 0.0)
            }
            list
        }
        /** Whether a wind-sea train runs offshore: then it only builds up with distance from the beach (fetch). */
        private fun fetch(c: Comp, q: Double) = if (cos(rad(c.fromDeg)) < -0.2) smooth(q / 250.0) * 0.85 + 0.15 else 1.0

        // wavelengths depend only on the distance out, so one row of the mesh shares them
        private var lastQ = Double.NaN; private var lenMain = 0.0
        private val lens = DoubleArray(comps.size)
        /** The result of [sample]: elevation, slopes and foam at a point. */
        class Sample { var eta = 0.0; var dx = 0.0; var dq = 0.0; var foam = 0.0; var crest = 0.0 }

        /**
         * The water surface at (x, q) at time t. [cell] is the size of the picture's mesh there, so
         * waves too short to show at that distance fade out instead of flickering.
         */
        fun sample(t: Double, x: Double, q: Double, cell: Double, out: Sample) {
            var eta = 0.0; var dx = 0.0; var dq = 0.0; var foam = 0.0
            // main swell
            val nPh = (Kq(q) + kx * x) / (2 * PI) + t / tv
            val id = kotlin.math.round(nPh).toInt()
            val f = nPh - id
            val H = waveHeight(id, q)
            if (q != lastQ) { lastQ = q; lenMain = waveLen(q, tv); for (i in comps.indices) lens[i] = waveLen(q, comps[i].T) }
            val Lq = lenMain
            val aa = smooth((Lq / (2.2 * cell) - 1.0))
            if (H > 0.01 && aa > 0) {
                val a = H / 2 * aa
                val kl = 2 * PI / Lq
                val steep = (kl * a * 0.5).coerceAtMost(0.45)
                val th = 2 * PI * f
                eta += a * (cos(th) + steep * cos(2 * th)) - a * steep
                val de = -a * (sin(th) + 2 * steep * sin(2 * th))
                val kq = kotlin.math.sqrt(max(kl * kl - kx * kx, 0.05 * kl * kl))
                dx += de * kx; dq += de * kq
                // broken water: a roller of foam on the front and a trail behind, thinning out
                val xb = breakX(id) * breakScale(x)
                if (q < xb && H > 0.2) {
                    val run = q / xb
                    val roller = smooth((f + 0.12) / 0.07) * (1 - smooth((f - 0.04) / 0.30))
                    val trail = 0.35 * (1 - smooth((f - 0.04) / 0.45)) * smooth((f + 0.12) / 0.07)
                    foam = max(foam, (roller * (0.75 + 0.25 * run) + trail) * smooth((xb - q) / (xb * 0.12 + 0.5)))
                } else if (q < xb * 1.25 && H > 0.2) {
                    // just about to go: the top starts to feather white
                    foam = max(foam, smooth((f + 0.03) / 0.04) * (1 - smooth(f / 0.05)) * smooth((xb * 1.25 - q) / (xb * 0.25)) * 0.6)
                }
            }
            val xbm = breakXMean
            for ((ci, c) in comps.withIndex()) {
                val L = lens[ci]
                val fade = smooth((L / (2.2 * cell) - 1.0))
                if (fade <= 0) continue
                val k = 2 * PI / L
                val sa = sin(rad(c.fromDeg)); val ca = cos(rad(c.fromDeg))
                // these break and die in the surf zone (wind sea earlier, it's smaller)
                val surf = if (c.nearFade > 0.5) smooth((q - xbm) / (xbm + 5)) else smooth(q / max(2.0, SCALE * 1.6 * c.a * 2 / slopeReal))
                val a = c.a * fade * surf * fetch(c, q)
                if (a <= 0.001) continue
                val th = k * (sa * x + ca * q) + 2 * PI * t / c.T + c.ph
                eta += a * cos(th)
                val de = -a * sin(th)
                dx += de * k * sa; dq += de * k * ca
            }
            out.eta = eta; out.dx = dx; out.dq = dq; out.foam = foam.coerceIn(0.0, 1.0); out.crest = f
        }

        private val scratch = Sample()
        /** Height of the water surface (metres) at a point: what boats and swimmers ride on. */
        fun height(t: Float, x: Double, z: Double): Double {
            sample(t.toDouble(), x, z - zs, 0.0, scratch)
            return scratch.eta
        }
    }

    private fun gauge(c: Canvas, v: View3, d: Float, t: Float, s: SceneInput, zs: Double, sw: Swell, day: Double) {
        val zm = zs + max(3.0, sw.breakXMean * 1.15 + 1.5)  // just outside the break, where it reads the full swell
        val xm = -(v.w / 2.0) / v.f * zm * 0.8           // well over on the left of the view
        val unit = if (s.feet) 0.3048 else 0.5            // a mark every foot, or every half metre
        val marks = if (s.feet) 10 else 6
        val topM = unit * marks
        val x = v.x(xm, zm)
        val yBase = v.y(zm); val yTop = v.y(zm, topM)
        val wPx = max(3f * d, v.size(0.16, zm))
        for (i in 0 until marks) {
            val y0 = v.y(zm, unit * i); val y1 = v.y(zm, unit * (i + 1))
            c.drawRect(x - wPx / 2, y1, x + wPx / 2, y0, p(shadeDay(if (i % 2 == 0) 0xFFF4F1EA.toInt() else 0xFFD84A3A.toInt(), day)))
        }
        val lab = Draw.paint(shadeDay(0xFFFFFFFF.toInt(), day), 9f * d, bold = true, align = Paint.Align.LEFT)
        val every = if (v.size(unit * 2, zm) > 11f * d) 2 else 5
        for (i in every..marks step every) c.drawText("$i", x + wPx / 2 + 2f * d, v.y(zm, unit * i) + 3f * d, lab)
        // Red triangle topmark on the pole
        val tri = max(9f * d, v.size(0.55, zm))
        c.drawRect(x - wPx * 0.2f, yTop - tri * 0.4f, x + wPx * 0.2f, yTop, p(shadeDay(0xFF2A2A2A.toInt(), day)))
        c.drawPath(Path().apply { moveTo(x, yTop - tri * 1.35f); lineTo(x + tri * 0.55f, yTop - tri * 0.4f); lineTo(x - tri * 0.55f, yTop - tri * 0.4f); close() },
            p(shadeDay(0xFFE02B20.toInt(), day)))
        c.drawText(if (s.feet) "ft" else "×½m", x + tri * 0.65f, yTop - tri * 0.6f, lab)
        // Water climbing the post
        val e = sw.height(t, xm, zm).coerceAtMost(topM)
        val yW = v.y(zm, e)
        c.drawRect(x - wPx / 2 - 2f * d, yW, x + wPx / 2 + 2f * d, yBase + 2f * d, p(alpha(mix(0xFF1F6E8C.toInt(), 0xFF6FB8D2.toInt(), day), 0.92)))
        c.drawOval(RectF(x - wPx * 1.6f, yW - 1.5f * d, x + wPx * 1.6f, yW + 1.5f * d), p(alpha(0xFFFFFFFF.toInt(), 0.9)))
    }

    private fun shadeDay(col: Int, day: Double) = mix(col, 0xFF0A1024.toInt(), (1 - day) * 0.6)

    private fun buoy(c: Canvas, v: View3, d: Float, t: Float, s: SceneInput, zs: Double, sw: Swell, day: Double) {
        // Out at sea, a mile off the beach
        val zm = zs + 1609.0
        val xm = (v.w / 2.0) / v.f * zm * 0.3
        if (layerQueue != null) { later(xm, zm) { buoy(c, v, d, t, s, zs, sw, day) }; return }
        val e = sw.height(t, xm, zm)
        val x = v.x(xm, zm); val y = v.y(zm, e)
        val sz2 = max(2.5f * d, v.size(0.9, zm))      // a mile off it's only just visible
        val tilt = sin(2 * PI * t / sw.tv).toFloat() * 10f * (sw.h.toFloat().coerceAtMost(2f) / 2f)
        c.save(); c.rotate(tilt, x, y)
        c.drawPath(Path().apply { moveTo(x - sz2 * 0.5f, y); lineTo(x - sz2 * 0.3f, y - sz2 * 1.4f); lineTo(x + sz2 * 0.3f, y - sz2 * 1.4f); lineTo(x + sz2 * 0.5f, y); close() },
            p(shadeDay(0xFFD8452F.toInt(), day)))
        c.drawRect(x - sz2 * 0.4f, y - sz2 * 0.8f, x + sz2 * 0.4f, y - sz2 * 0.6f, p(shadeDay(0xFFFFFFFF.toInt(), day)))
        if (day < 0.5 && (t % 3f) < 0.4f) c.drawCircle(x, y - sz2 * 1.5f, 3f * d, p(0xFFFFE680.toInt()))
        c.restore()
    }

    // ---- people on the water -----------------------------------------------------------

    private fun norm(x: Double, z: Double): Pair<Double, Double> { val l = kotlin.math.sqrt(x * x + z * z).coerceAtLeast(1e-6); return Pair(x / l, z / l) }

    /** A long shape seen from above (kayak, board), lying at height [h] and seen through the camera. */
    private fun outline(v: View3, x: Double, z: Double, hx: Double, hz: Double, len: Double,
                        width: (Double) -> Double, h: (Double) -> Double): Path {
        val nx = -hz; val nz = hx
        val path = Path()
        val n = 18
        for (i in 0..n) {
            val u = -1.0 + 2.0 * i / n
            val px = x + hx * u * len / 2 + nx * width(u); val pz = z + hz * u * len / 2 + nz * width(u)
            if (i == 0) path.moveTo(v.x(px, pz), v.y(pz, h(u))) else path.lineTo(v.x(px, pz), v.y(pz, h(u)))
        }
        for (i in n downTo 0) {
            val u = -1.0 + 2.0 * i / n
            val px = x + hx * u * len / 2 - nx * width(u); val pz = z + hz * u * len / 2 - nz * width(u)
            path.lineTo(v.x(px, pz), v.y(pz, h(u)))
        }
        path.close(); return path
    }

    /** Side view of the same shape: deck line down to the waterline along the centre. */
    private fun side(v: View3, x: Double, z: Double, hx: Double, hz: Double, len: Double, top: (Double) -> Double, bottom: (Double) -> Double): Path {
        val path = Path(); val n = 18
        for (i in 0..n) {
            val u = -1.0 + 2.0 * i / n
            val px = x + hx * u * len / 2; val pz = z + hz * u * len / 2
            if (i == 0) path.moveTo(v.x(px, pz), v.y(pz, top(u))) else path.lineTo(v.x(px, pz), v.y(pz, top(u)))
        }
        for (i in n downTo 0) {
            val u = -1.0 + 2.0 * i / n
            val px = x + hx * u * len / 2; val pz = z + hz * u * len / 2
            path.lineTo(v.x(px, pz), v.y(pz, bottom(u)))
        }
        path.close(); return path
    }

    /** A limb or body part: a capsule tapering from width [wa] at (ax, ay) to [wb] at (bx, by). */
    private fun limb(c: Canvas, ax: Float, ay: Float, bx: Float, by: Float, wa: Float, wb: Float, paint: Paint) {
        val dx = bx - ax; val dy = by - ay
        val l = kotlin.math.hypot(dx, dy)
        if (l < 0.01f) { c.drawCircle(ax, ay, max(wa, wb) / 2, paint); return }
        val nx = -dy / l; val ny = dx / l
        val path = Path()
        path.moveTo(ax + nx * wa / 2, ay + ny * wa / 2)
        path.lineTo(bx + nx * wb / 2, by + ny * wb / 2)
        path.lineTo(bx - nx * wb / 2, by - ny * wb / 2)
        path.lineTo(ax - nx * wa / 2, ay - ny * wa / 2)
        path.close()
        c.drawPath(path, paint)
        c.drawCircle(ax, ay, wa / 2, paint); c.drawCircle(bx, by, wb / 2, paint)
    }
    /** Where the elbow (or knee) goes between [a] and [b] for a limb of two parts each [seg] long,
     *  bending towards side [bend] (+1 / -1 relative to the direction a -> b). */
    private fun joint(ax: Float, ay: Float, bx: Float, by: Float, seg: Float, bend: Float): Pair<Float, Float> {
        val dx = bx - ax; val dy = by - ay
        val l = kotlin.math.hypot(dx, dy).coerceAtLeast(0.001f)
        val mx = (ax + bx) / 2; val my = (ay + by) / 2
        val off = kotlin.math.sqrt(max(0f, seg * seg - (l / 2) * (l / 2)))
        return Pair(mx - dy / l * off * bend, my + dx / l * off * bend)
    }
    /** A head in a helmet (or with hair, if [helmet] is 0), facing [face] (-1 left, +1 right). */
    private fun head(c: Canvas, x: Float, y: Float, r: Float, face: Float, skin: Int, helmet: Int, hair: Int) {
        c.drawCircle(x, y, r, p(skin))
        if (helmet != 0) {
            c.drawOval(RectF(x - r * 1.12f, y - r * 1.2f, x + r * 1.12f, y + r * 0.15f), p(helmet))
            c.drawRect(x - r * 1.15f + face * r * 0.2f, y - r * 0.05f, x + r * 1.15f + face * r * 0.2f, y + r * 0.12f, p(mix(helmet, 0xFF000000.toInt(), 0.25)))
        } else {
            // hair: over the top and the back of the head
            c.drawOval(RectF(x - r * 1.05f - face * r * 0.15f, y - r * 1.1f, x + r * 1.05f - face * r * 0.15f, y + r * 0.05f), p(hair))
            c.drawCircle(x - face * r * 0.55f, y + r * 0.1f, r * 0.55f, p(hair))
        }
    }

    private const val KAYAK_L = 5.2      // a sea touring kayak: long and narrow
    private fun kayakW(u: Double) = 0.29 * (1 - u * u).coerceAtLeast(0.0).pow(0.75) * (1 - 0.12 * u)
    private fun boardW(u: Double) = if (u >= 0) 0.27 * (1 - u.pow(2.2)).coerceAtLeast(0.0).pow(0.6)
        else 0.27 * (0.38 + 0.62 * (1 - (-u).pow(3.0)).coerceAtLeast(0.0).pow(0.5))

    /** What the paddler is doing with the paddle. */
    private enum class Stroke { PADDLE, SPRINT, RUDDER, BRACE, REST }
    /** The pieces a sea-kayak surf run is made of. */
    private enum class RunLeg { STRAIGHT, DIAG, STURN }
    /** How a run finishes: pull off over the back, ride the whitewater in and spin out, broach and
     *  side-surf in on a good brace, brace on the wrong side and roll up, or brace wrong and swim. */
    private enum class RunEnd { PULL, KICK, SIDE, ROLL, SWIM }

    /** Three boats, three colours: hull, darker side, deck line, buoyancy aid. */
    private val KAYAK_COLOURS = listOf(
        intArrayOf(0xFFFFB000.toInt(), 0xFFC97F00.toInt(), 0xFFFFD45C.toInt(), 0xFFD8452F.toInt()),   // yellow
        intArrayOf(0xFF1E6FD9.toInt(), 0xFF14498F.toInt(), 0xFF7FB2F0.toInt(), 0xFF2FA84F.toInt()),   // blue, paddler in green
        intArrayOf(0xFFD93A2B.toInt(), 0xFF8F2219.toInt(), 0xFFF29B8F.toInt(), 0xFF2E7BD6.toInt()),   // red
    )

    /**
     * A sea kayak and paddler. [side] picks which side a rudder or brace goes on (+1 = the
     * paddler's left as they face [hx],[hz]). [lean] tips the paddler that way, for bracing.
     */
    private fun seaKayak(c: Canvas, v: View3, d: Float, t: Float, x: Double, z: Double, hx: Double, hz: Double,
                         bob: Double, day: Double, moving: Boolean, colour: Int = 0, stroke: Stroke = Stroke.PADDLE,
                         side: Double = 1.0, foam: Double = 0.0, phase: Double = 0.0, roll: Double = 0.0, pitch: Double = 0.0,
                         empty: Boolean = false) {
        val col = KAYAK_COLOURS[colour]
        val hull = shadeDay(col[0], day)
        val hullDark = shadeDay(col[1], day)
        val deck = shadeDay(col[2], day)
        val nx = -hz; val nz = hx
        val px = { u: Double -> Pair(x + hx * u * KAYAK_L / 2, z + hz * u * KAYAK_L / 2) }   // along the centreline
        // bow and stern sweep up a little; the whole boat tilts when it's end-on to a wave
        val sheer = { u: Double -> bob + pitch * u / 2 + 0.2 + 0.1 * u * u }
        val keelAt = { u: Double -> bob + pitch * u / 2 }
        val capsized = roll > PI / 2
        fun dpt(u: Double, sd: Double, hh: Double): Pair<Float, Float> {
            val px = x + hx * u * KAYAK_L / 2 + nx * sd; val pz = z + hz * u * KAYAK_L / 2 + nz * sd
            return Pair(v.x(px, pz), v.y(pz, hh))
        }
        fun drawHull() {
            val kk = v.size(1.0, z)
            // its shadow on the water, then the hull side, the deck, and a highlight along the near edge
            if (bob > -0.5 && foam < 0.9) c.drawPath(outline(v, x, z, hx, hz, KAYAK_L * 1.02, { u -> kayakW(u) * 1.25 }) { keelAt(it) - 0.02 },
                p(alpha(0xFF0A2230.toInt(), 0.28)))
            c.drawPath(side(v, x, z, hx, hz, KAYAK_L, sheer, keelAt), p(hullDark))
            c.drawPath(outline(v, x, z, hx, hz, KAYAK_L, ::kayakW, sheer), p(if (capsized) hullDark else hull))
            if (capsized) { c.drawPath(outline(v, x, z, hx, hz, KAYAK_L, { u -> kayakW(u) * 0.08 }, sheer), p(hull)); return }   // keel
            c.drawPath(outline(v, x, z, hx, hz, KAYAK_L * 0.97, { u -> kayakW(u) * 0.55 }) { sheer(it) + 0.005 }, p(mix(hull, 0xFFFFFFFF.toInt(), 0.18)))
            c.drawPath(outline(v, x, z, hx, hz, KAYAK_L, { u -> kayakW(u) * 0.1 }) { sheer(it) + 0.01 }, p(deck))    // the deck seam
            if (kk < 6f * d) return                         // too far away for the fittings to show
            val ink = shadeDay(0xFF15181C.toInt(), day)
            // deck lines and elastics fore and aft
            val line = stroke(alpha(ink, 0.75), max(0.6f * d, kk * 0.012f))
            for (u in listOf(0.30, 0.40, 0.50, -0.42, -0.52)) {
                val w = kayakW(u) * 0.85
                val a = dpt(u, w, sheer(u) + 0.01); val b = dpt(u + 0.04, -w, sheer(u + 0.04) + 0.01)
                c.drawLine(a.first, a.second, b.first, b.second, line)
            }
            // hatches: a round day hatch ahead of the cockpit, an oval one behind
            for ((u, r) in listOf(0.62 to 0.075, -0.62 to 0.1)) {
                c.drawPath(outline(v, x + hx * u * KAYAK_L / 2, z + hz * u * KAYAK_L / 2, hx, hz, r * 2.4,
                    { uu -> 0.11 * kotlin.math.sqrt(max(0.0, 1 - uu * uu)) }) { sheer(u) + 0.015 }, p(ink))
                c.drawPath(outline(v, x + hx * u * KAYAK_L / 2, z + hz * u * KAYAK_L / 2, hx, hz, r * 2.0,
                    { uu -> 0.085 * kotlin.math.sqrt(max(0.0, 1 - uu * uu)) }) { sheer(u) + 0.02 }, p(deck))
            }
            // the cockpit rim
            c.drawPath(outline(v, x - hx * 0.25, z - hz * 0.25, hx, hz, 0.85,
                { uu -> 0.2 * kotlin.math.sqrt(max(0.0, 1 - uu * uu)) }) { sheer(-0.1) + 0.03 }, p(ink))
            // carry toggles at the ends
            for (u in listOf(1.0, -1.0)) {
                val e = dpt(u * 1.02, 0.0, sheer(u) - 0.03)
                c.drawCircle(e.first, e.second, max(0.5f * d, kk * 0.022f), stroke(ink, max(0.4f * d, kk * 0.01f)))
            }
        }
        // The camera looks along +z, so anything further away than the hull's centreline is behind it
        fun behind(pz: Double, at: Double = z) = pz > at + 0.12
        // Whitewater piling up against the hull when side-surfing or punching through a wave
        fun drawFoam(farSide: Boolean) {
            if (foam <= 0.05) return
            // a churning, ragged pile of whitewater along that side of the hull
            val (mx, mz) = px(0.0)
            if (behind(mz + nz * side * 0.45, mz) != farSide) return
            val top = Path(); val n = 14
            val a = min(foam, 1.0)
            for (m in 0..n) {
                val u = -0.95 + 1.9 * m / n
                val (fx, fz) = px(u)
                val ox = fx + nx * side * 0.42; val oz = fz + nz * side * 0.42
                val rag = 0.6 + 0.5 * vnoise(u * 3.0 + x, t * 2.2, 5.0) + 0.2 * vnoise(u * 9.0, t * 4.0, 1.0)
                val hgt = (0.12 + 0.38 * a * rag) * (1 - u * u * 0.6)
                if (m == 0) top.moveTo(v.x(ox, oz), v.y(oz, bob + hgt)) else top.lineTo(v.x(ox, oz), v.y(oz, bob + hgt))
            }
            for (m in n downTo 0) {
                val u = -0.95 + 1.9 * m / n
                val (fx, fz) = px(u)
                val ox = fx + nx * side * 0.55; val oz = fz + nz * side * 0.55
                top.lineTo(v.x(ox, oz), v.y(oz, bob - 0.05))
            }
            top.close()
            c.drawPath(top, p(alpha(mix(0xFFC9D8DF.toInt(), 0xFFFFFFFF.toInt(), day), 0.9 * a)))
        }
        if (capsized) {
            drawFoam(true); drawHull(); drawFoam(false)
            // Upside down: just the hull and a blade sweeping at the surface for the roll
            val cx = x - hx * 0.25; val cz = z - hz * 0.25
            val bx = cx + nx * side * 1.3 * cos(t * 3.0); val bz = cz + nz * side * 1.3 * cos(t * 3.0)
            if (!empty && !behind(bz, cz)) c.drawLine(v.x(bx, bz), v.y(bz, bob + 0.05), v.x(bx + hx * 0.4, bz + hz * 0.4), v.y(bz + hz * 0.4, bob + 0.05),
                stroke(shadeDay(0xFF1F4E6B.toInt(), day), max(2f * d, v.size(0.14, cz))))
            return
        }
        if (empty) { drawFoam(true); drawHull(); drawFoam(false); return }
        // Paddler, just behind the middle (leaning over as they roll or brace)
        val lean = if (roll > 0) 0.9 * sin(roll) * side else if (stroke == Stroke.BRACE) 0.3 * side else 0.0
        val cx = x - hx * 0.25; val cz = z - hz * 0.25
        val k = v.size(1.0, cz)
        val body = shadeDay(col[3], day)
        val skin = shadeDay(0xFFF1C27D.toInt(), day)
        val ink = shadeDay(0xFF2A2A2A.toInt(), day)
        val bladeCol = shadeDay(0xFF1F4E6B.toInt(), day)
        val hipX = v.x(cx, cz); val hipY = v.y(cz, bob + 0.22)
        val sx = cx + nx * lean; val sz = cz + nz * lean
        val shX = v.x(sx, sz); val shY = v.y(sz, bob + 0.75)
        // The paddle: two ends (x, z, height); the blade is drawn on each end
        val ends: List<Triple<Double, Double, Double>> = when (stroke) {
            Stroke.PADDLE, Stroke.SPRINT -> highAngle(cx, cz, hx, hz, bob, phase + t * (if (stroke == Stroke.SPRINT) 4.4 else 2.4))
            // Stern rudder: blade trailing in the water behind, on one side, steering
            Stroke.RUDDER -> listOf(Triple(cx - hx * 1.5 + nx * side * 0.75, cz - hz * 1.5 + nz * side * 0.75, bob + 0.02),
                Triple(cx + hx * 0.5 - nx * side * 0.3, cz + hz * 0.5 - nz * side * 0.3, bob + 0.95))
            // Low brace: blade flat on the water on the wave side, paddler leaning onto it
            Stroke.BRACE -> listOf(Triple(cx + nx * side * 1.4, cz + nz * side * 1.4, bob + 0.03),
                Triple(cx - nx * side * 0.7, cz - nz * side * 0.7, bob + 0.55))
            // Resting across the deck, waiting
            Stroke.REST -> listOf(Triple(cx + nx * 1.05 + hx * 0.3, cz + nz * 1.05 + hz * 0.3, bob + 0.4),
                Triple(cx - nx * 1.05 + hx * 0.3, cz - nz * 1.05 + hz * 0.3, bob + 0.4))
        }
        val (a, b) = ends
        fun lerp3(p: Triple<Double, Double, Double>, q: Triple<Double, Double, Double>, f: Double) =
            Triple(p.first + (q.first - p.first) * f, p.second + (q.second - p.second) * f, p.third + (q.third - p.third) * f)
        fun along(f: Double) = lerp3(a, b, f)
        /** A piece of paddle from [p] to [q], with anything under the water cut off (it's hidden by the sea). */
        fun seg(p: Triple<Double, Double, Double>, q: Triple<Double, Double, Double>, paint: Paint) {
            var p1 = p; var q1 = q
            val wl = bob + 0.02
            if (p1.third < wl && q1.third < wl) return
            if (p1.third < wl) p1 = lerp3(p1, q1, (wl - p1.third) / (q1.third - p1.third))
            if (q1.third < wl) q1 = lerp3(q1, p1, (wl - q1.third) / (p1.third - q1.third))
            c.drawLine(v.x(p1.first, p1.second), v.y(p1.second, p1.third), v.x(q1.first, q1.second), v.y(q1.second, q1.third), paint)
        }
        val shaftP = stroke(ink, max(1f * d, k * 0.04f))
        val bladeP = stroke(bladeCol, max(2f * d, k * 0.15f))
        val deckH = bob + 0.32
        /** Where the shaft from end [e] towards the other end [o] comes up past deck height. */
        fun rise(e: Triple<Double, Double, Double>, o: Triple<Double, Double, Double>) =
            if (e.third >= deckH || o.third <= e.third) e else lerp3(e, o, ((deckH - e.third) / (o.third - e.third)).coerceIn(0.0, 1.0))
        fun bladeOf(e: Triple<Double, Double, Double>, o: Triple<Double, Double, Double>) = lerp3(o, e, 0.82)
        val splashing = moving || stroke == Stroke.BRACE || stroke == Stroke.RUDDER
        val farEnds = ends.filter { behind(it.second, cz) && it.third < deckH }
        // 1. what's behind the boat: the far blade and the low part of its shaft, and foam on that side.
        //    The hull is drawn over them; no splash is shown there, it's hidden by the boat.
        drawFoam(true)
        for (e in farEnds) {
            val o = if (e === a) b else a
            seg(e, rise(e, o), shaftP)
            seg(bladeOf(e, o), e, bladeP)
        }
        // 2. the boat
        drawHull()
        drawFoam(false)
        // 3. the paddler: spraydeck round the waist, cag, buoyancy aid, arms bent to the shaft, helmet
        val cag = shadeDay(0xFF2E3D52.toInt(), day)
        val minW = 1.2f * d
        c.drawOval(RectF(hipX - k * 0.24f, hipY - k * 0.06f, hipX + k * 0.24f, hipY + k * 0.07f), p(shadeDay(0xFF1C1F24.toInt(), day)))
        val faceDir = if (v.x(x + hx, z + hz) >= v.x(x, z)) 1f else -1f
        val hands = listOf(0.3, 0.7).map { along(it) }.map { Pair(v.x(it.first, it.second), v.y(it.second, it.third)) }
        // the far arm goes behind the body
        val armOrder = hands.sortedBy { kotlin.math.abs(it.first - shX) }
        fun arm(hx0: Float, hy0: Float) {
            val sx2 = shX; val sy2 = shY + k * 0.06f
            // arms are a fixed length: never stretched to reach something
            val full = kotlin.math.hypot(hx0 - sx2, hy0 - sy2)
            val maxR = k * 0.72f
            val fr = if (full > maxR) maxR / full else 1f
            val hx2 = sx2 + (hx0 - sx2) * fr; val hy2 = sy2 + (hy0 - sy2) * fr
            val reach = kotlin.math.hypot(hx2 - sx2, hy2 - sy2)
            val seg = max(reach / 2 + 0.01f, k * 0.36f)
            val (ex, ey) = joint(sx2, sy2, hx2, hy2, seg, if (hx2 > sx2) 1f else -1f)
            limb(c, sx2, sy2, ex, ey, max(minW, k * 0.12f), max(minW, k * 0.1f), p(cag))
            limb(c, ex, ey, hx2, hy2, max(minW, k * 0.1f), max(minW * 0.8f, k * 0.08f), p(cag))
            c.drawCircle(hx2, hy2, max(0.8f * d, k * 0.05f), p(skin))
        }
        arm(armOrder[1].first, armOrder[1].second)
        limb(c, hipX, hipY, shX, shY, max(2f * d, k * 0.3f), max(2f * d, k * 0.36f), p(cag))
        limb(c, hipX + (shX - hipX) * 0.25f, hipY + (shY - hipY) * 0.25f, shX + (hipX - shX) * 0.08f, shY + (hipY - shY) * 0.08f,
            max(2.2f * d, k * 0.36f), max(2.2f * d, k * 0.4f), p(body))                                    // buoyancy aid
        arm(armOrder[0].first, armOrder[0].second)
        val helmetCol = shadeDay(if (colour == 1) 0xFFF2F2F2.toInt() else if (colour == 2) 0xFF2A2A2A.toInt() else 0xFFE53935.toInt(), day)
        head(c, shX + faceDir * k * 0.02f, v.y(sz, bob + 0.93), max(1.5f * d, k * 0.11f), faceDir, skin, helmetCol, 0)
        // 4. the rest of the paddle over the top: the shaft from above the deck, and the near blade with its splash
        val aa = if (farEnds.any { it === a }) rise(a, b) else a
        val bb = if (farEnds.any { it === b }) rise(b, a) else b
        seg(aa, bb, shaftP)
        for ((e, o) in listOf(a to b, b to a)) {
            if (farEnds.any { it === e }) continue
            seg(bladeOf(e, o), e, bladeP)
            if (e.third < bob + 0.15 && splashing)
                c.drawCircle(v.x(e.first, e.second), v.y(e.second, bob), max(1.5f * d, k * 0.13f), p(alpha(0xFFFFFFFF.toInt(), 0.75)))
        }
    }

    /**
     * The sea kayaker's high-angle forward stroke: the shaft close to upright with the top hand
     * at about eye height; the blade goes in right by the feet, close to the hull, is pulled back
     * fully buried to the hip with the torso unwinding, comes out, and the other blade goes in
     * on the other side. Returns the two paddle ends (x, z, height), the working blade first.
     */
    private fun highAngle(cx: Double, cz: Double, hx: Double, hz: Double, bob: Double, ph: Double): List<Triple<Double, Double, Double>> {
        val nx = -hz; val nz = hx
        val half = ph / PI
        val k = kotlin.math.floor(half).toLong()
        val u = half - k                                     // 0..1 through one side's stroke
        val sd = if (k % 2 == 0L) 1.0 else -1.0             // which side is working
        fun pose(side: Double, w: Double): Pair<Triple<Double, Double, Double>, Triple<Double, Double, Double>> {
            // w: 0 = the catch at the feet .. 1 = the exit at the hip
            val reach = 1.0 - 1.35 * w
            val depth = -0.25 * sin(PI * w)                  // buried during the pull
            val low = Triple(cx + nx * side * 0.55 + hx * reach, cz + nz * side * 0.55 + hz * reach, bob + 0.05 + depth)
            val high = Triple(cx - nx * side * 0.3 + hx * (0.35 + reach * 0.3), cz - nz * side * 0.3 + hz * (0.35 + reach * 0.3), bob + 1.6)
            return Pair(low, high)
        }
        fun lerp(p: Triple<Double, Double, Double>, q: Triple<Double, Double, Double>, f: Double) =
            Triple(p.first + (q.first - p.first) * f, p.second + (q.second - p.second) * f, p.third + (q.third - p.third) * f)
        return if (u < 0.78) {
            val (low, high) = pose(sd, u / 0.78)
            listOf(low, high)
        } else {
            // recovery: the working blade lifts out and rises; the top blade comes down to the next catch
            val f = smooth((u - 0.78) / 0.22)
            val (lowEnd, highEnd) = pose(sd, 1.0)
            val (nextLow, nextHigh) = pose(-sd, 0.0)
            listOf(lerp(highEnd, nextLow, f), lerp(lowEnd, nextHigh, f))
        }
    }

    /** What a group of sea kayaks gets up to on a GO day. */
    private enum class Act { TRIP, SURF, RAFT, ROLL, CRUISE }

    private class Seg(val act: Act, val start: Double, val len: Double, val who: Int, val seed: Int, val dir: Double)

    /**
     * The day's plan, made up as it goes: a string of activities of random length, with a
     * random choice of who joins in. Nothing repeats in a fixed pattern. Every activity
     * starts and finishes with the boats at their spots just beyond the break, so one
     * flows into the next; and segments start on a wave so the surfing lines up with the swell.
     */
    private fun kayakPlan(t: Double, tv: Double, surfable: Boolean, daySeed: Int, surfUp: Boolean = false, surfOnly: Boolean = false): Seg {
        var start = 0.0
        var prev: Act? = null
        var n = 0
        while (n < 100000) {
            fun r(j: Int) = hash(daySeed * 7919 + n * 131 + j * 17)
            var act = if (n == 0) (if (surfable) Act.SURF else Act.CRUISE) else {
                val x = r(1)
                if (surfOnly) Act.SURF                          // they've come out for the surf and nothing else
                else if (surfable && surfUp) when {             // a go day with real surf: mostly surfing
                    x < 0.65 -> Act.SURF; x < 0.72 -> Act.TRIP; x < 0.84 -> Act.CRUISE; x < 0.93 -> Act.RAFT; else -> Act.ROLL
                } else if (surfable) when {
                    x < 0.40 -> Act.SURF; x < 0.52 -> Act.TRIP; x < 0.72 -> Act.CRUISE; x < 0.86 -> Act.RAFT; else -> Act.ROLL
                } else when {
                    x < 0.25 -> Act.TRIP; x < 0.62 -> Act.CRUISE; x < 0.82 -> Act.RAFT; else -> Act.ROLL
                }
            }
            if (act == prev && !surfOnly) act = if (act == Act.SURF) Act.CRUISE else if (surfable) Act.SURF else Act.RAFT
            val secs = when (act) {
                Act.SURF -> 100.0 + 140.0 * r(2)
                Act.TRIP -> 90.0 + 150.0 * r(2)
                Act.CRUISE -> 55.0 + 50.0 * r(2)
                Act.RAFT -> 35.0 + 45.0 * r(2)
                Act.ROLL -> 26.0 + 24.0 * r(2)
            }
            val len = kotlin.math.ceil(secs / tv) * tv
            // Who joins in (bit i = kayak i): usually everyone, sometimes two, one for rolling practice
            val who = when (act) {
                Act.ROLL -> 1 shl (r(3) * 3).toInt().coerceIn(0, 2)
                Act.RAFT -> if (r(3) < 0.6) 7 else intArrayOf(3, 5, 6)[(r(4) * 3).toInt().coerceIn(0, 2)]
                Act.TRIP -> if (r(3) < 0.35) 7 else intArrayOf(3, 5, 6, 1, 2, 4)[(r(4) * 6).toInt().coerceIn(0, 5)]   // someone often stays behind
                else -> if (r(3) < 0.65) 7 else intArrayOf(3, 5, 6, 1, 2, 4)[(r(4) * 6).toInt().coerceIn(0, 5)]
            }
            if (t < start + len) return Seg(act, start, len, who, daySeed * 31 + n, if (r(5) < 0.5) -1.0 else 1.0)
            start += len; prev = act; n++
        }
        return Seg(Act.RAFT, start, 60.0, 7, 0, 1.0)
    }

    /**
     * Paddle call GO: three sea kayaks (yellow, blue, red). Depending on the plan they go off
     * on a trip out of sight, cruise along the coast across the view, raft up for a chat,
     * practise rolling, or, with a swell running, surf it out past the break, as sea
     * kayakers do:
     *  - wait beyond the breakers, bows to the beach, looking back for a good one;
     *  - sprint as the swell lifts the stern, then run in on the unbroken face steering with
     *    a stern rudder, straight or angled left or right;
     *  - pull off over the back before it breaks, turning along the wave and letting it pass;
     *  - now and then a long boat broaches: turned side-on, the paddler leans into the wave on
     *    a low brace and side-surfs the whitewater until it lets go;
     *  - then punch back out bow-first through the waves, paddling hard.
     * Boats sitting an activity out wait at their spot, some drifting, some pottering in circles.
     * Everything is kept inside the visible part of the view, whatever shape the screen is.
     */
    private fun kayakGroup(c: Canvas, v: View3, d: Float, t: Float, s: SceneInput, zs: Double, sw: Swell, day: Double,
                           surfUp: Boolean = false, surfOnly: Boolean = false, onSand: Boolean = false, behindSign: Boolean = false) {
        val breakDepth = sw.breakXMean * 1.3                // where the bigger set waves break
        val surfable = sw.h >= 0.4 && s.periodS >= 5.0      // nobody surfs short wind chop
        // whole wavelengths out, and always beyond the break (rounding down could put the lineup
        // inside it, making the rides run backwards in time and the boats jump about)
        val gaps = max(1.0, kotlin.math.ceil(sw.sAt(max(18.0, breakDepth + 8)) / sw.lambda - 0.05))
        val lineup = sw.xAt(sw.lambda * gaps)              // beyond the break, on a crest's path
        val zL = zs + lineup
        val halfW = (v.w / 2.0) / v.f * zL * 0.85           // half the visible width out there, metres
        val home = doubleArrayOf(-0.5 * halfW, 0.05 * halfW, 0.55 * halfW)
        val daySeed = (s.moonPhase * 1000).toInt()          // changes from day to day
        val seg = kayakPlan(t.toDouble(), sw.tv, surfable, daySeed, surfUp, surfOnly && surfable)
        val tl = t - seg.start                              // time into this activity
        val u = (tl / seg.len).coerceIn(0.0, 1.0)
        val speed = sw.speed

        class Pose(val x: Double, val z: Double, val hx: Double, val hz: Double, val h: Double,
                   val stroke: Stroke = Stroke.PADDLE, val side: Double = 1.0, val foam: Double = 0.0,
                   val moving: Boolean = true, val roll: Double = 0.0, val swimT: Double = -1.0, val run: Any? = null)

        fun bob(x: Double, z: Double) = sw.height(t, x, z)
        /** Sitting it out: drifting at the spot, or pottering round in a slow circle. */
        fun idle(i: Int): Pose {
            val r = hash(seg.seed * 3 + i * 7)
            val x0 = home[i]
            return if (r < 0.45) {
                val a = t * 0.12 * (if (r < 0.22) 1 else -1) + i * 2.0
                val rad = 3.0 * smooth(min(u, 1 - u) / 0.08)      // eases in from, and back to, the spot
                val x = x0 + rad * cos(a); val z = zL + rad * sin(a)
                val (hx, hz) = norm(-sin(a) * (if (r < 0.22) 1 else -1), cos(a) * (if (r < 0.22) 1 else -1))
                Pose(x, z, hx, hz, bob(x, z), Stroke.PADDLE)
            } else {
                val (hx, hz) = norm(if (r < 0.7) 1.0 else -0.4, if (r < 0.7) 0.15 else -1.0)
                Pose(x0, zL, hx, hz, bob(x0, zL), Stroke.REST, moving = false)
            }
        }
        /** Blend a pose towards the boat's own spot near the start and end of an activity. */
        fun settle(i: Int, p: Pose, edge: Double = 0.08): Pose {
            val w = 1 - smooth(min(u, 1 - u) / edge)
            if (w <= 0.001) return p
            val x = p.x + (home[i] - p.x) * w; val z = p.z + (zL - p.z) * w
            return Pose(x, z, p.hx, p.hz, bob(x, z), p.stroke, p.side, p.foam, p.moving, p.roll)
        }
        val members = (0..2).filter { seg.who and (1 shl it) != 0 }
        // ---- surfing ----
        val pullDepth = breakDepth + 3.0
        val rideT = sw.timeBetween(lineup, pullDepth)
        val pullT = 3.0
        fun waiting(i: Int, until: Double): Pose {
            val x = home[i]
            val r = hash(seg.seed * 5 + i)
            // Facing the beach and looking back for a wave; between sets some turn side-on to chat
            val (hx, hz) = if (until > 14 && r < 0.5) norm(if (i == 0) 1.0 else -1.0, 0.1) else norm(0.12, -1.0)
            return Pose(x, zL, hx, hz, bob(x, zL), Stroke.REST, moving = false)
        }
        /** Where a boat riding in from its spot is on the crest at [tc] (eased off the spot at first). */
        fun onCrest(i: Int, tc: Double, x: Double): Double {
            val z = zOnCrest(sw, sw.travel(lineup, tc), x)
            return zL + (z - zL) * smooth(tc / 2.0)
        }
        // ---- a surf run, built from pieces ----
        // Sea kayakers catch the swell well out, sprinting as the stern lifts, then run it: straight
        // down, angled across the face (a diagonal run, steering with a stern rudder), or carving
        // S-turns from one side to the other. Runs that carry on into the whitewater can broach (the
        // boat slews side-on): a quick low brace on the wave side and a strong stern rudder can turn
        // it back straight; or it side-surfs in on the brace; or, brace on the beach side, the wave
        // rolls it: up again with a roll, or, now and then, a swim.
        class RunPart(val type: RunLeg, val t0: Double, val t1: Double, val dir: Double, val per: Double)
        class Run(val parts: List<RunPart>, val tR: Double, val end: RunEnd, val turnAt: Double, val turnSgn: Double,
                  val sgnEnd: Double, val xs: DoubleArray, val scale: Double, val xE: Double, val zE: Double, val after: Double)
        val runDt = 0.1
        val kickT = 2.6; val rollRunT = 3.6
        /** A turned broach: slews side-on fast, held on a brace for a moment, then steered back. */
        fun broachBump(dt: Double) = when {
            dt < 0.0 -> 0.0
            dt < 0.6 -> smooth(dt / 0.6)
            dt < 1.1 -> 1.0
            dt < 2.5 -> 1 - smooth((dt - 1.1) / 1.4)
            else -> 0.0
        }
        /** Sideways speed the paddler is steering for at [tc] (before fitting the run into the view). */
        fun vlatRaw(parts: List<RunPart>, turnAt: Double, turnSgn: Double, tc: Double): Double {
            var vl = 0.0
            for (p in parts) {
                val w = smooth((tc - p.t0) / 0.8) * (1 - smooth((tc - p.t1) / 0.8))
                if (w <= 0) continue
                vl += w * when (p.type) {
                    RunLeg.STRAIGHT -> 0.0
                    RunLeg.DIAG -> p.dir * 0.7 * speed
                    RunLeg.STURN -> p.dir * 0.6 * speed * sin(2 * PI * (tc - p.t0) / p.per)
                }
            }
            if (turnAt >= 0) vl += turnSgn * 0.9 * broachBump(tc - turnAt)       // slipping sideways while it's broached
            return vl
        }
        fun crestZ(tc: Double, x: Double): Double { val z = zOnCrest(sw, sw.travel(lineup, tc), x); return zL + (z - zL) * smooth(tc / 2.0) }
        fun halfAt(z: Double) = (v.w / 2.0) / v.f * z * 0.85
        fun runX(r: Run, tc: Double): Double {
            val f = (tc / runDt).coerceIn(0.0, (r.xs.size - 1).toDouble())
            val k = f.toInt().coerceAtMost(r.xs.size - 2); val g = f - k
            return r.xs[k] + (r.xs[k + 1] - r.xs[k]) * g
        }
        /** Heading as an angle: 0 = straight at the beach, +90° = side-on heading right. */
        fun runTheta(r: Run, tc: Double): Double {
            val vl = r.scale * vlatRaw(r.parts, r.turnAt, r.turnSgn, tc)
            val base = kotlin.math.atan2(vl, speed * 0.9)
            return base + (if (r.turnAt >= 0) r.turnSgn * 1.3 * broachBump(tc - r.turnAt) else 0.0)
        }
        fun hOf(theta: Double) = Pair(sin(theta), -cos(theta))
        fun outTime(sx: Double, sz: Double, i: Int, rate: Double) = max(2.0, kotlin.math.hypot(home[i] - sx, zL - sz) / rate)
        /** Where the ending leaves the boat, and how long it takes to get back out. */
        fun endPoint(r: Run): Triple<Double, Double, Double> = when (r.end) {
            RunEnd.PULL -> Triple(r.xE + r.sgnEnd * 2.0, r.zE + 2.5, pullT)
            RunEnd.KICK -> Triple(r.xE + r.sgnEnd * 1.2, max(zs + 1.2, r.zE - 0.5), kickT)
            RunEnd.SIDE -> Triple(r.xE + r.sgnEnd * 1.5, zs + 1.5, max(2.5, (r.zE - zs - 1.5) / 1.1))
            RunEnd.ROLL -> Triple(r.xE + r.sgnEnd * 0.8, max(zs + 1.5, r.zE - 0.9 * rollRunT), rollRunT)
            RunEnd.SWIM -> Triple(r.xE, r.zE, 0.0)
        }
        fun makeRun(i: Int, rr: (Int) -> Double, forceSwim: Boolean, noSwim: Boolean = false): Run {
            // How it ends decides how far in it goes. One run in six goes wrong: a brace on the beach
            // side, over, out and a swim in to empty the boat and start again. The rest share out
            // the other endings (bigger surf: more broaches and rolls).
            val x = rr(2)
            val big = sw.h > 1.2
            val end = when {
                forceSwim -> RunEnd.SWIM
                !noSwim && x < SWIM_P -> RunEnd.SWIM
                else -> {
                    val y = if (noSwim) x else (x - SWIM_P) / (1 - SWIM_P)
                    when {
                        y < (if (big) 0.24 else 0.32) -> RunEnd.PULL
                        y < (if (big) 0.50 else 0.62) -> RunEnd.KICK
                        y < (if (big) 0.86 else 0.90) -> RunEnd.SIDE
                        else -> RunEnd.ROLL
                    }
                }
            }
            val inner = max(1.8, breakDepth * 0.3)
            val endDepth = when (end) {
                RunEnd.PULL -> pullDepth
                RunEnd.KICK -> inner
                else -> max(inner + 0.5, breakDepth * (0.45 + 0.4 * rr(7)))
            }
            val tR = max(3.0, sw.timeBetween(lineup, endDepth))
            val tBreak = sw.timeBetween(lineup, breakDepth)
            // the pieces: a sprint to catch it, then a mix of straight, diagonal and S-turns
            val parts = mutableListOf<RunPart>()
            val tCatch = 1.6 + 0.8 * rr(8)
            parts += RunPart(RunLeg.STRAIGHT, -1.0, tCatch, 0.0, 4.0)
            var t0 = tCatch; var k = 0
            val roomDir = if (home[i] > 0.15 * halfW) -1.0 else if (home[i] < -0.15 * halfW) 1.0 else (if (rr(9) < 0.5) -1.0 else 1.0)
            while (t0 < tR - 0.5 && k < 4) {
                val q = rr(10 + k)
                val type = when { q < 0.42 -> RunLeg.DIAG; q < 0.75 -> RunLeg.STURN; else -> RunLeg.STRAIGHT }
                val len = (2.5 + 4.0 * rr(20 + k)).coerceAtMost(tR - t0 + 1.0)
                val dir = if (type == RunLeg.DIAG) (if (sw.peel != 0.0 && rr(30 + k) < 0.55) sw.peel else roomDir) * (if (k > 0 && rr(40 + k) < 0.35) -1.0 else 1.0)
                          else if (rr(30 + k) < 0.5) -1.0 else 1.0
                parts += RunPart(type, t0, t0 + len, dir, 3.4 + 1.8 * rr(50 + k))
                t0 += len; k++
            }
            // a broach that's turned, part way through the whitewater, on some of the longer runs in
            val turnAt = if (end != RunEnd.PULL && tR - tBreak > 4.0 && rr(60) < 0.4) tBreak + 0.5 + (tR - tBreak - 3.5) * rr(61) else -1.0
            val turnSgn = if (rr(62) < 0.5) -1.0 else 1.0
            // fit it in the view: scale the sideways moves so the boat stays on screen all the way in
            val n = (tR / runDt).toInt() + 2
            val raw = DoubleArray(n)
            for (m in 1 until n) raw[m] = raw[m - 1] + runDt * vlatRaw(parts, turnAt, turnSgn, (m - 0.5) * runDt)
            var scale = 1.0
            for (m in 0 until n) {
                val lim = halfAt(crestZ(m * runDt, home[i]))
                val off = raw[m]
                if (abs(home[i] + off) > lim && abs(off) > 1e-6) scale = min(scale, max(0.0, (lim * sign(off) - home[i]) / off))
            }
            val xs = DoubleArray(n) { home[i] + scale * raw[it] }
            val tmp = Run(parts, tR, end, turnAt, turnSgn, 1.0, xs, scale, 0.0, 0.0, 0.0)
            val xE = runX(tmp, tR); val zE = crestZ(tR, xE)
            val thE = runTheta(tmp, tR)
            // turn off the way it's pointing (or, near an edge, back towards the middle)
            var sgnEnd = if (abs(thE) > 0.1) sign(thE) else if (rr(63) < 0.5) -1.0 else 1.0
            if (abs(xE) > 0.6 * halfAt(zE)) sgnEnd = -sign(xE)
            val r0 = Run(parts, tR, end, turnAt, turnSgn, sgnEnd, xs, scale, xE, zE, 0.0)
            val after = if (end == RunEnd.SWIM) 0.0 else {
                val (ex, ez, dur) = endPoint(r0)
                dur + outTime(ex, ez, i, if (end == RunEnd.PULL) 1.7 else 1.5)
            }
            return Run(parts, tR, end, turnAt, turnSgn, sgnEnd, xs, scale, xE, zE, after)
        }
        fun outPose(i: Int, sx: Double, sz: Double, th0: Double, w: Double, sprint: Boolean): Pose {
            val x = sx + (home[i] - sx) * w
            val z = sz + (zL - sz) * w
            val (tx, tz) = norm(home[i] - sx, zL - sz)
            val (h0x, h0z) = hOf(th0)
            val m = smooth(w / 0.15)
            val (hx, hz) = norm(h0x + (tx - h0x) * m, h0z + (tz - h0z) * m)
            val h = bob(x, z)
            val inBreak = z - zs < breakDepth + 1
            return Pose(x, z, hx, hz, h, if (sprint || inBreak) Stroke.SPRINT else Stroke.PADDLE, 1.0,
                if (inBreak && h > sw.h * 0.4) 0.7 else 0.0)
        }
        fun runPose(i: Int, tc: Double, r: Run): Pose {
            if (tc < r.tR) {
                // On the wave
                val x = runX(r, tc)
                val z = crestZ(tc, x)
                val depth = z - zs
                val face = min(sw.waveHeight(sw.idAt(depth, t.toDouble()), depth), v.camH * 0.92) * 0.55 * smooth(depth / 1.5)
                val th = runTheta(r, tc)
                val (hx, hz) = hOf(th)
                val b = if (r.turnAt >= 0) broachBump(tc - r.turnAt) else 0.0
                val white = depth < breakDepth
                return when {
                    tc < 1.6 -> Pose(x, z, hx, hz, face, Stroke.SPRINT)
                    b > 0.05 && tc - r.turnAt < 1.1 ->      // slewed side-on: low brace on the wave side
                        Pose(x, z, hx, hz, face * 0.6, Stroke.BRACE, if (hx > 0) 1.0 else -1.0, 0.9 * b)
                    else -> {
                        // stern rudder on the side away from where it's turning (and hauling it back after a broach)
                        val turning = r.scale * vlatRaw(r.parts, r.turnAt, r.turnSgn, tc + 0.4) - r.scale * vlatRaw(r.parts, r.turnAt, r.turnSgn, tc)
                        val side = if (b > 0.05) r.turnSgn else if (turning >= 0) -1.0 else 1.0
                        Pose(x, z, hx, hz, face, Stroke.RUDDER, side, if (white) 0.35 + 0.5 * b else 0.0)
                    }
                }
            }
            val te = tc - r.tR
            val thE = runTheta(r, r.tR)
            val sg = r.sgnEnd
            val (ex, ez, dur) = endPoint(r)
            if (te < dur) when (r.end) {
                RunEnd.PULL -> {
                    // Pull off: turn along the wave and up over its back, letting it go through
                    val w = te / dur
                    val th = thE + (sg * 2.2 - thE) * smooth(w)
                    val (hx, hz) = hOf(th)
                    val x = r.xE + sg * 2.0 * w; val z = r.zE + 2.5 * w * w
                    return Pose(x, z, hx, hz, bob(x, z), Stroke.RUDDER, -sg)
                }
                RunEnd.KICK -> {
                    // Ridden right in: spin out of the whitewater on a brace and face back out to sea
                    val w = te / dur
                    val th = thE + sg * PI * smooth(w)
                    val (hx, hz) = hOf(th)
                    val x = r.xE + sg * 1.2 * smooth(w); val z = r.zE + (ez - r.zE) * smooth(w)
                    val st = if (w < 0.5) Stroke.BRACE else Stroke.SPRINT
                    return Pose(x, z, hx, hz, bob(x, z) * 0.6, st, if (hx > 0) 1.0 else -1.0, 0.8 * (1 - w))
                }
                RunEnd.SIDE -> {
                    // Broached and side-surfing the whitewater in on a low brace on the wave side
                    val w = te / dur
                    val th = thE + (sg * PI / 2 - thE) * smooth(w * 4)
                    val (hx, hz) = hOf(th)
                    val x = r.xE + sg * 1.5 * w; val z = r.zE + (ez - r.zE) * w
                    return Pose(x, z, hx, hz, 0.25 * (1 - w), Stroke.BRACE, if (hx > 0) 1.0 else -1.0, 1.0 - 0.6 * w)
                }
                RunEnd.ROLL -> {
                    // Broached with the brace on the beach side: over it goes, a moment upside down, rolled up
                    val th = thE + (sg * PI / 2 - thE) * smooth(te / 0.9)
                    val (hx, hz) = hOf(th)
                    val roll = when {
                        te < 0.9 -> 0.0
                        te < 1.4 -> PI * smooth((te - 0.9) / 0.5)
                        te < 2.6 -> PI
                        else -> PI * (1 - smooth((te - 2.6) / 1.0))
                    }
                    val x = r.xE + sg * 0.8 * smooth(te / dur); val z = max(zs + 1.5, r.zE - 0.9 * te)
                    val beachSide = if (hx > 0) -1.0 else 1.0
                    return Pose(x, z, hx, hz, bob(x, z) * 0.5, Stroke.BRACE, if (te < 2.6) beachSide else -beachSide,
                        0.8, moving = false, roll = roll)
                }
                RunEnd.SWIM -> {}
            }
            // back out to the spot
            val thEnd = when (r.end) {
                RunEnd.PULL -> sg * 2.2
                RunEnd.KICK -> thE + sg * PI
                else -> sg * PI / 2
            }
            val outT = r.after - dur
            val w = ((te - dur) / outT).coerceIn(0.0, 1.0)
            return outPose(i, ex, ez, thEnd, w, r.end != RunEnd.PULL)
        }
        /** One wave a boat goes for. [hits] = the boat it runs into on a shared wave, if any. */
        class RidePlan(val tn: Double, val dir: Double, val total: Double, val hits: Int? = null, val run: Run? = null) {
            val swim: Boolean get() = run?.end == RunEnd.SWIM
        }
        val collideAt = 0.55 * rideT                       // seconds into a shared ride when they bump
        val rollT = 3.4                                     // knocked over, upside down, rolled back up
        /** Where a boat that steers into boat [j] ends up after its roll. */
        fun collideEnd(i: Int, j: Int, xj: Double): Pair<Double, Double> {
            val side = if (home[i] < home[j]) -1.0 else 1.0
            val xc = xj + side * 1.6
            val z0 = zOnCrest(sw, sw.travel(lineup, collideAt), xc)
            val zc = zL + (z0 - zL) * smooth(collideAt / 2.0)
            return Pair(xc + side * 0.5, max(zs + 1.5, zc - 0.9 * rollT))
        }
        fun collideLength(i: Int, j: Int, xj: Double): Double {
            val (sx, sz) = collideEnd(i, j, xj)
            return collideAt + rollT + max(3.0, kotlin.math.hypot(home[i] - sx, zL - sz) / 1.5)
        }
        // ---- a swim: braced on the wrong side, capsized, swum in, emptied out on the sand, relaunched ----
        // What it looks like (sea kayak surf-zone practice): the boat broaches, the paddler leans on
        // the beach side, the wave rolls the boat over towards the beach; a roll attempt fails and they
        // pull the spraydeck and come out. They keep hold of the paddle and the boat's seaward end,
        // never getting between the boat and the beach, and let the whitewater carry them in. In the
        // shallows they stand up and drag the boat up the sand, turn it side-on, lift the bow and
        // see-saw it to pour the water out of the cockpit, flip it upright, walk it back into the
        // shallows bow to sea, get in, fit the deck, wait for a lull and sprint out through the break.
        fun wadeDepth(z: Double) = ((z - zs) * 0.45).coerceIn(0.0, 0.5)
        val swTurn = 0.9; val swOver = 0.6; val swUnder = 1.8
        val swWalk = 2.5; val swDrag = 4.5; val swEmpty = 8.0; val swFlip = 3.0; val swCarry = 5.0; val swSeat = 4.0
        val shoreHalf = (v.w / 2.0) / v.f * zs * 0.7
        class SwimGeo(val sgn: Double, val x0: Double, val z0: Double, val zc: Double, val swimT: Double, val xe: Double, val outT: Double)
        fun swimGeo(i: Int, r: Run): SwimGeo {
            val sgn = r.sgnEnd
            val x0 = r.xE
            val z0 = r.zE
            val zc = max(zs + 1.5, z0 - 1.0)
            val swimT = max(6.0, (zc - zs - 0.35) / 0.55)
            val xe = (x0 + sgn * 2.0).coerceIn(-0.05 * shoreHalf, shoreHalf)   // clear of the sign on the left
            val outT = kotlin.math.hypot(home[i] - xe, lineup - 0.6) / 1.5
            return SwimGeo(sgn, x0, z0, zc, swimT, xe, outT)
        }
        fun swimLength(i: Int, r: Run): Double {
            val g = swimGeo(i, r)
            return r.tR + swTurn + swOver + swUnder + g.swimT + swWalk + swDrag + swEmpty + swFlip + swCarry + swSeat + g.outT
        }
        fun swimScene(i: Int, tc0: Double, r: Run, onSand: Boolean) {
            val g = swimGeo(i, r)
            val sgn = g.sgn
            val vest = KAYAK_COLOURS[i][3]
            val items = mutableListOf<Triple<Double, Double, () -> Unit>>()
            fun boat(x: Double, z: Double, hx: Double, hz: Double, h: Double, stroke: Stroke = Stroke.REST, side: Double = 1.0,
                     foam: Double = 0.0, moving: Boolean = false, roll: Double = 0.0, pitch: Double = 0.0, empty: Boolean = true) {
                if (!onSand) poseLog?.invoke(i, x, z, "SWIM/" + stroke.name)
                items += Triple(x, z) { seaKayak(c, v, d, t, x, z, hx, hz, h, day, moving, colour = i, stroke = stroke, side = side,
                    foam = foam, phase = i * 2.1, roll = roll, pitch = pitch, empty = empty) }
            }
            var phaseName = ""
            fun fig(x: Double, z: Double, draw: () -> Unit) {
                if (!onSand) poseLog?.invoke(i + 10, x, z, "SWIM/person/$phaseName")
                items += Triple(x, z, draw)
            }
            val L2 = KAYAK_L / 2
            var tc = tc0 - r.tR
            run {
                if (tc < swTurn) {
                    phaseName = "turn"
                    // Broached, but leaning on a brace on the beach side: the wave's about to win
                    val w = tc / swTurn
                    val turn = smooth(w * 2)
                    val (hx, hz) = norm(sgn * (0.25 + 0.75 * turn), -(1 - turn))
                    val x = g.x0 + sgn * 0.5 * w; val z = g.z0 - (g.z0 - g.zc) * 0.6 * w
                    val beachSide = if (hx > 0) -1.0 else 1.0
                    boat(x, z, hx, hz, 0.25, Stroke.BRACE, beachSide, foam = 1.0, empty = false)
                    return@run
                }
                tc -= swTurn
                val xA = g.x0 + sgn * 0.5; val zA = g.z0 - (g.z0 - g.zc) * 0.6
                if (tc < swOver + swUnder) {
                    phaseName = "over"
                    // Over towards the beach; a moment upside down and a roll that doesn't come up; then out
                    val x = xA + sgn * 0.3 * smooth(tc / swOver); val z = zA - (zA - g.zc) * smooth(tc / (swOver + swUnder))
                    val roll = PI * smooth(tc / swOver)
                    val out = tc > swOver + swUnder * 0.5
                    val (hx, hz) = norm(sgn, 0.0)
                    boat(x, z, hx, hz, bob(x, z) * 0.5, Stroke.BRACE, -sgn, foam = 0.9, roll = roll, empty = out)
                    if (out) {
                        val pop = smooth((tc - swOver - swUnder * 0.5) / (swUnder * 0.5))
                        val sxm = x - sgn * 0.2; val szm = z + 0.6
                        fig(sxm, szm) { swimmer(c, v, d, t, sxm, szm, bob(sxm, szm), pop, vest, null, x - sgn * 0.6, z, day) }
                    }
                    return@run
                }
                tc -= swOver + swUnder
                val xB = xA + sgn * 0.3
                if (tc < g.swimT) {
                    phaseName = "swim"
                    // Swimming it in: seaward end of the boat, paddle in hand, pushed along by each wave
                    val w = tc / g.swimT
                    val surge = (w + 0.05 * sin(2 * PI * tc / sw.tv) * sin(PI * w)).coerceIn(0.0, 1.0)   // pushed on by each wave; starts and ends exactly
                    val x = xB + (g.xe - xB) * smooth(w)
                    val z = g.zc - (g.zc - (zs + 0.35)) * surge
                    val endOn = smooth(w / 0.25)
                    val (hx, hz) = norm(sgn * (1 - endOn) + sgn * 0.2 * endOn, -endOn)
                    val inBreak = z - zs < breakDepth
                    boat(x, z, hx, hz, bob(x, z), roll = PI, foam = if (inBreak && bob(x, z) > sw.h * 0.3) 0.5 else 0.0)
                    // from where they surfaced, along the hull to the seaward end
                    val popX = xB - sgn * 0.2; val popZ = g.zc + 0.6
                    val endX = x - hx * (L2 + 0.35); val endZ = z - hz * (L2 + 0.35)
                    val m = smooth(w / 0.25)
                    val sxm = popX + (endX - popX) * m; val szm = popZ + (endZ - popZ) * m
                    val gx = x - hx * L2 * m; val gz = z - hz * L2 * m
                    fig(sxm, szm) { swimmer(c, v, d, t, sxm, szm, bob(sxm, szm), 1.0, vest, Pair(hx, hz), gx, gz, day) }
                    return@run
                }
                tc -= g.swimT
                val ze = zs - 1.7
                val xe = g.xe
                val (ehx, ehz) = norm(sgn * 0.2, -1.0)
                val zIn = zs + 0.35
                if (tc < swWalk) {
                    phaseName = "walk"
                    // Finding their feet and wading round the near side of the boat to its bow
                    val w = tc / swWalk
                    boat(xe, zIn, ehx, ehz, bob(xe, zIn) * 0.4, roll = PI)
                    val ax = xe - ehx * (L2 + 0.35); val az = zIn - ehz * (L2 + 0.35)
                    val bx = xe + ehx * (L2 + 0.4); val bz = zIn + ehz * (L2 + 0.4)
                    val px = ax + (bx - ax) * smooth(w) - 0.7 * sin(PI * w); val pz = az + (bz - az) * smooth(w)
                    fig(px, pz) { walker(c, v, d, px, pz, tc * 6.0, 0.1, wadeDepth(pz), vest, day, listOf(Triple(xe - 0.25, pz, 0.3))) }   // a hand on the hull beside them
                    return@run
                }
                tc -= swWalk
                if (tc < swDrag) {
                    phaseName = "drag"
                    // Standing up in the shallows and dragging the boat up the sand by its bow toggle
                    val w = tc / swDrag
                    val z = zIn + (ze - zIn) * smooth(w)
                    val side = smooth((w - 0.7) / 0.3)
                    val (hx, hz) = norm(sgn * 0.2 * (1 - side) + sgn * side, -(1 - side) - 0.05 * side)
                    boat(xe, z, hx, hz, if (z > zs) bob(xe, z) * 0.4 else 0.0, roll = PI)
                    val px = xe + hx * (L2 + 0.4); val pz = z + hz * (L2 + 0.4)
                    val bowX = xe + hx * L2; val bowZ = z + hz * L2
                    fig(px, pz) { walker(c, v, d, px, pz, tc * 6.5, 0.2, wadeDepth(pz), vest, day,
                        listOf(Triple(bowX, bowZ, 0.2))) }
                    return@run
                }
                tc -= swDrag
                val (sx, sz) = norm(sgn, -0.05)
                val padX = xe - sx * 0.4; val padZ = ze - 1.3
                if (tc < swEmpty + swFlip) fig(padX, padZ) { flatPaddle(c, v, d, padX, padZ, day) }
                if (tc < swEmpty) {
                    phaseName = "empty"
                    // Emptying: bow lifted high, see-sawed, water pouring out of the cockpit
                    val w = tc / swEmpty
                    val lift = when {
                        w < 0.12 -> smooth(w / 0.12)
                        w < 0.42 -> 1.0
                        w < 0.52 -> 1 - 0.75 * smooth((w - 0.42) / 0.1)
                        w < 0.62 -> 0.25 + 0.75 * smooth((w - 0.52) / 0.1)
                        w < 0.86 -> 1.0
                        else -> 1 - smooth((w - 0.86) / 0.14)
                    }
                    val pitch = 1.3 * lift
                    boat(xe, ze, sx, sz, 0.65 * lift, roll = PI, pitch = pitch)
                    val bowX = xe + sx * L2; val bowZ = ze + sz * L2
                    val px = xe + sx * (L2 + 0.4); val pz = ze + sz * (L2 + 0.4)     // where the drag left them
                    val pour = lift * (if (w < 0.5) 1.0 else 0.45) * smooth((w - 0.06) / 0.06)
                    if (pour > 0.05) {
                        val cxm = xe - sx * 0.15 * L2; val czm = ze - sz * 0.15 * L2
                        val top = 0.65 * lift - pitch * 0.15 / 2 + 0.05
                        fig(cxm, czm + 0.01) { pourOut(c, v, d, t, cxm, czm, top, pour, day) }
                    }
                    fig(px, pz) { walker(c, v, d, px, pz, 0.0, 0.35 * (1 - lift) + 0.05, 0.0, vest, day,
                        listOf(Triple(bowX, bowZ, 0.65 * lift + pitch / 2 + 0.1), Triple(bowX - sx * 0.3, bowZ, 0.65 * lift + pitch / 2 - 0.05))) }
                    return@run
                }
                tc -= swEmpty
                if (tc < swFlip) {
                    phaseName = "flip"
                    // Walk round to the middle, then roll it back over the right way up
                    val walkT = 1.4
                    val w = ((tc - walkT) / (swFlip - walkT)).coerceIn(0.0, 1.0)
                    boat(xe, ze, sx, sz, 0.0, roll = PI * (1 - smooth(w)))
                    val fromX = xe + sx * (L2 + 0.4); val fromZ = ze + sz * (L2 + 0.4)
                    val m = smooth(tc / walkT)
                    val px = fromX + (xe - fromX) * m; val pz = fromZ + (ze - 0.75 - fromZ) * m
                    // hands go to the hull once they're there (arms hanging while they walk round)
                    val grip = if (m > 0.85) listOf(Triple(xe - 0.3, ze - 0.2, 0.35), Triple(xe + 0.3, ze - 0.2, 0.35)) else emptyList()
                    fig(px, pz) { walker(c, v, d, px, pz, if (m < 0.95) tc * 6.0 else 0.0, if (m > 0.85) 0.6 else 0.15, 0.0, vest, day, grip) }
                    return@run
                }
                tc -= swFlip
                if (tc < swCarry) {
                    phaseName = "carry"
                    // Walking it back into the shallows, bow to sea, paddle in the other hand
                    val w = tc / swCarry
                    val turn = smooth(w / 0.3)
                    val (hx, hz) = norm(sx * (1 - turn) + 0.05 * turn, sz * (1 - turn) + turn)
                    val z = ze + (zs + 0.6 - ze) * smooth((w - 0.2) / 0.8)
                    boat(xe, z, hx, hz, if (z > zs) bob(xe, z) * 0.4 else 0.0)
                    val m = smooth(w / 0.2)                       // stepping round from the middle
                    val px = xe - 0.65 * m; val pz = (ze - 0.75) + (z - 0.15 - (ze - 0.75)) * m
                    fig(px, pz) { walker(c, v, d, px, pz, tc * 6.0, 0.15, wadeDepth(pz), vest, day,
                        listOf(Triple(xe - 0.25, z - 0.1, 0.35)), paddleIn = true) }
                    return@run
                }
                tc -= swCarry
                val zl = zs + 0.6
                if (tc < swSeat) {
                    phaseName = "seat"
                    // In, deck on, waiting for a lull between sets
                    boat(xe, zl, 0.05, 1.0, bob(xe, zl) * 0.4, Stroke.REST, empty = false)
                    return@run
                }
                tc -= swSeat
                // Out through the break, sprinting
                val w = (tc / g.outT).coerceIn(0.0, 1.0)
                val x = xe + (home[i] - xe) * w
                val z = zl + (zL - zl) * w
                val (hx, hz) = norm((home[i] - xe) * 0.03, 1.0)
                val h = bob(x, z)
                val inBreak = z - zs < breakDepth + 1
                boat(x, z, hx, hz, h, Stroke.SPRINT, 1.0, if (inBreak && h > sw.h * 0.4) 0.7 else 0.0, moving = true, empty = false)
            }
            // Anything right by the water's edge goes in with the beach (over the shore wash); the rest
            // slots in between the waves. Furthest first.
            for ((x, z, draw) in items.sortedByDescending { it.second }) {
                if ((z < zs + 1.0) != onSand) continue
                if (onSand && (z > SIGN_Z) != behindSign) continue      // the sign goes in between
                later(x, z, draw)
            }
        }

        /** Each boat picks its own waves: lets some go, takes others, sometimes two share one. */
        fun plan(i: Int, rank: Int, swimAt: Set<Int> = emptySet()): List<RidePlan> {
            val out = mutableListOf<RidePlan>()
            var free = 3.0 + rank * 4.0 * hash(seg.seed + i)
            var n = 1
            while (n < 1000) {
                val tn = n * sw.tv
                if (tn > seg.len - 4) break
                if (tn < free) { n++; continue }
                fun r(j: Int) = hash(seg.seed * 41 + i * 977 + n * 13 + j * 5)
                val keen = 0.42 + 0.25 * (sw.setFactor(sw.idAt(lineup, seg.start + tn)) - 1)   // keener on the bigger ones
                if (r(0) > keen) { n++; continue }
                var run = makeRun(i, { j -> r(j) }, testSwim || out.size in swimAt, noSwim = true)
                fun lengthOf(rn: Run) = rn.tR + (if (rn.end == RunEnd.SWIM) swimLength(i, rn) - rn.tR else rn.after)
                var total = lengthOf(run)
                // a swim takes a while; if the session's nearly over, this one goes right instead
                if (tn + total > seg.len - 2 && run.end == RunEnd.SWIM && !testSwim) { run = makeRun(i, { j -> r(j) }, false, noSwim = true); total = lengthOf(run) }
                if (tn + total > seg.len - 2) break
                // its general drift across the face (for working out who can bump into whom)
                val dir = sign(runX(run, collideAt) - home[i])
                out += RidePlan(tn, dir, total, run = run)
                free = tn + total + 2.0 + 14.0 * r(3)
                n++
            }
            return out
        }
        /**
         * One run in six goes wrong and ends in a swim. Which runs is shuffled (one somewhere in
         * each six), and a swim that wouldn't be finished before the session ends is moved earlier.
         */
        fun planWithSwims(i: Int, rank: Int): List<RidePlan> {
            if (testSwim) return plan(i, rank)
            val base = plan(i, rank)
            val phase = hash(seg.seed * 13 + i * 7 + 3)
            var best = base
            var k = base.size
            repeat(6) { attempt ->
                val need = kotlin.math.floor(k / 6.0 + phase).toInt()
                if (need == 0) return best
                val at = (0 until need).map { j -> (((j + hash(seg.seed + i * 31 + j * 7)) * k / need).toInt() - attempt).coerceIn(0, max(0, k - 1)) }.toSet()
                val p = plan(i, rank, at)
                val swims = p.count { it.swim }
                if (swims >= need || (swims > best.count { it.swim })) best = p
                if (swims >= need) return best
                k = p.size
            }
            return best
        }
        /**
         * All the boats' waves. When two go for the same wave the second steers into the first,
         * gets knocked over, and rolls back up (an Eskimo roll), then paddles back out.
         */
        // Planning a session's runs is a fair bit of work, so it's kept between frames
        val planKey = "${seg.start}|${seg.seed}|${seg.who}|${s.facing}|${s.waveM}|${s.periodS}|${s.swellFrom}|${s.windKn}|${s.windFrom}|${s.tide}|${v.w}|${v.f}|${v.bottom}|$testSwim"
        @Suppress("UNCHECKED_CAST")
        val plans: Map<Int, List<RidePlan>> by lazy { (synchronized(planCache) { planCache[planKey] } as Map<Int, List<RidePlan>>?) ?: run {
            // Decided boat by boat, so a bump is only ever into a boat that really is on that wave
            val done = LinkedHashMap<Int, List<RidePlan>>()
            for (i in members) {
                val mine = mutableListOf<RidePlan>()
                var busyUntil = -1.0
                for (rp in planWithSwims(i, members.indexOf(i))) {
                    runLog?.invoke("${seg.start}/$i/${rp.tn}/${rp.run?.end}")
                    if (rp.tn < busyUntil) continue
                    // Only a boat close enough to reach by steering across the face at a believable
                    // rate (about 1.2 m/s sideways); otherwise they both just ride their own bit of it.
                    fun xjAt(jp: RidePlan, j: Int) = jp.run?.let { runX(it, collideAt) } ?: home[j]
                    fun reachable(j: Int, jp: RidePlan): Boolean {
                        val side = if (home[i] < home[j]) -1.0 else 1.0
                        val gap = (xjAt(jp, j) + side * 1.6) - home[i]
                        return abs(gap) <= 1.2 * collideAt
                    }
                    val target = done.entries.firstNotNullOfOrNull { (j, js) ->
                        js.firstOrNull { it.tn == rp.tn && it.hits == null && !it.swim && reachable(j, it) }?.let { Pair(j, it) } }
                    val p2 = if (target != null) RidePlan(rp.tn, 0.0, collideLength(i, target.first, xjAt(target.second, target.first)), target.first)
                             else rp
                    if (p2.tn + p2.total > seg.len - 2) break
                    mine += p2; busyUntil = p2.tn + p2.total + 2.0
                }
                done[i] = mine
            }
            done
        }.also { synchronized(planCache) { if (planCache.size > 6) planCache.clear(); planCache[planKey] = it } } }
        fun collideRide(i: Int, tc: Double, rp: RidePlan): Pose {
            val j = rp.hits!!
            val jPlan = plans.getValue(j).firstOrNull { it.tn == rp.tn } ?: rp
            fun xi(tt: Double) = home[i]
            fun xj(tt: Double) = jPlan.run?.let { runX(it, tt) } ?: (home[j] + jPlan.dir * 0.3 * speed * tt)
            val side = if (home[i] < home[j]) -1.0 else 1.0
            fun x(tt: Double) = xi(tt) + (xj(tt) + side * 1.6 - xi(tt)) * smooth(tt / collideAt)   // steering into the other boat
            return when {
                tc < collideAt -> {
                    val xx = x(tc); val z = onCrest(i, tc, xx)
                    val depth = z - zs
                    val face = min(sw.waveHeight(sw.idAt(depth, t.toDouble()), depth), v.camH * 0.92) * 0.55 * smooth(depth / 1.5)
                    val (hx, hz) = norm((xj(tc) - xi(tc)) * 0.15 + 0.02, -1.0)
                    Pose(xx, z, hx, hz, face, if (tc < 1.6) Stroke.SPRINT else Stroke.RUDDER, -side)
                }
                tc < collideAt + rollT -> {
                    // Bumped: over she goes, a moment upside down, and a roll back up with a sweep
                    val w = tc - collideAt
                    val x0c = x(collideAt)
                    val z0c = onCrest(i, collideAt, x0c)
                    val z = max(zs + 1.5, z0c - 0.9 * w)          // pushed in a little by the wave
                    val xx = x0c + side * 0.5 * min(w, 1.0)
                    val roll = when {
                        w < 0.5 -> PI * smooth(w / 0.5)
                        w < 2.0 -> PI
                        w < 3.0 -> PI * (1 - smooth((w - 2.0) / 1.0))
                        else -> 0.0
                    }
                    val (hx, hz) = norm(side, -0.6)
                    Pose(xx, z, hx, hz, bob(xx, z) * 0.6, if (w > 2.0) Stroke.BRACE else Stroke.REST, side,
                        if (w < 3.0) 0.8 else 0.3, moving = false, roll = roll)
                }
                else -> {
                    // Upright again: back out to the spot
                    val outT = rp.total - collideAt - rollT
                    val w = ((tc - collideAt - rollT) / outT).coerceIn(0.0, 1.0)
                    val sx = x(collideAt) + side * 0.5
                    val sz = max(zs + 1.5, onCrest(i, collideAt, x(collideAt)) - 0.9 * rollT)
                    val xx = sx + (home[i] - sx) * w
                    val z = sz + (zL - sz) * w
                    val (hx, hz) = norm((home[i] - sx) * 0.03, 1.0)
                    Pose(xx, z, hx, hz, bob(xx, z), Stroke.SPRINT)
                }
            }
        }
        fun surfPose(i: Int, rank: Int): Pose {
            for (rp in plans.getValue(i)) {
                if (tl < rp.tn) return waiting(i, rp.tn - tl)
                if (tl < rp.tn + rp.total && rp.swim) {
                    val tc = tl - rp.tn
                    val run = rp.run!!
                    return if (tc < run.tR) runPose(i, tc, run) else Pose(home[i], zs + 1.0, 0.0, 1.0, 0.0, swimT = tc, run = run)
                }
                if (tl < rp.tn + rp.total) return if (rp.hits != null) collideRide(i, tl - rp.tn, rp) else runPose(i, tl - rp.tn, rp.run!!)
            }
            return waiting(i, 99.0)
        }


        val poses = (0..2).map { i ->
            if (i !in members) return@map idle(i)
            val rank = members.indexOf(i)
            when (seg.act) {
                Act.TRIP -> {
                    // Off on a trip: out along the coast and away past the edge of the view, then home
                    val reach = min(0.6 + 0.4 * hash(seg.seed + 5), 3.0 * seg.len / (PI * 260.0))   // no faster than ~3-4 m/s
                    // the others follow in the leader's wake a couple of seconds behind, a little to one side
                    val p = 2 * PI * (u - rank * 1.8 / seg.len).coerceIn(0.0, 1.0)
                    val lx = home[members[0]] + seg.dir * reach * (260.0 * (1 - cos(p)) / 2 + 25.0 * sin(p))
                    val lz = zL + reach * 300.0 * (1 - cos(p)) / 2
                    val (hx, hz) = norm(seg.dir * (130.0 * sin(p) + 25.0 * cos(p)), 150.0 * sin(p))
                    val lat = (if (rank == 1) 1.6 else -1.6 * (rank / 2)) * smooth(min(u, 1 - u) / 0.05)
                    val x = lx - hz * lat; val z = max(zs + 3, lz + hx * lat)
                    settle(i, Pose(x, z, hx, hz, bob(x, z)), 0.05)
                }
                Act.CRUISE -> {
                    // A run along the coast in a line: out of the view to one side and back on a wider lane
                    val uu = (u - rank * 0.03).coerceIn(0.0, 1.0)
                    val th = 2 * PI * uu
                    val span = halfW * 1.3 + 12
                    val x = home[members[0]] + seg.dir * span * (1 - cos(th)) / 2 + rank * 0.0
                    val z = zL + 6.0 * (1 - cos(th)) / 2 + 4.0 * sin(th) + rank * 0.9
                    val (hx, hz) = norm(seg.dir * span * sin(th) / 2, 3.0 * sin(th) + 4.0 * cos(th))
                    settle(i, Pose(x, z, hx, hz, bob(x, z)))
                }
                Act.RAFT -> {
                    // Paddle together and raft up side by side, paddles across the decks, for a chat
                    val mx = (members.sumOf { home[it] } / members.size)
                    val mz = zL + 4.0
                    val tx = mx; val tz = mz + rank * 1.05               // hulls touching
                    val go = smooth(u / 0.18); val leave = smooth((u - 0.82) / 0.18)
                    val w = go * (1 - leave)
                    val x = home[i] + (tx - home[i]) * w; val z = zL + (tz - zL) * w
                    val rafted = u > 0.18 && u < 0.82
                    val (hx, hz) = if (rafted) norm(seg.dir, 0.0) else norm((tx - home[i]) * (if (u < 0.5) 1 else -1), (tz - zL) * (if (u < 0.5) 1 else -1))
                    Pose(x, z, hx, hz, bob(x, z), if (rafted) Stroke.REST else Stroke.PADDLE, moving = !rafted)
                }
                Act.ROLL -> {
                    // Rolling practice: over, a moment upside down, and back up with a sweep
                    val cyc = 9.0 + 3.0 * hash(seg.seed + 9)
                    val k = (tl % cyc) / cyc
                    val roll = when {
                        k < 0.35 -> 0.0
                        k < 0.45 -> PI * smooth((k - 0.35) / 0.1)
                        k < 0.62 -> PI
                        k < 0.74 -> PI * (1 - smooth((k - 0.62) / 0.12))
                        else -> 0.0
                    }
                    val x = home[i]; val z = zL
                    val (hx, hz) = norm(seg.dir, 0.1)
                    Pose(x, z, hx, hz, bob(x, z), if (roll > 0) Stroke.BRACE else Stroke.REST, side = 1.0,
                        foam = if (roll > 0.2) 0.6 else 0.0, moving = false, roll = roll)
                }
                Act.SURF -> surfPose(i, rank)
            }
        }
        // Draw furthest first
        for (i in poses.indices.sortedByDescending { poses[it].z }) {
            val o = poses[i]
            if (o.swimT >= 0) { @Suppress("UNCHECKED_CAST") swimScene(i, o.swimT, o.run as Run, onSand); continue }
            if (!onSand) poseLog?.invoke(i, o.x, o.z, seg.act.name + "/" + o.stroke.name)
            if (onSand || o.z < zs + 1.0) continue
            // Riding in on a face: bow down the slope. Otherwise the swell tips it bow-up climbing a
            // face and bow-down coming off the back, when it's end-on to the waves.
            val riding = (o.stroke == Stroke.RUDDER || o.stroke == Stroke.SPRINT) && o.hz < -0.5 && o.h > 0.3
            val pitch = if (riding) -0.55 * min(o.h, 1.8) else
                (sw.height(t, o.x + o.hx * KAYAK_L / 2, o.z + o.hz * KAYAK_L / 2) - sw.height(t, o.x - o.hx * KAYAK_L / 2, o.z - o.hz * KAYAK_L / 2)).coerceIn(-1.4, 1.4)
            later(o.x, o.z) {
                seaKayak(c, v, d, t, o.x, o.z, o.hx, o.hz, o.h, day, o.moving, colour = i, stroke = o.stroke, side = o.side,
                    foam = o.foam, phase = i * 2.1, roll = o.roll, pitch = pitch)
            }
        }
    }


    /**
     * A sea kayaker standing or walking: wetsuit, buoyancy aid, helmet. [hands] are world points
     * (x, z, height) the hands reach to; [bend] leans the body forward; [wade] hides the legs
     * below the water when standing in the shallows.
     */
    private fun walker(c: Canvas, v: View3, d: Float, x: Double, z: Double, walk: Double, bend: Double, wade: Double,
                       vest: Int, day: Double, hands: List<Triple<Double, Double, Double>>, paddleIn: Boolean = false) {
        val k = v.size(1.0, z)
        val suit = shadeDay(0xFF23272E.toInt(), day)
        val vestC = shadeDay(vest, day)
        val skin = shadeDay(0xFFF1C27D.toInt(), day)
        val helmet = shadeDay(0xFFEDEDED.toInt(), day)
        val fx = v.x(x, z); val fy = v.y(z, 0.0)
        val hipY = fy - 0.88f * k
        val waterY = fy - (wade * k).toFloat()
        // the way they lean: towards what they're holding
        val toward = hands.firstOrNull()?.let { if (v.x(it.first, it.second) >= fx) 1f else -1f } ?: 0f
        val shX = fx + toward * (bend * 0.42).toFloat() * k
        val shY = hipY - 0.58f * k * (1 - 0.45f * bend.toFloat())
        // legs, cut off at the water line
        val legP = stroke(suit, max(1.2f * d, k * 0.12f))
        for (sgnL in listOf(-1f, 1f)) {
            val swing = (sin(walk) * 0.17).toFloat() * sgnL * k
            val footX = fx + swing + sgnL * 0.06f * k
            val footY = min(fy, waterY)
            val hipYc = hipY
            if (footY > hipYc) c.drawLine(fx + sgnL * 0.05f * k, hipYc, footX + (fx - footX) * ((fy - footY) / (fy - hipYc)).coerceIn(0f, 1f), footY, legP)
        }
        if (wade > 0.02) {
            c.drawOval(RectF(fx - 0.32f * k, waterY - 0.04f * k, fx + 0.32f * k, waterY + 0.05f * k), p(alpha(0xFFFFFFFF.toInt(), 0.55)))
        }
        // body: buoyancy aid over the wetsuit
        c.drawLine(fx, hipY, shX, shY, stroke(suit, max(2f * d, k * 0.24f)))
        c.drawLine(fx + (shX - fx) * 0.35f, hipY - (hipY - shY) * 0.35f, shX, shY, stroke(vestC, max(2.4f * d, k * 0.3f)))
        // arms
        val arm = stroke(suit, max(1.1f * d, k * 0.09f))
        val targets = hands.map { Pair(v.x(it.first, it.second), v.y(it.second, it.third)) }.toMutableList()
        while (targets.size < 2) targets += Pair(shX + (if (targets.isEmpty()) -0.12f else 0.12f) * k, shY + 0.55f * k)
        for ((tx, ty) in targets.take(2)) {
            // an arm is only so long: if the thing is out of reach, the hand stops short of it
            val ax = shX; val ay = shY + 0.05f * k
            val dl = kotlin.math.hypot(tx - ax, ty - ay)
            val reach = 0.68f * k
            val f = if (dl > reach) reach / dl else 1f
            c.drawLine(ax, ay, ax + (tx - ax) * f, ay + (ty - ay) * f, arm)
        }
        if (paddleIn) {
            // paddle carried at the side, level
            val (hx, hy) = targets[1]
            val sh = stroke(shadeDay(0xFF2E3238.toInt(), day), max(1f * d, k * 0.035f))
            c.drawLine(hx - 0.25f * k, hy + 0.35f * k, hx + 0.2f * k, hy - 0.5f * k, sh)
            c.drawOval(RectF(hx - 0.32f * k, hy + 0.3f * k, hx - 0.18f * k, hy + 0.62f * k), p(shadeDay(0xFF1F4E6B.toInt(), day)))
        }
        // head and helmet
        val hy = shY - 0.17f * k
        val hx = shX + toward * 0.03f * k * bend.toFloat()
        c.drawCircle(hx, hy, max(1.5f * d, k * 0.11f), p(skin))
        c.drawOval(RectF(hx - 0.12f * k, hy - 0.13f * k, hx + 0.12f * k, hy + 0.01f * k), p(helmet))
    }

    /**
     * A swimmer in the water after a capsize: helmet and head, buoyancy aid at the surface, one
     * hand on the boat's end toggle at (gx, gz). [pop] eases them up out of the water as they
     * come out of the boat. [along] is the boat's heading, for the paddle floating alongside.
     */
    private fun swimmer(c: Canvas, v: View3, d: Float, t: Float, x: Double, z: Double, bob: Double, pop: Double, vest: Int,
                        along: Pair<Double, Double>?, gx: Double, gz: Double, day: Double) {
        val k = v.size(1.0, z)
        val sx = v.x(x, z); val sy = v.y(z, bob)
        val a = pop.toFloat()
        // paddle floating beside them
        if (along != null) {
            val (hx, hz) = along
            val px = x - hz * 0.45; val pz = z + hx * 0.45
            val e1 = Pair(v.x(px - hx * 1.1, pz - hz * 1.1), v.y(pz - hz * 1.1, bob + 0.03))
            val e2 = Pair(v.x(px + hx * 1.1, pz + hz * 1.1), v.y(pz + hz * 1.1, bob + 0.03))
            c.drawLine(e1.first, e1.second, e2.first, e2.second, stroke(shadeDay(0xFF2E3238.toInt(), day), max(1f * d, k * 0.035f)))
            val bl = stroke(shadeDay(0xFF1F4E6B.toInt(), day), max(2f * d, k * 0.12f))
            c.drawLine(e1.first, e1.second, e1.first + (e2.first - e1.first) * 0.18f, e1.second + (e2.second - e1.second) * 0.18f, bl)
            c.drawLine(e2.first, e2.second, e2.first + (e1.first - e2.first) * 0.18f, e2.second + (e1.second - e2.second) * 0.18f, bl)
        }
        // splash ring and kicking
        c.drawOval(RectF(sx - 0.38f * k, sy - 0.05f * k, sx + 0.38f * k, sy + 0.07f * k), p(alpha(0xFFFFFFFF.toInt(), 0.5 * a)))
        if (sin(t * 5.0 + x) > 0.6) c.drawCircle(sx + 0.3f * k, sy - 0.05f * k, max(1f * d, k * 0.07f), p(alpha(0xFFFFFFFF.toInt(), 0.6 * a)))
        // buoyancy aid shoulders at the surface
        c.drawOval(RectF(sx - 0.25f * k, sy - 0.2f * k * a, sx + 0.25f * k, sy + 0.04f * k), p(shadeDay(vest, day)))
        // arm to the toggle
        val gxS = v.x(gx, gz); val gyS = v.y(gz, bob + 0.12)
        c.drawLine(sx, sy - 0.12f * k * a, gxS, gyS, stroke(shadeDay(0xFF23272E.toInt(), day), max(1f * d, k * 0.08f)))
        // head and helmet
        val hy = sy - 0.3f * k * a
        c.drawCircle(sx, hy, max(1.5f * d, k * 0.11f), p(shadeDay(0xFFF1C27D.toInt(), day)))
        c.drawOval(RectF(sx - 0.12f * k, hy - 0.13f * k, sx + 0.12f * k, hy + 0.01f * k), p(shadeDay(0xFFEDEDED.toInt(), day)))
    }

    /** Water pouring out of an upturned cockpit at height [top] onto the sand, with a splash. */
    private fun pourOut(c: Canvas, v: View3, d: Float, t: Float, x: Double, z: Double, top: Double, amount: Double, day: Double) {
        val k = v.size(1.0, z)
        val sx = v.x(x, z); val y0 = v.y(z, top); val y1 = v.y(z, 0.0)
        val water = alpha(mix(0xFF9FC6D4.toInt(), 0xFFE4F2F6.toInt(), day), 0.75 * amount)
        for (m in 0 until 3) {
            val off = (m - 1) * 0.07f * k + sin(t * 9.0 + m).toFloat() * 0.02f * k
            c.drawLine(sx + off, y0, sx + off * 1.6f, y1, stroke(water, max(1f * d, k * (0.05f + 0.03f * amount.toFloat()))))
        }
        // drops and the puddle splash
        for (m in 0 until 5) {
            val f = ((t * 2.3 + m * 0.21) % 1.0).toFloat()
            c.drawCircle(sx + (m - 2) * 0.05f * k, y0 + (y1 - y0) * f, max(0.8f * d, k * 0.025f), p(water))
        }
        c.drawOval(RectF(sx - 0.3f * k * amount.toFloat(), y1 - 0.05f * k, sx + 0.3f * k * amount.toFloat(), y1 + 0.04f * k), p(alpha(0xFFFFFFFF.toInt(), 0.6 * amount)))
        c.drawOval(RectF(sx - 0.45f * k, y1 - 0.02f * k, sx + 0.45f * k, y1 + 0.06f * k), p(alpha(shadeDay(0xFF6E5A3E.toInt(), day), 0.35)))
    }

    /** A paddle put down flat on the sand. */
    private fun flatPaddle(c: Canvas, v: View3, d: Float, x: Double, z: Double, day: Double) {
        val k = v.size(1.0, z)
        val a = Pair(v.x(x - 1.05, z - 0.15), v.y(z - 0.15, 0.03)); val b = Pair(v.x(x + 1.05, z + 0.15), v.y(z + 0.15, 0.03))
        c.drawLine(a.first, a.second, b.first, b.second, stroke(shadeDay(0xFF2E3238.toInt(), day), max(1.2f * d, k * 0.035f)))
        val bl = p(shadeDay(0xFF1F4E6B.toInt(), day))
        for ((e, o) in listOf(a to b, b to a)) {
            val mx = e.first + (o.first - e.first) * 0.11f; val my = e.second + (o.second - e.second) * 0.11f
            c.drawOval(RectF(mx - 0.24f * k, my - 0.05f * k, mx + 0.24f * k, my + 0.05f * k), bl)
        }
    }
    /**
     * A sea-kayak paddle left leaning against the upturned kayak: one blade on the sand, the shaft
     * angled up, the other blade resting on the hull facing us so its shape reads clearly.
     * 2.2 m long, blades about 46 x 17 cm, with small drip rings near each end.
     */
    private fun layingPaddle(c: Canvas, v: View3, d: Float, cx: Double, cz: Double, day: Double) {
        val shaftCol = shadeDay(0xFF2E3238.toInt(), day)
        val blade = shadeDay(0xFF1F4E6B.toInt(), day)
        val bladeLight = shadeDay(0xFF3C7FA6.toInt(), day)
        val shadow = alpha(shadeDay(0xFF8A7350.toInt(), day), 0.5)
        val len = 2.2
        // low end on the sand nearer us, high end up on the hull behind
        val ax = cx - 1.0; val az = cz - 0.25; val ah = 0.04
        val bx = cx + 0.95; val bz = cz + 0.35; val bh = 0.42
        fun pt(f: Double) = Triple(ax + (bx - ax) * f, az + (bz - az) * f, ah + (bh - ah) * f)
        fun scr(p: Triple<Double, Double, Double>) = Pair(v.x(p.first, p.second), v.y(p.second, p.third))
        fun scrSand(p: Triple<Double, Double, Double>) = Pair(v.x(p.first + 0.05, p.second - 0.06), v.y(p.second - 0.06, 0.0))
        val k = v.size(1.0, cz)
        // Blade shape in screen space: a teardrop along the shaft direction, rounded at the tip
        fun bladePath(root: Pair<Float, Float>, tip: Pair<Float, Float>, halfW: Float): Path {
            val dx = tip.first - root.first; val dy = tip.second - root.second
            val l = kotlin.math.hypot(dx, dy).coerceAtLeast(0.001f)
            val ux = dx / l; val uy = dy / l; val nx = -uy; val ny = ux
            val prof = listOf(0f to 0.12f, 0.2f to 0.6f, 0.5f to 0.95f, 0.78f to 1f, 0.92f to 0.82f, 1f to 0.35f)
            val pts = mutableListOf<Pair<Float, Float>>()
            for ((f, w) in prof) pts += Pair(root.first + ux * l * f + nx * halfW * w, root.second + uy * l * f + ny * halfW * w)
            pts += Pair(root.first + ux * l * 1.03f, root.second + uy * l * 1.03f)
            for ((f, w) in prof.reversed()) pts += Pair(root.first + ux * l * f - nx * halfW * w, root.second + uy * l * f - ny * halfW * w)
            return smoothClosed(pts)
        }
        val bladeFrac = 0.46 / len
        // Shadows on the sand first
        c.drawLine(scrSand(pt(bladeFrac)).first, scrSand(pt(bladeFrac)).second, scrSand(pt(1 - bladeFrac)).first, scrSand(pt(1 - bladeFrac)).second,
            stroke(shadow, max(1.4f * d, k * 0.05f)))
        c.drawPath(bladePath(scrSand(pt(bladeFrac)), scrSand(pt(0.0)), k * 0.06f), p(shadow))
        // Shaft
        val sa = scr(pt(bladeFrac * 0.9)); val sb = scr(pt(1 - bladeFrac * 0.9))
        c.drawLine(sa.first, sa.second, sb.first, sb.second, stroke(shaftCol, max(1.5f * d, k * 0.035f)))
        // Drip rings: small, just proud of the shaft
        for (f in listOf(0.27, 0.73)) {
            val r = scr(pt(f))
            c.drawCircle(r.first, r.second, max(1.6f * d, k * 0.035f), p(shadeDay(0xFF4A4F57.toInt(), day)))
        }
        // The blade lying on the sand: seen at a low angle, so only a little of its face shows
        c.drawPath(bladePath(scr(pt(bladeFrac)), scr(pt(0.0)), k * 0.055f), p(blade))
        // The blade resting on the hull, facing us
        val up = bladePath(scr(pt(1 - bladeFrac)), scr(pt(1.0)), k * 0.085f)
        c.drawPath(up, p(blade))
        val spineA = scr(pt(1 - bladeFrac * 0.85)); val spineB = scr(pt(1 - bladeFrac * 0.1))
        c.drawLine(spineA.first, spineA.second, spineB.first, spineB.second, stroke(bladeLight, max(0.8f * d, k * 0.012f)))
    }

    /** A smooth closed shape through the given screen points (rounded, no corners). */
    private fun smoothClosed(pts: List<Pair<Float, Float>>): Path {
        val path = Path()
        val n = pts.size
        fun mid(i: Int): Pair<Float, Float> { val a = pts[i % n]; val b = pts[(i + 1) % n]; return Pair((a.first + b.first) / 2, (a.second + b.second) / 2) }
        val m0 = mid(0)
        path.moveTo(m0.first, m0.second)
        for (i in 1..n) {
            val ctrl = pts[i % n]; val m = mid(i)
            path.quadTo(ctrl.first, ctrl.second, m.first, m.second)
        }
        path.close()
        return path
    }

    /** The beach's name drawn in the sand, foreshortened as if lying flat, in shallow grooves. */
    private fun nameInSand(c: Canvas, v: View3, d: Float, s: SceneInput, day: Double) {
        val name = s.title.substringBefore(" ·").trim()
        if (name.isEmpty() || name.startsWith("NEAR YOU")) return
        val z = PROP_Z - 0.2
        val y = v.y(z)
        if (y > v.bottom - 4 * d) return
        val squash = (v.camH / z).toFloat()                       // how flat it looks from up on the dune
        val probe = Draw.paint(0xFF000000.toInt(), 100f, bold = true, align = Paint.Align.CENTER)
        val want = min(v.w * 0.5f, v.size(1.25 * name.length, z))  // about 1.25 m a letter, written big
        val size = 100f * want / max(1f, probe.measureText(name))
        val groove = Draw.paint(alpha(shadeDay(0xFF9C7D4E.toInt(), day), 0.8), size, bold = true, align = Paint.Align.CENTER)
        val lip = Draw.paint(alpha(shadeDay(0xFFF6E3BD.toInt(), day), 0.7), size, bold = true, align = Paint.Align.CENTER)
        val x = v.x(across(v, z, 0.05), z)
        c.save()
        c.scale(1f, squash, x, y)
        c.drawText(name, x, y + size * 0.06f, lip)                 // the pushed-up edge catching the light
        c.drawText(name, x, y, groove)                              // the groove itself
        c.restore()
    }

    /** A surfboard; [pitch] = how much higher the nose is than the tail, metres (riding a slope). */
    private fun boards(c: Canvas, v: View3, x: Double, z: Double, hx: Double, hz: Double, h: Double, day: Double, pitch: Double = 0.0) {
        // nose rocker: the front third curves up a little
        val deckH = { u: Double -> h + pitch * u / 2 + 0.07 + 0.09 * max(0.0, u - 0.4).pow(2.0) / 0.36 }
        val railH = { u: Double -> h + pitch * u / 2 + 0.09 * max(0.0, u - 0.4).pow(2.0) / 0.36 }
        c.drawPath(side(v, x, z, hx, hz, 1.9, deckH, railH), p(shadeDay(0xFFB9C4CC.toInt(), day)))              // the rail
        c.drawPath(outline(v, x, z, hx, hz, 1.9, ::boardW, deckH), p(shadeDay(0xFFF7F4EC.toInt(), day)))
        c.drawPath(outline(v, x, z, hx, hz, 1.9, { u -> boardW(u) * 0.55 }) { deckH(it) + 0.003 }, p(shadeDay(0xFFFFFFFF.toInt(), day)))
        c.drawPath(outline(v, x, z, hx, hz, 1.9, { u -> boardW(u) * 0.06 }) { u -> deckH(u) + 0.006 }, p(shadeDay(0xFF4FA3D1.toInt(), day)))   // stringer
        // tail pad
        c.drawPath(outline(v, x - hx * 0.72, z - hz * 0.72, hx, hz, 0.42, { u -> boardW(-0.75 + 0.2 * u) * 0.8 }) { deckH(-0.75) + 0.008 },
            p(shadeDay(0xFF2B2B2B.toInt(), day)))
    }

    private fun boats(c: Canvas, v: View3, d: Float, t: Float, s: SceneInput, zs: Double, sw: Swell, day: Double, onSand: Boolean,
                      behindSign: Boolean = false) {
        // Kayaks: what they're doing = the paddle call, except that sea kayakers go out and surf
        // whenever the surf is up (the surf rating is already poor if it's too windy)
        val surfUp = (s.surf == Surf.FAIR || s.surf == Surf.GOOD || s.surf == Surf.EPIC) && s.periodS >= 5.0 && sw.h >= 0.4
        if (behindSign) {
            // only a swimmer's kayak being emptied further up the beach than the sign
            if (surfUp || s.paddle == Paddle.GO)
                kayakGroup(c, v, d, t, s, zs, sw, day, surfUp = surfUp, surfOnly = s.paddle != Paddle.GO, onSand = true, behindSign = true)
            return
        }
        if (surfUp && s.paddle != Paddle.GO) {
            kayakGroup(c, v, d, t, s, zs, sw, day, surfUp = true, surfOnly = true, onSand = onSand)
        } else when (s.paddle) {
            Paddle.GO -> kayakGroup(c, v, d, t, s, zs, sw, day, surfUp = surfUp, onSand = onSand)
            Paddle.MARGINAL -> if (!onSand) {
                // Stays close in, paddling up and down the beach
                val p = 2 * PI * t / 50.0
                val x = 7.0 * sin(p); val z = zs + 13.0 + 2.0 * sin(2 * p)
                val (hx, hz) = norm(7.0 * cos(p), 4.0 * cos(2 * p))
                // queued with everything else on the water, so it's drawn between the waves at its
                // distance (drawn straight away it was painted over by the sea)
                poseLog?.invoke(0, x, z, "MARGINAL")
                later(x, z) { seaKayak(c, v, d, t, x, z, hx, hz, sw.height(t, x, z), day, moving = true) }
            }
            else -> if (onSand) {
                // Upside down on the sand, paddle beside it: not today
                val z = PROP_Z + 1.6; val x = across(v, z, -0.26)
                val (hx, hz) = norm(0.75, 0.66)
                val hull = shadeDay(0xFFFFB000.toInt(), day)
                c.drawPath(side(v, x, z, hx, hz, KAYAK_L, { u -> 0.32 * (1 - u * u).pow(0.5) + 0.04 }) { 0.0 }, p(shadeDay(0xFFC97F00.toInt(), day)))
                c.drawPath(outline(v, x, z, hx, hz, KAYAK_L, ::kayakW) { u -> 0.32 * (1 - u * u).pow(0.5) + 0.04 }, p(hull))
                layingPaddle(c, v, d, x - 0.2, z - 1.1, day)       // lying on the sand in front of the boat
            }
        }
        // Surfer: what they're doing = the surf rating
        when (s.surf) {
            Surf.GOOD, Surf.EPIC, Surf.FAIR -> if (!onSand) surfer(c, v, d, t, s, zs, sw, day)
            else -> if (onSand) {
                // Board stuck in the sand, nose up: nothing worth surfing
                val zm = PROP_Z + 0.8; val xm = across(v, zm, 0.23)
                val x = v.x(xm, zm); val yb = v.y(zm)
                val L = v.size(1.9, zm); val W = v.size(0.54, zm)
                c.save(); c.rotate(7f, x, yb)
                val path = Path()
                val n = 20
                for (i in 0..n) { val u = -1.0 + 2.0 * i / n; val yy = yb + L * 0.06f - ((u + 1) / 2).toFloat() * L
                    val xx = x - (boardW(u) / 0.27).toFloat() * W / 2; if (i == 0) path.moveTo(xx, yy) else path.lineTo(xx, yy) }
                for (i in n downTo 0) { val u = -1.0 + 2.0 * i / n; val yy = yb + L * 0.06f - ((u + 1) / 2).toFloat() * L
                    path.lineTo(x + (boardW(u) / 0.27).toFloat() * W / 2, yy) }
                path.close()
                c.drawPath(path, p(shadeDay(0xFFF7F4EC.toInt(), day)))
                c.drawPath(path, stroke(shadeDay(0xFFB9C3CC.toInt(), day), 1.2f * d))
                c.drawLine(x, yb - L * 0.93f, x, yb, stroke(shadeDay(0xFF4FA3D1.toInt(), day), max(1f * d, W * 0.07f)))
                c.restore()
            }
        }
    }

    /**
     * The surfer, done the way surfers do it:
     *  - sits up on the board at the peak, just outside where the waves break (closer in
     *    than the sea kayaks, which catch the swell further out), facing out to watch for sets;
     *  - as a wave comes, swings the board round to the beach, lies down and paddles hard;
     *  - pops up as the wave lifts the tail, makes a bottom turn and runs along the open face
     *    ahead of the breaking part, pumping high and low on the wave;
     *  - kicks out over the back before it closes out, drops onto the board and paddles back,
     *    duck-diving under each broken wave on the way.
     * Better surf = shorter wait and a longer, faster ride; fair = long waits, short rides.
     */
    private fun surfer(c: Canvas, v: View3, d: Float, t: Float, s: SceneInput, zs: Double, sw: Swell, day: Double) {
        val suit = shadeDay(0xFF161616.toInt(), day)
        val skin = shadeDay(0xFFF1C27D.toInt(), day)
        val breakDepth = sw.breakXMean
        // Surfers wait for the sets: they take the biggest wave of each set (every 8th),
        // sitting just outside where those set waves break
        val setWave = 2
        // Take off out the back on the steepening face, before it breaks, and ride it ahead of the peel
        val peak = sw.breakX(setWave) + 3.0
        val halfW = (v.w / 2.0) / v.f * (zs + peak)        // visible half-width out at the peak
        val speed = sw.speed
        val latWanted = when (s.surf) { Surf.EPIC -> 3.4; Surf.GOOD -> 2.8; else -> 1.8 }     // across-the-face speed, m/s
        val waitMin = when (s.surf) { Surf.EPIC -> 6.0; Surf.GOOD -> 12.0; else -> 30.0 }
        val setT = 8 * sw.tv
        val base = ((sw.timeOf(setWave, peak) % setT) + setT) % setT       // a set wave passes the peak
        val t0 = base + kotlin.math.ceil(max(0.0, waitMin - base) / setT) * setT   // catch time within the cycle
        val rideT = max(2.0, sw.timeBetween(peak, RIDE_END))
        val lat = min(latWanted, 1.05 * halfW / rideT)       // stay in shot
        val kickT = 1.4
        val backT = (kotlin.math.hypot(lat * rideT, peak - 1.0) + 2.0) / 1.1
        val cycle = kotlin.math.ceil((t0 + rideT + kickT + backT + 2.0) / setT) * setT
        val rides = sw.h >= 0.3 && s.periodS >= 5.0
        val tc = if (rides) ((t % cycle) + cycle) % cycle else 0.0
        val ci = if (rides) kotlin.math.floor(t / cycle).toInt() else 0
        // Lefts or rights off a straight-on peak, chosen afresh each go (never changing mid-ride)
        fun dirOf(n: Int) = if (sw.peel != 0.0) sw.peel else if (hash(n / 3 + 77) < 0.5) -1.0 else 1.0
        val sgn = dirOf(ci)                                  // the direction they ride
        val x0 = -sgn * 0.55 * halfW                         // start upwave so the ride crosses the view
        val prevX0 = -dirOf(ci - 1) * 0.55 * halfW           // where they sat last time
        val endX = x0 + sgn * lat * rideT
        // Now and then it goes wrong: a wipeout part way along the wave (more often when it's only fair)
        val wipe = rides && hash(ci * 7 + 3) < (if (s.surf == Surf.FAIR) 0.4 else 0.27)
        val wipeAt = rideT * (0.3 + 0.45 * hash(ci * 7 + 5))
        val zTake = zs + peak                                // where they're lying when the wave picks them up
        fun rideAt(r: Double): Pair<Double, Double> {
            val x = x0 + sgn * lat * r * smooth(r / 1.0).coerceAtLeast(0.25)
            // onto the crest from where they took off, and never up the beach
            val zc = max(zs + 1.0, zOnCrest(sw, sw.travel(peak, r), x))
            return Pair(x, zTake + (zc - zTake) * smooth(r / 1.2))
        }
        val zRideEnd = rideAt(rideT).second
        val backStart = if (wipe) t0 + wipeAt + WIPE_T else t0 + rideT + kickT
        val backX = if (wipe) rideAt(wipeAt).first + sgn * 0.6 else endX + sgn * 0.8
        val backDepth = if (wipe) max(1.6, rideAt(wipeAt).second - zs - 1.4 * WIPE_T) else zRideEnd - zs + 2.5

        /** Nose-up or nose-down as the swell passes under a board lying end-on to it. */
        fun pitchAt(x: Double, z: Double, hx: Double, hz: Double) =
            (sw.height(t, x + hx * 0.95, z + hz * 0.95) - sw.height(t, x - hx * 0.95, z - hz * 0.95)).coerceIn(-0.9, 0.9)
        val suitHi = mix(suit, 0xFF5C6670.toInt(), 0.45)          // the lit side of the wetsuit
        val hair = shadeDay(0xFF5A3B22.toInt(), day)
        fun scr(px: Double, pz: Double, ph: Double) = Pair(v.x(px, pz), v.y(pz, ph))
        fun sittingNow(x: Double, z: Double, facingShore: Double) {
            val h = sw.height(t, x, z)
            val (hx, hz) = norm(sgn * 0.3 * facingShore, 1.0 - 2.0 * facingShore)       // out to sea -> round to the beach
            val pitch = pitchAt(x, z, hx, hz) + 0.12                                    // sat back, nose lifted
            boards(c, v, x, z, hx, hz, h, day, pitch)
            // Sitting astride the board a little back, legs dangling in the water, rocking with it
            val k = v.size(1.0, z) * SURFER_K
            val mw = 0.45f * d          // thin floor: far away he stays small rather than chunky
            val sx = x - hx * 0.25; val sz = z - hz * 0.25
            val deck = h - pitch * 0.25 / 2 + 0.08
            val (bx, by) = scr(sx, sz, deck)
            val nxs = -hz; val nzs = hx
            val water = alpha(mix(suit, 0xFF2D6C85.toInt(), 0.55), 0.85)
            for (sd in listOf(-1.0, 1.0)) {
                // thigh over the rail, shin down into the sea (shown through the water, paler), kicking gently
                val (kx, ky) = scr(sx + nxs * sd * 0.3 + hx * 0.2, sz + nzs * sd * 0.3 + hz * 0.2, deck - 0.05)
                val kick = sin(t * 1.3 + sd * 1.7 + x).toFloat() * 0.06f * k
                limb(c, bx, by, kx, ky, max(mw, k * 0.16f), max(mw, k * 0.12f), p(if (sd > 0) suitHi else suit))
                limb(c, kx, ky, kx + kick, ky + 0.36f * k, max(mw, k * 0.11f), max(mw, k * 0.08f), p(water))
            }
            // torso rocking a little with the swell, a slight slouch; now and then looking over a shoulder
            val sway = sin(t * 0.9 + x).toFloat() * 0.05f * k + (pitch * 0.1).toFloat() * k
            val f = if (v.x(x + hx, z + hz) >= v.x(x, z)) 1f else -1f
            val shX = bx + sway + f * 0.06f * k; val shY = by - 0.58f * k
            limb(c, bx, by, shX, shY, max(mw * 1.4f, k * 0.24f), max(mw * 1.4f, k * 0.28f), p(suit))
            limb(c, bx + f * 0.02f * k, by - 0.05f * k, shX + f * 0.03f * k, shY + 0.03f * k, max(mw, k * 0.08f), max(mw, k * 0.1f), p(suitHi))
            // arms: hands on the thighs, or one paddling a little to stay on the peak
            val scull = sin(t * 0.35 + x * 0.1) > 0.6
            for (sd in listOf(-1f, 1f)) {
                val hxS = bx + sd * 0.24f * k; val hyS = by + 0.02f * k - (if (scull && sd > 0) -0.25f * k * sin(t * 3.0).toFloat().coerceAtLeast(0f) else 0f)
                val (ex, ey) = joint(shX, shY + 0.05f * k, hxS, hyS, 0.3f * k, -sd)
                limb(c, shX, shY + 0.05f * k, ex, ey, max(mw, k * 0.1f), max(mw, k * 0.085f), p(suit))
                limb(c, ex, ey, hxS, hyS, max(mw, k * 0.085f), max(mw, k * 0.07f), p(suit))
                c.drawCircle(hxS, hyS, max(0.35f * d, k * 0.045f), p(skin))
            }
            val look = if (sin(t * 0.21 + x) > 0.7) -f else f
            head(c, shX + look * 0.03f * k, shY - 0.2f * k, max(0.8f * d, k * 0.115f), look, skin, 0, hair)
        }
        fun proneNow(x: Double, z: Double, hx: Double, hz: Double, h: Double, rate: Double) {
            val pitch = pitchAt(x, z, hx, hz)
            boards(c, v, x, z, hx, hz, h, day, pitch)
            val k = v.size(1.0, z) * SURFER_K
            val mw = 0.45f * d          // thin floor: far away he stays small rather than chunky
            val deck = { u: Double -> h + pitch * u / 2 + 0.08 }
            // lying a little back of centre: chest up off the board, back arched, head up, feet together
            val (fx, fy) = scr(x - hx * 0.85, z - hz * 0.85, deck(-0.9) + 0.12 + 0.03 * sin(t * 2.0))
            val (kx, ky) = scr(x - hx * 0.5, z - hz * 0.5, deck(-0.5) + 0.05)
            val (hipX, hipY) = scr(x - hx * 0.15, z - hz * 0.15, deck(-0.15) + 0.07)
            val (shX, shY) = scr(x + hx * 0.38, z + hz * 0.38, deck(0.4) + 0.2)
            val (hdX, hdY) = scr(x + hx * 0.55, z + hz * 0.55, deck(0.55) + 0.36)
            limb(c, kx, ky, fx, fy, max(mw, k * 0.11f), max(mw, k * 0.08f), p(suit))
            limb(c, hipX, hipY, kx, ky, max(mw, k * 0.15f), max(mw, k * 0.12f), p(suit))
            // the far arm first, then the body, then the near arm: windmilling, elbows bent, digging deep
            val arms = listOf(-1.0, 1.0).sortedByDescending { sd -> z + hx * sd }      // furthest first
            fun armAt(sd: Double) {
                val a = t * rate + if (sd > 0) 0.0 else PI
                val (ax0, ay0) = scr(x + hx * 0.36 - hz * sd * 0.18, z + hz * 0.36 + hx * sd * 0.18, deck(0.36) + 0.18)
                val reach = 0.55 * cos(a)
                val lift = 0.22 * sin(a)                         // above the water on the way forward, in it pulling back
                val (hX, hY) = scr(x + hx * (0.3 + reach) - hz * sd * 0.32, z + hz * (0.3 + reach) + hx * sd * 0.32, h + max(-0.12, lift))
                val (ex, ey) = joint(ax0, ay0, hX, hY, 0.3f * k, if (sd > 0) 1f else -1f)
                limb(c, ax0, ay0, ex, ey, max(mw, k * 0.1f), max(mw, k * 0.085f), p(if (sd > 0) suitHi else suit))
                limb(c, ex, ey, hX, hY, max(mw, k * 0.085f), max(mw, k * 0.07f), p(if (sd > 0) suitHi else suit))
                if (lift > -0.05) c.drawCircle(hX, hY, max(0.35f * d, k * 0.045f), p(skin))
                else c.drawCircle(hX, hY, max(0.5f * d, k * 0.07f), p(alpha(0xFFFFFFFF.toInt(), 0.6)))     // splash as it digs in
            }
            armAt(arms[0])
            limb(c, hipX, hipY, shX, shY, max(mw * 1.3f, k * 0.22f), max(mw * 1.3f, k * 0.27f), p(suit))
            limb(c, hipX, hipY - 0.04f * k, shX, shY - 0.05f * k, max(mw, k * 0.07f), max(mw, k * 0.09f), p(suitHi))
            val f = if (v.x(x + hx, z + hz) >= v.x(x, z)) 1f else -1f
            head(c, hdX, hdY, max(0.8f * d, k * 0.115f), f, skin, 0, hair)
            armAt(arms[1])
        }
        /** Standing: [up] 0 = just popping up .. 1 = full stance; [lean] tips the body into a turn. */
        fun standingNow(x: Double, z: Double, hx: Double, hz: Double, h: Double, up: Double, lean: Double, crouch: Double) {
            // Heading in on the face, the nose points down the slope; otherwise it follows the water
            val pitch = if (hz < -0.3) -0.45 * min(h, 1.6) * (-hz) else pitchAt(x, z, hx, hz)
            boards(c, v, x, z, hx, hz, h, day, pitch)
            val k = v.size(1.0, z) * SURFER_K
            val mw = 0.45f * d          // thin floor: far away he stays small rather than chunky
            val deck = { u: Double -> h + pitch * u / 2 + 0.08 }
            // feet across the board: back foot on the tail pad, front foot just ahead of the middle
            val (bfX, bfY) = scr(x - hx * 0.62, z - hz * 0.62, deck(-0.65))
            val (ffX, ffY) = scr(x + hx * 0.05, z + hz * 0.05, deck(0.05))
            // which way the surfer faces on screen (side-on stance, chest towards the wave's open face)
            val f = if (kotlin.math.abs(ffX - bfX) > 0.5f) (if (ffX > bfX) 1f else -1f) else sgn.toFloat()
            // knees flex with the wave: compressing through the bottom turn, extending up the face
            val cr = (crouch * (0.6 + 0.4 * up)).toFloat() - 0.06f * sin(t * 2.6 + x).toFloat()
            val hipX = (bfX + ffX) / 2 + f * (0.04f + lean.toFloat() * 0.18f) * k
            val hipY = min(bfY, ffY) - 0.86f * k * cr
            val legSeg = 0.47f * k
            val (fkX, fkY) = joint(hipX, hipY, ffX, ffY, legSeg, -f)        // front knee forward
            val (bkX, bkY) = joint(hipX, hipY, bfX, bfY, legSeg, -f * 0.6f) // back knee tucked in behind it
            // chest leaning into the turn and forward over the front foot; looking down the line
            val shX = hipX + f * (0.12f + lean.toFloat() * 0.32f) * k
            val shY = hipY - 0.55f * k * (0.85f + 0.15f * cr)
            val armSway = sin(t * 1.7 + x).toFloat() * 0.08f * k
            // the back arm and leg first (further side), then the body, front leg and front arm
            val armW = max(mw, k * 0.095f)
            val (bhX, bhY) = Pair(shX - f * 0.5f * k, shY + (0.05f - lean.toFloat() * 0.25f) * k + armSway)
            val (beX, beY) = joint(shX, shY + 0.06f * k, bhX, bhY, 0.3f * k, f)
            limb(c, shX, shY + 0.06f * k, beX, beY, armW, armW * 0.85f, p(suit)); limb(c, beX, beY, bhX, bhY, armW * 0.85f, armW * 0.7f, p(suit))
            limb(c, hipX, hipY, bkX, bkY, max(mw, k * 0.16f), max(mw, k * 0.12f), p(suit))
            limb(c, bkX, bkY, bfX, bfY, max(mw, k * 0.12f), max(mw, k * 0.09f), p(suit))
            limb(c, hipX, hipY, shX, shY, max(mw * 1.4f, k * 0.25f), max(mw * 1.4f, k * 0.29f), p(suit))
            limb(c, hipX + f * 0.04f * k, hipY - 0.04f * k, shX + f * 0.05f * k, shY + 0.02f * k, max(mw, k * 0.08f), max(mw, k * 0.1f), p(suitHi))
            limb(c, hipX, hipY, fkX, fkY, max(mw, k * 0.16f), max(mw, k * 0.12f), p(suitHi))
            limb(c, fkX, fkY, ffX, ffY, max(mw, k * 0.12f), max(mw, k * 0.09f), p(suitHi))
            c.drawCircle(bfX, bfY, max(0.3f * d, k * 0.04f), p(skin)); c.drawCircle(ffX, ffY, max(0.3f * d, k * 0.04f), p(skin))
            val (fhX, fhY) = Pair(shX + f * 0.55f * k, shY + (0.18f + lean.toFloat() * 0.25f) * k - armSway)
            val (feX, feY) = joint(shX, shY + 0.06f * k, fhX, fhY, 0.3f * k, -f)
            limb(c, shX, shY + 0.06f * k, feX, feY, armW, armW * 0.85f, p(suitHi)); limb(c, feX, feY, fhX, fhY, armW * 0.85f, armW * 0.7f, p(suitHi))
            c.drawCircle(fhX, fhY, max(0.35f * d, k * 0.045f), p(skin)); c.drawCircle(bhX, bhY, max(0.35f * d, k * 0.045f), p(skin))
            head(c, shX + f * 0.05f * k, shY - 0.19f * k, max(0.8f * d, k * 0.115f), f, skin, 0, hair)
        }

        fun sitting(x: Double, z: Double, f: Double) { poseLog?.invoke(20, x, z, "SURFER/sit"); later(x, z) { sittingNow(x, z, f) } }
        fun prone(x: Double, z: Double, hx: Double, hz: Double, h: Double, rate: Double) {
            poseLog?.invoke(20, x, z, "SURFER/prone"); later(x, z) { proneNow(x, z, hx, hz, h, rate) } }
        fun standing(x: Double, z: Double, hx: Double, hz: Double, h: Double, up: Double, lean: Double, crouch: Double) {
            poseLog?.invoke(20, x, z, "SURFER/stand"); later(x, z) { standingNow(x, z, hx, hz, h, up, lean, crouch) } }
        when {
            !rides || tc < t0 - 3.0 -> {
                // If this one's going the other way, paddle across to the other side of the peak first
                val moveT = min(max(t0 - 4.0, 2.0), abs(x0 - prevX0) / 1.0 + 1.0)
                if (rides && prevX0 != x0 && tc < moveT) {
                    val u = smooth(tc / moveT)
                    val x = prevX0 + (x0 - prevX0) * u
                    val z = zs + peak + 1.0
                    val (hx, hz) = norm(x0 - prevX0, 0.0)
                    prone(x, z, hx, hz, sw.height(t, x, z), 4.0)
                } else {
                    // Sitting at the peak; for the last few seconds before a wave, swinging round to face the beach
                    val turn = if (rides) smooth((tc - (t0 - 6.0)) / 3.0) else 0.0
                    sitting(x0, zs + peak + 1.0, turn)
                }
            }
            tc < t0 -> {
                // Lying down and paddling hard for it
                val u = (tc - (t0 - 3.0)) / 3.0
                val z = zs + peak + 1.0 - 1.0 * u
                val (hx, hz) = norm(sgn * 0.3, -1.0)
                prone(x0, z, hx, hz, sw.height(t, x0, z), 6.5)
            }
            tc < t0 + (if (wipe) wipeAt else rideT) -> {
                // Up and riding along the face, ahead of the breaking section
                val r = tc - t0
                val (x, z) = rideAt(r)
                val depth = z - zs
                val face = min(sw.waveHeight(sw.idAt(depth, t.toDouble()), depth), v.camH * 0.92) * smooth(depth / 1.0)
                // pumping: high and low on the face; a bottom turn at the start
                val pump = 0.5 + 0.3 * sin(r * 2.6)
                val bottom = smooth(r / 0.6) * (1 - smooth((r - 0.6) / 0.8))
                val h = face * (if (r < 0.6) 0.85 - 0.5 * smooth(r / 0.6) else pump)
                val (hx, hz) = norm(sgn * (0.35 + 0.6 * smooth(r / 1.0)), -1.0 + 0.6 * smooth(r / 1.0))
                val up = smooth(r / 0.6)
                if (up < 0.35) prone(x, z, hx, hz, h, 0.0)
                else standing(x, z, hx, hz, h, up, 0.6 * bottom + 0.2 * sin(r * 2.6), if (s.surf == Surf.EPIC) 0.85 else 0.92)
                // spray off the rail in the turns
                if (r > 0.4) c.drawCircle(v.x(x - hx * 1.0, z - hz * 1.0), v.y(z - hz, h + 0.1), max(1.5f * d, v.size(0.18 + 0.2 * bottom, z)),
                    p(alpha(0xFFFFFFFF.toInt(), 0.75)))
            }
            wipe && tc < t0 + wipeAt + WIPE_T -> later(rideAt(wipeAt).first, rideAt(wipeAt).second - 0.8.also {
                val w0 = tc - t0 - wipeAt; val (x0w, z0w) = rideAt(wipeAt)
                poseLog?.invoke(20, x0w + sgn * 0.6 * min(w0, 1.0), max(zs + 1.6, z0w - 1.4 * w0), "SURFER/wipe") }) {
                // Wipeout: catches a rail, pitches off, gets washed under, board tumbling in the whitewater
                val w = tc - t0 - wipeAt
                val (x0w, z0w) = rideAt(wipeAt)
                val z = max(zs + 1.6, z0w - 1.4 * w)              // washed in a little
                val x = x0w + sgn * 0.6 * min(w, 1.0)
                val h = sw.height(t, x, z) * 0.5
                val k = v.size(1.0, z) * SURFER_K
                val bx = v.x(x, z); val by = v.y(z, h)
                // the board, tumbling end over end then lying in the foam
                val spin = min(w / 1.2, 1.0) * 540.0
                c.save(); c.rotate((sgn * spin).toFloat(), bx + sgn.toFloat() * k * 0.6f, by - k * 0.5f * (1 - min(w, 1.0)).toFloat())
                boards(c, v, x + sgn * 0.6, z, sgn, -0.2, h + 0.6 * (1 - min(w, 1.0)), day)
                c.restore()
                if (w < 0.8) {
                    // pitched off: body flying forward, arms out, rotating towards the water
                    val a = (w / 0.8 * 110.0 * sgn).toFloat()
                    c.save(); c.rotate(a, bx, by)
                    c.drawLine(bx, by - 0.2f * k, bx, by - 1.1f * k, stroke(suit, max(1.6f * d, k * 0.16f)))
                    c.drawLine(bx - 0.6f * k, by - 0.9f * k, bx + 0.6f * k, by - 1.0f * k, stroke(suit, max(1.2f * d, k * 0.09f)))
                    c.drawLine(bx, by - 0.2f * k, bx - 0.25f * k, by + 0.3f * k, stroke(suit, max(1.4f * d, k * 0.12f)))
                    c.drawLine(bx, by - 0.2f * k, bx + 0.3f * k, by + 0.25f * k, stroke(suit, max(1.4f * d, k * 0.12f)))
                    c.drawCircle(bx, by - 1.28f * k, max(1.5f * d, k * 0.12f), p(skin))
                    c.restore()
                } else if (w > WIPE_T - 0.7) {
                    // surfacing: head and shoulders by the board
                    c.drawCircle(bx, by - 0.12f * k, max(1.5f * d, k * 0.12f), p(skin))
                }
                // the splash, then a patch of churned-up foam
                val splash = (1 - abs(w - 0.8) / 0.6).coerceAtLeast(0.0)
                val foam = p(alpha(0xFFFFFFFF.toInt(), 0.9))
                for (m in 0 until 5) {
                    val ox = (m - 2) * 0.35f * k
                    c.drawCircle(bx + ox, by - (0.2f + 0.9f * splash.toFloat() * (1 - kotlin.math.abs(m - 2) / 3f)) * k,
                        max(2f * d, k * (0.22f + 0.15f * splash.toFloat())), foam)
                }
            }
            tc < t0 + rideT + kickT && !wipe -> {
                // Kick out: turn up the face and over the back of the wave, then drop onto the board
                val u = (tc - t0 - rideT) / kickT
                val x = endX + sgn * 0.8 * u
                val z = zRideEnd + 2.5 * u
                val h = sw.height(t, x, z) + 0.5 * sin(u * PI) * sw.h
                val (hx, hz) = norm(sgn * (1 - u), 0.2 + u)
                if (u < 0.55) standing(x, z, hx, hz, h, 1.0, -0.4, 0.95) else prone(x, z, hx, hz, h, 0.0)
            }
            tc < backStart + backT -> {
                // Paddling back out; duck-diving under each broken wave
                val u = ((tc - backStart) / backT).coerceIn(0.0, 1.0)
                val x = backX + (x0 - backX) * u
                val z = zs + backDepth + (peak + 1.0 - backDepth) * u
                val (hx, hz) = norm((x0 - backX) * 0.05, 1.0)
                val h = sw.height(t, x, z)
                val duck = z - zs < breakDepth + 1.0 && h > sw.h * 0.45
                if (duck) later(x, z) {
                    // under the wave: just a patch of foam showing
                    c.drawCircle(v.x(x, z), v.y(z, h * 0.5), max(2f * d, v.size(0.5, z)), p(alpha(0xFFFFFFFF.toInt(), 0.85)))
                } else prone(x, z, hx, hz, h, 4.0)
            }
            else -> sitting(x0, zs + peak + 1.0, 0.0)
        }
    }

    private const val RIDE_END = 2.4        // kick out about here, before the shore break
    private const val WIPE_T = 2.6          // seconds from falling off to climbing back on
    private const val SURFER_K = 0.75f      // figure scale against the board

    /** Where a crest that crosses the middle at [q] is, at sideways position [x]. */
    private fun zOnCrest(sw: Swell, q: Double, x: Double): Double = sw.zs + sw.qOnCrest(q, x)

    // ---- the lifeboat ------------------------------------------------------------------

    private const val LB_OUT = 150.0          // seconds from launch until it's out of sight
    private const val LB_AWAY = 3600.0        // back an hour after launching
    private const val LB_L = 13.6             // a Shannon-class all-weather lifeboat: 13.6 m long, 4.5 m beam

    /** The launch this moment belongs to, if the lifeboat is out (launch time, epoch seconds). */
    private fun launch(s: SceneInput, wall: Double): Long? =
        s.launches.lastOrNull { wall >= it && wall < it + LB_AWAY + LB_OUT }

    private fun subtitles(s: SceneInput, wall: Double): List<String> {
        val heliLine = heliVisit(s, wall)?.let { "Rescue helicopter " + (if (s.heliCallOut) "on a call-out" else "out on a training exercise") }
        val extra = listOfNotNull(heliLine, ribSession(s, wall)?.let { "Inshore lifeboat crew out training" })
        val base = extra + s.subtitles
        val l = launch(s, wall) ?: return base
        val back = if (wall < l + LB_AWAY) "back about ${Fmt.time(l + LB_AWAY.toLong())}" else "coming home"
        return listOf("Lifeboat launched ${Fmt.time(l)} · $back") + base
    }

    /** Where the lifeboat is along its course: 0 on the beach .. 1 out of sight. */
    private fun lbPoint(u: Double, zs: Double): Pair<Double, Double> {
        val p0 = Pair(19.0, zs + 0.9); val p1 = Pair(4.0, zs + 3.0); val p2 = Pair(-14.0, zs + 70.0); val p3 = Pair(-150.0, zs + 330.0)
        val a = 1 - u
        fun b(i: Int) = a * a * a * (if (i == 0) p0.first else p0.second) + 3 * a * a * u * (if (i == 0) p1.first else p1.second) +
            3 * a * u * u * (if (i == 0) p2.first else p2.second) + u * u * u * (if (i == 0) p3.first else p3.second)
        return Pair(b(0), b(1))
    }

    private fun lifeboat(c: Canvas, v: View3, d: Float, t: Float, s: SceneInput, zs: Double, sw: Swell, day: Double, wall: Double) {
        val l = launch(s, wall) ?: return
        val since = wall - l
        val outbound = since < LB_OUT
        val homeward = since >= LB_AWAY
        if (!outbound && !homeward) return               // away on the shout
        // Progress along the course, eased: slow off the beach, full speed at sea
        val k = if (outbound) since / LB_OUT else 1 - (since - LB_AWAY) / LB_OUT
        val u = k * k * (3 - 2 * k) * 0.35 + k * 0.65
        val (x, z) = lbPoint(u, zs)
        if (layerQueue != null) { later(x, z) { lifeboat(c, v, d, t, s, zs, sw, day, wall) }; return }
        val (x2, z2) = lbPoint((u + 0.01).coerceAtMost(1.0), zs)
        var (hx, hz) = norm(x2 - x, z2 - z)
        if (!outbound) { hx = -hx; hz = -hz }
        val afloat = z > zs + 1.0
        val bob = if (afloat) sw.height(t, x, z) else 0.0
        val navy = shadeDay(0xFF13294B.toInt(), day)
        val orange = shadeDay(0xFFF26B21.toInt(), day)
        val white = shadeDay(0xFFF4F4F4.toInt(), day)
        // Fine deep-V bow, full beam (4.5 m) carried well aft to a broad transom
        val hullW = { uu: Double -> 2.25 * (if (uu > 0) (1 - uu.pow(1.8)).coerceAtLeast(0.0).pow(0.55) else 0.92 + 0.08 * (1 + uu)) }
        // Bow wave and wake, bigger the faster it goes
        val speedK = (if (outbound) 1.0 else 1.0) * (0.3 + 0.7 * smooth((z - zs) / 10))
        if (afloat) {
            val foam = p(alpha(0xFFFFFFFF.toInt(), 0.85))
            val bx = x + hx * LB_L / 2; val bz = z + hz * LB_L / 2
            val r = max(2f * d, v.size(0.9 * speedK, bz))
            c.drawCircle(v.x(bx, bz), v.y(bz, bob + 0.3), r, foam)
            // V-shaped wake spreading out behind
            val sx = x - hx * LB_L / 2; val sz = z - hz * LB_L / 2
            val nx = -hz; val nz = hx
            val wake = stroke(alpha(0xFFFFFFFF.toInt(), 0.8 * speedK), max(1.5f * d, v.size(0.5, z)))
            for (side in listOf(-1.0, 1.0)) {
                val ex = sx - hx * 22.0 + nx * side * 9.0; val ez = sz - hz * 22.0 + nz * side * 9.0
                if (ez > zs + 0.5 && sz > zs + 0.5) c.drawLine(v.x(sx + nx * side * 2.0, sz + nz * side * 2.0), v.y(sz + nz * side * 2.0, bob),
                    v.x(ex, ez), v.y(ez, bob * 0.3), wake)
            }
            c.drawLine(v.x(sx, sz), v.y(sz, bob), v.x(sx - hx * 18, sz - hz * 18), v.y(max(zs + 0.5, sz - hz * 18), 0.0),
                stroke(alpha(0xFFE8F4F8.toInt(), 0.6 * speedK), max(3f * d, v.size(2.5, z))))
        }
        // Water jets, not propellers: a churned white plume straight out of the transom
        if (afloat) {
            val sx = x - hx * LB_L / 2; val sz = z - hz * LB_L / 2
            val jet = p(alpha(0xFFFFFFFF.toInt(), 0.8 * speedK))
            for (i in 0..4) {
                val jx = sx - hx * i * 1.6; val jz = sz - hz * i * 1.6
                if (jz > zs + 0.5) c.drawCircle(v.x(jx, jz), v.y(jz, bob + 0.5 * speedK * (1 - i / 5.0)), max(2f * d, v.size(1.3 + 0.2 * i, jz)), jet)
            }
        }
        val nx = -hz; val nz = hx
        fun pt(uu: Double, sd: Double, hh: Double): Pair<Float, Float> {
            val px = x + hx * uu * LB_L / 2 + nx * sd; val pz = z + hz * uu * LB_L / 2 + nz * sd
            return Pair(v.x(px, pz), v.y(pz, bob + hh))
        }
        fun poly(col: Int, vararg q: Pair<Float, Float>) {
            val path = Path(); path.moveTo(q[0].first, q[0].second); for (i in 1 until q.size) path.lineTo(q[i].first, q[i].second); path.close()
            c.drawPath(path, p(col))
        }
        // Hull: freeboard about 1.3 m aft rising to 2.3 m at the bow
        val deckH = { uu: Double -> bob + 1.3 + 1.0 * max(0.0, uu).pow(1.5) }
        c.drawPath(side(v, x, z, hx, hz, LB_L, deckH) { bob }, p(navy))
        c.drawPath(outline(v, x, z, hx, hz, LB_L, hullW, deckH), p(navy))
        // Grey fendering round the sheer
        c.drawPath(side(v, x, z, hx, hz, LB_L, { uu -> deckH(uu) - 0.02 }) { uu -> deckH(uu) - 0.3 }, p(shadeDay(0xFFB9C0C8.toInt(), day)))
        // Orange superstructure, just aft of midships, with a raked front: roof about 3.4 m above the water
        val roof = 3.4
        val fr = 0.14; val ft = 0.02; val ar = -0.42                // front base, front top, aft (fractions of half-length)
        for (sd in listOf(1.6, -1.6)) poly(orange, pt(fr, sd, 1.45), pt(ft, sd * 0.85, roof), pt(ar, sd * 0.85, roof), pt(ar, sd, 1.35))
        poly(orange, pt(fr, 1.6, 1.45), pt(ft, 1.36, roof), pt(ft, -1.36, roof), pt(fr, -1.6, 1.45))      // raked front
        poly(shadeDay(0xFFE35C14.toInt(), day), pt(ft, 1.36, roof), pt(ar, 1.36, roof), pt(ar, -1.36, roof), pt(ft, -1.36, roof))   // roof
        // Wheelhouse windows: a dark band across the front and down the sides
        val glassCol = shadeDay(0xFF1B2533.toInt(), day)
        poly(glassCol, pt(fr - 0.03, 1.5, 2.3), pt(ft + 0.01, 1.3, roof - 0.25), pt(ft + 0.01, -1.3, roof - 0.25), pt(fr - 0.03, -1.5, 2.3))
        for (sd in listOf(1.58, -1.58)) poly(glassCol, pt(ft - 0.02, sd * 0.88, roof - 0.3), pt(-0.25, sd * 0.88, roof - 0.3), pt(-0.25, sd * 0.95, 2.4), pt(fr - 0.05, sd, 2.4))
        // Short radar mast with the blue light
        val (mx0, my0) = pt(-0.2, 0.0, roof)
        val (mx1, my1) = pt(-0.2, 0.0, roof + 1.3)
        c.drawLine(mx0, my0, mx1, my1, stroke(white, max(1.2f * d, v.size(0.12, z))))
        c.drawLine(pt(-0.2, 0.7, roof + 1.0).first, pt(-0.2, 0.7, roof + 1.0).second, pt(-0.2, -0.7, roof + 1.0).first, pt(-0.2, -0.7, roof + 1.0).second,
            stroke(white, max(1.2f * d, v.size(0.1, z))))
        if ((t % 1.0f) < 0.25f) {
            val r = max(3f * d, v.size(0.4, z))
            c.drawCircle(mx1, my1, r * 2.2f, p(alpha(0xFF3D7BFF.toInt(), 0.35)))
            c.drawCircle(mx1, my1, r, p(0xFF8FB4FF.toInt()))
        }
    }

    // ---- the rescue helicopter ---------------------------------------------------------

    private const val HELI_IN = 70.0          // flying in
    private const val HELI_HOVER = 160.0      // hovering over the sea, winch down
    private const val HELI_OUT = 70.0         // flying away
    private const val HELI_T = HELI_IN + HELI_HOVER + HELI_OUT

    private fun heliVisit(s: SceneInput, wall: Double): Long? = s.heliTimes.lastOrNull { wall >= it && wall < it + HELI_T }

    private fun heli(c: Canvas, v: View3, d: Float, t: Float, s: SceneInput, zs: Double, sw: Swell, day: Double, wall: Double) {
        val start = heliVisit(s, wall) ?: return
        val e = wall - start
        val r = hash((start / 60).toInt())
        val dir = if (r < 0.5) 1.0 else -1.0                       // which way it crosses
        val hz = zs + 60.0 + 70.0 * hash((start / 60).toInt() + 3)
        val half = (v.w / 2.0) / v.f
        val hx = (r - 0.5) * 0.5 * half * hz                       // where it hovers
        val (x, z, alt) = when {
            e < HELI_IN -> {
                val u = smooth(e / HELI_IN)
                val sx = hx - dir * (half * (hz + 80) * 1.3 + 40)
                Triple(sx + (hx - sx) * u, hz + 80 * (1 - u), 70.0 - 40.0 * u)
            }
            e < HELI_IN + HELI_HOVER -> {
                // holding station, drifting a little (eased in and out so it doesn't twitch)
                val wob = smooth((e - HELI_IN) / 5.0) * smooth((HELI_IN + HELI_HOVER - e) / 5.0)
                Triple(hx + 1.5 * sin(e * 0.3) * wob, hz, 30.0 + 1.0 * sin(e * 0.7) * wob)
            }
            else -> {
                val u = smooth((e - HELI_IN - HELI_HOVER) / HELI_OUT)
                val ex = hx + dir * (half * (hz + 120) * 1.3 + 40)
                Triple(hx + (ex - hx) * u, hz + 120 * u, 30.0 + 50.0 * u)
            }
        }
        val k = v.size(1.0, z)
        val bx = v.x(x, z); val by = v.y(z, alt)
        val f = dir.toFloat()
        val hovering = e >= HELI_IN && e < HELI_IN + HELI_HOVER
        // Rotor downwash whipping up spray on the sea under it
        if (alt < 45) {
            val sea = v.y(z); val wash = (1 - (alt - 25) / 20).coerceIn(0.0, 1.0)
            val spray = p(alpha(0xFFFFFFFF.toInt(), 0.35 * wash))
            for (m in 0 until 9) {
                val a = m / 9.0 * 2 * PI + t * 1.5
                c.drawCircle(v.x(x + 9 * cos(a), z + 4 * sin(a)), sea - (2f + hash(m).toFloat() * 3f) * k * 0.3f, max(2f * d, k * 1.6f), spray)
            }
        }
        // Winch line with someone on it while hovering
        if (hovering) {
            val w = e - HELI_IN
            val drop = ((if (w < 40) w / 40 else if (w > HELI_HOVER - 40) (HELI_HOVER - w) / 40 else 1.0) * (alt - 1.0)).coerceAtLeast(0.0)
            val ly = v.y(z, alt - drop)
            c.drawLine(bx + 1.5f * k * f, by + 1.2f * k, bx + 1.5f * k * f, ly, stroke(shadeDay(0xFF2A2A2A.toInt(), day), max(1f * d, k * 0.06f)))
            c.drawCircle(bx + 1.5f * k * f, ly + 0.5f * k, max(1.5f * d, k * 0.35f), p(shadeDay(0xFFFF8A00.toInt(), day)))
        }
        val red = shadeDay(0xFFD8231F.toInt(), day)
        val white = shadeDay(0xFFF2F2F2.toInt(), day)
        val glass = shadeDay(0xFF1E2A38.toInt(), day)
        // Tail boom and fin
        c.drawLine(bx - 2f * k * f, by, bx - 10f * k * f, by - 1.2f * k, stroke(red, max(2f * d, k * 0.9f)))
        c.drawPath(Path().apply { moveTo(bx - 9.3f * k * f, by - 1.0f * k); lineTo(bx - 10.6f * k * f, by - 3.6f * k); lineTo(bx - 11f * k * f, by - 1.1f * k); close() }, p(red))
        // tail rotor: a spinning disc
        c.drawCircle(bx - 10.5f * k * f, by - 2.2f * k, max(2f * d, k * 1.4f), p(alpha(0xFF9AA3AD.toInt(), 0.35)))
        // Body
        c.drawOval(RectF(bx - 3.5f * k, by - 1.8f * k, bx + 4.5f * k, by + 1.8f * k).also { if (f < 0) { val l = it.left; it.left = 2 * bx - it.right; it.right = 2 * bx - l } }, p(red))
        c.drawRect(bx - 3f * k, by - 0.1f * k, bx + 3.8f * k * 0 + 3f * k, by + 0.45f * k, p(white))          // white band
        c.drawOval(RectF(bx + 2.2f * k * f - 1.3f * k, by - 1.4f * k, bx + 2.2f * k * f + 1.3f * k, by - 0.1f * k), p(glass))   // cockpit
        // Wheels
        c.drawCircle(bx - 1.5f * k, by + 2.1f * k, max(1.2f * d, k * 0.35f), p(glass))
        c.drawCircle(bx + 2f * k * f, by + 2.1f * k, max(1.2f * d, k * 0.35f), p(glass))
        // Main rotor: a blur with a blade or two showing
        c.drawLine(bx, by - 1.8f * k, bx, by - 2.6f * k, stroke(glass, max(1.5f * d, k * 0.3f)))
        c.drawOval(RectF(bx - 8.5f * k, by - 2.9f * k, bx + 8.5f * k, by - 2.3f * k), p(alpha(0xFF9AA3AD.toInt(), 0.35)))
        val bl = (cos(t * 25.0) * 8.5).toFloat() * k
        c.drawLine(bx - bl, by - 2.6f * k, bx + bl, by - 2.6f * k, stroke(alpha(glass.toInt(), 0.7), max(1.2f * d, k * 0.2f)))
        // Anti-collision light, and a searchlight on the sea at night
        if ((t % 1.2f) < 0.15f) c.drawCircle(bx, by - 3f * k, max(2.5f * d, k * 0.6f), p(0xFFFF3B30.toInt()))
        if (day < 0.5 && hovering) {
            val sea = v.y(z)
            c.drawPath(Path().apply { moveTo(bx + 3f * k * f, by + 1f * k); lineTo(bx - 4f * k, sea); lineTo(bx + 10f * k, sea); close() },
                p(alpha(0xFFFFF6D8.toInt(), 0.18 * (1 - day))))
        }
    }

    // ---- inshore lifeboat training ------------------------------------------------------

    private const val RIB_L = 8.4            // an Atlantic-type inshore RIB: 8.4 m, crew of four

    /** Sunday mornings 09:00 to 10:00 the inshore crew train offshore, unless it's too wild. */
    private fun ribSession(s: SceneInput, wall: Double): Double? {
        if (s.windKn >= 34 || s.code >= 95) return null
        val z = java.time.Instant.ofEpochSecond(wall.toLong()).atZone(java.time.ZoneId.systemDefault())
        if (z.dayOfWeek != java.time.DayOfWeek.SUNDAY || z.hour != 9) return null
        return z.minute * 60.0 + z.second + (wall - kotlin.math.floor(wall))
    }

    /**
     * Where the RIB is during the hour. It comes in from one side, then runs a programme of
     * drills a couple of hundred metres out: high-speed runs, a man-overboard recovery,
     * figures of eight and station-holding. Every drill starts and ends at the same spot so
     * they join up. Returns x, z and how many crew are aboard (3 while one is in the water)
     * plus the casualty's position.
     */
    private class RibState(val x: Double, val z: Double, val aboard: Int, val mobX: Double?, val mobZ: Double?)

    private fun ribAt(e: Double, bx: Double, bz: Double, side: Double, halfAt: (Double) -> Double): RibState {
        val away = halfAt(bz) * 1.3 + 40
        if (e < 120) { val u = smooth(e / 120); return RibState(bx + side * away * (1 - u), bz, 4, null, null) }
        if (e > 3480) {
            // heading home from wherever the last drill left it
            val p0 = ribAt(3479.99, bx, bz, side, halfAt)
            val u = smooth((e - 3480) / 120)
            return RibState(p0.x + (bx - side * away - p0.x) * u, p0.z + 40 * u, 4, null, null)
        }
        val k = (e - 120) % 600
        return when {
            k < 150 -> {                                         // high-speed runs across the view and back
                val ph = 2 * PI * k / 150
                RibState(bx + 0.6 * halfAt(bz) * sin(ph), bz + 30 * sin(2 * ph), 4, null, null)
            }
            k < 480 -> {                                         // man overboard: drop, circle round, recover
                val tk = k - 150
                val r = 25.0
                val th0 = 0.6
                val th = when {
                    tk < 160 -> (2 * PI + th0) * smooth(tk / 160)
                    tk < 220 -> 2 * PI + th0                     // stopped alongside, hauling them back in
                    else -> 2 * PI + th0 + (2 * PI - th0) * smooth((tk - 220) / 110)
                }
                val x = bx + r * sin(th); val z = bz + r * (1 - cos(th))
                val inWater = tk in 15.0..220.0
                RibState(x, z, if (inWater) 3 else 4, if (inWater) bx + r * sin(th0) else null, if (inWater) bz + r * (1 - cos(th0)) else null)
            }
            k < 560 -> {                                         // figure of eight
                val ph = 2 * PI * (k - 480) / 80
                RibState(bx + 28 * sin(ph), bz + 18 * sin(ph) * cos(ph), 4, null, null)
            }
            else -> RibState(bx + 1.5 * sin((k - 560) / 40 * 2 * PI), bz, 4, null, null)   // station-holding, crew swap round
        }
    }

    private fun rib(c: Canvas, v: View3, d: Float, t: Float, s: SceneInput, zs: Double, sw: Swell, day: Double, wall: Double) {
        val e = ribSession(s, wall) ?: return
        val week = (wall / (7 * 86400)).toInt()
        val side = if (hash(week) < 0.5) 1.0 else -1.0
        val bz = zs + 180.0 + 60.0 * hash(week + 1)
        val half = { zz: Double -> (v.w / 2.0) / v.f * zz }
        val bx = (hash(week + 2) - 0.5) * 0.5 * half(bz)
        val now = ribAt(e, bx, bz, side, half)
        val next = ribAt(e + 0.5, bx, bz, side, half)
        val spd = kotlin.math.hypot(next.x - now.x, next.z - now.z) / 0.5      // m/s
        val (hx, hz) = if (spd > 0.2) norm(next.x - now.x, next.z - now.z) else norm(side, 0.3)
        val x = now.x; val z = now.z
        if (layerQueue != null) { later(x, z) { rib(c, v, d, t, s, zs, sw, day, wall) }; return }
        val bob = sw.height(t, x, z)
        val plane = smooth((spd - 4) / 6)                       // up on the plane, bow lifted
        val navy = shadeDay(0xFF13294B.toInt(), day)
        val tube = shadeDay(0xFFFF6A13.toInt(), day)
        val nx = -hz; val nz = hx
        // Wake and bow spray when moving fast
        if (spd > 3) {
            val foam = p(alpha(0xFFFFFFFF.toInt(), 0.75 * plane))
            for (i in 0..6) {
                val wx = x - hx * (RIB_L / 2 + i * 2.5); val wz = z - hz * (RIB_L / 2 + i * 2.5)
                c.drawCircle(v.x(wx, wz), v.y(wz, bob), max(1.5f * d, v.size(0.9 + i * 0.15, wz)), foam)
            }
            c.drawCircle(v.x(x + hx * RIB_L * 0.35 + nx * 1.4, z + hz * RIB_L * 0.35 + nz * 1.4), v.y(z, bob + 0.3), max(1.5f * d, v.size(0.7, z)), foam)
            c.drawCircle(v.x(x + hx * RIB_L * 0.35 - nx * 1.4, z + hz * RIB_L * 0.35 - nz * 1.4), v.y(z, bob + 0.3), max(1.5f * d, v.size(0.7, z)), foam)
        }
        val tubeH = { u: Double -> bob + 0.55 + 0.6 * plane * max(0.0, u) }         // bow rises on the plane
        val ribW = { u: Double -> 1.35 * (if (u > 0) (1 - u.pow(2.2)).coerceAtLeast(0.0).pow(0.5) else 1.0) }
        val keel = { u: Double -> bob + 0.1 * plane * max(0.0, u) }
        c.drawPath(side(v, x, z, hx, hz, RIB_L, { u -> keel(u) + 0.15 }, keel), p(navy))                 // the hull under the tubes
        c.drawPath(side(v, x, z, hx, hz, RIB_L, tubeH) { u -> keel(u) + 0.12 }, p(tube))                 // the big orange tube, side-on
        c.drawPath(outline(v, x, z, hx, hz, RIB_L, ribW, tubeH), p(tube))
        c.drawPath(outline(v, x, z, hx, hz, RIB_L * 0.8, { u -> ribW(u) * 0.45 }, tubeH), p(navy))    // the deck inside the fat orange tubes
        // Console and the A-frame aft
        fun at(u: Double, sd: Double) = Pair(x + hx * u * RIB_L / 2 + nx * sd, z + hz * u * RIB_L / 2 + nz * sd)
        val (cx, cz) = at(0.05, 0.0)
        c.drawLine(v.x(cx, cz), v.y(cz, tubeH(0.05)), v.x(cx, cz), v.y(cz, tubeH(0.05) + 0.9), stroke(shadeDay(0xFFB0B6BE.toInt(), day), max(2f * d, v.size(0.6, cz))))
        val (a1x, a1z) = at(-0.8, 0.9); val (a2x, a2z) = at(-0.8, -0.9)
        val frame = stroke(shadeDay(0xFF30343A.toInt(), day), max(1f * d, v.size(0.1, z)))
        c.drawLine(v.x(a1x, a1z), v.y(a1z, tubeH(-0.8)), v.x(a1x, a1z), v.y(a1z, tubeH(-0.8) + 1.6), frame)
        c.drawLine(v.x(a2x, a2z), v.y(a2z, tubeH(-0.8)), v.x(a2x, a2z), v.y(a2z, tubeH(-0.8) + 1.6), frame)
        c.drawLine(v.x(a1x, a1z), v.y(a1z, tubeH(-0.8) + 1.6), v.x(a2x, a2z), v.y(a2z, tubeH(-0.8) + 1.6), frame)
        // Crew: helm and navigator standing at the console, two more forward and aft (one in the water during the drill)
        val suit = shadeDay(0xFFFFC21A.toInt(), day)
        val helmet = shadeDay(0xFFF4F4F4.toInt(), day)
        val spots = listOf(Triple(-0.05, 0.35, 1.5), Triple(-0.05, -0.35, 1.5), Triple(0.45, 0.4, 1.0), Triple(-0.5, -0.4, 1.0))
        for ((n, sp) in spots.withIndex()) {
            if (n >= now.aboard) break
            val (px, pz) = at(sp.first, sp.second)
            val base = tubeH(sp.first) - 0.2
            c.drawLine(v.x(px, pz), v.y(pz, base), v.x(px, pz), v.y(pz, base + sp.third * 0.8), stroke(suit, max(1.4f * d, v.size(0.4, pz))))
            c.drawCircle(v.x(px, pz), v.y(pz, base + sp.third * 0.8 + 0.15), max(0.7f * d, v.size(0.15, pz)), p(helmet))
        }
        // The "casualty" in the water: a head, a yellow suit and a splash
        if (now.mobX != null && now.mobZ != null) {
            val mh = sw.height(t, now.mobX, now.mobZ)
            c.drawCircle(v.x(now.mobX, now.mobZ), v.y(now.mobZ, mh + 0.1), max(1.2f * d, v.size(0.5, now.mobZ)), p(alpha(0xFFFFFFFF.toInt(), 0.7)))
            c.drawCircle(v.x(now.mobX, now.mobZ), v.y(now.mobZ, mh + 0.15), max(0.7f * d, v.size(0.18, now.mobZ)), p(helmet))
        }
        // Blue light on the A-frame
        if ((t % 1.0f) < 0.25f) c.drawCircle(v.x((a1x + a2x) / 2, (a1z + a2z) / 2), v.y(a1z, tubeH(-0.8) + 1.75), max(0.9f * d, v.size(0.3, z)), p(0xFF8FB4FF.toInt()))
    }

    // ---- ships on the horizon ----------------------------------------------------------

    /**
     * A container ship passes far out in the shipping lane about every two hours: 200-400 m
     * long, 5-8 km off, at 12-18 knots, so it takes the best part of half an hour to cross
     * the view. Each two-hour slot has its own ship (size, colours, direction, timing), fixed
     * by the clock so the app and the wallpaper agree. Lost in fog; lit up at night.
     */
    private fun ships(c: Canvas, v: View3, d: Float, s: SceneInput, day: Double, fog: Double, wall: Double) {
        val slot = 7200.0
        val n0 = kotlin.math.floor(wall / slot).toLong()
        for (n in n0 - 1..n0) {                                // a ship from the last slot may still be crossing
            fun r(i: Int) = hash((n % 100000).toInt() * 31 + i)
            val dist = 5000.0 + 3000.0 * r(1)
            if (s.visibilityM < dist * 0.9) continue           // out of sight in the murk
            val len = 200.0 + 200.0 * r(2)
            val speed = (12.0 + 6.0 * r(3)) * 0.5144           // knots to m/s
            val dir = if (r(4) < 0.5) 1.0 else -1.0
            val half = (v.w / 2.0) / v.f * dist + len
            val startAt = n * slot + r(5) * 3600.0
            val x = -dir * half + dir * speed * (wall - startAt)
            if (wall < startAt || abs(x) > half) continue
            val k = v.size(1.0, dist)
            val bx = v.x(x, dist); val wl = v.y(dist, 0.0)
            val haze = (0.45 + 0.5 * (1 - fog)) * (0.75 + 0.25 * (1 - (dist - 5000) / 3000))
            val hullCol = mix(intArrayOf(0xFF1D2B44.toInt(), 0xFF6B1E1E.toInt(), 0xFF1E1E1E.toInt(), 0xFF1F4A3A.toInt())[(r(6) * 4).toInt().coerceIn(0, 3)],
                0xFF8FA3B5.toInt(), 1 - haze)
            val hullCol2 = shadeDay(hullCol, day)
            val f = dir.toFloat()
            val L = (len * k).toFloat()
            // Hull, about 15 m showing, with a raked bow and a squared-off stern
            val hullH = (15 * k).toFloat()
            val hull = Path().apply {
                moveTo(bx - L / 2 * f, wl); lineTo(bx + L * 0.47f * f, wl); lineTo(bx + L / 2 * f, wl - hullH * 1.1f)
                lineTo(bx - L / 2 * f, wl - hullH); close()
            }
            c.drawPath(hull, p(hullCol2))
            // Containers stacked along most of the deck, in random colours, faded by distance
            val boxes = intArrayOf(0xFFB5462F.toInt(), 0xFF2F6DB5.toInt(), 0xFFD9A43A.toInt(), 0xFF4E8A4A.toInt(), 0xFF9AA3AD.toInt(), 0xFF7A3B6E.toInt())
            val bays = 14
            val stackTop = (12 + 10 * r(7)) * k
            for (b in 0 until bays) {
                val u0 = -0.38 + 0.78 * b / bays; val u1 = u0 + 0.78 / bays * 0.92
                val hb = (stackTop * (0.7 + 0.3 * hash((n % 100000).toInt() * 7 + b))).toFloat()
                val col = mix(boxes[(hash((n % 100000).toInt() * 11 + b) * boxes.size).toInt().coerceIn(0, boxes.size - 1)], 0xFF8FA3B5.toInt(), 1 - haze)
                c.drawRect(bx + (u0 * L).toFloat() * f, wl - hullH - hb, bx + (u1 * L).toFloat() * f, wl - hullH, p(shadeDay(col, day)))
            }
            // Bridge block near the stern, and the funnel behind it
            val tower = (34 * k).toFloat()
            val white = shadeDay(mix(0xFFEDEDED.toInt(), 0xFF8FA3B5.toInt(), 1 - haze), day)
            val tx = bx - L * 0.4f * f
            c.drawRect(min(tx, tx + 0.06f * L * f), wl - hullH - tower, max(tx, tx + 0.06f * L * f), wl - hullH, p(white))
            c.drawRect(min(tx - 0.03f * L * f, tx + 0.09f * L * f), wl - hullH - tower, max(tx - 0.03f * L * f, tx + 0.09f * L * f), wl - hullH - tower + max(1f, tower * 0.12f), p(white))
            c.drawRect(min(tx - 0.05f * L * f, tx - 0.02f * L * f), wl - hullH - tower * 0.9f, max(tx - 0.05f * L * f, tx - 0.02f * L * f), wl - hullH, p(hullCol2))
            // Navigation lights after dark: white masthead, and red or green on the side we can see
            if (day < 0.5) {
                val a = (1 - day * 2)
                c.drawCircle(tx, wl - hullH - tower - 2 * d, 1.5f * d, p(alpha(0xFFFFF6D8.toInt(), a)))
                c.drawCircle(bx + L * 0.3f * f, wl - hullH - (stackTop + 6 * k).toFloat(), 1.3f * d, p(alpha(0xFFFFF6D8.toInt(), a)))
                c.drawCircle(bx, wl - hullH * 0.6f, 1.2f * d, p(alpha(if (dir > 0) 0xFF3DDC84.toInt() else 0xFFFF3B30.toInt(), a)))
            }
        }
    }

    // ---- the beach ------------------------------------------------------------------------

    /**
     * The water's edge: never a straight line. The beach has gentle cusps and bays, each wave
     * runs up the sand in lobes (reaching the peak first and the shoulders a moment later), leaves
     * a lace of foam and a dark wet sheen, and drains back before the next. How far it runs
     * depends on the breaker size and type.
     */
    private fun shoreline(c: Canvas, v: View3, d: Float, t: Float, s: SceneInput, zs: Double, day: Double, overcast: Double) {
        val sw = Swell(s, zs, v.camH)
        val near = mix(mix(0xFF0B1A2C.toInt(), 0xFF1F6E8C.toInt(), day), 0xFF3E5566.toInt(), overcast * 0.5)
        val sheet = mix(near, 0xFFB9DCE4.toInt(), 0.35)
        val flat = s.waveM < FLAT_M
        val runup = if (flat) 0.25 else (0.5 + sw.average.hb * 1.3) * (if (sw.average.type == Surfzone.Type.SURGING) 1.5 else 1.0)
        val step = 5f * d
        val n = (v.w / step).toInt() + 2
        val peakX = -sw.peel * 7.0
        val edge = FloatArray(n); val reach = FloatArray(n); val xsA = FloatArray(n)
        var foamLevel = 0.0
        for (i in 0 until n) {
            val xs = i * step
            val xm = (xs - v.w / 2) / v.f * zs
            // the shape of the beach itself: slow bays and small cusps, always a little water over the line
            val cusp = 0.25 + 0.2 * (sin(xm * 0.11 + 1.3) + 1) + 0.12 * (sin(xm * 0.37 + 4.0) + 1)
            // this wave's run-up here: arrives at the peak first, spreads along, in uneven lobes
            val ph = ((t / sw.tv - abs(xm - peakX) * 0.012) % 1.0 + 1.0) % 1.0
            val run = if (ph < 0.3) smooth(ph / 0.3) else 1 - smooth((ph - 0.3) / 0.6)
            val lobe = 0.7 + 0.3 * sin(xm * 0.45 + kotlin.math.floor(t / sw.tv) * 1.7)
            val z = max(1.4, zs - cusp - runup * run * lobe)
            val zMax = max(1.4, zs - cusp - runup * lobe)
            xsA[i] = xs; edge[i] = v.y(z); reach[i] = v.y(zMax)
            foamLevel = max(foamLevel, run)
        }
        val top = v.y(zs + 0.8)
        // dark wet sheen where the last wave reached
        val wetP = Path(); wetP.moveTo(0f, top)
        for (i in 0 until n) wetP.lineTo(xsA[i], reach[i])
        wetP.lineTo(v.w, top); wetP.close()
        c.drawPath(wetP, p(alpha(shadeDay(0xFF6E5A3E.toInt(), day), 0.3)))
        // the thin sheet of water
        val water = Path(); water.moveTo(0f, top)
        for (i in 0 until n) water.lineTo(xsA[i], edge[i])
        water.lineTo(v.w, top); water.close()
        c.drawPath(water, p(alpha(sheet, 0.85)))
        val sheen = Path(); sheen.moveTo(0f, top)
        for (i in 0 until n) sheen.lineTo(xsA[i], top + (edge[i] - top) * 0.55f)
        sheen.lineTo(v.w, top); sheen.close()
        c.drawPath(sheen, p(alpha(near, 0.45)))
        // the shore dump: a band of churned white water rushing up the sand with each wave
        if (!flat) run {
            val fp = p(alpha(mix(0xFFDCE6EA.toInt(), 0xFFF6FBFC.toInt(), day), 0.9))
            val fs = p(alpha(mix(0xFF8FA7B1.toInt(), 0xFFC3D5DC.toInt(), day), 0.8))
            val hTop = FloatArray(n)
            for (i in 0 until n) {
                val xm = (xsA[i] - v.w / 2) / v.f * zs
                val ph = ((t / sw.tv - abs(xm - peakX) * 0.012) % 1.0 + 1.0) % 1.0
                val surge = if (ph < 0.32) 1 - smooth(ph / 0.32) * 0.75 else (1 - smooth((ph - 0.32) / 0.2)) * 0.25
                val rag = 0.55 + 0.6 * vnoise(xm * 0.7, t * 0.8, 3.3) + 0.25 * vnoise(xm * 2.3, t * 1.5, 9.1)
                hTop[i] = (max(0.0, surge - 0.05) * rag).toFloat() * max(1.5f * d, v.size(0.12 + min(sw.average.hb, 2.5) * 0.2, zs))
            }
            // runs of white where the bore is still churning; it thins to nothing as it spreads out
            var i = 0
            while (i < n) {
                if (hTop[i] < 0.4f * d) { i++; continue }
                val st = i
                while (i < n && hTop[i] >= 0.4f * d) i++
                if (i - st < 2) continue
                val band = Path(); val under = Path()
                for (m in st until i) { val y = edge[m] - hTop[m]; if (m == st) band.moveTo(xsA[m], y) else band.lineTo(xsA[m], y) }
                for (m in i - 1 downTo st) band.lineTo(xsA[m], edge[m] + hTop[m] * 0.15f)
                band.close()
                for (m in st until i) { val y = edge[m] - hTop[m] * 0.35f; if (m == st) under.moveTo(xsA[m], y) else under.lineTo(xsA[m], y) }
                for (m in i - 1 downTo st) under.lineTo(xsA[m], edge[m] + hTop[m] * 0.35f)
                under.close()
                c.drawPath(band, fp)
                c.drawPath(under, fs)
            }
        }
        // the lace of foam along the edge, with a few bubbles
        val lace = Path()
        for (i in 0 until n) if (i == 0) lace.moveTo(xsA[i], edge[i]) else lace.lineTo(xsA[i], edge[i])
        c.drawPath(lace, stroke(alpha(0xFFFFFFFF.toInt(), 0.85), 1.6f * d))
        val bub = p(alpha(0xFFFFFFFF.toInt(), 0.7))
        for (i in 0 until n step 5) {
            val r = (0.5f + hash(i + 2300).toFloat() * 0.8f) * d
            c.drawCircle(xsA[i] + (hash(i + 2310).toFloat() - 0.5f) * step, edge[i] - r * (0.5f + hash(i + 2320).toFloat()) * 1.5f, r, bub)
        }
    }

    private fun beach(c: Canvas, v: View3, d: Float, t: Float, s: SceneInput, wind: WindView, day: Double, overcast: Double, wall: Double) {
        val zs = shoreZ(s)
        val ys = v.y(zs)
        val wet = shadeDay(mix(0xFFB89A6A.toInt(), 0xFF8C7A5E.toInt(), overcast * 0.4), day)
        val dry = shadeDay(mix(0xFFE9CF9A.toInt(), 0xFFC8B48E.toInt(), overcast * 0.4), day)
        val fill = p(0)
        val bands = 48
        val yHigh = v.y(HIGH_Z)
        for (i in 0 until bands) {
            val y0 = ys + (v.bottom - ys) * i / bands
            val y1 = ys + (v.bottom - ys) * (i + 1) / bands
            val zMid = v.f * v.camH / ((y0 + y1) / 2 - v.horizon)
            fill.color = if (zMid > HIGH_Z) wet else mix(wet, dry, ((HIGH_Z - zMid) / 2.0).coerceIn(0.0, 1.0))
            c.drawRect(0f, y0, v.w, y1 + 1f, fill)
        }
        // The water's edge, with each wave running up the sand and draining back
        shoreline(c, v, d, t, s, zs, day, overcast)
        // High-water mark: a line of weed
        if (yHigh > ys) {
            val weed = p(shadeDay(0xFF4F5B3A.toInt(), day))
            for (i in 0 until 40) {
                val x = (hash(i + 900) * v.w).toFloat()
                c.drawOval(RectF(x - 4f * d, yHigh - 1f * d, x + 4f * d, yHigh + 1.5f * d), weed)
            }
        }
        // The beach's name written in the sand
        nameInSand(c, v, d, s, day)
        // Tide sign with the scrolling amber display
        boats(c, v, d, t, s, zs, Swell(s, zs), day, onSand = true, behindSign = true)
        sign(c, v, d, t, s, day, wall)
        boats(c, v, d, t, s, zs, Swell(s, zs), day, onSand = true)
    }

    private fun sign(c: Canvas, v: View3, d: Float, t: Float, s: SceneInput, day: Double, wall: Double) {
        // Set back up the beach and over to the left, on a tall post, so the kayak and board don't hide it
        val zm = SIGN_Z; val xm = across(v, zm, -0.4)
        val x = v.x(xm, zm); val yb = v.y(zm)
        val postH = v.size(1.25, zm); val bw = v.size(1.3, zm); val bh = v.size(0.52, zm)
        c.drawRect(x - 2f * d, yb - postH, x + 2f * d, yb, p(shadeDay(0xFF6B4A2E.toInt(), day)))
        val r = RectF(x - bw / 2, yb - postH - bh * 0.4f, x + bw / 2, yb - postH + bh * 0.6f)
        c.drawRoundRect(r, 4f * d, 4f * d, p(shadeDay(0xFF1F4E6B.toInt(), day)))
        c.drawRoundRect(r, 4f * d, 4f * d, stroke(shadeDay(0xFFF4E6C8.toInt(), day), 1.5f * d))
        val tp = Draw.paint(shadeDay(0xFFFFFFFF.toInt(), day), (bh * 0.30f), bold = true, align = Paint.Align.CENTER)
        c.drawText("TIDE " + (if (s.tideRising) "▲" else "▼"), r.centerX(), r.top + bh * 0.38f, tp)
        c.drawText(s.tideSign, r.centerX(), r.top + bh * 0.78f, tp)
        // An amber dot-matrix display bolted under the board, scrolling the readings
        ledBoard(c, RectF(r.left + bw * 0.03f, r.bottom + bh * 0.04f, r.right - bw * 0.03f, r.bottom + bh * 0.04f + bh * 0.5f), d, t, subtitles(s, wall))
    }

    private val DOTS: Map<Char, Long> = mapOf(
        ' ' to 0L, '0' to 15623448110L, '1' to 4701950094L, '2' to 15603929375L, '3' to 33357366830L, '4' to 2359917634L,
        '5' to 33854359086L, '6' to 6728664622L, '7' to 33321787656L, '8' to 15621113390L, '9' to 15621129292L, 'A' to 15621226033L,
        'B' to 32801506878L, 'C' to 15620129326L, 'D' to 30687151708L, 'E' to 33840644639L, 'F' to 33840644624L, 'G' to 15620359727L,
        'H' to 18842895921L, 'I' to 15170932878L, 'J' to 7585466956L, 'K' to 18879369809L, 'L' to 17734058527L, 'M' to 19182306865L,
        'N' to 18850958897L, 'O' to 15621211694L, 'P' to 32801505808L, 'Q' to 15621215821L, 'R' to 32801509969L, 'S' to 16660235326L,
        'T' to 33424543876L, 'U' to 18842437166L, 'V' to 18842436932L, 'W' to 18842572458L, 'X' to 18834663985L, 'Y' to 18842194052L,
        'Z' to 33321787935L, '.' to 396L, ',' to 12424L, ':' to 415248768L, '-' to 1015808L, '+' to 139432064L,
        '/' to 35791360L, '(' to 2290360450L, ')' to 8726317192L, '%' to 26610901603L, '\'' to 13027508224L, '?' to 15603929092L,
        '!' to 4433514500L, '&' to 13510137421L, '°' to 13508149248L, '·' to 405504L, '▲' to 149913600L, '▼' to 32968704L,
    )


    /**
     * A scrolling amber LED dot-matrix sign: 5x7 characters, one dot gap, the message running
     * right to left at a steady pace and starting again when it's gone. Unlit dots glow faintly.
     */
    private fun ledBoard(c: Canvas, box: RectF, d: Float, t: Float, lines: List<String>) {
        val frame = p(0xFF0E0904.toInt())
        c.drawRoundRect(RectF(box.left - 1.5f * d, box.top - 1.5f * d, box.right + 1.5f * d, box.bottom + 1.5f * d), 2f * d, 2f * d, p(0xFF2B2B2B.toInt()))
        c.drawRect(box, frame)
        val rows = 9
        val pitch = box.height() / rows
        if (pitch < 0.8f) return
        val cols = (box.width() / pitch).toInt()
        val x0 = box.left + (box.width() - cols * pitch) / 2 + pitch / 2
        val y0 = box.top + pitch / 2
        val msg = lines.joinToString("   ·   ").uppercase().map { if (DOTS.containsKey(it)) it else ' ' }
        val total = msg.size * 6
        val speed = 18.0                                   // columns a second
        val shift = ((t * speed) % (total + cols)).toInt() - cols       // starts just off the right edge
        val dim = p(0x2EFF9A1A); val lit = p(0xFFFFB020.toInt()); val glow = p(0x55FF8A00)
        val r = pitch * 0.36f
        for (cx in 0 until cols) {
            val mc = cx + shift                             // which column of the message is here
            val ch = if (mc >= 0 && mc < total) msg[mc / 6] else ' '
            val gc = if (mc >= 0) mc % 6 else 5
            val bits = DOTS[ch] ?: 0L
            for (ry in 0 until 7) {
                val on = gc < 5 && ((bits shr (34 - (ry * 5 + gc))) and 1L) == 1L
                val px = x0 + cx * pitch; val py = y0 + (ry + 1) * pitch
                if (on) { c.drawCircle(px, py, r * 1.7f, glow); c.drawCircle(px, py, r, lit) }
                else c.drawCircle(px, py, r * 0.85f, dim)
            }
        }
    }

    private fun pole(c: Canvas, v: View3, d: Float, t: Float, s: SceneInput, wind: WindView, day: Double) {
        val zm = PROP_Z; val xm = across(v, zm, 0.73)
        val x = v.x(xm, zm); val yb = v.y(zm)
        val ph = v.size(2.3, zm)
        val top = yb - ph
        c.drawLine(x, yb, x, top, stroke(shadeDay(0xFFDADADA.toInt(), day), max(1.6f * d, v.size(0.04, zm))))
        val g = wind.gust(t)
        // Windsock: lifts with wind strength, points downwind, flicks in gusts.
        val lift = (PI / 2 * (0.15 + 0.85 * min((s.windKn + 6 * g) / 15.0, 1.0)))
        val L = v.size(0.72, zm) * (0.35f + 0.65f * abs(wind.side).toFloat().coerceAtLeast(0.2f))
        val dir = if (abs(wind.side) < 0.1) 1f else kotlin.math.sign(wind.side).toFloat()
        val wob = (sin(t * (4 + 10 * wind.strength) ) * (0.04 + 0.12 * g)).toFloat()
        val ang = (lift + wob).toFloat()
        val r0 = v.size(0.11, zm); val r1 = r0 * 0.55f
        val segs = 5
        for (i in 0 until segs) {
            val u0 = i / segs.toFloat(); val u1 = (i + 1) / segs.toFloat()
            fun pt(u: Float, side: Float, rr: Float): Pair<Float, Float> {
                // Bends a little along its length: the tip droops more in light air
                val a = ang - (1 - wind.strength.toFloat()) * 0.35f * u
                val bx = x + dir * sin(a) * L * u
                val by = top + 3f * d + cos(a) * L * u
                return Pair(bx - cos(a) * rr * side, by + dir * sin(a) * rr * side)
            }
            val rr0 = r0 + (r1 - r0) * u0; val rr1 = r0 + (r1 - r0) * u1
            val a0 = pt(u0, -1f, rr0); val b0 = pt(u0, 1f, rr0); val a1 = pt(u1, -1f, rr1); val b1 = pt(u1, 1f, rr1)
            val path = Path().apply { moveTo(a0.first, a0.second); lineTo(a1.first, a1.second); lineTo(b1.first, b1.second); lineTo(b0.first, b0.second); close() }
            c.drawPath(path, p(shadeDay(if (i % 2 == 0) 0xFFF26B21.toInt() else 0xFFFFFFFF.toInt(), day)))
        }
        // Beach flag in the paddle colour
        val fw = v.size(0.5, zm); val fh = v.size(0.32, zm)
        val fy = top + v.size(0.52, zm)
        val flagCol = shadeDay(when (s.paddle) { Paddle.GO -> 0xFF2DBE6C.toInt(); Paddle.MARGINAL -> 0xFFF7C948.toInt(); Paddle.NO_GO -> 0xFFE23D3D.toInt(); else -> 0xFF9AA5B1.toInt() }, day)
        val fl = Path()
        val n = 8
        val lim = (0.25 + 0.75 * min(s.windKn / 15.0, 1.0)).toFloat()
        for (i in 0..n) {
            val u = i / n.toFloat()
            val off = sin(u * 5f - t * (4f + 10f * wind.strength.toFloat())) * u * fh * 0.15f
            val xx = x + dir * fw * u * lim; val yy = fy + off + (1 - lim) * u * fh * 1.2f
            if (i == 0) fl.moveTo(xx, yy) else fl.lineTo(xx, yy)
        }
        for (i in n downTo 0) {
            val u = i / n.toFloat()
            val off = sin(u * 5f - t * (4f + 10f * wind.strength.toFloat()) + 0.5f) * u * fh * 0.15f
            fl.lineTo(x + dir * fw * u * lim, fy + fh + off + (1 - lim) * u * fh * 1.2f)
        }
        fl.close()
        c.drawPath(fl, p(flagCol))
    }

    // ---- life ----------------------------------------------------------------------------

    private fun gulls(c: Canvas, v: View3, d: Float, t: Float, s: SceneInput, wind: WindView, day: Double) {
        val col = if (day < 0.35) 0xFFB8C4D6.toInt() else 0xFF2E2E2E.toInt()
        val grounded = s.windKn >= 26 || s.rainMm >= 2.5 || s.code >= 95
        if (grounded) {
            // Sitting it out on the sand, hunched and facing into the wind, on a clear patch
            // between the board and the flagpole (furthest drawn first)
            val white = shadeDay(0xFFF2F2F2.toInt(), day)
            val grey = shadeDay(0xFF9AA3AD.toInt(), day)
            val legs = stroke(shadeDay(0xFFE8A9A0.toInt(), day), max(0.8f * d, 1f))
            for (i in 2 downTo 0) {
                val zm = PROP_Z + 0.2 + i * 0.55
                val xm = across(v, zm, 0.5 + i * 0.07 + (if (i == 1) -0.02 else 0.0))
                val x = v.x(xm, zm); val ground = v.y(zm)
                val sz = v.size(0.2, zm)
                val face = if (wind.side > 0) -1f else 1f            // beak into the wind
                val legH = sz * 0.35f
                val y = ground - legH                                  // body sits on its legs
                // shadow on the sand
                c.drawOval(RectF(x - sz * 1.1f, ground - sz * 0.12f, x + sz * 1.1f, ground + sz * 0.16f), p(alpha(shadeDay(0xFF7A6446.toInt(), day), 0.35)))
                // legs
                c.drawLine(x - sz * 0.2f, y - sz * 0.05f, x - sz * 0.25f, ground, legs)
                c.drawLine(x + sz * 0.15f, y - sz * 0.05f, x + sz * 0.12f, ground, legs)
                // body, grey wing folded on top, tail tipped up behind
                c.drawPath(Path().apply { moveTo(x - face * sz * 0.8f, y - sz * 0.5f); lineTo(x - face * sz * 1.35f, y - sz * 0.75f); lineTo(x - face * sz * 0.9f, y - sz * 0.3f); close() }, p(grey))
                c.drawOval(RectF(x - sz, y - sz * 0.85f, x + sz, y), p(white))
                c.drawOval(RectF(x - sz * 0.85f - face * sz * 0.1f, y - sz * 0.75f, x + sz * 0.55f - face * sz * 0.1f, y - sz * 0.3f), p(grey))
                // head pulled in low against the wind, and the yellow beak
                c.drawCircle(x + face * sz * 0.75f, y - sz * 0.85f, sz * 0.33f, p(white))
                c.drawCircle(x + face * sz * 0.85f, y - sz * 0.9f, max(0.6f * d, sz * 0.05f), p(shadeDay(0xFF222222.toInt(), day)))
                c.drawLine(x + face * sz * 1.02f, y - sz * 0.82f, x + face * sz * 1.35f, y - sz * 0.78f, stroke(shadeDay(0xFFF2B33D.toInt(), day), sz * 0.14f))
                c.drawCircle(x + face * sz * 1.25f, y - sz * 0.76f, max(0.5f * d, sz * 0.05f), p(shadeDay(0xFFD2372B.toInt(), day)))   // the red spot
                c.drawPath(Path().apply { moveTo(x - face * sz * 1.0f, y - sz * 0.55f); lineTo(x - face * sz * 1.4f, y - sz * 0.72f); lineTo(x - face * sz * 1.05f, y - sz * 0.42f); close() },
                    p(shadeDay(0xFF1E1E1E.toInt(), day)))                                                                    // black wingtips over the tail
            }
            return
        }
        // Light wind: drifting and soaring. Strong wind: flapping hard, barely moving, blown about.
        val drift = (12 + 30 * (1 - wind.strength)) * d * (if (abs(wind.side) < 0.1) 1.0 else wind.side) * (if (wind.strength > 0.6) 0.25 else 1.0)
        val lit = day >= 0.35
        val bodyC = if (lit) shadeDay(0xFFF4F5F6.toInt(), day) else col
        val wingC = if (lit) shadeDay(0xFFA9B3BD.toInt(), day) else col
        val tipC = if (lit) shadeDay(0xFF1E1E1E.toInt(), day) else col
        for (i in 0 until 3) {
            val span = v.w + 60f * d
            var x = ((hash(i + 21) * span + t * drift * (0.8 + 0.2 * i)) % span).toFloat()
            if (x < 0) x += span
            x -= 30f * d
            val y = (v.skyTop + (v.horizon - v.skyTop) * (0.35 + 0.12 * i)).toFloat() + sin(t * 0.8f + i * 2f) * 6f * d +
                (if (wind.strength > 0.5) sin(t * 5f + i) * 4f * d else 0f)
            val sz = (7f + 2f * i) * d                         // half the wingspan on screen
            val fx = if (drift >= 0) 1f else -1f               // which way it's heading
            // Wingbeats: steady flapping in a blow; in light air long glides with a few lazy flaps
            val beat = (2.5 + 9 * wind.strength) * (1 + 0.1 * i)
            val glide = if (wind.strength < 0.25) smooth((sin(t * 0.4 + i * 2.3) + 0.3) / 0.6) else 0.0
            val ph = sin(t * beat + i * 1.7)
            val flap = (ph * (1 - glide)).toFloat()
            // inner wing up from the shoulder, outer wing bent down from the wrist (the gull's "M")
            val inner = (0.28f + 0.55f * flap)                   // radians above level
            val outer = inner - (0.55f + 0.35f * (1 - flap) / 2)
            fun wingPath(sd: Float): Path {
                val shX = x + sd * sz * 0.08f; val shY = y
                val wX = shX + sd * sz * 0.48f * cos(inner); val wY = shY - sz * 0.48f * sin(inner)
                val tX = wX + sd * sz * 0.56f * cos(outer); val tY = wY - sz * 0.56f * sin(outer)
                val chord = sz * 0.2f
                return Path().apply {
                    moveTo(shX, shY - chord * 0.3f)
                    quadTo((shX + wX) / 2, (shY + wY) / 2 - chord * 0.35f, wX, wY - chord * 0.2f)
                    lineTo(tX, tY)
                    lineTo(wX - sd * chord * 0.25f, wY + chord * 0.45f)
                    quadTo((shX + wX) / 2, (shY + wY) / 2 + chord * 0.45f, shX, shY + chord * 0.5f)
                    close()
                }
            }
            if (sz < 6f * d || !lit) {
                // small and far, or against a bright sky: just the silhouette
                for (sd in listOf(-1f, 1f)) c.drawPath(wingPath(sd), p(col))
                c.drawOval(RectF(x - sz * 0.22f, y - sz * 0.07f, x + sz * 0.22f, y + sz * 0.07f), p(col))
                continue
            }
            for (sd in listOf(-1f, 1f)) {
                c.drawPath(wingPath(sd), p(wingC))
                // black wingtips
                val shX = x + sd * sz * 0.08f
                val wX = shX + sd * sz * 0.48f * cos(inner); val wY = y - sz * 0.48f * sin(inner)
                val tX = wX + sd * sz * 0.56f * cos(outer); val tY = wY - sz * 0.56f * sin(outer)
                limb(c, wX + (tX - wX) * 0.62f, wY + (tY - wY) * 0.62f, tX, tY, sz * 0.14f, sz * 0.04f, p(tipC))
            }
            // white body and head, tail behind, little yellow beak
            c.drawOval(RectF(x - sz * 0.3f, y - sz * 0.09f, x + sz * 0.3f, y + sz * 0.1f), p(bodyC))
            c.drawCircle(x + fx * sz * 0.3f, y - sz * 0.05f, sz * 0.09f, p(bodyC))
            c.drawLine(x + fx * sz * 0.38f, y - sz * 0.04f, x + fx * sz * 0.47f, y - sz * 0.02f, stroke(shadeDay(0xFFF2B33D.toInt(), day), max(0.6f * d, sz * 0.04f)))
            c.drawPath(Path().apply { moveTo(x - fx * sz * 0.25f, y - sz * 0.06f); lineTo(x - fx * sz * 0.45f, y - sz * 0.02f); lineTo(x - fx * sz * 0.25f, y + sz * 0.08f); close() }, p(bodyC))
        }
    }

    // ---- weather ---------------------------------------------------------------------------

    private fun weather(c: Canvas, v: View3, d: Float, t: Float, s: SceneInput, wind: WindView, fog: Double, flash: Boolean) {
        // Fog: layers of haze thickest towards the horizon
        if (fog > 0.02) {
            // a smooth haze, thickest at the horizon, fading out into the scene's sky above and
            // over the sea and beach below (thin bands with no overlap, so no lines show)
            val hz = p(0)
            val up = (v.horizon - v.skyTop) * 0.45f
            val down = (v.bottom - v.horizon) + 2f
            val top = v.horizon - up; val n = 60
            val col = mix(0xFFDDE3E8.toInt(), 0xFF39424E.toInt(), 0.0)
            var yPrev = kotlin.math.floor(top)
            for (i in 1..n) {
                val f = i / n.toFloat()
                val y = kotlin.math.floor(top + (up + down) * f)
                val mid = top + (up + down) * (f - 0.5f / n)
                val k = if (mid < v.horizon) smooth(1.0 - ((v.horizon - mid) / up).toDouble()) else 1 - smooth(((mid - v.horizon) / down).toDouble()) * 0.7
                hz.color = alpha(col, fog * 0.6 * k)
                if (y > yPrev) c.drawRect(0f, yPrev, v.w, y, hz)
                yPrev = y
            }
        }
        val snow = s.code in 71..77 || s.code in 85..86
        val rate = when {
            s.rainMm > 0 -> s.rainMm
            s.code in 51..57 -> 0.2
            s.code in 61..67 || s.code in 80..82 -> 1.0
            snow -> 0.8
            else -> 0.0
        }
        if (rate > 0) {
            val n = (20 + rate * 70).toInt().coerceAtMost(220)
            val slant = (wind.side * (0.2 + 0.8 * wind.strength)).toFloat()
            if (snow) {
                val flake = p(0xEEFFFFFF.toInt())
                for (i in 0 until n) {
                    val fall = ((t * (0.12 + 0.05 * hash(i)) + hash(i + 1000)) % 1.0).toFloat()
                    val x = ((hash(i + 1100).toFloat() * v.w + slant * fall * 80f * d + sin(t + i) * 6f * d) % v.w)
                    val y = v.top + fall * (v.bottom - v.top)
                    c.drawCircle(x, y, (1.2f + hash(i + 1200).toFloat() * 1.6f) * d, flake)
                }
            } else {
                val drop = stroke(0x88DCE6F0.toInt(), (if (rate < 0.3) 0.8f else 1.2f) * d)
                val len = (if (rate < 0.3) 6f else 12f) * d
                for (i in 0 until n) {
                    val fall = ((t * (1.4 + 0.4 * hash(i)) + hash(i + 1000)) % 1.0).toFloat()
                    val x = ((hash(i + 1100) * v.w).toFloat() + slant * fall * 60f * d) % v.w
                    val y = v.top + fall * (v.bottom - v.top)
                    c.drawLine(x, y, x + slant * len * 0.6f, y + len, drop)
                }
            }
        }
        if (flash) {
            c.drawRect(0f, v.top, v.w, v.bottom, p(0x66FFFFFF.toInt()))
            val bolt = Path().apply {
                var x = v.w * 0.62f; var y = v.top + 10f * d
                moveTo(x, y)
                for (k in 0 until 6) { x += ((hash(k + 1300) - 0.5) * 30).toFloat() * d; y += (v.horizon - v.top) / 6f; lineTo(x, y) }
            }
            c.drawPath(bolt, stroke(0xFFFFFFFF.toInt(), 2.5f * d))
        }
    }

    // ---- framing -----------------------------------------------------------------------------

    private fun vignette(c: Canvas, w: Float, top: Float, bottom: Float, d: Float) {
        val steps = 30
        val fill = p(0)
        for (i in 0 until steps) {
            val inset = i * 1f * d
            fill.color = alpha(0xFF000000.toInt(), 0.016)
            c.drawRect(0f, top, inset + 3f * d, bottom, fill)
            c.drawRect(w - inset - 3f * d, top, w, bottom, fill)
        }
    }

    private fun bars(c: Canvas, w: Float, h: Float, d: Float, t: Float, s: SceneInput, barTop: Float, barBottom: Float, wall: Double) {
        val black = p(0xFF050505.toInt())
        c.drawRect(0f, 0f, w, barTop, black)
        c.drawRect(0f, h - barBottom, w, h, black)
        val small = Draw.paint(0xFFBFC7D1.toInt(), 10f * d, bold = true, align = Paint.Align.LEFT)
        c.drawText(s.title, 12f * d, barTop - 9f * d, small)
        // Subtitles, one at a time, fading in and out
        val subs = subtitles(s, wall)
        if (subs.isNotEmpty()) {
            val each = 4.5f
            val i = ((t / each).toInt()) % subs.size
            val ph = (t % each) / each
            val a = when { ph < 0.12f -> ph / 0.12f; ph > 0.88f -> (1 - ph) / 0.12f; else -> 1f }
            val sub = Draw.paint(alpha(0xFFFFFFFF.toInt(), a.toDouble()), 13f * d, bold = false, align = Paint.Align.CENTER)
            c.drawText(subs[i], w / 2f, h - barBottom / 2f + 4.5f * d, sub)
        }
    }
}

/** The scene as a view. About 30 frames a second while on screen; stops when it isn't. */
class SceneView(context: Context) : View(context) {
    private var input: SceneInput? = null
    private val start = System.nanoTime()
    private var running = false
    var heightDp = 300f

    private var builtAt = System.currentTimeMillis()
    /** Rebuilds the scene from the saved forecast once a minute, so it follows the weather as the hour goes on. */
    var rebuild: (() -> SceneInput?)? = null

    fun set(input: SceneInput) { this.input = input; builtAt = System.currentTimeMillis(); invalidate() }

    private val d: Float get() = resources.displayMetrics.density

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension(MeasureSpec.getSize(widthMeasureSpec), (heightDp * d).toInt())
    }

    init {
        // The sea is drawn as a shaded mesh; before Android 10 the GPU renderer can't draw those.
        if (android.os.Build.VERSION.SDK_INT < 29) setLayerType(LAYER_TYPE_SOFTWARE, null)
    }

    override fun onAttachedToWindow() { super.onAttachedToWindow(); running = true; invalidate() }
    override fun onDetachedFromWindow() { running = false; super.onDetachedFromWindow() }

    override fun onDraw(canvas: Canvas) {
        if (System.currentTimeMillis() - builtAt > 60_000) {
            builtAt = System.currentTimeMillis()
            rebuild?.invoke()?.let { input = it }
        }
        val s = input ?: return
        val t = (System.nanoTime() - start) / 1e9f
        SceneArt.draw(canvas, width.toFloat(), height.toFloat(), d, t, s)
        if (running && isShown) postInvalidateDelayed(33)
    }
}
