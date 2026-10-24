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
                subs += "Waves ${b.type.words}, breaking about $far m out at this tide"
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

    fun breaker(h0: Double, periodS: Double, tide: Double): Breaker {
        val h = max(h0, 0.05)
        val l0 = 9.81 * periodS * periodS / (2 * PI)
        val hb = (0.56 * h * (h / l0).pow(-0.2)).coerceAtMost(2.2 * h)
        val db = hb / 0.78
        val m = slope(tide)
        val xi = m / kotlin.math.sqrt(h / l0)
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

    /** A sideways position as a share of the visible half-width at distance [z] (-1 left edge .. 1 right edge). */
    private fun across(v: View3, z: Double, frac: Double) = (v.w / 2.0) / v.f * z * frac

    /** Crests are drawn out to this far (metres); beyond it the distant swell lines take over. */
    private const val FAR_CREST = 260.0

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
    private const val PROP_Z = 7.0      // where things on the beach stand

    private fun sea(c: Canvas, v: View3, d: Float, t: Float, s: SceneInput, wind: WindView, day: Double, golden: Double, wall: Double,
                    overcast: Double, fog: Double) {
        val zs = shoreZ(s)
        val far = mix(mix(0xFF1A2A44.toInt(), 0xFF5F9CC4.toInt(), day), 0xFF7E8C99.toInt(), overcast * 0.6)
        val near = mix(mix(0xFF0B1A2C.toInt(), 0xFF1F6E8C.toInt(), day), 0xFF3E5566.toInt(), overcast * 0.5)
        val farF = mix(far, 0xFFF0A868.toInt(), golden * 0.35)
        val yShore = v.y(zs)
        val fill = p(0)
        val bands = 30
        for (i in 0 until bands) {
            val y0 = v.horizon + (yShore - v.horizon) * i / bands
            val y1 = v.horizon + (yShore - v.horizon) * (i + 1) / bands
            fill.color = mix(farF, near, (i / (bands - 1.0)).pow(0.7))
            c.drawRect(0f, y0, v.w, y1 + 1f, fill)
        }

        // Glitter path under the sun or moon
        val src = if (s.sunElev > -1.5) skyPoint(v, s, s.sunAz, s.sunElev) else moonPoint(v, s)
        val glitter = if (s.sunElev > -1.5) (1 - overcast) * (1 - smooth((s.sunElev - 25) / 20)) else (1 - day) * (1 - overcast) * 0.6 * (1 - abs(s.moonPhase - 0.5) * 2)
        if (src != null && glitter > 0.05) {
            val gp = p(0)
            for (i in 0 until 90) {
                val fz = hash(i + 500)
                val y = v.horizon + (yShore - v.horizon) * fz.pow(1.8).toFloat()
                val spread = (6f + 70f * fz.toFloat()) * d
                val x = src.first + ((hash(i + 600) - 0.5) * 2).toFloat() * spread
                val tw = 0.5 + 0.5 * sin(t * (3 + hash(i) * 4) + i)
                gp.color = alpha(if (s.sunElev > -1.5) 0xFFFFE9B0.toInt() else 0xFFE6ECF7.toInt(), glitter * tw)
                c.drawRect(x - 3f * d * (0.3f + fz.toFloat()), y, x + 3f * d * (0.3f + fz.toFloat()), y + 1.2f * d, gp)
            }
        }

        // Ships far out on the horizon, behind the waves
        ships(c, v, d, s, day, fog, wall)

        // Swell lines. Out at sea they come in at the real swell angle; nearer the beach they
        // bend round (refract) until they meet the shore side-on, as real waves do. They come
        // in sets, slow and bunch up in the shallows, grow, and break where the water gets too
        // shallow for them: bigger waves further out. How they break follows the beach slope
        // and the swell (spilling, plunging or surging), and the tide changes both.
        val sw = Swell(s, zs, v.camH)
        val hw = sw.h
        val crests = 16
        val face = mix(near, 0xFF04101A.toInt(), 0.3)
        val glass = mix(mix(0xFF1B4E63.toInt(), 0xFF4FB3C4.toInt(), day), 0xFF7A8C96.toInt(), overcast * 0.5)
        val crestCol = mix(0xFF9FC6DA.toInt(), 0xFFE9F4F8.toInt(), day)
        // How messy the sea is: short-period wind swell, lots of chop for the swell size, or an
        // onshore wind roughing it up. Messy seas get fewer clean lines and more broken texture.
        val onshore = max(0.0, -wind.away) * wind.strength
        val mess = maxOf(smooth((8.0 - s.periodS) / 3.5), (s.chopM / max(s.waveM, 0.3)).coerceIn(0.0, 1.0) * 0.8, onshore * 0.9).coerceIn(0.0, 1.0)
        val trough = mix(near, 0xFF02080F.toInt(), 0.5)
        val deepFace = mix(face, 0xFF061A26.toInt(), 0.3)
        val midFace = mix(face, glass, 0.45)
        val bright = mix(glass, 0xFFBFEFF2.toInt(), 0.35 * day * (1 - overcast))
        val glint = alpha(mix(0xFFFFF4D6.toInt(), 0xFFFFFFFF.toInt(), 0.5), 0.55 * day * (1 - overcast))
        for (k in crests - 1 downTo 0) {
            val dist = sw.crestDepth(k, t)
            if (dist <= 0.05) continue
            val id = sw.crestId(k, t)
            val br = sw.breaker(id)
            val xb = sw.breakX(id)
            // In a messy sea, a lot of the lines out the back never form a clean crest: those
            // rise up gradually as they get near the break instead of popping into view
            val ghost = hash(id * 7 + 3) < mess * 0.55
            val appear = if (ghost) smooth((xb * 1.3 + 3 + 14 - dist) / 14) else 1.0
            if (appear <= 0.02) continue
            val pts = sw.crestLine(v, dist)
            val base = mutableListOf<Pair<Float, Float>>()
            val topL = mutableListOf<Pair<Float, Float>>()
            val brk = mutableListOf<Boolean>()
            val prog = mutableListOf<Double>()      // 0 just breaking .. 1 at the water's edge
            val faceM = mutableListOf<Double>()
            for ((xm, zm) in pts) {
                val depth = zm - zs
                val xs = v.x(xm, zm)
                base += Pair(xs, v.y(zm))
                // lumpy, uneven crests in a messy sea
                val lump = mess * (0.6 * sin(xm * 0.9 + t * 1.7 + id) + 0.4 * sin(xm * 2.3 - t * 2.9 + id * 3.0))
                // fades out before the far end, so a slanting crest doesn't end in a hard point at the horizon
                val hh = min(sw.waveHeight(id, depth) * (1 + 0.45 * lump), v.camH * 0.92) * smooth(depth / 1.0) * smooth((FAR_CREST - depth) / 90.0) * appear
                faceM += hh
                topL += Pair(xs, v.y(zm, hh))
                val xbl = xb * sw.breakScale(xm)          // breaks first at the peak, shoulders later
                brk += depth < xbl && br.hb > 0.25
                prog += ((xbl - depth) / xbl).coerceIn(0.0, 1.0)
            }
            if (base.size < 2) continue
            val fadeFar = (1 - fog * 0.8) * (1 - smooth((dist - 120) / 300)) * appear
            // A band from 0 (at the water line) to 1 (the crest) across the whole wave
            fun band(f0: Float, f1: Float, col: Int, a: Double) {
                val b = Path()
                for (j in topL.indices) {
                    val y = base[j].second + (topL[j].second - base[j].second) * f1
                    if (j == 0) b.moveTo(topL[j].first, y) else b.lineTo(topL[j].first, y)
                }
                for (j in topL.indices.reversed()) b.lineTo(topL[j].first, base[j].second + (topL[j].second - base[j].second) * f0)
                b.close()
                c.drawPath(b, p(alpha(col, a * fadeFar)))
            }
            // the shadowed trough just in front of the wave
            val tr = Path()
            for (j in topL.indices) { val y = base[j].second + (base[j].second - topL[j].second) * 0.35f + 0.5f * d; if (j == 0) tr.moveTo(base[j].first, y) else tr.lineTo(base[j].first, y) }
            for (j in topL.indices.reversed()) tr.lineTo(base[j].first, base[j].second)
            tr.close()
            c.drawPath(tr, p(alpha(trough, 0.35 * fadeFar)))
            // the face, dark and deep at the bottom, turning see-through green-blue towards the top
            val nearness = (1 - dist / 150).coerceIn(0.15, 1.0)
            // far off the bands merge into one soft tone, so the distant sea stays calm-looking
            val detail = smooth((nearness - 0.15) / 0.5)
            band(0f, 0.35f, deepFace, 0.9)
            band(0.35f, 0.62f, mix(deepFace, face, 0.4 + 0.6 * detail), 0.88)
            band(0.62f, 0.82f, mix(face, midFace, detail), 0.85)
            band(0.82f, 1f, mix(face, bright, detail), 0.85)
            // the crest line: one clean line on a tidy swell, broken and ragged in a messy one
            val crestP = stroke(alpha(crestCol, fadeFar * (0.25 + 0.75 * nearness)), (1.6f * nearness.toFloat() + 0.5f) * d)
            val glintP = stroke(alpha(glint, fadeFar * nearness * nearness), (0.8f * nearness.toFloat() + 0.4f) * d)
            // drawn as joined runs (no overlapping ends), with gaps where a messy crest breaks up
            val crestPath = Path(); val glintPath = Path()
            var open = false
            for (j in topL.indices) {
                val gap = mess > 0.15 && hash(id * 131 + j / 3) < mess * 0.5
                if (gap) { open = false; continue }
                val (px, py) = topL[j]
                if (!open) { crestPath.moveTo(px, py); glintPath.moveTo(px, py + 1.2f * d); open = true }
                else { crestPath.lineTo(px, py); glintPath.lineTo(px, py + 1.2f * d) }
            }
            c.drawPath(crestPath, crestP)
            if (nearness > 0.35) c.drawPath(glintPath, glintP)
            if (!brk.any { it }) continue

            // Plunging: the lip throws forward over a hollow face before it lands
            val curlEnd = 0.2
            if (br.type == Surfzone.Type.PLUNGING) {
                var j = 0
                while (j < topL.size) {
                    if (!(brk[j] && prog[j] < curlEnd)) { j++; continue }
                    val start = j
                    while (j < topL.size && brk[j] && prog[j] < curlEnd) j++
                    if (j - start < 2) continue
                    val curtain = Path()
                    val edge = Path()
                    for (m in start until j) {
                        val curl = sin(prog[m] / curlEnd * PI / 2) * 0.8
                        val ly = topL[m].second + (base[m].second - topL[m].second) * curl.toFloat()
                        if (m == start) { curtain.moveTo(topL[m].first, topL[m].second); edge.moveTo(topL[m].first, ly) }
                        else { curtain.lineTo(topL[m].first, topL[m].second); edge.lineTo(topL[m].first, ly) }
                    }
                    for (m in j - 1 downTo start) {
                        val curl = sin(prog[m] / curlEnd * PI / 2) * 0.8
                        curtain.lineTo(topL[m].first, topL[m].second + (base[m].second - topL[m].second) * curl.toFloat())
                    }
                    curtain.close()
                    c.drawPath(curtain, p(alpha(mix(glass, 0xFFFFFFFF.toInt(), 0.15), 0.95 * fadeFar)))
                    c.drawPath(edge, stroke(alpha(0xFFFFFFFF.toInt(), 0.95 * fadeFar), 2.2f * d))
                }
            }
            // Whitewater: how it starts depends on the type of break; then it rolls in as a bore
            val froth = p(alpha(0xFFF4FAFC.toInt(), 0.92 * fadeFar))
            val shade = p(alpha(0xFFC9DCE4.toInt(), 0.85 * fadeFar))
            for ((j, pt) in topL.withIndex()) if (brk[j]) {
                val pr = prog[j]
                val zm = v.f * v.camH / max(1f, base[j].second - v.horizon)
                val hb = min(br.hb, 3.5)
                val (grow, sitHigh) = when (br.type) {
                    Surfzone.Type.SPILLING -> Pair(0.15 + 0.85 * smooth(pr * 1.6), 0.1 + 0.4 * smooth(pr * 2))   // foam tumbles down the face
                    Surfzone.Type.PLUNGING -> if (pr < curlEnd) Pair(0.0, 0.0) else Pair(smooth((pr - curlEnd) * 6), 0.45)
                    Surfzone.Type.SURGING -> Pair(0.25 * smooth(pr * 3), 0.6)
                }
                if (grow <= 0.01) continue
                val edgeK = (if (j > 0 && brk[j - 1]) 0.5f else 0f) + (if (j < brk.size - 1 && brk[j + 1]) 0.5f else 0f)
                // the whitewater thins out as it runs in: a band of foam, not a wall of it
                // thins as it runs in, then bursts up again where it dumps on the shore at the end
                val dump = smooth((pr - 0.78) / 0.2)
                val thin = (1.0 - 0.6 * smooth((pr - 0.3) / 0.45) + 0.75 * dump).toFloat()
                if (pr > 0.45 && pr < 0.8 && j % 2 == 1) continue
                val r0 = max(1.6f * d, v.size(0.06 + hb * 0.15, zm.toDouble())) * (0.45f + 0.55f * edgeK) * grow.toFloat() * thin
                // Plunging waves explode where the lip lands
                if (br.type == Surfzone.Type.PLUNGING && j % 2 == 0) {
                    val burst = (1 - abs(pr - curlEnd - 0.06) / 0.08).coerceAtLeast(0.0)
                    if (burst > 0) {
                        val up = v.size(hb * 0.9 * burst, zm.toDouble())
                        c.drawCircle(pt.first, pt.second - up, r0 * 1.1f, p(alpha(0xFFFFFFFF.toInt(), 0.85 * burst * fadeFar)))
                    }
                }
                for (m in 0 until 3) {
                    val hj = hash(j * 7 + m + k * 31).toFloat()
                    val r = r0 * (0.6f + 0.6f * hj)
                    val cx = pt.first + (m - 1) * r0 * 0.7f
                    val cy = pt.second + (base[j].second - pt.second) * (sitHigh.toFloat() * (0.5f + 0.5f * m / 2f)) + sin(t * 5 + j + m) * 0.8f * d
                    c.drawCircle(cx, cy + r * 0.25f, r, shade)
                    c.drawCircle(cx, cy, r * 0.9f, froth)
                }
            }
        }


        // Choppy texture: little wavelets everywhere in a messy sea, light and dark
        if (mess > 0.2) {
            val n = (mess * 160).toInt()
            val lite = stroke(alpha(0xFFDDEFF4.toInt(), 0.35 * (1 - fog * 0.7)), 1.1f * d)
            val dark = stroke(alpha(0xFF06202E.toInt(), 0.3 * (1 - fog * 0.7)), 1.1f * d)
            for (i in 0 until n) {
                val zm = zs + 3 + hash(i + 1500).pow(1.6) * 160
                val xm = (hash(i + 1510) - 0.5) * 2 * zm * (v.w / v.f) * 0.55 + wind.side * ((t * 0.4 + hash(i)) % 1.0) * 2
                val x = v.x(xm, zm); val y = v.y(zm, sw.height(t, xm, zm) * 0.5) - 0.5f * d
                val len = v.size(0.5 + s.chopM * 0.8, zm) * (0.6f + hash(i + 1520).toFloat())
                val ph = sin(t * (2.0 + hash(i + 1530) * 2) + i).toFloat()
                c.drawLine(x - len / 2, y, x, y - len * 0.25f * ph, if (i % 2 == 0) lite else dark)
                c.drawLine(x, y - len * 0.25f * ph, x + len / 2, y, if (i % 2 == 0) lite else dark)
            }
        }

        // White horses: wind waves tipping over into foam. A few from about 8 knots, lots in a
        // strong wind. Each one peaks, tumbles white, leaves a streak of foam downwind and fades.
        val caps = ((s.windKn - 7) * 3.0).toInt().coerceIn(0, 100)
        if (caps > 0) {
            val capFade = 1 - fog * 0.7
            val faceP = p(alpha(mix(near, 0xFF04101A.toInt(), 0.35), 0.7 * capFade))
            for (i in 0 until caps) {
                val cyc = 3.5 + 2.5 * hash(i + 705)
                val life = ((t / cyc + hash(i + 700) * 10) % 1.0)
                val zm = zs + 8 + hash(i + 710).pow(1.3) * 180
                val xm = (hash(i + 720) - 0.5) * 2 * zm * (v.w / v.f) * 0.55 + wind.side * life * 4 * wind.strength
                val x = v.x(xm, zm); val y = v.y(zm, sw.height(t, xm, zm) * 0.6) - 0.5f * d
                val L = max(3f * d, v.size(0.9 + s.chopM * 1.4, zm)) * (0.75f + 0.5f * hash(i + 730).toFloat())
                val rise = smooth(life / 0.18)                      // the little wave steepens and tips
                val fade = 1 - smooth((life - 0.35) / 0.6)          // then the foam spreads and fades
                if (fade <= 0.02) continue
                val hgt = L * 0.32f * rise.toFloat() * (1 - smooth((life - 0.25) / 0.3)).toFloat()
                // dark little face under the cap while it's breaking
                if (hgt > 0.5f) c.drawPath(Path().apply {
                    moveTo(x - L * 0.55f, y); quadTo(x - L * 0.1f, y - hgt * 1.3f, x + L * 0.35f, y - hgt * 0.4f); lineTo(x + L * 0.5f, y); close()
                }, faceP)
                // the tumbling white top, then a streak of foam drawn out downwind
                val spread = 1f + 1.6f * smooth((life - 0.2) / 0.6).toFloat()
                val drift = (wind.side * L * 0.6 * smooth(life / 0.8)).toFloat()
                val a = fade * rise * capFade
                for (m in 0 until 4) {
                    val ox = drift + (m - 1.5f) * L * 0.22f * spread
                    val rr = L * (0.16f + 0.06f * hash(i * 5 + m).toFloat()) * (1.1f - 0.35f * (spread - 1f) / 1.6f)
                    val cy = y - hgt * 0.8f - rr * 0.3f
                    c.drawOval(RectF(x + ox - rr * 1.3f, cy - rr * 0.3f, x + ox + rr * 1.3f, cy + rr * 0.8f), p(alpha(0xFFC4D9E2.toInt(), a * 0.8)))
                    c.drawOval(RectF(x + ox - rr * 1.2f, cy - rr * 0.5f, x + ox + rr * 1.2f, cy + rr * 0.5f), p(alpha(0xFFF7FBFC.toInt(), a * 0.95)))
                }
            }
        }

        // Wave gauge: a striped post in the water. Waves climb it to their real height.
        gauge(c, v, d, t, s, zs, sw, day)
        // Buoy: bobs with the swell, tag = sea temperature.
        buoy(c, v, d, t, s, zs, sw, day)
        // Kayak and surfer
        boats(c, v, d, t, s, zs, sw, day, onSand = false)
        lifeboat(c, v, d, t, s, zs, sw, day, wall)
        heli(c, v, d, t, s, zs, sw, day, wall)
        rib(c, v, d, t, s, zs, sw, day, wall)
    }

    /**
     * The swell as seen from this beach: angle, spacing and speed, how it bends near the
     * shore, how it comes in sets, and where and how each wave breaks (see [Surfzone]).
     */
    private class Swell(val s: SceneInput, val zs: Double, camH: Double = CAM_H) {
        /** How far off the beach's heading the swell comes from, degrees (-180..180). */
        private val off = rel(s.swellFrom ?: s.facing, s.facing)
        /**
         * Crest angle far out, degrees (0 = parallel to the beach). Waves always travel in
         * towards the beach and break onto it: swell from the side or from behind only
         * reaches it by wrapping round, so it arrives at a steep angle.
         */
        val angle: Double = off.coerceIn(-70.0, 70.0)
        /**
         * Which way the break runs along the wave, across the screen (+1 = left to right).
         * The end of a crest that's nearer the beach reaches breaking depth first, and the break
         * peels away from it along the crest, so riders go that way, ahead of the whitewater.
         * 0 = the swell's coming straight in: an A-frame peak that breaks both ways.
         */
        val peel: Double = if (abs(angle) < 4) 0.0 else if (angle > 0) -1.0 else 1.0
        /** Wave height drawn: the forecast swell as it is (wind chop still makes small waves on a flat day). */
        val h: Double = max(s.waveM, s.chopM * 0.5)
        val lambda = 4.0 + s.periodS * 0.9   // gap between crests far out, metres (scaled for the picture)
        val tv = s.periodS                    // real time: one wave reaches the beach every swell period
        val speed: Double get() = lambda / tv // crest speed in deep water
        // Seen from the beach, real angles flatten out to near-level lines, so they're exaggerated:
        // about 4x from the dune, less from the higher wallpaper camera.
        private val boost = 4.0 * CAM_H / camH
        // Crests keep the swell angle far out and swing round over the last hundred metres or so,
        // so they're lined up with the beach well before they break.
        private val straightBy = 14.0         // metres out where they're parallel to the shore
        private val bendOver = 110.0          // ...turning over this distance beyond that
        /** The picture squeezes the surf zone: real breaking depth -> metres from the water's edge. */
        private val sceneSlope = 0.3 * Surfzone.slope(s.tide) / 0.0325

        /** Waves come in sets: every 8th is the biggest, with a lull in between, and no two quite alike. */
        fun setFactor(id: Int): Double = 1.0 + 0.28 * sin(2 * PI * id / 8.0) + 0.08 * (hash(id * 13 + 7) - 0.5)
        /** Where along the crest it breaks first: a peak on the sandbar upwave of the middle, shoulders either side. */
        private val peakX = -peel * 7.0
        fun breakScale(xm: Double): Double = 0.62 + 0.55 * kotlin.math.exp(-((xm - peakX) / 13.0).pow(2.0))
        fun breaker(id: Int) = Surfzone.breaker(h * setFactor(id), s.periodS, s.tide)
        val average = Surfzone.breaker(h, s.periodS, s.tide)
        /** Where wave [id] starts to break, metres out from the water's edge (in the picture). */
        fun breakX(id: Int) = breaker(id).depth / sceneSlope
        val breakXMean: Double get() = average.depth / sceneSlope

        /** Height of wave [id] at [x] metres out: growing as it shoals, then shrinking with the depth once broken. */
        fun waveHeight(id: Int, x: Double): Double {
            val b = breaker(id); val h0 = h * setFactor(id); val xb = b.depth / sceneSlope
            // Drawn at the forecast swell height (sets above and below it) so the picture and the
            // post agree with the numbers; the breaker physics only decides where and how it breaks.
            return if (x >= xb) h0 else h0 * (x / xb) * (0.6 + 0.4 * x / xb)
        }

        // Crests slow down in shallow water (c = sqrt(g d)), so they bunch up as they come in.
        // sAt(x) is "deep-water distance": crests move through it at a steady speed.
        private val step = 0.1
        private val table: DoubleArray = run {
            val xd = max(4.0, breakXMean * 2.5)
            val n = 7000
            val a = DoubleArray(n + 1)
            for (i in 1..n) {
                val x = (i - 0.5) * step
                a[i] = a[i - 1] + step / max(0.25, kotlin.math.sqrt(min(1.0, x / xd)))
            }
            a
        }
        fun sAt(x: Double): Double {
            if (x <= 0) return x
            val i = (x / step).toInt()
            if (i >= table.size - 1) return table.last() + (x - (table.size - 1) * step)
            val f = x / step - i
            return table[i] + (table[i + 1] - table[i]) * f
        }
        fun xAt(sv: Double): Double {
            if (sv <= 0) return sv
            if (sv >= table.last()) return (table.size - 1) * step + (sv - table.last())
            var lo = 0; var hi = table.size - 1
            while (hi - lo > 1) { val mid = (lo + hi) / 2; if (table[mid] < sv) lo = mid else hi = mid }
            return (lo + (sv - table[lo]) / (table[hi] - table[lo])) * step
        }
        /** Where a crest that's at [x0] metres out will be [dt] seconds later. */
        fun travel(x0: Double, dt: Double) = xAt(sAt(x0) - speed * dt)
        /** Seconds for a crest to run in from [x1] to [x2]. */
        fun timeBetween(x1: Double, x2: Double) = (sAt(x1) - sAt(x2)) / speed

        /** Slope of a crest (dz/dx) at this distance from the water's edge. */
        fun slope(depth: Double): Double {
            val turn = 0.22 + 0.78 * smooth((depth - straightBy) / bendOver)   // still a little angle as they reach the beach
            return (-tan(rad(angle * turn)) * boost).coerceIn(-6.0, 6.0)
        }

        /** Distance out where crest [k] crosses the middle of the view, and which wave it is. */
        fun crestDepth(k: Int, t: Float): Double {
            val ph = ((t / tv) % 1.0 + 1.0) % 1.0
            return xAt(lambda * (k - ph))           // always moving in towards the beach
        }
        fun crestId(k: Int, t: Float): Int = k + kotlin.math.floor(t / tv).toInt()
        /** The wave number of the crest that's at [x] out at time [t] (they count up as time goes on). */
        fun idAt(x: Double, t: Double): Int = kotlin.math.round(sAt(x) / lambda + t / tv).toInt()
        /** When (within whole wave periods) wave [id] passes [x] out. */
        fun timeOf(id: Int, x: Double): Double = (id - sAt(x) / lambda) * tv

        /** Points (x, z) along the crest that crosses the middle at [depth], left to right. */
        fun crestLine(v: View3, depth: Double): List<Pair<Double, Double>> {
            fun walk(dir: Int): List<Pair<Double, Double>> {
                val out = mutableListOf<Pair<Double, Double>>()
                var x = 0.0; var z = zs + depth
                while (z > zs && z < zs + FAR_CREST && out.size < 400) {
                    val xs = v.x(x, z)
                    if (xs < -40 || xs > v.w + 40) break
                    out += Pair(x, z)
                    val dx = dir * (if (z - zs < 60) 1.2 else 4.0)
                    z = max(zs + 0.01, z + slope(z - zs) * dx)
                    x += dx
                }
                return out
            }
            return walk(-1).asReversed() + walk(1).drop(1)
        }

        /** Where the crest through (x, z) crosses the middle of the view. */
        fun depthAtCentre(x: Double, z: Double): Double {
            var zz = z; val n = 12; val dx = -x / n
            repeat(n) { zz = max(zs + 0.01, zz + slope(zz - zs) * dx) }
            return zz - zs
        }

        /** Height of the water surface (metres) at a point. */
        fun height(t: Float, x: Double, z: Double): Double {
            val q = depthAtCentre(x, z)
            val n = sAt(q) / lambda + t / tv
            val id = kotlin.math.round(n).toInt()
            val f = n - id                                       // 0 at a crest
            return waveHeight(id, q) * ((cos(2 * PI * f) + 1) / 2).pow(3.0)
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

    private const val KAYAK_L = 5.2      // a sea touring kayak: long and narrow
    private fun kayakW(u: Double) = 0.29 * (1 - u * u).coerceAtLeast(0.0).pow(0.75) * (1 - 0.12 * u)
    private fun boardW(u: Double) = if (u >= 0) 0.27 * (1 - u.pow(2.2)).coerceAtLeast(0.0).pow(0.6)
        else 0.27 * (0.38 + 0.62 * (1 - (-u).pow(3.0)).coerceAtLeast(0.0).pow(0.5))

    /** What the paddler is doing with the paddle. */
    private enum class Stroke { PADDLE, SPRINT, RUDDER, BRACE, REST }

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
                         side: Double = 1.0, foam: Double = 0.0, phase: Double = 0.0, roll: Double = 0.0, pitch: Double = 0.0) {
        val col = KAYAK_COLOURS[colour]
        val hull = shadeDay(col[0], day)
        val hullDark = shadeDay(col[1], day)
        val deck = shadeDay(col[2], day)
        val nx = -hz; val nz = hx
        val px = { u: Double -> Pair(x + hx * u * KAYAK_L / 2, z + hz * u * KAYAK_L / 2) }
        // bow and stern sweep up a little; the whole boat tilts when it's end-on to a wave
        val sheer = { u: Double -> bob + pitch * u / 2 + 0.2 + 0.1 * u * u }
        val keelAt = { u: Double -> bob + pitch * u / 2 }
        val capsized = roll > PI / 2
        fun drawHull() {
            c.drawPath(side(v, x, z, hx, hz, KAYAK_L, sheer, keelAt), p(hullDark))
            c.drawPath(outline(v, x, z, hx, hz, KAYAK_L, ::kayakW, sheer), p(if (capsized) hullDark else hull))
            if (!capsized) c.drawPath(outline(v, x, z, hx, hz, KAYAK_L, { u -> kayakW(u) * 0.12 }, sheer), p(deck))   // deck line
            else c.drawPath(outline(v, x, z, hx, hz, KAYAK_L, { u -> kayakW(u) * 0.08 }, sheer), p(hull))           // keel
        }
        // The camera looks along +z, so anything further away than the hull's centreline is behind it
        fun behind(pz: Double, at: Double = z) = pz > at + 0.12
        // Whitewater piling up against the hull when side-surfing or punching through a wave
        fun drawFoam(farSide: Boolean) {
            if (foam <= 0.05) return
            val white = p(alpha(0xFFFFFFFF.toInt(), 0.9 * min(foam, 1.0)))
            for (i in -3..3) {
                val u = i / 3.5
                val (fx, fz) = px(u)
                val ox = fx + nx * side * 0.45; val oz = fz + nz * side * 0.45
                if (behind(oz, fz) != farSide) continue
                c.drawCircle(v.x(ox, oz), v.y(oz, bob + 0.15), max(2f * d, v.size(0.45 * foam, oz)) * (0.8f + 0.4f * hash(i + 40).toFloat()), white)
            }
        }
        if (capsized) {
            drawFoam(true); drawHull(); drawFoam(false)
            // Upside down: just the hull and a blade sweeping at the surface for the roll
            val cx = x - hx * 0.25; val cz = z - hz * 0.25
            val bx = cx + nx * side * 1.3 * cos(t * 3.0); val bz = cz + nz * side * 1.3 * cos(t * 3.0)
            if (!behind(bz, cz)) c.drawLine(v.x(bx, bz), v.y(bz, bob + 0.05), v.x(bx + hx * 0.4, bz + hz * 0.4), v.y(bz + hz * 0.4, bob + 0.05),
                stroke(shadeDay(0xFF1F4E6B.toInt(), day), max(2f * d, v.size(0.14, cz))))
            return
        }
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
        // 3. the paddler: body, arms reaching to the shaft, head
        c.drawLine(hipX, hipY, shX, shY, stroke(body, max(2f * d, k * 0.34f)))
        val arm = stroke(body, max(1.2f * d, k * 0.11f))
        for (f in listOf(0.3, 0.7)) {
            val hnd = along(f)
            c.drawLine(shX, shY + k * 0.05f, v.x(hnd.first, hnd.second), v.y(hnd.second, hnd.third), arm)
        }
        c.drawCircle(shX, v.y(sz, bob + 0.92), max(1.5f * d, k * 0.12f), p(skin))
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
    private fun kayakPlan(t: Double, tv: Double, surfable: Boolean, daySeed: Int): Seg {
        var start = 0.0
        var prev: Act? = null
        var n = 0
        while (n < 100000) {
            fun r(j: Int) = hash(daySeed * 7919 + n * 131 + j * 17)
            var act = if (n == 0) (if (surfable) Act.SURF else Act.CRUISE) else {
                val x = r(1)
                if (surfable) when {
                    x < 0.40 -> Act.SURF; x < 0.52 -> Act.TRIP; x < 0.72 -> Act.CRUISE; x < 0.86 -> Act.RAFT; else -> Act.ROLL
                } else when {
                    x < 0.25 -> Act.TRIP; x < 0.62 -> Act.CRUISE; x < 0.82 -> Act.RAFT; else -> Act.ROLL
                }
            }
            if (act == prev) act = if (act == Act.SURF) Act.CRUISE else if (surfable) Act.SURF else Act.RAFT
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
    private fun kayakGroup(c: Canvas, v: View3, d: Float, t: Float, s: SceneInput, zs: Double, sw: Swell, day: Double) {
        val breakDepth = sw.breakXMean * 1.3                // where the bigger set waves break
        val surfable = sw.h >= 0.4 && s.periodS >= 5.0      // nobody surfs short wind chop
        val gaps = max(1.0, kotlin.math.round(sw.sAt(max(18.0, breakDepth + 8)) / sw.lambda))
        val lineup = sw.xAt(sw.lambda * gaps)              // beyond the break, on a crest's path
        val zL = zs + lineup
        val halfW = (v.w / 2.0) / v.f * zL * 0.85           // half the visible width out there, metres
        val home = doubleArrayOf(-0.5 * halfW, 0.05 * halfW, 0.55 * halfW)
        val daySeed = (s.moonPhase * 1000).toInt()          // changes from day to day
        val seg = kayakPlan(t.toDouble(), sw.tv, surfable, daySeed)
        val tl = t - seg.start                              // time into this activity
        val u = (tl / seg.len).coerceIn(0.0, 1.0)
        val speed = sw.speed

        class Pose(val x: Double, val z: Double, val hx: Double, val hz: Double, val h: Double,
                   val stroke: Stroke = Stroke.PADDLE, val side: Double = 1.0, val foam: Double = 0.0,
                   val moving: Boolean = true, val roll: Double = 0.0)

        fun bob(x: Double, z: Double) = sw.height(t, x, z)
        /** Sitting it out: drifting at the spot, or pottering round in a slow circle. */
        fun idle(i: Int): Pose {
            val r = hash(seg.seed * 3 + i * 7)
            val x0 = home[i]
            return if (r < 0.45) {
                val a = t * 0.12 * (if (r < 0.22) 1 else -1) + i * 2.0
                val x = x0 + 3.0 * cos(a); val z = zL + 3.0 * sin(a)
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
        val broachRideT = sw.timeBetween(lineup, breakDepth)
        val pullT = 3.0
        val broachT = max(3.0, (breakDepth - 1.5) / 1.1)     // side-surfing the whitewater in
        fun waiting(i: Int, until: Double): Pose {
            val x = home[i]
            val r = hash(seg.seed * 5 + i)
            // Facing the beach and looking back for a wave; between sets some turn side-on to chat
            val (hx, hz) = if (until > 14 && r < 0.5) norm(if (i == 0) 1.0 else -1.0, 0.1) else norm(0.12, -1.0)
            return Pose(x, zL, hx, hz, bob(x, zL), Stroke.REST, moving = false)
        }
        fun ride(i: Int, tc: Double, dir: Double, broach: Boolean): Pose {
            val drift = { tt: Double -> home[i] + dir * 0.3 * speed * tt }
            val rideEnd = if (broach) broachRideT else rideT
            val outT = kotlin.math.hypot(drift(rideEnd) - home[i], lineup - pullDepth) / 1.7
            val outLongT = kotlin.math.hypot(drift(rideEnd) - home[i], lineup - 1.5) / 1.5
            when {
                tc < rideEnd -> {
                    // On the face: sprint to catch it, then run with a stern rudder
                    val q = sw.travel(lineup, tc)
                    val x = drift(tc)
                    val z = zOnCrest(sw, q, x)
                    val depth = z - zs
                    val face = min(sw.waveHeight(sw.idAt(depth, t.toDouble()), depth), v.camH * 0.92) * 0.55 * smooth(depth / 1.5)
                    val (hx, hz) = norm(dir * 0.3 + 0.02, -1.0)
                    return Pose(x, z, hx, hz, face, if (tc < 1.6) Stroke.SPRINT else Stroke.RUDDER, if (dir >= 0) -1.0 else 1.0)
                }
                !broach && tc < rideEnd + pullT -> {
                    // Pull off: turn along the wave and over its back, letting it go through
                    val w = (tc - rideEnd) / pullT
                    val sgn = if (dir != 0.0) dir else if (i % 2 == 0) 1.0 else -1.0
                    val ang = w * 2.2
                    val (hx, hz) = norm(sgn * sin(ang), -cos(ang))
                    val x = drift(rideEnd) + sgn * 2.0 * w
                    val z = zs + pullDepth + 2.5 * w * w
                    return Pose(x, z, hx, hz, bob(x, z), Stroke.RUDDER, -sgn)
                }
                !broach && tc < rideEnd + pullT + outT -> {
                    val w = (tc - rideEnd - pullT) / outT
                    val sx = drift(rideEnd) + 2.0
                    val x = sx + (home[i] - sx) * w
                    val z = zs + pullDepth + 2.5 + (lineup - pullDepth - 2.5) * w
                    val (hx, hz) = norm((home[i] - sx) / max(1.0, outT), 1.7)
                    return Pose(x, z, hx, hz, bob(x, z), Stroke.PADDLE)
                }
                broach && tc < rideEnd + broachT -> {
                    // Broached: side-on in the whitewater, leaning on a low brace into the wave
                    val w = (tc - rideEnd) / broachT
                    val sgn = if (dir != 0.0) dir else if (hash(i + seg.seed) < 0.5) 1.0 else -1.0
                    val x = drift(rideEnd) + sgn * 1.5 * w
                    val z = zs + breakDepth - (breakDepth - 1.5) * w
                    val turn = smooth(w * 4)
                    val (hx, hz) = norm(sgn * (0.25 + 0.75 * turn), -(1 - turn))
                    val seaSide = if (hx > 0) 1.0 else -1.0
                    return Pose(x, z, hx, hz, 0.25 * (1 - w), Stroke.BRACE, -seaSide, 1.0 - 0.6 * w)
                }
                broach && tc < rideEnd + broachT + outLongT -> {
                    // Spun out: bow back to sea and punching out through the breakers
                    val w = (tc - rideEnd - broachT) / outLongT
                    val sx = drift(rideEnd) + 1.5
                    val x = sx + (home[i] - sx) * w
                    val z = zs + 1.5 + (lineup - 1.5) * w
                    val (hx, hz) = norm((home[i] - sx) * 0.03, 1.0)
                    val h = bob(x, z)
                    val inBreak = z - zs < breakDepth + 1
                    return Pose(x, z, hx, hz, h, Stroke.SPRINT, 1.0, if (inBreak && h > sw.h * 0.4) 0.7 else 0.0)
                }
                else -> return waiting(i, 99.0)
            }
        }
        fun rideLength(i: Int, dir: Double, broach: Boolean): Double {
            val rideEnd = if (broach) broachRideT else rideT
            val dx = dir * 0.3 * speed * rideEnd
            return if (broach) rideEnd + broachT + kotlin.math.hypot(dx, lineup - 1.5) / 1.5
            else rideEnd + pullT + kotlin.math.hypot(dx, lineup - pullDepth) / 1.7
        }
        /** One wave a boat goes for. [hits] = the boat it runs into on a shared wave, if any. */
        class RidePlan(val tn: Double, val dir: Double, val broach: Boolean, val total: Double, val hits: Int? = null)
        val collideAt = 0.55 * rideT                       // seconds into a shared ride when they bump
        val rollT = 3.4                                     // knocked over, upside down, rolled back up
        fun collideLength(i: Int, dir: Double): Double {
            val dx = dir * 0.3 * speed * collideAt
            return collideAt + rollT + kotlin.math.hypot(dx, lineup - pullDepth) / 1.5
        }
        /** Each boat picks its own waves: lets some go, takes others, sometimes two share one. */
        fun plan(i: Int, rank: Int): List<RidePlan> {
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
                val rd = r(1)
                // straight in, or angled with the peel (either way off a straight-on peak)
                val peelDir = if (sw.peel != 0.0) sw.peel else if (r(4) < 0.5) -1.0 else 1.0
                var dir = if (rd < 0.35) 0.0 else peelDir
                if (home[i] + dir * 0.3 * speed * rideT > halfW * 0.8 || home[i] + dir * 0.3 * speed * rideT < -halfW * 0.8) dir = 0.0   // near the edge: straight in
                val broachChance = if (sw.h > 1.2) 0.35 else 0.18
                val broach = r(2) < broachChance
                val total = rideLength(i, dir, broach)
                if (tn + total > seg.len - 2) break
                out += RidePlan(tn, dir, broach, total)
                free = tn + total + 2.0 + 14.0 * r(3)
                n++
            }
            return out
        }
        /**
         * All the boats' waves. When two go for the same wave the second steers into the first,
         * gets knocked over, and rolls back up (an Eskimo roll), then paddles back out.
         */
        val plans: Map<Int, List<RidePlan>> by lazy {
            val raw = members.associateWith { plan(it, members.indexOf(it)) }
            members.associateWith { i ->
                val mine = mutableListOf<RidePlan>()
                var busyUntil = -1.0
                for (rp in raw.getValue(i)) {
                    if (rp.tn < busyUntil) continue
                    val other = members.firstOrNull { j -> j < i && raw.getValue(j).any { it.tn == rp.tn } }
                    val p2 = if (other != null) RidePlan(rp.tn, rp.dir, false, collideLength(i, rp.dir), other) else rp
                    if (p2.tn + p2.total > seg.len - 2) break
                    mine += p2; busyUntil = p2.tn + p2.total + 2.0
                }
                mine
            }
        }
        fun collideRide(i: Int, tc: Double, rp: RidePlan): Pose {
            val j = rp.hits!!
            val jPlan = plans.getValue(j).first { it.tn == rp.tn }
            fun xi(tt: Double) = home[i] + rp.dir * 0.3 * speed * tt
            fun xj(tt: Double) = home[j] + jPlan.dir * 0.3 * speed * tt
            val side = if (home[i] < home[j]) -1.0 else 1.0
            fun x(tt: Double) = xi(tt) + (xj(tt) + side * 1.6 - xi(tt)) * smooth(tt / collideAt)   // steering into the other boat
            return when {
                tc < collideAt -> {
                    val q = sw.travel(lineup, tc)
                    val xx = x(tc); val z = zOnCrest(sw, q, xx)
                    val depth = z - zs
                    val face = min(sw.waveHeight(sw.idAt(depth, t.toDouble()), depth), v.camH * 0.92) * 0.55 * smooth(depth / 1.5)
                    val (hx, hz) = norm((xj(tc) - xi(tc)) * 0.15 + 0.02, -1.0)
                    Pose(xx, z, hx, hz, face, if (tc < 1.6) Stroke.SPRINT else Stroke.RUDDER, -side)
                }
                tc < collideAt + rollT -> {
                    // Bumped: over she goes, a moment upside down, and a roll back up with a sweep
                    val w = tc - collideAt
                    val x0c = x(collideAt)
                    val z0c = zOnCrest(sw, sw.travel(lineup, collideAt), x0c)
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
                    val sz = max(zs + 1.5, zOnCrest(sw, sw.travel(lineup, collideAt), x(collideAt)) - 0.9 * rollT)
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
                if (tl < rp.tn + rp.total) return if (rp.hits != null) collideRide(i, tl - rp.tn, rp) else ride(i, tl - rp.tn, rp.dir, rp.broach)
            }
            return waiting(i, 99.0)
        }


        val poses = (0..2).map { i ->
            if (i !in members) return@map idle(i)
            val rank = members.indexOf(i)
            when (seg.act) {
                Act.TRIP -> {
                    // Off on a trip: out along the coast and away past the edge of the view, then home
                    val p = 2 * PI * u
                    val reach = 0.6 + 0.4 * hash(seg.seed + 5)
                    val lx = home[members[0]] + seg.dir * reach * (260.0 * (1 - cos(p)) / 2 + 25.0 * sin(p))
                    val lz = zL + reach * 300.0 * (1 - cos(p)) / 2
                    val (hx, hz) = norm(seg.dir * (130.0 * sin(p) + 25.0 * cos(p)), 150.0 * sin(p))
                    val back = rank * 5.0; val lat = if (rank == 1) 1.6 else -1.6 * (rank / 2)
                    val x = lx - hx * back - hz * lat; val z = max(zs + 3, lz - hz * back + hx * lat)
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
            if (o.z < zs + 1.0) continue
            // Riding in on a face: bow down the slope. Otherwise the swell tips it bow-up climbing a
            // face and bow-down coming off the back, when it's end-on to the waves.
            val riding = (o.stroke == Stroke.RUDDER || o.stroke == Stroke.SPRINT) && o.hz < -0.5 && o.h > 0.3
            val pitch = if (riding) -0.55 * min(o.h, 1.8) else
                (sw.height(t, o.x + o.hx * KAYAK_L / 2, o.z + o.hz * KAYAK_L / 2) - sw.height(t, o.x - o.hx * KAYAK_L / 2, o.z - o.hz * KAYAK_L / 2)).coerceIn(-1.4, 1.4)
            seaKayak(c, v, d, t, o.x, o.z, o.hx, o.hz, o.h, day, o.moving, colour = i, stroke = o.stroke, side = o.side,
                foam = o.foam, phase = i * 2.1, roll = o.roll, pitch = pitch)
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
        c.drawPath(outline(v, x, z, hx, hz, 1.9, ::boardW) { u -> h + pitch * u / 2 }, p(shadeDay(0xFFF7F4EC.toInt(), day)))
        c.drawPath(outline(v, x, z, hx, hz, 1.9, { u -> boardW(u) * 0.08 }) { u -> h + pitch * u / 2 + 0.01 }, p(shadeDay(0xFF4FA3D1.toInt(), day)))   // stringer
    }

    private fun boats(c: Canvas, v: View3, d: Float, t: Float, s: SceneInput, zs: Double, sw: Swell, day: Double, onSand: Boolean) {
        // Kayak: what it's doing = the paddle call
        when (s.paddle) {
            Paddle.GO -> if (!onSand) kayakGroup(c, v, d, t, s, zs, sw, day)
            Paddle.MARGINAL -> if (!onSand) {
                // Stays close in, paddling up and down the beach
                val p = 2 * PI * t / 50.0
                val x = 7.0 * sin(p); val z = zs + 13.0 + 2.0 * sin(2 * p)
                val (hx, hz) = norm(7.0 * cos(p), 4.0 * cos(2 * p))
                seaKayak(c, v, d, t, x, z, hx, hz, sw.height(t, x, z), day, moving = true)
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
        val aFrame = if (hash(kotlin.math.floor(t / (32 * sw.tv)).toInt() + 77) < 0.5) -1.0 else 1.0   // lefts or rights off a straight-on peak
        val sgn = if (sw.peel != 0.0) sw.peel else aFrame   // the direction they ride
        val halfW = (v.w / 2.0) / v.f * (zs + peak)        // visible half-width out at the peak
        val x0 = -sgn * 0.55 * halfW                         // start upwave so the ride crosses the view
        val speed = sw.speed
        val latWanted = when (s.surf) { Surf.EPIC -> 3.4; Surf.GOOD -> 2.8; else -> 1.8 }     // across-the-face speed, m/s
        val waitMin = when (s.surf) { Surf.EPIC -> 6.0; Surf.GOOD -> 12.0; else -> 30.0 }
        val setT = 8 * sw.tv
        val base = ((sw.timeOf(setWave, peak) % setT) + setT) % setT       // a set wave passes the peak
        val t0 = base + kotlin.math.ceil(max(0.0, waitMin - base) / setT) * setT   // catch time within the cycle
        val rideT = max(2.0, sw.timeBetween(peak, RIDE_END))
        val lat = min(latWanted, 1.05 * halfW / rideT)       // stay in shot
        val kickT = 1.4
        val endX = x0 + sgn * lat * rideT
        val backT = (kotlin.math.hypot(endX - x0, peak - 1.0) + 2.0) / 1.1
        val cycle = kotlin.math.ceil((t0 + rideT + kickT + backT + 2.0) / setT) * setT
        val rides = sw.h >= 0.3 && s.periodS >= 5.0
        val tc = if (rides) ((t % cycle) + cycle) % cycle else 0.0
        // Now and then it goes wrong: a wipeout part way along the wave (more often when it's only fair)
        val ci = kotlin.math.floor(t / cycle).toInt()
        val wipe = rides && hash(ci * 7 + 3) < (if (s.surf == Surf.FAIR) 0.4 else 0.27)
        val wipeAt = rideT * (0.3 + 0.45 * hash(ci * 7 + 5))
        fun rideAt(r: Double): Pair<Double, Double> {
            val x = x0 + sgn * lat * r * smooth(r / 1.0).coerceAtLeast(0.25)
            return Pair(x, zOnCrest(sw, sw.travel(peak, r), x))
        }
        val backStart = if (wipe) t0 + wipeAt + WIPE_T else t0 + rideT + kickT
        val backX = if (wipe) rideAt(wipeAt).first + sgn * 0.6 else endX + sgn * 0.8
        val backDepth = if (wipe) max(1.6, rideAt(wipeAt).second - zs - 1.4 * WIPE_T) else RIDE_END + 2.5

        /** Nose-up or nose-down as the swell passes under a board lying end-on to it. */
        fun pitchAt(x: Double, z: Double, hx: Double, hz: Double) =
            (sw.height(t, x + hx * 0.95, z + hz * 0.95) - sw.height(t, x - hx * 0.95, z - hz * 0.95)).coerceIn(-0.9, 0.9)
        fun sitting(x: Double, z: Double, facingShore: Double) {
            val h = sw.height(t, x, z)
            val (hx, hz) = norm(sgn * 0.3 * facingShore, 1.0 - 2.0 * facingShore)       // out to sea -> round to the beach
            boards(c, v, x, z, hx, hz, h, day, pitchAt(x, z, hx, hz) + 0.12)            // sat back, nose lifted
            // Sitting astride the board, a little back so the nose lifts, legs dangling in the water
            val k = v.size(1.0, z) * SURFER_K
            val sx = x - hx * 0.25; val sz = z - hz * 0.25
            val bx = v.x(sx, sz); val by = v.y(sz, h + 0.05)
            val nxs = -hz; val nzs = hx
            val water = alpha(mix(suit, 0xFF2D6C85.toInt(), 0.55), 0.85)
            for (sd in listOf(-1.0, 1.0)) {
                // thigh over the rail, shin down into the sea (shown through the water, paler)
                val kx = v.x(sx + nxs * sd * 0.32 + hx * 0.15, sz + nzs * sd * 0.32 + hz * 0.15); val ky = v.y(sz, h + 0.02)
                c.drawLine(bx, by, kx, ky, stroke(suit, max(1.4f * d, k * 0.14f)))
                c.drawLine(kx, ky, kx, ky + 0.38f * k, stroke(water, max(1.2f * d, k * 0.12f)))
            }
            val sway = sin(t * 0.9 + x).toFloat() * 0.04f * k
            c.drawLine(bx, by, bx + sway, by - 0.62f * k, stroke(suit, max(1.6f * d, k * 0.17f)))        // upright torso
            // hands resting on the thighs
            c.drawLine(bx + sway, by - 0.5f * k, bx - 0.22f * k, by - 0.05f * k, stroke(suit, max(1.1f * d, k * 0.09f)))
            c.drawLine(bx + sway, by - 0.5f * k, bx + 0.22f * k, by - 0.05f * k, stroke(suit, max(1.1f * d, k * 0.09f)))
            c.drawCircle(bx + sway, by - 0.78f * k, max(1.5f * d, k * 0.12f), p(skin))
        }
        fun prone(x: Double, z: Double, hx: Double, hz: Double, h: Double, rate: Double) {
            boards(c, v, x, z, hx, hz, h, day, pitchAt(x, z, hx, hz))
            val head = Pair(x + hx * 0.55, z + hz * 0.55)
            c.drawLine(v.x(x - hx * 0.6, z - hz * 0.6), v.y(z - hz * 0.6, h + 0.1), v.x(head.first, head.second), v.y(head.second, h + 0.14),
                stroke(suit, max(1.6f * d, v.size(0.26, z))))
            c.drawCircle(v.x(head.first + hx * 0.15, head.second + hz * 0.15), v.y(head.second + hz * 0.15, h + 0.22), max(1.5f * d, v.size(0.12, z)), p(skin))
            for (side in listOf(-1.0, 1.0)) {
                val a = t * rate + if (side > 0) 0.0 else PI
                val sx = x + hx * 0.35 - hz * side * 0.2; val sz = z + hz * 0.35 + hx * side * 0.2
                val ex = sx + hx * 0.5 * cos(a) - hz * side * 0.25; val ez = sz + hz * 0.5 * cos(a) + hx * side * 0.25
                c.drawLine(v.x(sx, sz), v.y(sz, h + 0.14), v.x(ex, ez), v.y(ez, h + 0.1 + 0.3 * sin(a)), stroke(suit, max(1.2f * d, v.size(0.09, z))))
            }
        }
        /** Standing: [up] 0 = just popping up .. 1 = full stance; [lean] tips the body into a turn. */
        fun standing(x: Double, z: Double, hx: Double, hz: Double, h: Double, up: Double, lean: Double, crouch: Double) {
            // Heading in on the face, the nose points down the slope; otherwise it follows the water
            val pitch = if (hz < -0.3) -0.45 * min(h, 1.6) * (-hz) else pitchAt(x, z, hx, hz)
            boards(c, v, x, z, hx, hz, h, day, pitch)
            val k = v.size(1.0, z) * SURFER_K; val bx = v.x(x, z); val by = v.y(z, h)
            val f = sgn.toFloat()
            val cr = (crouch * (0.55 + 0.45 * up)).toFloat()
            val w = max(1.6f * d, k * 0.13f)
            val leg = stroke(suit, w)
            val hip = Pair(bx + (lean * 0.25).toFloat() * k * f, by - 0.8f * k * cr)
            val sh = Pair(hip.first + (0.1 + lean * 0.35).toFloat() * k * f, hip.second - 0.5f * k * cr)
            c.drawLine(bx - 0.34f * k * f, by, bx - 0.14f * k * f, by - 0.45f * k * cr, leg)
            c.drawLine(bx + 0.32f * k * f, by, bx + 0.2f * k * f, by - 0.45f * k * cr, leg)
            c.drawLine(bx - 0.14f * k * f, by - 0.45f * k * cr, hip.first, hip.second, leg)
            c.drawLine(bx + 0.2f * k * f, by - 0.45f * k * cr, hip.first, hip.second, leg)
            c.drawLine(hip.first, hip.second, sh.first, sh.second, stroke(suit, w * 1.3f))
            val armY = sh.second + 0.12f * k
            c.drawLine(sh.first, armY, sh.first - 0.62f * k * f, armY + (0.12f - lean.toFloat() * 0.3f) * k, stroke(suit, w * 0.75f))
            c.drawLine(sh.first, armY, sh.first + 0.6f * k * f, armY + (0.05f + lean.toFloat() * 0.3f) * k, stroke(suit, w * 0.75f))
            c.drawCircle(sh.first + 0.03f * k * f, sh.second - 0.16f * k, max(1.5f * d, k * 0.12f), p(skin))
        }

        when {
            !rides || tc < t0 - 3.0 -> {
                // Sitting at the peak; for the last few seconds before a wave, swinging round to face the beach
                val turn = if (rides) smooth((tc - (t0 - 6.0)) / 3.0) else 0.0
                sitting(x0, zs + peak + 1.0, turn)
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
            wipe && tc < t0 + wipeAt + WIPE_T -> {
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
                val z = zs + RIDE_END + 2.5 * u
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
                if (duck) {
                    // under the wave: just a patch of foam showing
                    c.drawCircle(v.x(x, z), v.y(z, h * 0.5), max(2f * d, v.size(0.5, z)), p(alpha(0xFFFFFFFF.toInt(), 0.85)))
                } else prone(x, z, hx, hz, h, 4.0)
            }
            else -> sitting(x0, zs + peak + 1.0, 0.0)
        }
    }

    private const val RIDE_END = 2.4        // kick out about here, before the shore break
    private const val WIPE_T = 2.6          // seconds from falling off to climbing back on
    private const val SURFER_K = 0.85f      // figure scale against the board

    /** Where a crest that crosses the middle at [q] is, at sideways position [x]. */
    private fun zOnCrest(sw: Swell, q: Double, x: Double): Double {
        var z = sw.zs + q; val n = 12; val dx = x / n
        repeat(n) { z = max(sw.zs + 0.01, z + sw.slope(z - sw.zs) * dx) }
        return z
    }

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
            e < HELI_IN + HELI_HOVER -> Triple(hx + 1.5 * sin(e * 0.3), hz, 30.0 + 1.0 * sin(e * 0.7))
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
        if (e > 3480) { val u = smooth((e - 3480) / 120); return RibState(bx - side * away * u, bz + 40 * u, 4, null, null) }
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
            else -> RibState(bx + 1.5 * sin(k * 0.05), bz, 4, null, null)   // station-holding, crew swap round
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
        val runup = (0.5 + sw.average.hb * 1.3) * (if (sw.average.type == Surfzone.Type.SURGING) 1.5 else 1.0)
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
        run {
            val fp = p(alpha(0xFFF4FAFC.toInt(), 0.9)); val fs = p(alpha(0xFFC9DCE4.toInt(), 0.85))
            for (i in 0 until n step 2) {
                val xs = xsA[i]
                val xm = (xs - v.w / 2) / v.f * zs
                val ph = ((t / sw.tv - abs(xm - peakX) * 0.012) % 1.0 + 1.0) % 1.0
                val surge = if (ph < 0.32) 1 - smooth(ph / 0.32) * 0.75 else (1 - smooth((ph - 0.32) / 0.2)) * 0.25
                if (surge < 0.05) continue
                val r = max(1.5f * d, v.size(0.12 + min(sw.average.hb, 2.5) * 0.2, zs)) * surge.toFloat() * (0.7f + 0.6f * hash(i + 2400).toFloat())
                val y = edge[i] - r * 0.4f
                c.drawCircle(xs, y + r * 0.25f, r, fs)
                c.drawCircle(xs, y, r * 0.9f, fp)
            }
        }
        // the lace of foam along the edge, with a few bubbles
        val lace = Path()
        for (i in 0 until n) if (i == 0) lace.moveTo(xsA[i], edge[i]) else lace.lineTo(xsA[i], edge[i])
        c.drawPath(lace, stroke(alpha(0xFFFFFFFF.toInt(), 0.85), 1.6f * d))
        val bub = p(alpha(0xFFFFFFFF.toInt(), 0.7))
        for (i in 0 until n step 3) {
            val r = (0.8f + hash(i + 2300).toFloat() * 1.4f) * d
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
        // Tide sign: next high or low and an arrow for rising/falling
        sign(c, v, d, t, s, day, wall)
        // Flagpole: windsock on top, paddle flag below
        pole(c, v, d, t, s, wind, day)
        boats(c, v, d, t, s, zs, Swell(s, zs), day, onSand = true)
    }

    private fun sign(c: Canvas, v: View3, d: Float, t: Float, s: SceneInput, day: Double, wall: Double) {
        // Set back up the beach and over to the left, on a tall post, so the kayak and board don't hide it
        val zm = PROP_Z + 2.6; val xm = across(v, zm, -0.4)
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
            val legs = stroke(shadeDay(0xFFE0A33A.toInt(), day), max(0.8f * d, 1f))
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
            }
            return
        }
        val wing = stroke(col, 1.8f * d)
        // Light wind: drifting and soaring. Strong wind: flapping hard, barely moving, blown about.
        val drift = (12 + 30 * (1 - wind.strength)) * d * (if (abs(wind.side) < 0.1) 1.0 else wind.side) * (if (wind.strength > 0.6) 0.25 else 1.0)
        for (i in 0 until 3) {
            val span = v.w + 60f * d
            var x = ((hash(i + 21) * span + t * drift * (0.8 + 0.2 * i)) % span).toFloat()
            if (x < 0) x += span
            x -= 30f * d
            val y = (v.skyTop + (v.horizon - v.skyTop) * (0.35 + 0.12 * i)).toFloat() + sin(t * 0.8f + i * 2f) * 6f * d +
                (if (wind.strength > 0.5) sin(t * 5f + i) * 4f * d else 0f)
            val flap = sin(t * (2.5 + 9 * wind.strength).toFloat() + i * 1.7f) * 5f * d * (if (wind.strength < 0.25) 0.4f else 1f)
            val sz = (7f + 2f * i) * d
            val path = Path().apply {
                moveTo(x - sz, y - flap * 0.4f)
                quadTo(x - sz * 0.5f, y - sz * 0.6f - flap, x, y)
                quadTo(x + sz * 0.5f, y - sz * 0.6f - flap, x + sz, y - flap * 0.4f)
            }
            c.drawPath(path, wing)
        }
    }

    // ---- weather ---------------------------------------------------------------------------

    private fun weather(c: Canvas, v: View3, d: Float, t: Float, s: SceneInput, wind: WindView, fog: Double, flash: Boolean) {
        // Fog: layers of haze thickest towards the horizon
        if (fog > 0.02) {
            val hz = p(0)
            for (i in 0 until 10) {
                val y0 = v.horizon - (v.horizon - v.top) * 0.35f + (v.bottom - v.horizon) * i / 10f
                hz.color = alpha(0xFFDDE3E8.toInt(), fog * 0.55 * (1 - i / 11.0))
                c.drawRect(0f, y0, v.w, y0 + (v.bottom - v.horizon) / 10f + 1f, hz)
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
