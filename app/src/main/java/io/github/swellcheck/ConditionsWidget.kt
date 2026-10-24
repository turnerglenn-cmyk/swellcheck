package io.github.swellcheck

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.RectF
import android.os.Bundle
import android.widget.RemoteViews

/**
 * Home-screen widget. Refreshes about once an hour using the last location the
 * app saw (no background location access). Tap it to open the app, which also
 * updates your location.
 */
class ConditionsWidget : AppWidgetProvider() {

    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        render(context, manager, ids) // show cached data straight away
        val app = context.applicationContext
        val pending = goAsync()
        Thread {
            try {
                runCatching { Repo.refresh(app) }
                render(app, manager, ids)
            } finally {
                pending.finish()
            }
        }.start()
    }

    override fun onAppWidgetOptionsChanged(context: Context, manager: AppWidgetManager, id: Int, newOptions: Bundle?) {
        render(context, manager, intArrayOf(id)) // redraw the chart at the new size
    }

    companion object {
        fun updateAll(context: Context) {
            val manager = AppWidgetManager.getInstance(context)
            val ids = manager.getAppWidgetIds(ComponentName(context, ConditionsWidget::class.java))
            if (ids.isNotEmpty()) render(context, manager, ids)
        }

        fun render(context: Context, manager: AppWidgetManager, ids: IntArray) {
            for (id in ids) {
                runCatching { manager.updateAppWidget(id, build(context, manager, id)) }
            }
        }

        private fun build(context: Context, manager: AppWidgetManager, id: Int): RemoteViews {
            val v = RemoteViews(context.packageName, R.layout.widget_conditions)
            val open = PendingIntent.getActivity(
                context, 0,
                Intent(context, MainActivity::class.java),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            v.setOnClickPendingIntent(R.id.w_root, open)

            // Follow the theme chosen in Settings.
            val pal = Themes.load(context)
            v.setInt(R.id.w_root, "setBackgroundResource", if (pal.light) R.drawable.widget_bg_light else R.drawable.widget_bg)
            v.setTextColor(R.id.w_place, pal.text)
            v.setTextColor(R.id.w_updated, pal.accent)
            for (id2 in intArrayOf(R.id.w_wind, R.id.w_swell, R.id.w_tide)) v.setTextColor(id2, pal.subtle)

            val prefs = Prefs(context)
            val f = Repo.cached(context)
            val lat = prefs.lat
            val lon = prefs.lon
            val place = when {
                !prefs.useGps && prefs.placeName != null -> prefs.placeName!!.substringBefore(",")
                lat != null && lon != null -> "Near you"
                else -> "SwellCheck"
            }
            v.setTextViewText(R.id.w_place, place)

            if (f == null) {
                v.setTextViewText(R.id.w_updated, "")
                v.setTextViewText(R.id.w_wind, "Tap to set up")
                v.setTextViewText(R.id.w_swell, "Open the app once to choose a location")
                v.setTextViewText(R.id.w_tide, "")
                v.setImageViewBitmap(R.id.w_chart, null)
                return v
            }

            val units = prefs.units
            val facing = prefs.facingOrNull
            val limits = prefs.limits
            Rating.windLimits = limits
            val nowSec = System.currentTimeMillis() / 1000
            val upcoming = f.upcoming(nowSec)
            // If the cache is old, "now" is better taken from the hourly forecast.
            val current = if (nowSec - f.now.time < 3 * 3600) f.now else (upcoming.firstOrNull() ?: f.now)

            v.setTextViewText(R.id.w_updated, Fmt.time(f.fetchedAt / 1000))
            v.setTextViewText(R.id.w_wind, "Wind  ${Fmt.wind(current, units)}")
            v.setTextViewText(R.id.w_swell, "Swell  ${Fmt.swell(current, units.feet)}")
            val springNeap = Moon.tideLabel(Moon.tideStrength(nowSec)).lowercase()
            v.setTextViewText(R.id.w_tide, Tide.summary(f.hours, nowSec)?.let { "Tide  ${it.replaceFirstChar { c -> c.lowercase() }} · $springNeap" } ?: "Tide  $springNeap")
            v.setImageViewBitmap(R.id.w_chart, picture(context, manager, id, current, upcoming.take(13), facing, limits, units, nowSec, lat, lon))
            return v
        }

        /** Rating tiles plus a 12-hour mini chart, drawn to fit the widget's size. */
        private fun picture(
            context: Context, manager: AppWidgetManager, id: Int, now: Hour, hours: List<Hour>,
            facing: Int?, limits: Limits, units: Units, nowSec: Long, lat: Double?, lon: Double?,
        ): Bitmap {
            val d = context.resources.displayMetrics.density
            val opts = manager.getAppWidgetOptions(id)
            val wDp = opts.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, 0).takeIf { it > 0 } ?: 300
            val hDp = opts.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT, 0).takeIf { it > 0 } ?: 200
            // Space left for the picture after the text lines and padding.
            val picWDp = (wDp - 20).coerceIn(160, 600)
            val picHDp = (hDp - 90).coerceIn(90, 300)
            val bmp = Bitmap.createBitmap((picWDp * d).toInt(), (picHDp * d).toInt(), Bitmap.Config.ARGB_8888)
            val c = Canvas(bmp)
            val pal = Themes.current
            val w = bmp.width.toFloat()
            val gap = 6f * d
            val tileH = 44f * d

            val surf = Rating.surf(now, facing).rating
            val paddle = Rating.paddle(now, facing, limits).rating
            Draw.tile(c, RectF(0f, 0f, w / 2f - gap / 2f, tileH), "PADDLE", paddle.label, paddle.color, d * 0.8f, pal)
            Draw.tile(c, RectF(w / 2f + gap / 2f, 0f, w, tileH), "SURF", surf.label, surf.color, d * 0.8f, pal)

            val chartTop = tileH + gap
            c.save()
            c.translate(0f, chartTop)
            Draw.chart(c, w, bmp.height - chartTop, hours, facing, limits, units, d, pal, nowSec, compact = true, lat = lat, lon = lon)
            c.restore()
            return bmp
        }
    }
}
