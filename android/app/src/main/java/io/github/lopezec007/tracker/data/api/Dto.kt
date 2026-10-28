// Wire types, named exactly as the server's JSON (snake_case kept on
// purpose so the mapping to the server's store is obvious).
package io.github.lopezec007.tracker.data.api

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

@Serializable
data class VersionDto(
    val version: String = "",
    val date: String = "",
    val build: String = "",
    /** "tracker" on a tracker server (0.1.0+); anything else answered at that address. */
    val app: String = "",
    /** Stable for the life of that server's database; what the app pins (README "Server identity"). */
    val server_id: String = "",
)

/** One fix as the server stores it; everything but the coordinates may be null. */
@Serializable
data class FixDto(
    val id: Long = 0,
    val entity_id: String = "",
    /** The server's receive time, authoritative for "latest" and for ages. */
    val received_at: String = "",
    val device_time: String? = null,
    val lat: Double,
    val lon: Double,
    val accuracy_m: Double? = null,
    val altitude_m: Double? = null,
    val speed_mps: Double? = null,
    val heading_deg: Double? = null,
    val battery_pct: Int? = null,
    val metadata: JsonObject? = null,
)

/** One fix as the app sends it (POST /api/entities/{id}/fixes takes a list). */
@Serializable
data class FixInput(
    val device_time: String?,
    val lat: Double,
    val lon: Double,
    val accuracy_m: Double?,
    val altitude_m: Double?,
    val speed_mps: Double?,
    val heading_deg: Double?,
    val battery_pct: Int?,
    val metadata: JsonObject?,
)

@Serializable
data class EntityDto(
    val id: String,
    val name: String = "",
    val source: String = "",
    val created_at: String = "",
    val updated_at: String = "",
    /** An OsmAnd drawable name ("" = default) and "#rrggbb" ("" = default); see osmand/EntityAppearance. */
    val icon: String = "",
    val color: String = "",
    val latest: FixDto? = null,
)

@Serializable
data class PostFixesDto(
    val accepted: Int = 0,
    val latest: FixDto? = null,
)

@Serializable
data class ErrorDto(val error: String = "")
