// OsmAnd over its AIDL API (README "OsmAnd integration"): binds to the
// OsmandAidlServiceV2 of whichever OsmAnd is installed and keeps one map
// layer, "Tracker", with a point per entity; on request it also starts
// navigation to a point, centres the map on one, and imports / hides /
// removes a history track. Binding is lazy and redone after a disconnect;
// a missing OsmAnd only shows in `state`.
package io.github.lopezec007.tracker.osmand

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.IBinder
import android.os.RemoteException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import net.osmand.aidlapi.IOsmAndAidlInterface
import net.osmand.aidlapi.gpx.HideGpxParams
import net.osmand.aidlapi.gpx.ImportGpxParams
import net.osmand.aidlapi.gpx.RemoveGpxParams
import net.osmand.aidlapi.map.ALatLon
import net.osmand.aidlapi.maplayer.AMapLayer
import net.osmand.aidlapi.maplayer.AddMapLayerParams
import net.osmand.aidlapi.maplayer.point.AMapPoint
import net.osmand.aidlapi.maplayer.point.RemoveMapPointParams
import net.osmand.aidlapi.maplayer.point.ShowMapPointParams
import net.osmand.aidlapi.navigation.NavigateParams

enum class OsmAndStatus { NOT_INSTALLED, DISCONNECTED, CONNECTING, CONNECTED }

data class OsmAndState(
    val status: OsmAndStatus = OsmAndStatus.DISCONNECTED,
    /** The installed OsmAnd flavour, once looked up. */
    val packageName: String? = null,
    val lastPushAt: Long? = null,
    val lastError: String? = null,
    val pointCount: Int = 0,
)

class OsmAndBridge(private val context: Context, private val now: () -> Long = System::currentTimeMillis) {
    private val _state = MutableStateFlow(OsmAndState())
    val state: StateFlow<OsmAndState> = _state

    private val mutex = Mutex()
    private val icons = PointIcons(context)
    @Volatile private var api: IOsmAndAidlInterface? = null
    @Volatile private var bound = false
    private var connected: CompletableDeferred<IOsmAndAidlInterface?>? = null

