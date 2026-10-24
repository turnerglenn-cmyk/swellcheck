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
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/** Base for the small drawn views below: density, theme and a fixed height. */
abstract class DrawnView(context: Context) : View(context) {
    protected val d: Float get() = resources.displayMetrics.density
    protected val pal: Palette get() = Themes.current
    protected abstract fun heightDp(): Float

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension(MeasureSpec.getSize(widthMeasureSpec), (heightDp() * d).toInt())
    }

    protected fun text(c: Canvas, s: String, x: Float, y: Float, sizeDp: Float, color: Int, bold: Boolean = false,
                       align: Paint.Align = Paint.Align.LEFT) =
        c.drawText(s, x, y, Draw.paint(color, sizeDp * d, bold, align))
}

/** Surf and paddle rating tiles side by side. */
class RatingTilesView(context: Context) : DrawnView(context) {
    private var surf: Surf = Surf.FLAT
    private var paddle: Paddle = Paddle.UNKNOWN
    override fun heightDp() = 72f

    fun set(surf: Surf, paddle: Paddle) { this.surf = surf; this.paddle = paddle; invalidate() }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat(); val gap = 10f * d; val h = height.toFloat()
        // Paddle call on the left, surf on the right
        Draw.tile(canvas, RectF(0f, 0f, w / 2f - gap / 2f, h), "PADDLE", paddle.label, paddle.color, d, pal)
        Draw.tile(canvas, RectF(w / 2f + gap / 2f, 0f, w, h), "SURF", surf.label, surf.color, d, pal)
    }
}

/** "Best times to go": one 24-hour strip per day, coloured by the paddle call, faded at night. */
class BestTimesView(context: Context) : DrawnView(context) {
    private var days: List<Plan.Day> = emptyList()
    override fun heightDp() = 70f * max(1, days.size)

    fun set(days: List<Plan.Day>) { this.days = days; requestLayout(); invalidate() }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val cell = w / 24f
        val fill = Paint(Paint.ANTI_ALIAS_FLAG)
        days.forEachIndexed { k, day ->
            val top = k * 70f * d
            text(canvas, day.label, 0f, top + 16f * d, 15f, pal.text, bold = true)
            text(canvas, day.verdict, w - 2f * d, top + 16f * d, 14f, pal.rc(day.verdictColor), bold = true, align = Paint.Align.RIGHT)
            val y0 = top + 24f * d; val y1 = y0 + 22f * d
            for (h in 0 until 24) {
                val r = day.ratings[h]
                val lit = day.light[h]
                val base = if (r == null || lit < 0.15f) pal.subtle else pal.rc(r.color)
                val alpha = ((0.30f + 0.70f * lit) * 255).toInt().coerceIn(0, 255)
                fill.color = (base and 0x00FFFFFF) or (alpha shl 24)
                canvas.drawRoundRect(RectF(h * cell + 1f * d, y0, (h + 1) * cell - 1f * d, y1), 3f * d, 3f * d, fill)
            }
            for ((i, lbl) in listOf("00", "06", "12", "18", "24").withIndex()) {
                val x = i * 6 * cell
                val align = when (i) { 0 -> Paint.Align.LEFT; 4 -> Paint.Align.RIGHT; else -> Paint.Align.CENTER }
                text(canvas, lbl, min(x, w - 2f * d), y1 + 14f * d, 11f, pal.subtle, align = align)
            }
        }
    }
}

/** The next hours as rows: time, wind, swell, tide and a go dot, each row lit by the sky at that hour. */
class HourListView(context: Context) : DrawnView(context) {
    private sealed class Row {
        class HourRow(val h: Hour) : Row()
        class SunRow(val label: String) : Row()
        class DayRow(val label: String) : Row()
    }

    private var rows: List<Row> = emptyList()
    private var facing: Int? = null
    private var limits = Limits(15.0, 22.0, 1.2)
    private var units = Units(WindUnit.MPH, true)
    private var lat: Double? = null
    private var lon: Double? = null
    private var tides: List<TideEvent> = emptyList()
    private var levels: Map<Long, Double> = emptyMap()

