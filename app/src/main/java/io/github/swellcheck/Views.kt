package io.github.swellcheck

import android.content.Context
import android.graphics.Canvas
import android.graphics.RectF
import android.view.View
import kotlin.math.min

/** "Right now": two rating tiles, then wind and swell compass dials. */
class NowView(context: Context) : View(context) {
    private var hour: Hour? = null
    private var facing: Int? = null
    private var limits = Limits(15.0, 22.0, 1.2)
    private var units = Units(WindUnit.MPH, true)

    fun set(hour: Hour, facing: Int?, limits: Limits, units: Units) {
        this.hour = hour; this.facing = facing; this.limits = limits; this.units = units
        invalidate()
    }

    private val d get() = resources.displayMetrics.density

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension(MeasureSpec.getSize(widthMeasureSpec), (262 * d).toInt())
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val h = hour ?: return
        val d = d
        val pal = Themes.current
        val w = width.toFloat()
        val gap = 10f * d

        // Rating tiles
        val surf = Rating.surf(h, facing).rating
        val paddle = Rating.paddle(h, facing, limits).rating
        val tileH = 70f * d
        // Paddle call on the left, surf on the right
        Draw.tile(canvas, RectF(0f, 0f, w / 2f - gap / 2f, tileH), "PADDLE", paddle.label, paddle.color, d, pal)
        Draw.tile(canvas, RectF(w / 2f + gap / 2f, 0f, w, tileH), "SURF", surf.label, surf.color, d, pal)

        // Dials
        val title = Draw.paint(pal.subtle, 11f * d, bold = true)
        val top = tileH + 16f * d
        canvas.drawText("WIND", w / 4f, top + 10f * d, title)
        canvas.drawText("SWELL", w * 3f / 4f, top + 10f * d, title)
        val r = min(w / 4f - 22f * d, 72f * d)
        val cy = top + 24f * d + r
        val gustTxt = h.gustKn?.let { "gusts ${units.speed(it).toInt()}" } ?: ""
        Draw.dial(
            canvas, w / 4f, cy, r, d, pal, h.windDir, pal.wind,
            Fmt.speed(h.windKn, units), gustTxt, facing,
        )
        val metres = h.swellM ?: h.waveM
        val period = h.swellPeriod ?: h.wavePeriod
        Draw.dial(
            canvas, w * 3f / 4f, cy, r, d, pal, h.swellDir ?: h.waveDir, pal.swell,
            Fmt.height(metres, units.feet), if (period != null) Fmt.seconds(period) else "no sea data", facing,
        )
        val sub = Draw.paint(pal.subtle, 11f * d)
        val subY = cy + r + 16f * d
        canvas.drawText("from ${Fmt.compass(h.windDir)}", w / 4f, subY, sub)
        canvas.drawText("from ${Fmt.compass(h.swellDir ?: h.waveDir)}", w * 3f / 4f, subY, sub)
    }
}

/** One of the two 3-day charts. Wider than the screen; put it in a HorizontalScrollView. */
class ForecastChartView(context: Context, private val sea: Boolean) : View(context) {
    private var hours: List<Hour> = emptyList()
    private var facing: Int? = null
    private var limits = Limits(15.0, 22.0, 1.2)
    private var units = Units(WindUnit.MPH, true)
    private var lat: Double? = null
    private var lon: Double? = null

    fun set(hours: List<Hour>, facing: Int?, limits: Limits, units: Units, lat: Double?, lon: Double?) {
        this.hours = hours; this.facing = facing; this.limits = limits; this.units = units
        this.lat = lat; this.lon = lon
        requestLayout()
        invalidate()
    }

    private val d get() = resources.displayMetrics.density

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val wPx = (hours.size * 15 * d).toInt().coerceAtLeast(MeasureSpec.getSize(widthMeasureSpec))
        setMeasuredDimension(wPx, ((if (sea) 310 else 200) * d).toInt())
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val now = System.currentTimeMillis() / 1000
        if (sea) Draw.seaChart(canvas, width.toFloat(), height.toFloat(), hours, facing, units, d, Themes.current, now, lat, lon)
        else Draw.windChart(canvas, width.toFloat(), height.toFloat(), hours, facing, limits, units, d, Themes.current, now, lat, lon)
    }
}
