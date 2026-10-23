// Room tables: pending_fixes is the offline queue of fixes not yet
// acknowledged by the server, in the order they were recorded.
package io.github.lopezec007.tracker.data.db

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "pending_fixes")
data class PendingFixEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    /** The device's timestamp for the fix, epoch millis UTC. */
    val deviceTime: Long,
    val lat: Double,
    val lon: Double,
    val accuracyM: Double?,
    val altitudeM: Double?,
    val speedMps: Double?,
    val headingDeg: Double?,
    val batteryPct: Int?,
    /** A JSON object: at least {"provider": ...}. */
    val metadata: String,
)
