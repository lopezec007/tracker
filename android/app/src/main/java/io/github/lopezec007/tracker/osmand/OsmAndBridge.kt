// OsmAnd over its AIDL API (README "OsmAnd integration"): binds to the
// OsmandAidlServiceV2 of whichever OsmAnd is installed and keeps one map
// layer, "Tracker", with a point per entity. Binding is lazy and redone
// after a disconnect; a missing OsmAnd only shows in `state`.
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
import net.osmand.aidlapi.map.ALatLon
import net.osmand.aidlapi.maplayer.AMapLayer
import net.osmand.aidlapi.maplayer.AddMapLayerParams
import net.osmand.aidlapi.maplayer.point.AMapPoint
import net.osmand.aidlapi.maplayer.point.RemoveMapPointParams

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
                val aidlPoints = points.map { it.toAidl() }
                // addMapLayer is an upsert in OsmAnd: a new layer is created, an
                // existing one takes the points (merged by id) and the map reloads.
                val layer = AMapLayer(LAYER_ID, LAYER_NAME, LAYER_Z_ORDER, aidlPoints)
                layer.setImagePoints(false)
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

    private fun TrackerPoint.toAidl(): AMapPoint = AMapPoint(
        id,
        name,       // shortName: what the layer draws next to the point
        name,       // fullName: the context menu title
        typeName,   // the context menu subtitle
        LAYER_ID,
        color,
        ALatLon(lat, lon),
        details,
        params,
    )

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
