package io.github.swellcheck

import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import java.util.Locale
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sin

/** One warm, dark look everywhere: deep browns, cream text, amber and coral accents. */
/** Colours for screens, charts and the widget. Defaults are the current theme. */
class Palette(
    val bg: Int = 0xFF04070D.toInt(),
    val card: Int = 0xFF0A1320.toInt(),
    val text: Int = 0xFFE6F1FF.toInt(),
    val subtle: Int = 0xFF7F9BB8.toInt(),
    val grid: Int = 0x2E4FD1E8,
    val ring: Int = 0x664FD1E8,
    val swell: Int = 0xFF3A86FF.toInt(),
    val wind: Int = 0xFF3DDC97.toInt(),
    val gust: Int = 0xAA3DDC97.toInt(),
    val tide: Int = 0xFF4FD1E8.toInt(),
    val sea: Int = 0x664FD1E8,
    val now: Int = 0xFFFFFFFF.toInt(),
    val onTile: Int = 0xFF04121F.toInt(),
    val accent: Int = 0xFF4FD1E8.toInt(),
    val night: Int = 0xFF020408.toInt(),   // chart background at night...
    val day: Int = 0xFF173A63.toInt(),     // ...fading to this by day
    val limit: Int = 0xFFFF6B6B.toInt(),   // "your limit" line
    val dusk: Int = 0,                     // optional sunrise/sunset glow in the sky fade (0 = none)
    val light: Boolean = false,            // light background: darken rating colours so they read
    val redOnly: Boolean = false,          // night-vision theme: show everything in shades of red
) {
    /** A rating colour (green/amber/red...) adjusted so it reads on this theme. */
    fun rc(color: Int): Int {
        val r = (color shr 16) and 0xFF
        val g = (color shr 8) and 0xFF
        val b = color and 0xFF
        return when {
            redOnly -> when (color) {
                // Night vision: no greens or ambers. Tell ratings apart by brightness instead.
                0xFF4FD1E8.toInt() -> 0xFFFFB3AB.toInt()                                  // epic
                0xFF3DDC97.toInt() -> 0xFFFF7A6E.toInt()                                  // good / go
                0xFFFFC857.toInt() -> 0xFFC2392C.toInt()                                  // fair / marginal
                0xFFFF6B6B.toInt() -> 0xFF6E1610.toInt()                                  // poor / no go
                else -> 0xFF3A0A06.toInt()                                                 // flat / no data
            }
            light -> (0xFF shl 24) or ((r * 0.62).toInt() shl 16) or ((g * 0.62).toInt() shl 8) or (b * 0.62).toInt()
            else -> color
        }
    }
}

/** Drawing shared by the app screens and the widget bitmap. */
object Draw {

