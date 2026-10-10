package io.github.swellcheck

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

class MainActivity : Activity() {

    private lateinit var prefs: Prefs
    private lateinit var placeView: TextView
    private lateinit var statusView: TextView
    private lateinit var content: LinearLayout
    private var askedPermission = false
    private var busy = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)

        val scroll = ScrollView(this).apply { clipToPadding = false }
        val root = Ui.column(this)
        scroll.addView(root)

        // Header: just the place and a quiet status line, centred. No buttons up here.
        placeView = Ui.text(this, "", 22f, bold = true).apply { gravity = Gravity.CENTER }
        root.addView(placeView, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        statusView = Ui.text(this, "", 12f).apply { gravity = Gravity.CENTER; setTextColor(Palette().accent) }
        root.addView(statusView, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        enablePullToRefresh(scroll)

        content = Ui.column(this)
        root.addView(content)

        root.addView(footer())
        Ui.applySystemBarPadding(this, scroll)
        setContentView(scroll)
    }

    override fun onResume() {
        super.onResume()
        Repo.cached(this)?.let { render(it) } ?: renderEmpty()
        start(force = false)
        ticker.removeCallbacks(tick)
        ticker.postDelayed(tick, AUTO_REFRESH_MS)
    }

    override fun onPause() {
        super.onPause()
        ticker.removeCallbacks(tick)
    }

    // ---- automatic refresh ---------------------------------------------

    private val ticker = android.os.Handler(android.os.Looper.getMainLooper())
    private val tick = object : Runnable {
        override fun run() {
            start(force = false) // fetches only if the data is older than 20 minutes
            ticker.postDelayed(this, AUTO_REFRESH_MS)
        }
    }

    /** Pull down at the top of the screen to refresh straight away. No extra libraries needed. */
    private fun enablePullToRefresh(scroll: ScrollView) {
        var startY = -1f
        val threshold = Ui.dp(this, 90)
        scroll.setOnTouchListener { _, e ->
            when (e.actionMasked) {
                android.view.MotionEvent.ACTION_DOWN -> startY = if (scroll.scrollY == 0) e.rawY else -1f
                android.view.MotionEvent.ACTION_MOVE ->
                    if (startY >= 0 && e.rawY - startY > threshold && !busy) statusView.text = "Release to refresh"
                android.view.MotionEvent.ACTION_UP -> {
                    if (startY >= 0 && scroll.scrollY == 0 && e.rawY - startY > threshold) start(force = true)
                    startY = -1f
                }
                android.view.MotionEvent.ACTION_CANCEL -> startY = -1f
            }
            false // let the ScrollView handle scrolling as normal
        }
    }

    // ---- getting location + data ---------------------------------------

    private fun start(force: Boolean) {
        if (!prefs.useGps) {
            if (prefs.lat == null) {
                statusView.text = "Choose a place in Settings."
                return
            }
            fetch(force)
            return
        }
        if (!Locator.hasPermission(this)) {
            if (!askedPermission) {
                askedPermission = true
                requestPermissions(
                    arrayOf(Manifest.permission.ACCESS_COARSE_LOCATION, Manifest.permission.ACCESS_FINE_LOCATION),
                    REQ_LOCATION,
                )
            } else {
                statusView.text = "Location is off for SwellCheck. Allow it, or pick a fixed place in Settings."
            }
            return
        }
        statusView.text = "Finding your location…"
        Locator.locate(this) { loc ->
            if (isFinishing || isDestroyed) return@locate
            if (loc != null) {
                prefs.lat = loc.latitude
                prefs.lon = loc.longitude
            }
            if (prefs.lat == null) {
                statusView.text = "Couldn't get your location. Try outside, or pick a place in Settings."
            } else {
                fetch(force)
            }
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != REQ_LOCATION) return
        if (grantResults.any { it == PackageManager.PERMISSION_GRANTED }) {
            start(force = true)
        } else {
            statusView.text = "No location permission. You can pick a fixed place in Settings instead."
        }
    }

    private fun fetch(force: Boolean) {
        if (busy) return
        busy = true
        statusView.text = "Updating…"
        val app = applicationContext
        Thread {
            val result = runCatching { Repo.refresh(app, force) }
            runOnUiThread {
                busy = false
                if (isFinishing || isDestroyed) return@runOnUiThread
                val f = result.getOrNull()
                if (f != null) {
                    render(f)
                    ConditionsWidget.updateAll(app)
                } else {
                    val cached = Repo.cached(this)
                    statusView.text = if (cached != null) {
                        "Offline: showing forecast from ${Fmt.time(cached.fetchedAt / 1000)}"
                    } else {
                        "Couldn't reach the forecast service. Check your connection and tap ↻."
                    }
                }
            }
        }.start()
    }

    // ---- drawing ---------------------------------------------------------

    private fun placeLabel(): String {
        val lat = prefs.lat
        val lon = prefs.lon
        return when {
            !prefs.useGps && prefs.placeName != null -> prefs.placeName!!
            lat != null && lon != null -> "Near you · ${Fmt.coords(lat, lon)}"
            else -> getString(R.string.app_name)
        }
    }

    private fun renderEmpty() {
        placeView.text = placeLabel()
        content.removeAllViews()
    }

    private fun render(f: Forecast) {
        placeView.text = placeLabel()
        statusView.text = "● Live  ·  updated ${Fmt.time(f.fetchedAt / 1000)}"
        content.removeAllViews()

        val units = prefs.units
        val feet = units.feet
        val facing = prefs.facingOrNull
        val limits = prefs.limits
        val nowSec = System.currentTimeMillis() / 1000
        // If the saved forecast is old, take "now" from the hourly data instead.
        val upcoming = f.upcoming(nowSec)
        val now = if (nowSec - f.now.time < 3 * 3600) f.now else (upcoming.firstOrNull() ?: f.now)

        // Right now: tiles + dials
        val nowCard = Ui.card(this)
        nowCard.addView(NowView(this).apply { set(now, facing, limits, units) })
        val surf = Rating.surf(now, facing)
        val paddle = Rating.paddle(now, facing, limits)
        val why = (surf.notes + paddle.reasons).distinct()
        if (why.isNotEmpty()) nowCard.addView(Ui.text(this, why.joinToString(" · "), 13f))
        content.addView(nowCard)

        // Details: label column + value, like an instrument readout
        val details = Ui.card(this)
        if (now.waveM != null) {
            details.addView(Ui.kv(this, "Waves", "${Fmt.height(now.waveM, feet)} total · chop ${Fmt.height(now.windWaveM, feet)}"))
        }
        details.addView(Ui.kv(this, "Temp", "sea ${Fmt.temp(now.seaTempC)} · air ${Fmt.temp(now.airC)}"))
        Tide.summary(f.hours, nowSec)?.let { details.addView(Ui.kv(this, "Tide", it.replaceFirstChar { c -> c.lowercase() })) }
        // Tide strength: today's range from the forecast, plus spring/neap from the moon (worked out offline).
        TideRange.today(f.hours, nowSec - 6 * 3600, nowSec + 18 * 3600)?.let { range ->
            details.addView(Ui.kv(this, "Range", "${Fmt.height(range, feet)} high to low"))
        }
        val strength = Moon.tideStrength(nowSec)
        val next = if (Moon.buildingToSprings(nowSec)) "next springs ${Fmt.dayShort(Moon.nextSprings(nowSec))}"
            else "next neaps ${Fmt.dayShort(Moon.nextNeaps(nowSec))}"
        details.addView(Ui.kv(this, "Strength", android.text.SpannableStringBuilder().apply {
            append(Moon.bar(strength)).append("  ").append(Moon.tideLabel(strength))
            setSpan(android.text.style.ForegroundColorSpan(Palette().tide), 0, length, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }))
        details.addView(Ui.kv(this, "Moon", "${Moon.name(nowSec)} · $next"))
        if (!f.hasSea) {
            details.addView(Ui.text(this, "No sea data here. Pick a spot on the coast for swell and tides.", 13f))
        }
        content.addView(details)

        // 3-day charts: sea on top, wind below, scrolling together so the hours line up.
        val chartCard = Ui.card(this)
        chartCard.addView(Ui.text(this, "72-hour outlook  ›  swipe", 17f, bold = true))
        chartCard.addView(Ui.text(this, legend(units), 12f))
        val scroller = HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false }
        val stack = Ui.column(this)
        val shown = upcoming.take(72)
        // Each chart sizes itself (wider than the screen), so give it wrap_content width.
        // Default match_parent width collapses to nothing inside a horizontal scroller.
        fun wrap() = android.widget.LinearLayout.LayoutParams(
            android.widget.LinearLayout.LayoutParams.WRAP_CONTENT,
            android.widget.LinearLayout.LayoutParams.WRAP_CONTENT,
        )
        stack.addView(ForecastChartView(this, sea = true).apply { set(shown, facing, limits, units) }, wrap())
        stack.addView(ForecastChartView(this, sea = false).apply { set(shown, facing, limits, units) },
            wrap().apply { topMargin = Ui.dp(this@MainActivity, 18) })
        scroller.addView(stack)
        chartCard.addView(scroller)
        content.addView(chartCard)
    }

    private fun legend(u: Units): CharSequence {
        val pal = Palette()
        val b = android.text.SpannableStringBuilder()
        fun key(sym: String, color: Int, label: String) {
            val start = b.length
            b.append(sym)
            b.setSpan(android.text.style.ForegroundColorSpan(color), start, b.length, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            b.append(" ").append(label).append("   ")
        }
        key("▮", pal.swell, "swell ${u.lenLabel}")
        key("∿", pal.tide, "tide")
        key("━", pal.wind, "wind ${u.wind.label}")
        key("┅", pal.gust, "gust")
        key("━", 0xFFFF6B6B.toInt(), "your limit")
        return b
    }

    private fun footer(): LinearLayout {
        val box = Ui.column(this)
        box.setPadding(0, Ui.dp(this, 20), 0, 0)

        // Settings and donate side by side, equal width.
        val buttons = Ui.row(this)
        val settings = Ui.button(this, "⚙  Settings", filled = false) {
            startActivity(Intent(this@MainActivity, SettingsActivity::class.java))
        }
        val beer = Ui.button(this, "☕  Buy me a coffee") { openUrl(getString(R.string.donate_url)) }
        buttons.addView(settings, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        buttons.addView(beer, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
            leftMargin = Ui.dp(this@MainActivity, 10)
        })
        box.addView(buttons)
        return box
    }

    private fun openUrl(url: String) {
        runCatching { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
    }

    companion object {
        private const val REQ_LOCATION = 1
        private const val AUTO_REFRESH_MS = 30 * 60 * 1000L
    }
}