    private fun rowH(r: Row) = when (r) { is Row.HourRow -> 50f; else -> 32f }
    override fun heightDp() = 30f + rows.sumOf { rowH(it).toDouble() }.toFloat()

    fun set(hours: List<Hour>, all: List<Hour>, facing: Int?, limits: Limits, units: Units, lat: Double?, lon: Double?) {
        this.facing = facing; this.limits = limits; this.units = units; this.lat = lat; this.lon = lon
        tides = Tide.events(all)
        levels = all.mapNotNull { h -> h.seaLevelM?.let { h.time to it } }.toMap()
        val out = mutableListOf<Row>()
        var lastDay: Long? = null
        val suns = mutableListOf<Pair<Long, String>>()
        if (lat != null && lon != null && hours.isNotEmpty()) {
            var day = Plan.dayStart(hours.first().time)
            while (day <= hours.last().time) {
                val dl = Plan.daylight(lat, lon, day + 12 * 3600)
                dl.rise?.let { suns += it to "Sunrise ${Fmt.time(it)}" }
                dl.set?.let { suns += it to "Sunset ${Fmt.time(it)}" }
                day = Plan.dayStart(day + 27 * 3600)
            }
        }
        for (h in hours) {
            val ds = Plan.dayStart(h.time)
            if (lastDay != null && ds != lastDay) out += Row.DayRow(Fmt.dayName(h.time))
            lastDay = ds
            out += Row.HourRow(h)
            suns.filter { it.first >= h.time && it.first < h.time + 3600 }.forEach { out += Row.SunRow(it.second) }
        }
        rows = out
        requestLayout(); invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        // Column positions
        val xTime = 10f * d; val xWind = 62f * d; val xSwell = w * 0.42f; val xTide = w * 0.70f; val xDot = w - 16f * d
        var y = 0f
        text(canvas, "TIME", xTime, 18f * d, 11f, pal.subtle, bold = true)
        text(canvas, "WIND", xWind, 18f * d, 11f, pal.subtle, bold = true)
        text(canvas, "SWELL", xSwell, 18f * d, 11f, pal.subtle, bold = true)
        text(canvas, "TIDE", xTide, 18f * d, 11f, pal.subtle, bold = true)
        text(canvas, "GO", w - 8f * d, 18f * d, 11f, pal.subtle, bold = true, align = Paint.Align.RIGHT)
        y += 30f * d
        val fill = Paint(Paint.ANTI_ALIAS_FLAG)
        val lineP = Draw.paint(pal.grid).apply { strokeWidth = 1f * d }
        for (r in rows) {
            val rh = rowH(r) * d
            when (r) {
                is Row.DayRow -> {
                    fill.color = pal.card
                    canvas.drawRect(0f, y, w, y + rh, fill)
                    text(canvas, r.label, xTime, y + rh / 2f + 5f * d, 14f, pal.text, bold = true)
                }
                is Row.SunRow -> {
                    fill.color = Draw.mix(pal.card, pal.dusk.takeIf { it != 0 } ?: pal.accent, 0.25f)
                    canvas.drawRect(0f, y, w, y + rh, fill)
                    text(canvas, "☀  " + r.label, xTime, y + rh / 2f + 5f * d, 13f, pal.accent, bold = true)
                }
                is Row.HourRow -> {
                    val h = r.h
                    fill.color = Draw.skyColor(pal, Plan.light(lat, lon, h.time + 1800))
                    canvas.drawRect(0f, y, w, y + rh, fill)
                    canvas.drawLine(0f, y, w, y, lineP)
                    val mid = y + rh / 2f
                    text(canvas, Fmt.time(h.time).substringBefore(":"), xTime, mid + 6f * d, 17f, pal.text, bold = true)
                    // Wind: arrow, speed, gust
                    h.windDir?.let { Draw.arrow(canvas, xWind + 7f * d, mid, 7f * d, (it + 180.0) % 360.0, Draw.paint(pal.wind)) }
                    text(canvas, h.windKn?.let { "${units.speed(it).toInt()}" } ?: "–", xWind + 18f * d, mid + 6f * d, 17f, pal.wind, bold = true)
                    h.gustKn?.let { text(canvas, "g${units.speed(it).toInt()}", xWind + 44f * d, mid + 5f * d, 12f, pal.subtle) }
                    // Swell: little bar sized by height, then the numbers
                    val m = h.swellM ?: h.waveM
                    if (m != null) {
                        val cap = if (units.feet) 8.0 else 2.5
                        val bw = (min(units.len(m), cap) / cap * 24f * d).toFloat()
                        fill.color = pal.swell
                        canvas.drawRoundRect(RectF(xSwell, mid - 4f * d, xSwell + max(bw, 3f * d), mid + 4f * d), 4f * d, 4f * d, fill)
                        text(canvas, Fmt.height(m, units.feet), xSwell + 28f * d, mid + 1f * d, 14f, pal.text, bold = true)
                        (h.swellPeriod ?: h.wavePeriod)?.let { text(canvas, Fmt.seconds(it), xSwell + 28f * d, mid + 15f * d, 11f, pal.subtle) }
                    }
                    text(canvas, tideText(h), xTide, mid + 5f * d, 13f, pal.tide, bold = true)
                    val rating = Rating.paddle(h, facing, limits).rating
                    fill.color = pal.rc(rating.color)
                    canvas.drawCircle(xDot, mid, 7f * d, fill)
                }
            }
            y += rh
        }
    }

