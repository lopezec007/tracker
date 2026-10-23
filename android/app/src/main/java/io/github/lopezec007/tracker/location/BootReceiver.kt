// Brings the service back after a reboot. Starting a location foreground
// service from a broadcast is allowed for BOOT_COMPLETED, but a service
// started from the background only gets fixes when the app holds
// ACCESS_BACKGROUND_LOCATION ("Allow all the time"); without it the start
// is left to the next time the app is opened.
package io.github.lopezec007.tracker.location

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import io.github.lopezec007.tracker.TrackerApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        if (!Permissions.hasLocation(context) || !Permissions.hasBackgroundLocation(context)) return
        val result = goAsync()
        CoroutineScope(Dispatchers.Default).launch {
            try {
                val prefs = TrackerApp.graph(context).prefs.current()
                if (prefs.isConfigured) TrackerService.start(context)
            } finally {
                result.finish()
            }
        }
    }
}
