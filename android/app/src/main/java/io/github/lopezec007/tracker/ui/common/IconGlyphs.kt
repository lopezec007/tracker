// The app's own glyph for each curated OsmAnd icon name (EntityAppearance),
// so the entity list shows what OsmAnd's context menu will; a free-text
// name falls back to a generic marker.
package io.github.lopezec007.tracker.ui.common

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.DirectionsBike
import androidx.compose.material.icons.automirrored.filled.DirectionsWalk
import androidx.compose.material.icons.filled.DirectionsBus
import androidx.compose.material.icons.filled.DirectionsCar
import androidx.compose.material.icons.filled.ElectricScooter
import androidx.compose.material.icons.filled.Flag
import androidx.compose.material.icons.filled.Flight
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.LocalShipping
import androidx.compose.material.icons.filled.LocalTaxi
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Phone
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.filled.Sailing
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.Train
import androidx.compose.material.icons.filled.TwoWheeler
import androidx.compose.material.icons.filled.Work
import androidx.compose.ui.graphics.vector.ImageVector

object IconGlyphs {
    private val byName: Map<String, ImageVector> = mapOf(
        "ic_action_pedestrian_dark" to Icons.AutoMirrored.Filled.DirectionsWalk,
        "ic_action_car_dark" to Icons.Filled.DirectionsCar,
        "ic_action_bicycle_dark" to Icons.AutoMirrored.Filled.DirectionsBike,
        "ic_action_motorcycle_dark" to Icons.Filled.TwoWheeler,
        "ic_action_scooter" to Icons.Filled.ElectricScooter,
        "ic_action_bus_dark" to Icons.Filled.DirectionsBus,
        "ic_action_truck_dark" to Icons.Filled.LocalShipping,
        "ic_action_taxi" to Icons.Filled.LocalTaxi,
        "ic_action_train" to Icons.Filled.Train,
        "ic_action_sail_boat_dark" to Icons.Filled.Sailing,
        "ic_action_aircraft" to Icons.Filled.Flight,
        "ic_action_home_dark" to Icons.Filled.Home,
        "ic_action_work" to Icons.Filled.Work,
        "ic_action_favorite" to Icons.Filled.Star,
        "ic_action_flag" to Icons.Filled.Flag,
        "ic_action_phone" to Icons.Filled.Phone,
        "ic_action_photo_dark" to Icons.Filled.PhotoCamera,
        "ic_action_user" to Icons.Filled.Person,
    )

    /** The glyph for an OsmAnd icon name; the default marker for "" and for names outside the curated list. */
    fun glyph(osmandName: String): ImageVector = byName[osmandName] ?: Icons.Filled.Place
}