    private fun tideText(h: Hour): String {
        tides.firstOrNull { it.time >= h.time && it.time < h.time + 3600 }?.let {
            return (if (it.high) "High " else "Low ") + Fmt.time(it.time)
        }
        val now = levels[h.time] ?: return ""
        val next = levels[h.time + 3600] ?: now
        val v = if (units.feet) now * 3.28084 else now
        val sign = if (v >= 0) "+" else "−"
        return (if (next >= now) "↑ " else "↓ ") + sign + String.format(java.util.Locale.UK, "%.1f", abs(v)) + (if (units.feet) " ft" else " m")
    }
}

/** 24-hour tide curve on the day/night sky, with high and low times and a dot for now. */
class TideSparkView(context: Context) : DrawnView(context) {
    private var hours: List<Hour> = emptyList()
    private var lat: Double? = null
    private var lon: Double? = null
    private var nowSec = 0L
    override fun heightDp() = 96f

    fun set(hours: List<Hour>, lat: Double?, lon: Double?, nowSec: Long) {
        this.hours = hours; this.lat = lat; this.lon = lon; this.nowSec = nowSec; invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        if (hours.size < 3) return
        val w = width.toFloat(); val h = height.toFloat()
        val t0 = hours.first().time; val t1 = hours.last().time
        fun x(t: Long) = (t - t0).toFloat() / (t1 - t0) * w
        // Sky
        val fill = Paint()
        var px = 0f
        while (px < w) {
            val t = t0 + ((px / w) * (t1 - t0)).toLong()
            fill.color = Draw.skyColor(pal, Plan.light(lat, lon, t))
            canvas.drawRect(px, 0f, px + 2f * d + 0.5f, h, fill)
            px += 2f * d
        }
        val levels = hours.mapNotNull { it.seaLevelM }
        if (levels.size < 3) { text(canvas, "No tide data here", w / 2f, h / 2f, 13f, pal.subtle, align = Paint.Align.CENTER); return }
        val scale = max(levels.maxOf { abs(it) }, 1.0)
        val mid = h / 2f; val half = h / 2f - 22f * d
        fun y(v: Double) = mid - (v / scale * half).toFloat()
        val path = Path()
        var started = false
        for (hr in hours) {
            val v = hr.seaLevelM ?: continue
            if (!started) { path.moveTo(x(hr.time), y(v)); started = true } else path.lineTo(x(hr.time), y(v))
        }
        val area = Path(path).apply { lineTo(x(t1), h); lineTo(0f, h); close() }
        canvas.drawPath(area, Draw.paint((pal.tide and 0x00FFFFFF) or 0x2A000000))
        canvas.drawPath(path, Draw.paint(pal.tide).apply { style = Paint.Style.STROKE; strokeWidth = 2.6f * d })
        for (e in Tide.events(hours)) {
            val ex = x(e.time).coerceIn(28f * d, w - 28f * d)
            val label = (if (e.high) "H " else "L ") + Fmt.time(e.time)
            text(canvas, label, ex, if (e.high) 14f * d else h - 5f * d, 11f, pal.text, bold = true, align = Paint.Align.CENTER)
        }
        if (nowSec in t0..t1) {
            val i = hours.indexOfLast { it.time <= nowSec }
            hours.getOrNull(i)?.seaLevelM?.let { v ->
                canvas.drawCircle(x(nowSec), y(v), 5.5f * d, Draw.paint(pal.now))
                canvas.drawCircle(x(nowSec), y(v), 5.5f * d, Draw.paint(pal.card).apply { style = Paint.Style.STROKE; strokeWidth = 2f * d })
            }
        }
    }
}

