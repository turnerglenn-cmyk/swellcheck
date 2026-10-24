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
    private var builtTheme: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)
        Themes.load(this)
        builtTheme = prefs.theme
        Ui.themeWindow(this)

        val scroll = ScrollView(this).apply { clipToPadding = false }
        val root = Ui.column(this)
        scroll.addView(root)

        // Header: just the place and a quiet status line, centred. No buttons up here.
        placeView = Ui.text(this, "", 22f, bold = true).apply { gravity = Gravity.CENTER }
        root.addView(placeView, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        statusView = Ui.text(this, "", 12f).apply { gravity = Gravity.CENTER; setTextColor(Themes.current.accent) }
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
        // Theme changed in Settings: rebuild the whole screen in the new colours.
        if (prefs.theme != builtTheme) { recreate(); return }
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
        Rating.windLimits = limits
        val nowSec = System.currentTimeMillis() / 1000
        // If the saved forecast is old, take "now" from the hourly data instead.
        val upcoming = f.upcoming(nowSec)
        val now = if (nowSec - f.now.time < 3 * 3600) f.now else (upcoming.firstOrNull() ?: f.now)

        val lat = prefs.lat
        val lon = prefs.lon
        val surf = Rating.surf(now, facing)
        val paddle = Rating.paddle(now, facing, limits)
        val pal = Themes.current

        when (prefs.layout) {
            ScreenLayout.CLASSIC -> {
                val nowCard = Ui.card(this)
                nowCard.addView(NowView(this).apply { set(now, facing, limits, units) })
                val why = (surf.notes + paddle.reasons).distinct()
                if (why.isNotEmpty()) nowCard.addView(Ui.text(this, why.joinToString(" · "), 13f))
                content.addView(nowCard)
                content.addView(detailsCard(f, now, nowSec, units))
                content.addView(chartsCard(upcoming, facing, limits, units))
            }

            ScreenLayout.VERDICT -> {
                val days = Plan.days(f.hours, facing, limits, lat, lon, nowSec)
                val hero = Ui.card(this)
                hero.addView(Ui.label(this, "Paddle now"))
                hero.addView(Ui.text(this, paddle.rating.label, 40f, bold = true).apply { setTextColor(pal.rc(paddle.rating.color)) })
                hero.addView(Ui.text(this, Plan.reason(f.hours, facing, limits, units, lat, lon, nowSec), 16f))
                days.firstOrNull()?.let { today ->
                    val best = today.best
                    hero.addView(Ui.text(this, Ui.badge(
                        if (best != null) "Best window today: " else "Today: ",
                        if (best != null) "${Fmt.time(best.first)} – ${Fmt.time(best.second)}" else today.verdict.lowercase(),
                        pal.rc(today.verdictColor)), 15f))
                }
                hero.addView(Ui.text(this, Ui.badge("Surf: ", surf.rating.label, pal.rc(surf.rating.color)), 15f).apply {
                    append("  ·  ${Fmt.swell(now, feet)}")
                })
                content.addView(hero)

                val best = Ui.card(this)
                best.addView(Ui.text(this, "Best times to go", 17f, bold = true))
                best.addView(Ui.text(this, "Coloured by your paddle call · faded when it's dark", 12f).apply { setTextColor(pal.subtle) })
                best.addView(BestTimesView(this).apply { set(days) }, matchWrap(top = 8))
                content.addView(best)

                content.addView(statGrid(f, now, nowSec, units))
                content.addView(chartsCard(upcoming, facing, limits, units))
            }

            ScreenLayout.HOURLY -> {
                content.addView(RatingTilesView(this).apply { set(surf.rating, paddle.rating) }, matchWrap(top = 12))
                val summary = Ui.card(this)
                summary.addView(Ui.text(this, Plan.reason(f.hours, facing, limits, units, lat, lon, nowSec), 15f))
                Tide.summary(f.hours, nowSec)?.let { summary.addView(Ui.text(this, "Tide: " + it.replaceFirstChar { c -> c.lowercase() }, 14f).apply { setTextColor(pal.tide) }) }
                content.addView(summary)
                val list = Ui.card(this).apply { setPadding(0, Ui.dp(this@MainActivity, 4), 0, 0) }
                list.addView(HourListView(this).apply { set(upcoming.take(30), f.hours, facing, limits, units, lat, lon) }, matchWrap())
                content.addView(list)
            }

            ScreenLayout.TILES -> {
                content.addView(RatingTilesView(this).apply { set(surf.rating, paddle.rating) }, matchWrap(top = 12))
                content.addView(Ui.pair(this,
                    Ui.statCard(this, "Wind", Fmt.speed(now.windKn, units), "${Fmt.compass(now.windDir)} · gusts ${Fmt.speed(now.gustKn, units)}", pal.wind),
                    Ui.statCard(this, "Swell", Fmt.height(now.swellM ?: now.waveM, feet),
                        "${Fmt.seconds(now.swellPeriod ?: now.wavePeriod)} · ${Fmt.compass(now.swellDir ?: now.waveDir)}", pal.swell)))
                val tide = Ui.card(this)
                tide.addView(Ui.label(this, "Tide · next 24 hours"))
                TideRange.today(f.hours, nowSec - 6 * 3600, nowSec + 18 * 3600)?.let { range ->
                    val st = Moon.tideStrength(nowSec)
                    tide.addView(Ui.text(this, "${Fmt.height(range, feet)} range · ${Moon.tideLabel(st).lowercase()}", 13f).apply { setTextColor(pal.tide) })
                }
                tide.addView(TideSparkView(this).apply { set(upcoming.take(25), lat, lon, nowSec) }, matchWrap(top = 6))
                content.addView(tide)
                val sun = Ui.card(this)
                sun.addView(DaylightView(this).apply { set(lat, lon, nowSec) }, matchWrap())
                content.addView(sun)
                val springs = if (Moon.buildingToSprings(nowSec)) "Next springs" to Fmt.dayShort(Moon.nextSprings(nowSec))
                    else "Next neaps" to Fmt.dayShort(Moon.nextNeaps(nowSec))
                content.addView(Ui.pair(this,
                    Ui.statCard(this, "Sea", Fmt.temp(now.seaTempC), "air ${Fmt.temp(now.airC)}", pal.text),
                    Ui.statCard(this, springs.first, springs.second, Moon.name(nowSec), pal.text)))
                content.addView(chartsCard(upcoming, facing, limits, units))
            }

            ScreenLayout.SCENE -> {
                val scene = SceneView(this).apply {
                    heightDp = 300f
                    set(SceneInput.from(f, SceneInput.moment(f, nowSec), placeLabel(), facing, limits, units, lat, lon, nowSec))
                    val label = placeLabel()
                    rebuild = {
                        val sec = System.currentTimeMillis() / 1000
                        SceneInput.from(f, SceneInput.moment(f, sec), label, facing, limits, units, lat, lon, sec)
                    }
                }
                content.addView(scene, matchWrap(top = 12))
                if (!SceneWallpaper.isActive(this)) {
                    content.addView(Ui.button(this, "Set as phone wallpaper", filled = false) { SceneWallpaper.offer(this) }, matchWrap(top = 10))
                }
                content.addView(RatingTilesView(this).apply { set(surf.rating, paddle.rating) }, matchWrap(top = 12))
                val why = Ui.card(this)
                // Why the surf and paddle calls are what they are (same notes as the Classic layout)
                val notes = (surf.notes + paddle.reasons).distinct()
                if (notes.isNotEmpty()) why.addView(Ui.text(this, notes.joinToString(" · "), 14f).apply { setTextColor(pal.subtle) })
                why.addView(Ui.text(this, Plan.reason(f.hours, facing, limits, units, lat, lon, nowSec), 15f))
                if (facing == null) why.addView(Ui.text(this,
                    "Set which way your beach faces in Settings so the scene looks the right way out to sea.", 13f).apply { setTextColor(pal.subtle) })
                content.addView(why)
                val key = Ui.card(this)
                key.addView(Ui.text(this, "What you're seeing", 17f, bold = true))
                key.addView(Ui.text(this, sceneKey(), 13f).apply { setTextColor(pal.subtle); setLineSpacing(0f, 1.25f) })
                content.addView(key)
                content.addView(chartsCard(upcoming, facing, limits, units))
            }
        }
        if (!f.hasSea) {
            content.addView(Ui.card(this).apply {
                addView(Ui.text(this@MainActivity, "No sea data here. Pick a spot on the coast for swell and tides.", 13f))
            })
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        SceneWallpaper.onResult(this, requestCode, resultCode)
    }

    private fun sceneKey() = listOf(
        "Waves: angle = swell direction; they come in sets every swell period, in real time, slow and steepen in the shallows and break where it gets too shallow: bigger waves further out. Wide spilling surf at low tide, closer and more plunging higher up; long-period swell plunges.",
        "Water's edge: the tide. The weed line is high water.",
        "Kayaks: on a go day a yellow, blue and red sea kayak make their own fun: surfing the swell out past the break, cruising the coast, heading off out of sight, rafting up for a chat or practising rolls. Whenever the surf is up they're out surfing it, even if it's too big for a paddling trip. Otherwise one alone close in = marginal, upside down on the sand = no go.",
        "Surfer: sits at the peak just outside the break and rides across the face; good surf = more and longer rides, fair = long waits. Board in the sand = poor or flat.",
        "Sun and moon are where they really are; the moon's shape shows springs or neaps.",
        "Clouds, rain, fog and lightning follow the forecast. The buoy bobs a mile out. Gulls sit it out in a gale.",
        "Now and then the surfer wipes out, kayaks bump on a shared wave and roll back up, and on rare days the rescue helicopter comes by.",
        "On rough days (no-go with 25+ kn wind, 38+ kn gusts, big seas or thunder) the all-weather lifeboat launches soon after it turns rough, up to 3 times a day, and comes back an hour later. Sunday 9-10am the inshore lifeboat crew train out at sea.",
        "Every couple of hours a container ship passes far out on the horizon.",
    ).joinToString("\n") { "• $it" }

    private fun matchWrap(top: Int = 0) = LinearLayout.LayoutParams(
        LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT,
    ).apply { topMargin = Ui.dp(this@MainActivity, top) }

    /** Details: label column + value, like an instrument readout. */
    private fun detailsCard(f: Forecast, now: Hour, nowSec: Long, units: Units): LinearLayout {
        val feet = units.feet
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
            setSpan(android.text.style.ForegroundColorSpan(Themes.current.tide), 0, length, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }))
        details.addView(Ui.kv(this, "Moon", "${Moon.name(nowSec)} · $next"))
        val lat = prefs.lat
        val lon = prefs.lon
        if (lat != null && lon != null) {
            val dl = Plan.daylight(lat, lon, nowSec)
            if (dl.rise != null && dl.set != null) details.addView(Ui.kv(this, "Daylight", "sunrise ${Fmt.time(dl.rise)} · sunset ${Fmt.time(dl.set)}"))
        }
        return details
    }

    /** Six small cards: wind, swell, tide, range, sea, daylight. */
    private fun statGrid(f: Forecast, now: Hour, nowSec: Long, units: Units): LinearLayout {
        val pal = Themes.current
        val feet = units.feet
        val box = Ui.column(this)
        val next = Tide.events(f.hours).filter { it.time > nowSec }.take(2)
        val tideBig = next.firstOrNull()?.let { (if (it.high) "High " else "Low ") + Fmt.time(it.time) } ?: "–"
        val tideSmall = next.getOrNull(1)?.let { (if (it.high) "then high " else "then low ") + Fmt.time(it.time) } ?: ""
        val range = TideRange.today(f.hours, nowSec - 6 * 3600, nowSec + 18 * 3600)
        val st = Moon.tideStrength(nowSec)
        val lat = prefs.lat
        val lon = prefs.lon
        val dl = if (lat != null && lon != null) Plan.daylight(lat, lon, nowSec) else null
        box.addView(Ui.pair(this,
            Ui.statCard(this, "Wind", "${Fmt.speed(now.windKn, units)} ${Fmt.compass(now.windDir)}", "gusts ${Fmt.speed(now.gustKn, units)}", pal.wind),
            Ui.statCard(this, "Swell", Fmt.height(now.swellM ?: now.waveM, feet),
                "${Fmt.seconds(now.swellPeriod ?: now.wavePeriod)} from ${Fmt.compass(now.swellDir ?: now.waveDir)}", pal.swell)))
        box.addView(Ui.pair(this,
            Ui.statCard(this, "Tide", tideBig, tideSmall, pal.tide),
            Ui.statCard(this, "Range", range?.let { Fmt.height(it, feet) } ?: "–", Moon.tideLabel(st).lowercase(), pal.tide)))
        box.addView(Ui.pair(this,
            Ui.statCard(this, "Sea", Fmt.temp(now.seaTempC), "air ${Fmt.temp(now.airC)}", pal.text),
            Ui.statCard(this, "Daylight", dl?.rise?.let { Fmt.time(it) } ?: "–", dl?.set?.let { "sunset ${Fmt.time(it)}" } ?: "", pal.text)))
        return box
    }

    /** 3-day charts: sea on top, wind below, scrolling together so the hours line up. */
    private fun chartsCard(upcoming: List<Hour>, facing: Int?, limits: Limits, units: Units): LinearLayout {
        val chartCard = Ui.card(this)
        chartCard.addView(Ui.text(this, "72-hour outlook  ›  swipe", 17f, bold = true))
        chartCard.addView(Ui.text(this, legend(units), 12f))
        val scroller = HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false }
        val stack = Ui.column(this)
        val shown = upcoming.take(72)
        // Each chart sizes itself (wider than the screen), so give it wrap_content width.
        // Default match_parent width collapses to nothing inside a horizontal scroller.
        fun wrap() = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        stack.addView(ForecastChartView(this, sea = true).apply { set(shown, facing, limits, units, prefs.lat, prefs.lon) }, wrap())
        stack.addView(ForecastChartView(this, sea = false).apply { set(shown, facing, limits, units, prefs.lat, prefs.lon) },
            wrap().apply { topMargin = Ui.dp(this@MainActivity, 18) })
        scroller.addView(stack)
        chartCard.addView(scroller)
        return chartCard
    }

    private fun legend(u: Units): CharSequence {
        val pal = Themes.current
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
        key("━", pal.limit, "your limit")
        return b
    }

    private fun footer(): LinearLayout {
        val box = Ui.column(this)
        box.setPadding(0, Ui.dp(this, 20), 0, 0)

        val buttons = Ui.button(this, "⚙  Settings", filled = false) {
            startActivity(Intent(this@MainActivity, SettingsActivity::class.java))
        }
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