    fun paint(color: Int = 0, sizePx: Float = 12f, bold: Boolean = false, align: Paint.Align = Paint.Align.CENTER) =
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.color = color
            textSize = sizePx
            textAlign = align
            typeface = if (bold) Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD) else Typeface.SANS_SERIF
        }

    /** Rounded coloured tile: small caption on top, big value below. */
    fun tile(c: Canvas, r: RectF, caption: String, value: String, rawColor: Int, d: Float, pal: Palette) {
        val color = pal.rc(rawColor)
        c.drawRoundRect(r, 14f * d, 14f * d, paint((color and 0x00FFFFFF) or 0x22000000))
        c.drawRoundRect(r, 14f * d, 14f * d, paint(pal.ring).apply { style = Paint.Style.STROKE; strokeWidth = 1.5f * d })
        val cap = paint(pal.subtle, 11f * d, bold = true)
        val big = paint(color, 24f * d, bold = true)
        val gap = 4f * d
        val total = cap.textSize + gap + big.textSize
        val top = r.centerY() - total / 2f
        c.drawText(caption, r.centerX(), top + cap.textSize, cap)
        c.drawText(value, r.centerX(), top + cap.textSize + gap + big.textSize * 0.9f, big)
    }

    /** Small moon disc showing the lit part for the moon phase at [sec]. */
    fun moon(c: Canvas, cx: Float, cy: Float, r: Float, sec: Long, pal: Palette) {
        val dark = paint(0xFF1B2A3D.toInt())
        val lit = paint(0xFFE6F1FF.toInt())
        val oval = RectF(cx - r, cy - r, cx + r, cy + r)
        c.drawCircle(cx, cy, r, dark)
        val theta = 2 * Math.PI * Moon.phase(sec)
        val waxing = Moon.phase(sec) < 0.5
        // Lit half: right side while waxing, left while waning (northern hemisphere).
        c.drawArc(oval, if (waxing) -90f else 90f, 180f, true, lit)
        // Terminator ellipse: dark over the lit half for a crescent, lit over the dark half for gibbous.
        val k = kotlin.math.cos(theta).toFloat()
        val rx = kotlin.math.abs(k) * r
        c.drawOval(RectF(cx - rx, cy - r, cx + rx, cy + r), if (k > 0) dark else lit)
        c.drawCircle(cx, cy, r, paint(pal.ring).apply { style = Paint.Style.STROKE; strokeWidth = 1f })
    }

    /** Arrow pointing along compass bearing [toward] (0 = up/north, clockwise). */
    fun arrow(c: Canvas, cx: Float, cy: Float, size: Float, toward: Double, p: Paint) {
        val path = Path().apply {
            moveTo(cx, cy - size)
            lineTo(cx + size * 0.6f, cy + size * 0.65f)
            lineTo(cx, cy + size * 0.25f)
            lineTo(cx - size * 0.6f, cy + size * 0.65f)
            close()
        }
        c.save()
        c.rotate(toward.toFloat(), cx, cy)
        c.drawPath(path, p)
        c.restore()
    }

    /**
     * Compass dial. [from] is the bearing the wind/swell comes FROM; the arrow sits
     * on that side and points the way it travels. If [facing] is set, the sea side
     * of the beach is shaded on the ring.
     */
    fun dial(
        c: Canvas, cx: Float, cy: Float, r: Float, d: Float, pal: Palette,
        from: Double?, accent: Int, big: String, small: String, facing: Int?,
    ) {
        val oval = RectF(cx - r, cy - r, cx + r, cy + r)
        if (facing != null) {
            val sea = paint(pal.sea).apply { style = Paint.Style.STROKE; strokeWidth = 7f * d }
            // Canvas angles start at 3 o'clock; compass bearings start at 12 o'clock.
            c.drawArc(oval, (facing - 60 - 90).toFloat(), 120f, false, sea)
        }
        val ring = paint(pal.ring).apply { style = Paint.Style.STROKE; strokeWidth = 1.5f * d }
        c.drawCircle(cx, cy, r, ring)
        c.drawCircle(cx, cy, r * 0.62f, paint(pal.grid).apply { style = Paint.Style.STROKE; strokeWidth = 1f * d })
        val tick = paint(pal.ring).apply { strokeWidth = 1.2f * d }
        for (deg in 0 until 360 step 15) {
            val a = Math.toRadians(deg.toDouble())
            val len = if (deg % 90 == 0) 7f * d else if (deg % 45 == 0) 5f * d else 3f * d
            c.drawLine(cx + (r * sin(a)).toFloat(), cy - (r * cos(a)).toFloat(),
                cx + ((r - len) * sin(a)).toFloat(), cy - ((r - len) * cos(a)).toFloat(), tick)
        }

        val letters = paint(pal.subtle, 10f * d, bold = true)
        val inset = r - 16f * d
        listOf("N" to 0.0, "E" to 90.0, "S" to 180.0, "W" to 270.0).forEach { (t, b) ->
            val a = Math.toRadians(b)
            c.drawText(t, cx + (inset * sin(a)).toFloat(), cy - (inset * cos(a)).toFloat() + letters.textSize * 0.35f, letters)
        }

        if (from != null) {
            val a = Math.toRadians(from)
            val ax = cx + (r * sin(a)).toFloat()
            val ay = cy - (r * cos(a)).toFloat()
            arrow(c, ax, ay, 13f * d, from + 180.0, paint(accent))
        }

        val bigP = paint(pal.text, 20f * d, bold = true)
        val smallP = paint(pal.subtle, 11f * d)
        c.drawText(big, cx, cy + bigP.textSize * 0.2f, bigP)
        c.drawText(small, cx, cy + bigP.textSize * 0.2f + smallP.textSize + 3f * d, smallP)
    }

    // ---- forecast chart --------------------------------------------------

    /**
     * Hour-by-hour chart. Columns are equal width. [compact] is the widget version:
     * rating strip, swell bars, wind line and hour labels only.
     */
    fun chart(
        c: Canvas, w: Float, h: Float, hours: List<Hour>, facing: Int?, limits: Limits,
        u: Units, d: Float, pal: Palette, nowSec: Long, compact: Boolean,
        lat: Double? = null, lon: Double? = null,
    ) {
        if (hours.isEmpty() || w <= 0f || h <= 0f) return
        val n = hours.size
        val cw = w / n
        sky(c, w, 0f, h, hours, cw, lat, lon, d, pal)
        fun xc(i: Int) = cw * i + cw / 2f
        fun xAt(sec: Long) = ((sec - hours[0].time) / 3600f) * cw + cw / 2f
        // Labels and arrows go on the 3-hourly marks (00, 03, 06 ...).
        val labelIdx = hours.indices.filter { Fmt.hourOfDay(hours[it].time) % 3 == 0 }
        val fill = paint()
        val grid = paint(pal.grid).apply { strokeWidth = 1f * d }
        val small = paint(pal.subtle, 9.5f * d)

        var y = 0f

        // Day labels and separators
        if (!compact) {
            val dayP = paint(pal.text, 11f * d, bold = true, align = Paint.Align.LEFT)
            for (i in hours.indices) {
                if (i == 0 || !Fmt.sameDay(hours[i - 1].time, hours[i].time)) {
                    val x = cw * i
                    if (i > 0) c.drawLine(x, 0f, x, h, grid)
                    c.drawText(Fmt.dayShort(hours[i].time), x + 4f * d, 13f * d, dayP)
                }
            }
            y += 18f * d
        }

        // Rating strips
        val stripH = (if (compact) 7f else 11f) * d
        for (i in hours.indices) {
            fill.color = pal.rc(Rating.surf(hours[i], facing).rating.color)
            c.drawRect(cw * i, y, cw * (i + 1) + 0.5f, y + stripH, fill)
        }
        y += stripH + 2f * d
        if (!compact) {
            for (i in hours.indices) {
                fill.color = pal.rc(Rating.paddle(hours[i], facing, limits).rating.color)
                c.drawRect(cw * i, y, cw * (i + 1) + 0.5f, y + stripH, fill)
            }
            y += stripH + 2f * d
        }

        // Rows below the plot (bottom up)
        val hourRowH = 13f * d
        val tideRowH = if (compact) 0f else 50f * d
        val periodRowH = if (compact) 0f else 14f * d
        val arrowRowH = if (compact) 0f else 32f * d
        val plotTop = y + 14f * d
        val plotBottom = h - hourRowH - tideRowH - periodRowH - arrowRowH - 2f * d
        val plotH = plotBottom - plotTop
        if (plotH <= 10f) return

        // Scales
        val waves = hours.map { (it.swellM ?: it.waveM)?.let(u::len) }
        val winds = hours.map { it.windKn?.let(u::speed) }
        val gusts = hours.map { it.gustKn?.let(u::speed) }
        val waveMax = max(waves.filterNotNull().maxOrNull() ?: 0.0, if (u.feet) 6.0 else 2.0) * 1.15
        val windMax = max(
            max(winds.filterNotNull().maxOrNull() ?: 0.0, gusts.filterNotNull().maxOrNull() ?: 0.0),
            u.speed(25.0),
        ) * 1.1
        fun yWave(v: Double) = plotBottom - (v / waveMax * plotH).toFloat()
        fun yWind(v: Double) = plotBottom - (v / windMax * plotH).toFloat()

        c.drawLine(0f, plotBottom, w, plotBottom, grid)

        // Your wind limit as a faint dashed line
        val limitY = yWind(u.speed(limits.maxWind))
        if (limitY > plotTop) {
            val dash = paint(pal.gust).apply {
                style = Paint.Style.STROKE; strokeWidth = 1f * d
                pathEffect = DashPathEffect(floatArrayOf(4f * d, 4f * d), 0f)
            }
            c.drawLine(0f, limitY, w, limitY, dash)
        }

        // Swell bars
        fill.color = pal.swell
        val pad = cw * 0.14f
        for (i in hours.indices) {
            val v = waves[i] ?: continue
            c.drawRoundRect(RectF(cw * i + pad, yWave(v), cw * (i + 1) - pad, plotBottom), 4f * d, 4f * d, fill)
        }

        // Gust (dashed) and wind (solid) lines
        fun line(vals: List<Double?>, p: Paint) {
            val path = Path()
            var started = false
            for (i in vals.indices) {
                val v = vals[i]
                if (v == null) { started = false; continue }
                if (!started) { path.moveTo(xc(i), yWind(v)); started = true } else path.lineTo(xc(i), yWind(v))
            }
            c.drawPath(path, p)
        }
        line(gusts, paint(pal.gust).apply {
            style = Paint.Style.STROKE; strokeWidth = 1.5f * d
            pathEffect = DashPathEffect(floatArrayOf(5f * d, 4f * d), 0f)
        })
        line(winds, paint(pal.wind).apply { style = Paint.Style.STROKE; strokeWidth = 2.5f * d; strokeJoin = Paint.Join.ROUND })

        // Value labels every 3 hours
        val waveLbl = paint(pal.swell, 10f * d, bold = true)
        val windLbl = paint(pal.wind, 10f * d, bold = true)
        val onBar = paint(pal.onTile, 10f * d, bold = true)
        for (i in labelIdx) {
            waves[i]?.let { v ->
                val txt = if (u.feet) v.roundToInt().toString() else String.format(Locale.UK, "%.1f", v)
                val barTop = yWave(v)
                if (plotBottom - barTop >= 15f * d) c.drawText(txt, xc(i), barTop + 12f * d, onBar)
                else c.drawText(txt, xc(i), barTop - 3f * d, waveLbl) // short bar: label above it
            }
            // Wind numbers go under the arrows (full chart) or in the widget's text line.
        }

        // Now marker
        if (nowSec >= hours[0].time && nowSec <= hours[n - 1].time) {
            val nowX = xAt(nowSec)
            val nowP = paint(pal.now).apply { strokeWidth = 2f * d }
            c.drawLine(nowX, plotTop - 10f * d, nowX, plotBottom, nowP)
        }

        var rowY = plotBottom + 2f * d

        if (!compact) {
            // Wind direction arrows
            val arrowP = paint(pal.wind)
            for (i in labelIdx) {
                hours[i].windDir?.let { arrow(c, xc(i), rowY + 9f * d, 7f * d, it + 180.0, arrowP) }
                winds[i]?.let { c.drawText(it.roundToInt().toString(), xc(i), rowY + 29f * d, windLbl) }
            }
            rowY += arrowRowH

            // Swell period
            for (i in labelIdx) {
                val per = hours[i].swellPeriod ?: hours[i].wavePeriod ?: continue
                c.drawText("${per.roundToInt()}s", xc(i), rowY + 10f * d, small)
            }
            rowY += periodRowH

            // Tide curve with high/low times
            val levels = hours.map { it.seaLevelM }
            val lo = levels.filterNotNull().minOrNull()
            val hi = levels.filterNotNull().maxOrNull()
            if (lo != null && hi != null && hi - lo > 0.05) {
                val top = rowY + 12f * d
                val bottom = rowY + tideRowH - 15f * d
                fun yT(v: Double) = bottom - ((v - lo) / (hi - lo) * (bottom - top)).toFloat()
                val path = Path()
                var started = false
                levels.forEachIndexed { i, v ->
                    if (v == null) { started = false; return@forEachIndexed }
                    if (!started) { path.moveTo(xc(i), yT(v)); started = true } else path.lineTo(xc(i), yT(v))
                }
                c.drawPath(path, paint(pal.tide).apply { style = Paint.Style.STROKE; strokeWidth = 2f * d })
                val tideLbl = paint(pal.tide, 9.5f * d, bold = true)
                Tide.events(hours).forEach { e ->
                    val x = xAt(e.time)
                    val ty = if (e.high) top - 2f * d else bottom + 12f * d
                    c.drawText("${if (e.high) "H" else "L"} ${Fmt.time(e.time)}", x, ty, tideLbl)
                }
            }
            rowY += tideRowH
        }

        // Hour labels
        for (i in hours.indices) {
            val hr = Fmt.hourOfDay(hours[i].time)
            if (hr % 3 == 0) c.drawText(String.format(Locale.UK, "%02d", hr), xc(i), h - 3f * d, small)
        }
    }

    /** Sky colour for daylight level f (0 night .. 1 day), with the theme's dawn/dusk glow. */
    fun skyColor(pal: Palette, f: Float): Int {
        var col = mix(pal.night, pal.day, f)
        if (pal.dusk != 0) col = mix(col, pal.dusk, 2.6f * f * (1 - f))   // glow peaks mid-twilight
        return col
    }

    /** Mix two colours; f = 0 gives a, 1 gives b. */
    fun mix(a: Int, b: Int, f: Float): Int {
        fun ch(sh: Int) = (((a shr sh) and 0xFF) + (((b shr sh) and 0xFF) - ((a shr sh) and 0xFF)) * f).roundToInt()
        return (0xFF shl 24) or (ch(16) shl 16) or (ch(8) shl 8) or ch(0)
    }

    /**
     * Background that fades from night to day with the real sun at this place,
     * including dawn and dusk. Drawn first so everything else sits on top.
     */
    private fun sky(
        c: Canvas, w: Float, top: Float, bottom: Float, hours: List<Hour>, cw: Float,
        lat: Double?, lon: Double?, d: Float, pal: Palette,
    ) {
        if (lat == null || lon == null || hours.isEmpty()) return
        val fill = paint()
        val step = 2f * d
        var x = 0f
        while (x < w) {
            // Column centres are on the hour, so x = cw/2 is hours[0].time.
            val sec = hours[0].time + (((x + step / 2f) - cw / 2f) / cw * 3600f).toLong()
            fill.color = skyColor(pal, Sun.light(lat, lon, sec))
            c.drawRect(x, top, x + step + 0.5f, bottom, fill)
            x += step
        }
    }

    // ---- separate sea and wind charts for the main screen -----------------

    /** Common frame: day labels + separators, a rating strip, hour labels. Returns the y below the strip. */
    private fun frame(
        c: Canvas, w: Float, h: Float, hours: List<Hour>, d: Float, pal: Palette,
        title: String, lat: Double?, lon: Double?, stripColor: (Hour) -> Int,
    ): Float {
        val n = hours.size
        val cw = w / n
        sky(c, w, 0f, h, hours, cw, lat, lon, d, pal)
        val grid = paint(pal.grid).apply { strokeWidth = 1f * d }
        val dayP = paint(pal.text, 11f * d, bold = true, align = Paint.Align.LEFT)
        val titleP = paint(pal.subtle, 10f * d, bold = true, align = Paint.Align.LEFT)
        val starts = hours.indices.filter { it == 0 || !Fmt.sameDay(hours[it - 1].time, hours[it].time) }
        starts.forEachIndexed { k, i ->
            val x = cw * i
            val room = ((if (k + 1 < starts.size) starts[k + 1] else n) - i) * cw
            if (i > 0) c.drawLine(x, 0f, x, h, grid)
            val label = Fmt.dayShort(hours[i].time)
            val dayW = dayP.measureText(label)
            // Only label a day if it fits before the next one starts (e.g. late evening).
            if (room >= dayW + 10f * d) c.drawText(label, x + 4f * d, 13f * d, dayP)
            // Repeat the chart's name at each day so it's visible wherever you've scrolled to.
            if (room >= dayW + titleP.measureText(title) + 22f * d) c.drawText(title, x + 12f * d + dayW, 13f * d, titleP)
        }
        val top = 18f * d
        val stripH = 11f * d
        val fill = paint()
        for (i in hours.indices) {
            fill.color = stripColor(hours[i])
            c.drawRect(cw * i, top, cw * (i + 1) + 0.5f, top + stripH, fill)
        }
        val small = paint(pal.subtle, 9.5f * d)
        for (i in hours.indices) {
            val hr = Fmt.hourOfDay(hours[i].time)
            if (hr % 3 == 0) c.drawText(String.format(Locale.UK, "%02d", hr), cw * i + cw / 2f, h - 3f * d, small)
        }
        return top + stripH + 4f * d
    }

    /** Modelled sea level at [sec], interpolated between hours. */
    private fun levelAt(hours: List<Hour>, sec: Long): Double? {
        val i = hours.indexOfLast { it.time <= sec }
        if (i < 0) return hours.firstOrNull()?.seaLevelM
        val a = hours[i].seaLevelM ?: return null
        val nxt = hours.getOrNull(i + 1) ?: return a
        val b = nxt.seaLevelM ?: return a
        val f = (sec - hours[i].time).toDouble() / (nxt.time - hours[i].time)
        return a + (b - a) * f
    }

    private fun nowLine(c: Canvas, hours: List<Hour>, cw: Float, top: Float, bottom: Float, nowSec: Long, d: Float, pal: Palette) {
        if (hours.isEmpty() || nowSec < hours[0].time || nowSec > hours.last().time) return
        val x = ((nowSec - hours[0].time) / 3600f) * cw + cw / 2f
        c.drawLine(x, top, x, bottom, paint(pal.now).apply { strokeWidth = 2f * d })
    }

    /** Sea chart: surf rating strip, swell bars, swell period, tide curve. */
    fun seaChart(
        c: Canvas, w: Float, h: Float, hours: List<Hour>, facing: Int?, u: Units,
        d: Float, pal: Palette, nowSec: Long, lat: Double? = null, lon: Double? = null,
    ) {
        if (hours.isEmpty() || w <= 0f || h <= 0f) return
        val n = hours.size
        val cw = w / n
        fun xc(i: Int) = cw * i + cw / 2f
        fun xAt(sec: Long) = ((sec - hours[0].time) / 3600f) * cw + cw / 2f
        val labelIdx = hours.indices.filter { Fmt.hourOfDay(hours[it].time) % 3 == 0 }

        val y0 = frame(c, w, h, hours, d, pal, "SEA", lat, lon) { pal.rc(Rating.surf(it, facing).rating.color) }
        val hourRowH = 20f * d
        val tideRowH = 84f * d
        val periodRowH = 36f * d   // swell direction arrow + period

        // Tide curve at the top, drawn on a FIXED scale around mean sea level so the
        // height of the wave shows the real range: springs look big, neaps look small.
        val levels = hours.map { it.seaLevelM }
        val tideTop = y0 + 16f * d
        val tideBottom = y0 + tideRowH - 14f * d
        val mid = (tideTop + tideBottom) / 2f
        val half = (tideBottom - tideTop) / 2f
        val known = levels.filterNotNull()
        if (known.size >= 3 && (known.max() - known.min()) > 0.05) {
            // ±3 m covers most UK/Irish springs; bigger tides (e.g. Bristol Channel) widen it.
            val scale = max(known.maxOf { kotlin.math.abs(it) }, 3.0)
            fun yT(v: Double) = mid - (v / scale * half).toFloat()
            // Faint mean-sea-level line for reference.
            c.drawLine(0f, mid, w, mid, paint(pal.grid).apply {
                strokeWidth = 1f * d; pathEffect = DashPathEffect(floatArrayOf(3f * d, 4f * d), 0f)
            })
            // Soft fill between the curve and mean sea level, then the curve itself.
            val area = Path()
            val path = Path()
            var started = false
            var firstX = 0f
            var lastX = 0f
            levels.forEachIndexed { i, v ->
                if (v == null) return@forEachIndexed
                if (!started) { path.moveTo(xc(i), yT(v)); area.moveTo(xc(i), mid); area.lineTo(xc(i), yT(v)); started = true; firstX = xc(i) }
                else { path.lineTo(xc(i), yT(v)); area.lineTo(xc(i), yT(v)) }
                lastX = xc(i)
            }
            area.lineTo(lastX, mid); area.close()
            c.drawPath(area, paint((pal.tide and 0x00FFFFFF) or 0x22000000))
            c.drawPath(path, paint(pal.tide).apply { style = Paint.Style.STROKE; strokeWidth = 2f * d })

            // High/low times; each low also shows the range from the high before it.
            val tideLbl = paint(pal.tide, 9.5f * d, bold = true)
            val rangeLbl = paint(pal.subtle, 9f * d)
            var lastHigh: Double? = null
            Tide.events(hours).forEach { e ->
                val lvl = levelAt(hours, e.time)
                if (e.high) {
                    c.drawText("H ${Fmt.time(e.time)}", xAt(e.time), tideTop - 3f * d, tideLbl)
                    lastHigh = lvl
                } else {
                    c.drawText("L ${Fmt.time(e.time)}", xAt(e.time), tideBottom + 11f * d, tideLbl)
                    val hiLvl = lastHigh
                    if (hiLvl != null && lvl != null) {
                        val r = hiLvl - lvl
                        val txt = if (u.feet) "${(r * 3.28084).roundToInt()} ft range" else String.format(Locale.UK, "%.1f m range", r)
                        c.drawText(txt, xAt(e.time), tideBottom + 21f * d, rangeLbl)
                    }
                }
            }
        }

        // Swell bars below the tide.
        val plotTop = y0 + tideRowH + 22f * d
        val plotBottom = h - hourRowH - periodRowH
        if (plotBottom - plotTop < 20f * d) return

        val waves = hours.map { (it.swellM ?: it.waveM)?.let(u::len) }
        val waveMax = max(waves.filterNotNull().maxOrNull() ?: 0.0, if (u.feet) 6.0 else 2.0) * 1.1
        fun yWave(v: Double) = plotBottom - (v / waveMax * (plotBottom - plotTop)).toFloat()
        c.drawLine(0f, plotBottom, w, plotBottom, paint(pal.grid).apply { strokeWidth = 1f * d })

        val fill = paint(pal.swell)
        val pad = cw * 0.14f
        for (i in hours.indices) {
            val v = waves[i] ?: continue
            c.drawRoundRect(RectF(cw * i + pad, yWave(v), cw * (i + 1) - pad, plotBottom), 4f * d, 4f * d, fill)
        }
        val onBar = paint(pal.onTile, 10f * d, bold = true)
        val above = paint(pal.swell, 10f * d, bold = true)
        for (i in labelIdx) {
            val v = waves[i] ?: continue
            val txt = if (u.feet) v.roundToInt().toString() else String.format(Locale.UK, "%.1f", v)
            val top = yWave(v)
            if (plotBottom - top >= 15f * d) c.drawText(txt, xc(i), top + 12f * d, onBar)
            else c.drawText(txt, xc(i), top - 3f * d, above)
        }
        nowLine(c, hours, cw, tideTop - 6f * d, plotBottom, nowSec, d, pal)

        // Swell direction (arrow points the way the swell is travelling) with the period underneath.
        val rowY = plotBottom
        val small = paint(pal.subtle, 9.5f * d)
        val swellArrow = paint(pal.swell)
        for (i in labelIdx) {
            (hours[i].swellDir ?: hours[i].waveDir)?.let { arrow(c, xc(i), rowY + 11f * d, 7f * d, it + 180.0, swellArrow) }
            val per = hours[i].swellPeriod ?: hours[i].wavePeriod ?: continue
            c.drawText("${per.roundToInt()}s", xc(i), rowY + 31f * d, small)
        }
    }

    /** Wind chart: paddle rating strip, wind and gust lines, your limit, direction arrows. */
    fun windChart(
        c: Canvas, w: Float, h: Float, hours: List<Hour>, facing: Int?, limits: Limits, u: Units,
        d: Float, pal: Palette, nowSec: Long, lat: Double? = null, lon: Double? = null,
    ) {
        if (hours.isEmpty() || w <= 0f || h <= 0f) return
        val n = hours.size
        val cw = w / n
        fun xc(i: Int) = cw * i + cw / 2f
        val labelIdx = hours.indices.filter { Fmt.hourOfDay(hours[it].time) % 3 == 0 }

        val y0 = frame(c, w, h, hours, d, pal, "WIND", lat, lon) { pal.rc(Rating.paddle(it, facing, limits).rating.color) }
        val hourRowH = 14f * d
        val arrowRowH = 50f * d
        val plotTop = y0 + 10f * d
        val plotBottom = h - hourRowH - arrowRowH
        if (plotBottom - plotTop < 20f * d) return

        val winds = hours.map { it.windKn?.let(u::speed) }
        val gusts = hours.map { it.gustKn?.let(u::speed) }
        val windMax = max(
            max(winds.filterNotNull().maxOrNull() ?: 0.0, gusts.filterNotNull().maxOrNull() ?: 0.0),
            u.speed(limits.maxWind) * 1.2,
        ) * 1.08
        fun yWind(v: Double) = plotBottom - (v / windMax * (plotBottom - plotTop)).toFloat()
        c.drawLine(0f, plotBottom, w, plotBottom, paint(pal.grid).apply { strokeWidth = 1f * d })

        // Shade the area under the wind line softly, then draw the lines.
        val area = Path()
        var startedA = false
        var lastX = 0f
        winds.forEachIndexed { i, v ->
            if (v == null) return@forEachIndexed
            if (!startedA) { area.moveTo(xc(i), plotBottom); area.lineTo(xc(i), yWind(v)); startedA = true }
            else area.lineTo(xc(i), yWind(v))
            lastX = xc(i)
        }
        if (startedA) { area.lineTo(lastX, plotBottom); area.close(); c.drawPath(area, paint((pal.wind and 0x00FFFFFF) or 0x22000000)) }

        val limitY = yWind(u.speed(limits.maxWind))
        val limitRed = pal.limit
        c.drawLine(0f, limitY, w, limitY, paint(limitRed).apply {
            style = Paint.Style.STROKE; strokeWidth = 1.5f * d
            pathEffect = DashPathEffect(floatArrayOf(4f * d, 4f * d), 0f)
        })
        c.drawText("your limit", 6f * d, limitY - 4f * d, paint(limitRed, 9.5f * d, bold = true, align = Paint.Align.LEFT))

        fun line(vals: List<Double?>, p: Paint) {
            val path = Path()
            var started = false
            for (i in vals.indices) {
                val v = vals[i]
                if (v == null) { started = false; continue }
                if (!started) { path.moveTo(xc(i), yWind(v)); started = true } else path.lineTo(xc(i), yWind(v))
            }
            c.drawPath(path, p)
        }
        line(gusts, paint(pal.gust).apply {
            style = Paint.Style.STROKE; strokeWidth = 1.5f * d
            pathEffect = DashPathEffect(floatArrayOf(5f * d, 4f * d), 0f)
        })
        line(winds, paint(pal.wind).apply { style = Paint.Style.STROKE; strokeWidth = 2.5f * d; strokeJoin = Paint.Join.ROUND })
        nowLine(c, hours, cw, plotTop, plotBottom, nowSec, d, pal)

        val arrowP = paint(pal.wind)
        val speedP = paint(pal.wind, 10.5f * d, bold = true)
        val gustP = paint(pal.gust, 9.5f * d, bold = true)
        val rowY = plotBottom
        for (i in labelIdx) {
            hours[i].windDir?.let { arrow(c, xc(i), rowY + 10f * d, 7f * d, it + 180.0, arrowP) }
            winds[i]?.let { c.drawText(it.roundToInt().toString(), xc(i), rowY + 30f * d, speedP) }
            gusts[i]?.let { c.drawText("g${it.roundToInt()}", xc(i), rowY + 41f * d, gustP) }
        }
    }
}