/** Sun arc from sunrise to sunset with the sun where it is now, plus "sunset in 3 h" text. */
class DaylightView(context: Context) : DrawnView(context) {
    private var rise: Long? = null
    private var set: Long? = null
    private var nowSec = 0L
    override fun heightDp() = 88f

    fun set(lat: Double?, lon: Double?, nowSec: Long) {
        this.nowSec = nowSec
        if (lat != null && lon != null) {
            var dl = Plan.daylight(lat, lon, nowSec)
            // After sunset, show tomorrow's sunrise.
            if (dl.set != null && nowSec > dl.set!!) dl = Plan.daylight(lat, lon, nowSec + 86400)
            rise = dl.rise; set = dl.set
        }
        invalidate()
    }

    private fun span(sec: Long): String {
        val m = (sec / 60).toInt()
        return if (m < 60) "$m min" else "${m / 60} h ${m % 60} min"
    }

    private fun short(sec: Long): String {
        val m = (sec / 60).toInt()
        return if (m < 60) "${m}m" else "${m / 60}h ${m % 60}m"
    }

    override fun onDraw(canvas: Canvas) {
        val r = rise; val s = set
        val aw = 120f * d; val cx = aw / 2f + 14f * d; val base = height - 16f * d; val rad = aw / 2f - 6f * d
        val arc = Draw.paint(pal.accent).apply { style = Paint.Style.STROKE; strokeWidth = 3f * d; pathEffect = android.graphics.DashPathEffect(floatArrayOf(4f * d, 5f * d), 0f) }
        canvas.drawArc(RectF(cx - rad, base - rad, cx + rad, base + rad), 180f, 180f, false, arc)
        canvas.drawLine(cx - rad - 6f * d, base, cx + rad + 6f * d, base, Draw.paint(pal.subtle).apply { strokeWidth = 1.5f * d })
        val tx = aw + 26f * d
        if (r == null || s == null) {
            text(canvas, "DAYLIGHT", tx, 24f * d, 11f, pal.subtle, bold = true)
            text(canvas, "Set a location", tx, 50f * d, 16f, pal.text, bold = true)
            return
        }
        val f = ((nowSec - r).toFloat() / (s - r)).coerceIn(-0.08f, 1.08f)
        val ang = PI * (1 - f)
        val sx = cx + (rad * cos(ang)).toFloat()
        val sy = base - (rad * sin(ang)).toFloat().let { if (f < 0 || f > 1) -10f * d else it }
        canvas.drawCircle(sx, sy, 8f * d, Draw.paint(pal.accent))
        text(canvas, Fmt.time(r), cx - rad, base + 13f * d, 10f, pal.subtle, align = Paint.Align.CENTER)
        text(canvas, Fmt.time(s), cx + rad, base + 13f * d, 10f, pal.subtle, align = Paint.Align.CENTER)
        text(canvas, "DAYLIGHT", tx, 24f * d, 11f, pal.subtle, bold = true)
        val headline = when {
            nowSec < r -> "Sunrise in ${short(r - nowSec)}"
            nowSec < s -> "Sunset in ${short(s - nowSec)}"
            else -> "Sunrise ${Fmt.time(r)}"
        }
        text(canvas, headline, tx, 50f * d, 16f, pal.text, bold = true)
        text(canvas, "${span(s - r)} of light", tx, 70f * d, 13f, pal.subtle)
    }
}
