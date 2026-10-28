// An entity's icon and colour as the server stores them (README "Custom
// icons"): the icon is an OsmAnd drawable name that OsmAnd resolves with
// Resources.getIdentifier(name, "drawable", ...) for the context menu's
// type icon (AMapPointMenuController reads POINT_TYPE_ICON_NAME_PARAM);
// the colour is "#rrggbb". Pure Kotlin: the curated list, the server's
// validation rules and the colour parsing, tested on the JVM. The app's own
// glyph for each curated icon lives in ui/common/IconGlyphs.
package io.github.lopezec007.tracker.osmand

/** One entry of the curated icon grid: OsmAnd's drawable name and a label for people. */
data class IconChoice(val osmandName: String, val label: String)

/** One entry of the colour palette: "#rrggbb" and a label. */
data class ColorChoice(val hex: String, val label: String)

object EntityAppearance {
    /** The server's rules: icon `^[a-z0-9_]{0,64}$`, colour "" or `^#[0-9a-fA-F]{6}$`. */
    private val ICON_RE = Regex("^[a-z0-9_]{0,64}$")
    private val COLOR_RE = Regex("^#[0-9a-fA-F]{6}$")

    fun isValidIcon(name: String): Boolean = ICON_RE.matches(name)
    fun isValidColor(hex: String): Boolean = hex.isEmpty() || COLOR_RE.matches(hex)

    /**
     * OsmAnd drawables (OsmAnd/res/drawable in the OsmAnd repository) that
     * exist by these names; "" is the default (no type icon).
     */
    val ICONS: List<IconChoice> = listOf(
        IconChoice("", "Default"),
        IconChoice("ic_action_pedestrian_dark", "Walking"),
        IconChoice("ic_action_car_dark", "Car"),
        IconChoice("ic_action_bicycle_dark", "Bicycle"),
        IconChoice("ic_action_motorcycle_dark", "Motorcycle"),
        IconChoice("ic_action_scooter", "Scooter"),
        IconChoice("ic_action_bus_dark", "Bus"),
        IconChoice("ic_action_truck_dark", "Truck"),
        IconChoice("ic_action_taxi", "Taxi"),
        IconChoice("ic_action_train", "Train"),
        IconChoice("ic_action_sail_boat_dark", "Boat"),
        IconChoice("ic_action_aircraft", "Plane"),
        IconChoice("ic_action_home_dark", "Home"),
        IconChoice("ic_action_work", "Work"),
        IconChoice("ic_action_favorite", "Star"),
        IconChoice("ic_action_flag", "Flag"),
        IconChoice("ic_action_phone", "Phone"),
        IconChoice("ic_action_photo_dark", "Camera"),
        IconChoice("ic_action_user", "Person"),
    )

    /** "" is the default (blue for this phone, green for others, grey when stale). */
    val COLORS: List<ColorChoice> = listOf(
        ColorChoice("", "Default"),
        ColorChoice("#E53935", "Red"),
        ColorChoice("#FB8C00", "Orange"),
        ColorChoice("#FDD835", "Yellow"),
        ColorChoice("#7CB342", "Light green"),
        ColorChoice("#2E7D32", "Green"),
        ColorChoice("#00897B", "Teal"),
        ColorChoice("#039BE5", "Light blue"),
        ColorChoice("#1E88E5", "Blue"),
        ColorChoice("#8E24AA", "Purple"),
        ColorChoice("#D81B60", "Pink"),
        ColorChoice("#6D4C41", "Brown"),
    )

    /** "#rrggbb" to opaque ARGB; null for "" or anything malformed. */
    fun parseColor(hex: String): Int? {
        if (!COLOR_RE.matches(hex)) return null
        return (0xFF shl 24) or hex.substring(1).toInt(16)
    }

    /**
     * The colour as OsmAnd's importGpx takes it: its GpxUtilities.parseColor
     * accepts "#rrggbb" directly (besides the renderer's names), so the hex
     * goes through as it is; null (OsmAnd's default track colour) when unset.
     */
    fun gpxColor(hex: String): String? = if (COLOR_RE.matches(hex)) hex.lowercase() else null

    /** The label of a curated icon, else the raw name (a free-text OsmAnd drawable). */
    fun iconLabel(name: String): String = ICONS.firstOrNull { it.osmandName == name }?.label ?: name
}