    /** Point ids sent in the previous push, so ones that vanished are removed from the layer. */
    private var shownIds: Set<String> = emptySet()

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val iface = service?.let { IOsmAndAidlInterface.Stub.asInterface(it) }
            api = iface
            shownIds = emptySet() // a fresh OsmAnd process knows nothing of our layer
            _state.update { it.copy(status = if (iface != null) OsmAndStatus.CONNECTED else OsmAndStatus.DISCONNECTED) }
            connected?.complete(iface)
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            api = null
            _state.update { it.copy(status = OsmAndStatus.DISCONNECTED) }
        }

        override fun onBindingDied(name: ComponentName?) {
            dropBinding()
            _state.update { it.copy(status = OsmAndStatus.DISCONNECTED, lastError = "OsmAnd went away; will reconnect") }
        }

        override fun onNullBinding(name: ComponentName?) {
            dropBinding()
            _state.update { it.copy(status = OsmAndStatus.DISCONNECTED, lastError = "OsmAnd refused the connection") }
            connected?.complete(null)
        }
    }

    /** The first OsmAnd flavour found on the phone, or null. */
    fun installedPackage(): String? {
        val pm = context.packageManager
        return PACKAGES.firstOrNull { pkg ->
            try {
                pm.getPackageInfo(pkg, 0)
                true
            } catch (_: PackageManager.NameNotFoundException) {
                false
            }
        }
    }

    /** An intent that opens OsmAnd's map, or null when it is not installed. */
    fun launchIntent(): Intent? {
        val pkg = installedPackage() ?: return null
        return context.packageManager.getLaunchIntentForPackage(pkg)?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }

    /**
     * Shows [points] on the Tracker layer, replacing what was shown before.
     * Returns false (with `state.lastError`) when OsmAnd is not installed or
     * could not be reached; tracking is unaffected either way.
     */
    suspend fun push(points: List<TrackerPoint>): Boolean = mutex.withLock {
        val iface = ensureBound() ?: return false
        withContext(Dispatchers.IO) {
            try {
                val pkg = _state.value.packageName ?: installedPackage()
                val aidlPoints = points.map { it.toAidl(pkg) }
                // addMapLayer is an upsert in OsmAnd: a new layer is created, an
                // existing one takes the points (merged by id) and the map reloads.
                // Image points: OsmAnd draws each point's rendered icon (PointIcons)
                // at its small/big icon zooms and a plain coloured circle beyond.
                val layer = AMapLayer(LAYER_ID, LAYER_NAME, LAYER_Z_ORDER, aidlPoints)
                layer.setImagePoints(true)
                val ok = iface.addMapLayer(AddMapLayerParams(layer))
                if (!ok) {
                    _state.update { it.copy(lastError = "OsmAnd rejected the layer: check that Tracker is enabled under OsmAnd → Plugins → Connected apps") }
                    return@withContext false
                }
                val ids = points.map { it.id }.toSet()
                for (gone in shownIds - ids) iface.removeMapPoint(RemoveMapPointParams(LAYER_ID, gone))
                shownIds = ids
                _state.update { it.copy(status = OsmAndStatus.CONNECTED, lastPushAt = now(), lastError = null, pointCount = points.size) }
                true
            } catch (e: RemoteException) {
                api = null
                _state.update { it.copy(status = OsmAndStatus.DISCONNECTED, lastError = "OsmAnd call failed: ${e.message}") }
                false
            } catch (e: Exception) {
                _state.update { it.copy(lastError = "OsmAnd call failed: $e") }
                false
            }
        }
    }

    /**
     * Starts OsmAnd's navigation to [lat],[lon] with [profile] ("car",
     * "bicycle", "pedestrian"): `navigate(NavigateParams(startName = "",
     * startLat = 0, startLon = 0, destName, destLat, destLon, profile,
     * force = true, needLocationPermission = false))`. A zero start is
     * OsmAnd's "from my position" (its receiver turns 0/0 into a null start);
     * force skips the "stop current navigation?" question.
     */
    suspend fun navigateTo(name: String, lat: Double, lon: Double, profile: String): Boolean =
        call("navigation") { iface ->
            iface.navigate(NavigateParams("", 0.0, 0.0, name, lat, lon, profile, true, false))
        }

    /**
     * Centres OsmAnd's map on [point] and opens its context menu:
     * `showMapPoint(ShowMapPointParams(layerId, point))`. OsmAnd swaps in
     * its own copy of the point when the layer already holds that id.
     */
    suspend fun showPoint(point: TrackerPoint): Boolean =
        call("show on map") { iface -> iface.showMapPoint(ShowMapPointParams(LAYER_ID, point.toAidl(_state.value.packageName))) }

    /**
     * Imports a GPX document as `<OsmAnd>/tracks/[fileName]` and shows it:
     * `importGpx(ImportGpxParams(sourceRawData = gpx, destinationPath =
     * fileName, color, show = true))`. [color] is "#rrggbb" or null for
     * OsmAnd's default. Importing the same name again replaces the track.
     */
    suspend fun importGpx(gpx: String, fileName: String, color: String?): Boolean =
        call("history import") { iface -> iface.importGpx(ImportGpxParams(gpx, fileName, color, true)) }

    /** Hides a track shown by [importGpx]: `hideGpx(HideGpxParams(fileName))` (matched by the file's name). */
    suspend fun hideGpx(fileName: String): Boolean =
        call("history hide") { iface -> iface.hideGpx(HideGpxParams(fileName)) }

    /** Deletes a track imported by [importGpx]: `removeGpx(RemoveGpxParams(fileName))` (only API-imported files are removed). */
    suspend fun removeGpx(fileName: String): Boolean =
        call("history remove") { iface -> iface.removeGpx(RemoveGpxParams(fileName)) }

    /**
     * One AIDL call: binds when needed, runs [block] off the main thread and
     * turns a `false` answer or a dead binder into `state.lastError`.
     */
    private suspend fun call(what: String, block: (IOsmAndAidlInterface) -> Boolean): Boolean = mutex.withLock {
        val iface = ensureBound() ?: return false
        withContext(Dispatchers.IO) {
            try {
                val ok = block(iface)
                if (!ok) {
                    _state.update { it.copy(lastError = "OsmAnd refused the $what: check that Tracker is enabled under OsmAnd → Plugins → Connected apps") }
                } else {
                    _state.update { it.copy(status = OsmAndStatus.CONNECTED, lastError = null) }
                }
                ok
            } catch (e: RemoteException) {
                api = null
                _state.update { it.copy(status = OsmAndStatus.DISCONNECTED, lastError = "OsmAnd call failed: ${e.message}") }
                false
            } catch (e: Exception) {
                _state.update { it.copy(lastError = "OsmAnd call failed: $e") }
                false
            }
        }
    }

    /** The live interface, binding first when needed (waits up to a few seconds for OsmAnd to start). */
    private suspend fun ensureBound(): IOsmAndAidlInterface? {
        api?.let { return it }
        val pkg = installedPackage()
        if (pkg == null) {
            _state.update { it.copy(status = OsmAndStatus.NOT_INSTALLED, packageName = null, lastError = null) }
            return null
        }
        if (bound) {
            // Bound but not (yet, or any more) connected: OsmAnd may be restarting.
            // Drop the binding and bind afresh so bindService starts it again.
            dropBinding()
        }
        val deferred = CompletableDeferred<IOsmAndAidlInterface?>()
        connected = deferred
        _state.update { it.copy(status = OsmAndStatus.CONNECTING, packageName = pkg) }
        val intent = Intent(SERVICE_ACTION).setPackage(pkg)
        val ok = try {
            context.bindService(intent, connection, Context.BIND_AUTO_CREATE)
        } catch (e: SecurityException) {
            _state.update { it.copy(status = OsmAndStatus.DISCONNECTED, lastError = "cannot bind to OsmAnd: ${e.message}") }
            return null
        }
        if (!ok) {
            context.unbindService(connection)
            _state.update { it.copy(status = OsmAndStatus.DISCONNECTED, lastError = "OsmAnd $pkg has no AIDL service (too old?)") }
            return null
        }
        bound = true
        val iface = withTimeoutOrNull(BIND_TIMEOUT_MS) { deferred.await() }
        if (iface == null) {
            _state.update { it.copy(status = OsmAndStatus.DISCONNECTED, lastError = it.lastError ?: "OsmAnd did not answer in time") }
        }
        return iface
    }

    private fun dropBinding() {
        api = null
        if (bound) {
            bound = false
            try {
                context.unbindService(connection)
            } catch (_: IllegalArgumentException) {
                // not registered (already unbound)
            }
        }
    }

    /** Releases the binding (the service going down); the next push binds again. */
    fun unbind() {
        dropBinding()
        _state.update { it.copy(status = if (it.status == OsmAndStatus.NOT_INSTALLED) it.status else OsmAndStatus.DISCONNECTED) }
    }

    /**
     * The AIDL point, with its marker image when [osmandPackage] is known
     * (the image URI must be granted to that package). A rendering failure
     * only costs the image: OsmAnd then draws its placeholder.
     */
    private fun TrackerPoint.toAidl(osmandPackage: String?): AMapPoint {
        val p = LinkedHashMap(params)
        if (osmandPackage != null) {
            try {
                p[AMapPoint.POINT_IMAGE_URI_PARAM] = icons.uriFor(icon, color, osmandPackage).toString()
            } catch (e: Exception) {
                _state.update { it.copy(lastError = "cannot render icon for $name: $e") }
            }
        }
        return AMapPoint(
            id,
            name,       // shortName: what the layer draws next to the point
            name,       // fullName: the context menu title
            typeName,   // the context menu subtitle
            LAYER_ID,
            color,
            ALatLon(lat, lon),
            details,
            p,
        )
    }

    companion object {
        const val SERVICE_ACTION = "net.osmand.aidl.OsmandAidlServiceV2"

        /** OsmAnd flavours, in order of preference. */
        val PACKAGES = listOf("net.osmand.plus", "net.osmand", "net.osmand.dev")

        const val LAYER_ID = "tracker"
        const val LAYER_NAME = "Tracker"
        const val LAYER_Z_ORDER = 5.5f
        const val BIND_TIMEOUT_MS = 8_000L
    }
}
