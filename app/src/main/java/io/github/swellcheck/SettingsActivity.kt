package io.github.swellcheck

import android.app.Activity
import android.content.Intent
import android.content.res.ColorStateList
import android.net.Uri
import android.os.Bundle
import android.text.InputType
import android.text.method.LinkMovementMethod
import android.text.util.Linkify
import android.view.Gravity
import android.widget.ArrayAdapter
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast

/** Settings, in the same card style as the main screen. */
class SettingsActivity : Activity() {

    private lateinit var prefs: Prefs
    /** Saves what's been typed above. Run by Save, and before a theme change reloads this screen. */
    private var saveEdits: (quiet: Boolean) -> Boolean = { true }
    private lateinit var useGps: RadioButton
    private lateinit var useFixed: RadioButton
    private lateinit var fixedLabel: TextView
    private lateinit var results: LinearLayout

    // A place picked from search but not saved yet.
    private var pending: Place? = null

    private val pal: Palette get() = Themes.current
    private val tint by lazy {
        ColorStateList(
            arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()),
            intArrayOf(pal.accent, pal.subtle),
        )
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)
        Themes.load(this)
        Ui.themeWindow(this)

        val scroll = ScrollView(this).apply { clipToPadding = false }
        val root = Ui.column(this)
        scroll.addView(root)

        // Header, centred like the main screen
        root.addView(Ui.text(this, getString(R.string.settings), 22f, bold = true).apply { gravity = Gravity.CENTER }, full())
        root.addView(Ui.text(this, "Saved on this phone only", 12f).apply {
            gravity = Gravity.CENTER; setTextColor(pal.accent)
        }, full())


        // --- Location
        val loc = Ui.card(this)
        loc.addView(Ui.label(this, "Location"))
        useGps = radio("Use my phone's location", 1001)
        useFixed = radio("Use a fixed place", 1002)
        val group = RadioGroup(this).apply {
            addView(useGps)
            addView(useFixed)
            check(if (prefs.useGps) useGps.id else useFixed.id)
        }
        loc.addView(group)
        fixedLabel = Ui.text(this, "Fixed place: ${prefs.placeName ?: "none chosen"}", 13f).apply { setTextColor(pal.subtle) }
        loc.addView(fixedLabel)
        val searchRow = Ui.row(this).apply { gravity = Gravity.CENTER_VERTICAL }
        val query = EditText(this).apply {
            hint = "Search a beach or town"
            inputType = InputType.TYPE_CLASS_TEXT
            isSingleLine = true
            Ui.styleField(this)
        }
        searchRow.addView(query, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        val go = Ui.button(this, "Search") { search(query.text.toString()) }
        searchRow.addView(go, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
            leftMargin = Ui.dp(this@SettingsActivity, 8)
        })
        loc.addView(searchRow, full(top = 8))
        results = Ui.column(this)
        loc.addView(results)
        root.addView(loc)

        // --- Beach direction
        val beach = Ui.card(this)
        beach.addView(Ui.label(this, "Which way the beach faces"))
        beach.addView(Ui.text(this,
            "Stand on the beach looking out to sea. Which way are you facing? " +
                "This lets SwellCheck spot offshore wind and swell that won't reach the beach.", 13f).apply {
            setTextColor(pal.subtle)
        })
        val options = listOf("Not set") + (0 until 16).map { "${Fmt.compassName(it)}  (${(it * 22.5).toInt()}°)" }
        val facing = dropdown(options).apply {
            val f = prefs.beachFacing
            setSelection(if (f < 0) 0 else (((f / 22.5) + 0.5).toInt() % 16) + 1)
        }
        beach.addView(facing, full(top = 8))
        root.addView(beach)

        // --- Units
        val unitsCard = Ui.card(this)
        unitsCard.addView(Ui.label(this, "Units"))
        val units = WindUnit.values()
        val windUnit = dropdown(listOf("Wind in mph", "Wind in knots", "Wind in km/h")).apply {
            setSelection(units.indexOf(prefs.windUnit).coerceAtLeast(0))
        }
        unitsCard.addView(windUnit, full(top = 6))
        val feet = CheckBox(this).apply {
            text = "Wave heights in feet"
            isChecked = prefs.useFeet
            buttonTintList = tint
            setTextColor(pal.text)
            textSize = 15f
        }
        unitsCard.addView(feet, full(top = 6))
        root.addView(unitsCard)

        // --- Paddling limits (shown in the units in use when this screen opened)
        val u = prefs.units
        val limitsCard = Ui.card(this)
        limitsCard.addView(Ui.label(this, "Your paddling limits"))
        limitsCard.addView(Ui.text(this, "Above these it's \"No go\". Within 75% of them it's \"Marginal\" (60% for offshore wind).", 13f).apply {
            setTextColor(pal.subtle)
        })
        val maxWind = numberRow(limitsCard, "Max wind", u.wind.label, round1(u.speed(prefs.maxWind)))
        val maxGust = numberRow(limitsCard, "Max gusts", u.wind.label, round1(u.speed(prefs.maxGust)))
        val maxWave = numberRow(limitsCard, "Max waves", u.lenLabel, round1(u.len(prefs.maxWave)))
        val maxOffshore = numberRow(limitsCard, "Max offshore wind", u.wind.label, round1(u.speed(prefs.maxOffshore)))
        limitsCard.addView(Ui.text(this, "Offshore wind blows you away from the beach and the sea looks calm close in. Keep this low.", 12f).apply {
            setTextColor(pal.subtle)
        })
        root.addView(limitsCard)

        // --- Save
        val save = Ui.button(this, "Save") { }
        root.addView(save, full(top = 18))
        saveEdits = save@{ quiet ->
            val wantGps = group.checkedRadioButtonId == useGps.id
            val chosen = pending
            if (!wantGps && chosen == null && prefs.placeName == null) {
                if (!quiet) Toast.makeText(this, "Search for a place first", Toast.LENGTH_SHORT).show()
                return@save false
            }
            if (wantGps) {
                if (!prefs.useGps) { prefs.lat = null; prefs.lon = null }
                prefs.useGps = true
                prefs.placeName = null
            } else {
                prefs.useGps = false
                if (chosen != null) {
                    prefs.lat = chosen.lat
                    prefs.lon = chosen.lon
                    prefs.placeName = chosen.name
                }
            }
            val sel = facing.selectedItemPosition
            prefs.beachFacing = if (sel <= 0) -1 else ((sel - 1) * 22.5).toInt()
            // Limits were typed in the units shown on screen; store them in knots / metres.
            val lenFactor = if (u.feet) 3.28084 else 1.0
            maxWind.text.toString().toDoubleOrNull()?.takeIf { it > 0 }?.let { prefs.maxWind = it / u.wind.perKnot }
            maxGust.text.toString().toDoubleOrNull()?.takeIf { it > 0 }?.let { prefs.maxGust = it / u.wind.perKnot }
            maxWave.text.toString().toDoubleOrNull()?.takeIf { it > 0 }?.let { prefs.maxWave = it / lenFactor }
            maxOffshore.text.toString().toDoubleOrNull()?.takeIf { it > 0 }?.let { prefs.maxOffshore = it / u.wind.perKnot }
            prefs.useFeet = feet.isChecked
            prefs.windUnit = units.getOrElse(windUnit.selectedItemPosition) { WindUnit.MPH }
            Rating.windLimits = prefs.limits                 // the surf call uses them straight away too
            prefs.settingsChanged = System.currentTimeMillis()   // the live wallpaper picks this up
            ConditionsWidget.updateAll(applicationContext)
            true
        }
        save.setOnClickListener { if (saveEdits(false)) finish() }

        // --- Look: colour theme and screen layout (both apply straight away, so they sit below Save)
        root.addView(themeCard())
        root.addView(layoutCard())

        // --- About (disclaimer, data credit, privacy)
        val about = Ui.card(this)
        about.addView(Ui.label(this, "About"))
        about.addView(aboutText(
            "Forecasts come from computer models and can be wrong, especially close to the coast. " +
                "Tide times, ranges and spring/neap timing are approximate and not for navigation. " +
                "Always check an official forecast and use your own judgement on the water."))
        about.addView(aboutText(
            "No ads, no tracking, no account. Your location is rounded to about 1 km and only sent " +
                "to Open-Meteo to get the forecast. Moon phase is worked out on your phone."))
        about.addView(Ui.text(this, "", 13f).apply {
            setTextColor(pal.subtle)
            setLinkTextColor(pal.accent)
            autoLinkMask = Linkify.WEB_URLS // must be set before the text
            text = "Weather and marine data by Open-Meteo.com (CC BY 4.0)\nhttps://open-meteo.com/"
            movementMethod = LinkMovementMethod.getInstance()
            setPadding(0, Ui.dp(this@SettingsActivity, 6), 0, 0)
        })
        val version = runCatching { packageManager.getPackageInfo(packageName, 0).versionName }.getOrNull() ?: ""
        about.addView(Ui.text(this, "SwellCheck $version · open source (GPL-3.0)", 12f).apply {
            setTextColor(pal.subtle); setPadding(0, Ui.dp(this@SettingsActivity, 8), 0, 0)
        })
        about.addView(Ui.button(this, "Source code", filled = false) { openUrl(getString(R.string.source_url)) }, full(top = 10))
        about.addView(aboutText(
            "SwellCheck is free and always will be. If it's useful to you, you can chip in towards a coffee."))
        about.addView(Ui.button(this, "☕  Buy me a coffee") { openUrl(getString(R.string.donate_url)) }, full(top = 10))
        root.addView(about)

        Ui.applySystemBarPadding(this, scroll)
        setContentView(scroll)
    }

    // ---- theme and layout pickers ---------------------------------------

    private fun themeCard(): LinearLayout {
        val card = Ui.card(this)
        card.addView(Ui.label(this, "Theme"))
        card.addView(Ui.text(this, "Tap to try one. It changes straight away.", 13f).apply { setTextColor(pal.subtle) })
        val perRow = 3
        Themes.all.chunked(perRow).forEach { chunk ->
            val row = Ui.row(this)
            chunk.forEachIndexed { i, theme ->
                val swatch = themeSwatch(theme, theme.id == prefs.theme)
                row.addView(swatch, LinearLayout.LayoutParams(0, Ui.dp(this, 78), 1f).apply {
                    if (i > 0) leftMargin = Ui.dp(this@SettingsActivity, 8)
                })
            }
            // Keep the last row's tiles the same width as the others.
            repeat(perRow - chunk.size) {
                row.addView(android.view.View(this), LinearLayout.LayoutParams(0, 1, 1f).apply { leftMargin = Ui.dp(this@SettingsActivity, 8) })
            }
            card.addView(row, full(top = 8))
        }
        return card
    }

    private fun themeSwatch(theme: Theme, selected: Boolean): LinearLayout {
        val t = theme.pal
        val box = Ui.column(this).apply {
            setPadding(Ui.dp(this@SettingsActivity, 10), Ui.dp(this@SettingsActivity, 10), Ui.dp(this@SettingsActivity, 8), Ui.dp(this@SettingsActivity, 8))
            background = android.graphics.drawable.GradientDrawable().apply {
                cornerRadius = Ui.dp(this@SettingsActivity, 14).toFloat()
                setColor(t.bg)
                if (selected) setStroke(Ui.dp(this@SettingsActivity, 3), pal.accent)
                else setStroke(Ui.dp(this@SettingsActivity, 1), t.ring)
            }
            isClickable = true
            contentDescription = theme.name + if (selected) ", selected" else ""
            setOnClickListener {
                if (prefs.theme != theme.id) {
                    saveEdits(true)                     // keep anything typed above: the screen reloads in the new colours
                    prefs.theme = theme.id
                    ConditionsWidget.updateAll(applicationContext)
                    recreate()
                }
            }
        }
        val dots = Ui.row(this)
        for (col in listOf(t.accent, t.wind, t.swell)) {
            dots.addView(android.view.View(this).apply {
                background = android.graphics.drawable.GradientDrawable().apply {
                    shape = android.graphics.drawable.GradientDrawable.OVAL
                    setColor(col)
                }
            }, LinearLayout.LayoutParams(Ui.dp(this, 14), Ui.dp(this, 14)).apply { rightMargin = Ui.dp(this@SettingsActivity, 4) })
        }
        box.addView(dots)
        box.addView(Ui.text(this, theme.name, 12.5f, bold = true).apply {
            setTextColor(t.text)
            maxLines = 2
        }, full(top = 6))
        return box
    }

    private fun layoutCard(): LinearLayout {
        val card = Ui.card(this)
        card.addView(Ui.label(this, "Main screen layout"))
        val options = mutableListOf<LinearLayout>()
        fun paint(selected: ScreenLayout) {
            ScreenLayout.values().forEachIndexed { i, l ->
                options[i].background = android.graphics.drawable.GradientDrawable().apply {
                    cornerRadius = Ui.dp(this@SettingsActivity, 12).toFloat()
                    setColor(if (l == selected) Draw.mix(pal.card, pal.accent, 0.16f) else pal.bg)
                    setStroke(Ui.dp(this@SettingsActivity, if (l == selected) 2 else 1), if (l == selected) pal.accent else pal.ring)
                }
                options[i].contentDescription = l.title + if (l == selected) ", selected" else ""
            }
        }
        ScreenLayout.values().forEach { l ->
            val opt = Ui.column(this).apply {
                setPadding(Ui.dp(this@SettingsActivity, 14), Ui.dp(this@SettingsActivity, 10), Ui.dp(this@SettingsActivity, 14), Ui.dp(this@SettingsActivity, 10))
                isClickable = true
                addView(Ui.text(this@SettingsActivity, l.title, 15f, bold = true))
                addView(Ui.text(this@SettingsActivity, l.blurb, 13f).apply { setTextColor(pal.subtle) })
                setOnClickListener {
                    prefs.layout = l
                    paint(l)
                }
            }
            options += opt
            card.addView(opt, full(top = 8))
        }
        paint(prefs.layout)
        val on = SceneWallpaper.isActive(this)
        card.addView(Ui.text(this, "Live wallpaper", 15f, bold = true), full(top = 14))
        card.addView(Ui.text(this,
            if (on) "The live scene is your phone wallpaper. It updates by itself as conditions change."
            else "Use the live scene as your phone's background, whatever layout you pick here. It moves only while your home screen is showing and updates by itself as conditions change.",
            13f).apply { setTextColor(pal.subtle) }, full(top = 4))
        card.addView(Ui.button(this, if (on) "Change wallpaper" else "Set as phone wallpaper", filled = !on) {
            SceneWallpaper.offer(this)
        }, full(top = 8))
        return card
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        SceneWallpaper.onResult(this, requestCode, resultCode)
    }

    // ---- small builders -------------------------------------------------

    private fun full(top: Int = 0) = LinearLayout.LayoutParams(
        LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT,
    ).apply { topMargin = Ui.dp(this@SettingsActivity, top) }

    private fun radio(label: String, viewId: Int) = RadioButton(this).apply {
        text = label
        id = viewId
        buttonTintList = tint
        setTextColor(pal.text)
        textSize = 15f
        setPadding(Ui.dp(this@SettingsActivity, 6), Ui.dp(this@SettingsActivity, 6), 0, Ui.dp(this@SettingsActivity, 6))
    }

    private fun dropdown(items: List<String>) = Spinner(this).apply {
        // Colour the text ourselves: the system default is light text, unreadable on the light themes.
        adapter = object : ArrayAdapter<String>(this@SettingsActivity, android.R.layout.simple_spinner_dropdown_item, items) {
            override fun getView(position: Int, convertView: android.view.View?, parent: android.view.ViewGroup): android.view.View =
                super.getView(position, convertView, parent).also { (it as? TextView)?.setTextColor(pal.text) }
            override fun getDropDownView(position: Int, convertView: android.view.View?, parent: android.view.ViewGroup): android.view.View =
                super.getDropDownView(position, convertView, parent).also { (it as? TextView)?.setTextColor(pal.text) }
        }
        Ui.styleField(this)
        setPopupBackgroundDrawable(Ui.fieldBackground(this@SettingsActivity).apply { setColor(pal.card) })
    }

    /** "Max wind   [ 15 ]  mph" on one line. Returns the text field. */
    private fun numberRow(parent: LinearLayout, label: String, unit: String, value: Double): EditText {
        val row = Ui.row(this).apply { gravity = Gravity.CENTER_VERTICAL }
        row.addView(Ui.text(this, label, 15f), LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        val field = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
            setText(if (value % 1.0 == 0.0) value.toInt().toString() else value.toString())
            gravity = Gravity.CENTER
            Ui.styleField(this)
        }
        row.addView(field, LinearLayout.LayoutParams(Ui.dp(this, 84), LinearLayout.LayoutParams.WRAP_CONTENT))
        row.addView(Ui.text(this, unit, 14f).apply { setTextColor(pal.subtle); setPadding(Ui.dp(this@SettingsActivity, 10), 0, 0, 0) },
            LinearLayout.LayoutParams(Ui.dp(this, 52), LinearLayout.LayoutParams.WRAP_CONTENT))
        parent.addView(row, full(top = 10))
        return field
    }

    private fun aboutText(s: String) = Ui.text(this, s, 13f).apply {
        setTextColor(pal.subtle)
        setPadding(0, Ui.dp(this@SettingsActivity, 6), 0, 0)
    }

    private fun round1(v: Double): Double = Math.round(v * 10.0) / 10.0

    private fun openUrl(url: String) {
        runCatching { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
    }

    private fun search(q: String) {
        if (q.isBlank()) return
        results.removeAllViews()
        results.addView(Ui.text(this, "Searching…", 13f).apply { setTextColor(pal.subtle) })
        Thread {
            val found = runCatching { Api.search(q) }
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                results.removeAllViews()
                val list = found.getOrNull()
                when {
                    list == null -> results.addView(Ui.text(this, "Search failed. Check your connection.", 13f))
                    list.isEmpty() -> results.addView(Ui.text(this, "Nothing found. Try a nearby town.", 13f))
                    else -> list.forEach { place ->
                        val pick = Ui.button(this, place.name, filled = false) {
                            pending = place
                            useFixed.isChecked = true
                            fixedLabel.text = "Fixed place: ${place.name} (tap Save)"
                            results.removeAllViews()
                        }
                        results.addView(pick, full(top = 6))
                    }
                }
            }
        }.start()
    }
}
