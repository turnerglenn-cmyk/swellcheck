package io.github.swellcheck

/** The ways the main screen can be laid out. Picked in Settings. */
enum class ScreenLayout(val title: String, val blurb: String) {
    CLASSIC("Classic", "Ratings, wind and swell dials, details, then the 3-day charts."),
    VERDICT("Verdict first", "A big paddle call with the reason and the best time to go, then the week at a glance."),
    HOURLY("Hour by hour", "A list of the next 30 hours: wind, swell, tide and a go/no-go dot for each."),
    TILES("Tiles", "Big tiles for wind, swell, tide and daylight. Easy to read at a glance."),
    SCENE("Live scene", "An animated view of the beach drawn from the forecast: waves, tide, wind, weather and your paddle call."),
}
