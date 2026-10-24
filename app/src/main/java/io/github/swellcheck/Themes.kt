package io.github.swellcheck

import android.content.Context

/** One colour theme the user can pick in Settings. */
data class Theme(val id: String, val name: String, val pal: Palette)

/**
 * All the colour themes. [current] is what every screen, chart and the widget
 * draws with; call [load] before building a screen so it follows the setting.
 */
object Themes {
    private fun c(hex: String): Int = (0xFF000000L or hex.removePrefix("#").toLong(16)).toInt()
    private fun a(alpha: Int, hex: String): Int = ((alpha.toLong() shl 24) or hex.removePrefix("#").toLong(16)).toInt()

    val all: List<Theme> = listOf(
        Theme("golden", "Golden Hour", Palette(
            bg = c("16110D"), card = c("241B14"), text = c("FFF4E2"), subtle = c("C2A88A"),
            grid = a(0x30, "FFC56B"), ring = a(0x70, "E9A23B"), swell = c("3FA7D6"), wind = c("F4845F"),
            gust = a(0xC0, "F4845F"), tide = c("8FD3D8"), sea = a(0x60, "8FD3D8"), now = c("FFFFFF"),
            onTile = c("1E140C"), accent = c("FFB547"), night = c("0A0705"), day = c("2F5878"),
            limit = c("FF5E5B"), dusk = c("C8642E"))),
        Theme("harbour", "Harbour", Palette(
            bg = c("0D1417"), card = c("152126"), text = c("F6F1E7"), subtle = c("9FB3B2"),
            grid = a(0x2C, "F2C14E"), ring = a(0x66, "F2C14E"), swell = c("4CC9F0"), wind = c("F2C14E"),
            gust = a(0xC0, "F2C14E"), tide = c("F79256"), sea = a(0x60, "F79256"), now = c("FFFFFF"),
            onTile = c("10181A"), accent = c("F2C14E"), night = c("060A0C"), day = c("1E4E66"),
            limit = c("FF6B6B"), dusk = c("B85C38"))),
        Theme("sunset", "Sunset Coast", Palette(
            bg = c("120A0C"), card = c("1F1216"), text = c("FFEDE3"), subtle = c("C79A93"),
            grid = a(0x30, "FF8A65"), ring = a(0x70, "FF7A59"), swell = c("5AA9FF"), wind = c("FFD166"),
            gust = a(0xC0, "FFD166"), tide = c("6ED3CF"), sea = a(0x60, "6ED3CF"), now = c("FFFFFF"),
            onTile = c("1A0B0E"), accent = c("FF7A59"), night = c("0B0507"), day = c("3A2A5C"),
            limit = c("FF4D6D"), dusk = c("E0603F"))),
        Theme("driftwood", "Driftwood", Palette(
            bg = c("1A1714"), card = c("26221E"), text = c("F3EDE4"), subtle = c("ADA192"),
            grid = a(0x2A, "E8D5B5"), ring = a(0x55, "D4B483"), swell = c("6CA6C1"), wind = c("C1D37F"),
            gust = a(0xC0, "C1D37F"), tide = c("E8A87C"), sea = a(0x60, "E8A87C"), now = c("FFF8EE"),
            onTile = c("1A1714"), accent = c("E8A87C"), night = c("0F0D0B"), day = c("3B4F5C"),
            limit = c("E76F51"), dusk = c("9C5A3C"))),
        Theme("ember", "Ember", Palette(
            bg = c("130B08"), card = c("21130E"), text = c("FFEADB"), subtle = c("C9967E"),
            grid = a(0x30, "FF7B3A"), ring = a(0x70, "FF6B2C"), swell = c("6FB7FF"), wind = c("FF9F43"),
            gust = a(0xC0, "FF9F43"), tide = c("FFD6A5"), sea = a(0x60, "FFD6A5"), now = c("FFFFFF"),
            onTile = c("1A0C06"), accent = c("FF6B2C"), night = c("080403"), day = c("4A2A22"),
            limit = c("FF4D4D"), dusk = c("D9541E"))),
        Theme("heather", "Heather Moor", Palette(
            bg = c("15101A"), card = c("221A2A"), text = c("F4ECF7"), subtle = c("B7A3C2"),
            grid = a(0x30, "D98BC9"), ring = a(0x66, "D98BC9"), swell = c("7EB6FF"), wind = c("F5B971"),
            gust = a(0xC0, "F5B971"), tide = c("9FE0C9"), sea = a(0x60, "9FE0C9"), now = c("FFFFFF"),
            onTile = c("1A1120"), accent = c("E59BD8"), night = c("0A070D"), day = c("3D3566"),
            limit = c("FF6B8B"), dusk = c("C46A6A"))),
        Theme("kelp", "Kelp Forest", Palette(
            bg = c("0C1410"), card = c("14211A"), text = c("EEF5E9"), subtle = c("A3B8A5"),
            grid = a(0x2C, "D9B44A"), ring = a(0x66, "D9B44A"), swell = c("5EC4E6"), wind = c("E9C46A"),
            gust = a(0xC0, "E9C46A"), tide = c("F4A261"), sea = a(0x60, "F4A261"), now = c("FFFFFF"),
            onTile = c("0C1410"), accent = c("E9C46A"), night = c("050A07"), day = c("1F4A45"),
            limit = c("FF6B6B"), dusk = c("B5763A"))),
        Theme("storm", "Storm Grey", Palette(
            bg = c("121417"), card = c("1C2025"), text = c("F1F1EE"), subtle = c("A6ADB5"),
            grid = a(0x2C, "F0B35A"), ring = a(0x60, "F0B35A"), swell = c("6BB8FF"), wind = c("F0B35A"),
            gust = a(0xC0, "F0B35A"), tide = c("8EE3EF"), sea = a(0x60, "8EE3EF"), now = c("FFFFFF"),
            onTile = c("121417"), accent = c("F0B35A"), night = c("08090B"), day = c("37465A"),
            limit = c("FF6B6B"), dusk = c("A86A3D"))),
        Theme("mission", "Mission (cool)", Palette()),
        Theme("nightwatch", "Night Watch (red)", Palette(
            bg = c("0A0000"), card = c("140303"), text = c("FF8A80"), subtle = c("C25B52"),
            grid = a(0x30, "FF5A4E"), ring = a(0x60, "FF5A4E"), swell = c("E0473B"), wind = c("FFB0A8"),
            gust = a(0xC0, "FFB0A8"), tide = c("FF7A6E"), sea = a(0x50, "FF7A6E"), now = c("FFD0CB"),
            onTile = c("0A0000"), accent = c("FF6E62"), night = c("050000"), day = c("2A0805"),
            limit = c("FFE0DC"), dusk = c("4A100A"), redOnly = true)),
        Theme("sand", "Sand (light)", Palette(
            bg = c("F3EADB"), card = c("FFFAF2"), text = c("2A1D12"), subtle = c("6E5A47"),
            grid = a(0x30, "6B4F36"), ring = a(0x80, "C9894B"), swell = c("1F6FB2"), wind = c("23744A"),
            gust = a(0xC0, "23744A"), tide = c("0E7F96"), sea = a(0x50, "0E7F96"), now = c("2A1D12"),
            onTile = c("FFFFFF"), accent = c("B84E1C"), night = c("D5DCE6"), day = c("FFF3D6"),
            limit = c("C23B3B"), dusk = c("F6C49A"), light = true)),
        Theme("seaglass", "Sea Glass (light)", Palette(
            bg = c("E8F1EE"), card = c("F8FCFA"), text = c("15302C"), subtle = c("4D6B66"),
            grid = a(0x30, "2F5D57"), ring = a(0x80, "4FA393"), swell = c("1D6FA3"), wind = c("B5541C"),
            gust = a(0xC0, "B5541C"), tide = c("0F8577"), sea = a(0x50, "0F8577"), now = c("15302C"),
            onTile = c("FFFFFF"), accent = c("0F7A6D"), night = c("CFD8E3"), day = c("FCF6E3"),
            limit = c("C23B3B"), dusk = c("F3C9A6"), light = true)),
    )

    const val DEFAULT = "golden"

    @Volatile var current: Palette = byId(DEFAULT).pal
        private set

    fun byId(id: String?): Theme = all.firstOrNull { it.id == id } ?: all.first { it.id == DEFAULT }

    /** Read the chosen theme from settings. Cheap; call at the start of every screen and widget update. */
    fun load(context: Context): Palette {
        current = byId(Prefs(context).theme).pal
        return current
    }
}
