// The runtime permissions the service needs, checked in one place.
package io.github.lopezec007.tracker.location

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat

object Permissions {
    fun has(context: Context, permission: String): Boolean =
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

    /** Fine location: needed to collect fixes and to start the location foreground service at all. */
    fun hasLocation(context: Context) = has(context, Manifest.permission.ACCESS_FINE_LOCATION)

    /** "Allow all the time": needed for the service started at boot (from the background) to get fixes. */
    fun hasBackgroundLocation(context: Context) = has(context, Manifest.permission.ACCESS_BACKGROUND_LOCATION)

    /** Without it the persistent notification is hidden (the service still runs). */
    fun hasNotifications(context: Context) = has(context, Manifest.permission.POST_NOTIFICATIONS)
}
