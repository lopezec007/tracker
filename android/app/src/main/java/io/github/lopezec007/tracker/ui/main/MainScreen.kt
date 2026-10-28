// The status screen: permissions still missing, the service (tracking /
// paused, last fix, queue, sync), a warning when a different server answers
// at the server's address, OsmAnd, and every entity with the age of its
// last fix and its actions (show on map, go to, history, edit icon).
package io.github.lopezec007.tracker.ui.main

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.lopezec007.tracker.data.api.EntityDto
import io.github.lopezec007.tracker.data.sync.HistoryRange
import io.github.lopezec007.tracker.data.sync.ServerIdentity
import io.github.lopezec007.tracker.data.sync.SyncPhase
import io.github.lopezec007.tracker.location.Permissions
import io.github.lopezec007.tracker.osmand.OsmAndStatus
import io.github.lopezec007.tracker.osmand.PointFormat
import io.github.lopezec007.tracker.ui.TrackerViewModel
import io.github.lopezec007.tracker.ui.common.IconGlyphs
import io.github.lopezec007.tracker.ui.common.IconPickerDialog
import io.github.lopezec007.tracker.ui.common.entityTint
import io.github.lopezec007.tracker.ui.nav.Nav
import io.github.lopezec007.tracker.ui.nav.Screen
import io.github.lopezec007.tracker.ui.theme.LocalPalette
import io.github.lopezec007.tracker.util.Ages
import kotlinx.coroutines.delay

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(vm: TrackerViewModel, nav: Nav) {
    val prefs by vm.prefs.collectAsStateWithLifecycle()
    val p = prefs ?: return
    val service by vm.service.collectAsStateWithLifecycle()
    val sync by vm.sync.collectAsStateWithLifecycle()
    val poll by vm.poll.collectAsStateWithLifecycle()
    val osmand by vm.osmand.collectAsStateWithLifecycle()
    val pending by vm.pendingCount.collectAsStateWithLifecycle()
    val message by vm.message.collectAsStateWithLifecycle()
    val busy by vm.busy.collectAsStateWithLifecycle()
    val historyShown by vm.historyShown.collectAsStateWithLifecycle()
    val context = LocalContext.current

    // The outcome of an action (go to, history, icon) as a snackbar.
    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(message) {
        val m = message ?: return@LaunchedEffect
        snackbar.showSnackbar(m)
        vm.clearMessage()
    }

    // The entity whose icon or history sheet is open.
    var iconFor by remember { mutableStateOf<EntityDto?>(null) }
    var historyFor by remember { mutableStateOf<EntityDto?>(null) }

    // "x min ago" texts are recomputed every half minute.
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(30_000)
            now = System.currentTimeMillis()
        }
    }

    // Permissions are re-read whenever the screen comes back (the system
    // pages for background location and notifications return through resume).
    var permissionsVersion by remember { mutableIntStateOf(0) }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                permissionsVersion++
                now = System.currentTimeMillis()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    val hasLocation = remember(permissionsVersion) { Permissions.hasLocation(context) }
    val hasBackground = remember(permissionsVersion) { Permissions.hasBackgroundLocation(context) }
    val hasNotifications = remember(permissionsVersion) { Permissions.hasNotifications(context) }
    val osmandInstalled = remember(permissionsVersion) { vm.osmandInstalled() }

    val askLocation = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        permissionsVersion++
        vm.startServiceIfAllowed(context)
    }
    val askBackground = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { permissionsVersion++ }
    val askNotifications = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { permissionsVersion++ }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = { Text("Tracker") },
                actions = {
                    IconButton(onClick = { nav.push(Screen.Settings) }) {
                        Icon(Icons.Filled.Settings, contentDescription = "Settings")
                    }
                },
            )
        },
    ) { inner ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(inner)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (!hasLocation || !hasBackground || !hasNotifications) {
                PermissionsCard(
                    hasLocation = hasLocation,
                    hasBackground = hasBackground,
                    hasNotifications = hasNotifications,
                    onAskLocation = { askLocation.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)) },
                    onAskBackground = { askBackground.launch(Manifest.permission.ACCESS_BACKGROUND_LOCATION) },
                    onAskNotifications = { askNotifications.launch(Manifest.permission.POST_NOTIFICATIONS) },
                )
            }

            StatusCard(
                running = service.running,
                paused = p.paused,
                error = service.error,
                provider = service.provider,
                lastFixAt = service.lastFixAt ?: p.lastFixAt,
                lastFixReason = service.lastFixReason?.wire,
                lastAccuracy = service.lastAccuracyM,
                pending = pending,
                syncPhase = sync.phase,
                lastSync = sync.lastSuccess ?: p.lastSyncAt,
                lastSent = sync.lastSentDeviceTime,
                syncError = sync.lastError,
                dropped = sync.dropped,
                now = now,
                onPause = { vm.setPaused(it) },
                onSyncNow = { vm.requestSync() },
            )

            if (sync.phase == SyncPhase.WRONG_SERVER || poll.wrongServer) {
                WrongServerCard(sync.lastError ?: poll.lastError ?: "a different server answered", pinned = p.pinnedServerId)
            }

            OsmAndCard(
                status = osmand.status,
                packageName = osmand.packageName,
                installed = osmandInstalled,
                lastPush = osmand.lastPushAt,
                error = osmand.lastError,
                points = osmand.pointCount,
                now = now,
                onOpen = { vm.openOsmAnd(context) },
                onPush = { vm.refreshEntities() },
            )

            EntitiesSection(
                entities = poll.entities,
                myId = p.entityId,
                lastPoll = poll.lastSuccess,
                error = poll.lastError,
                polling = poll.polling,
                now = now,
                historyShown = historyShown,
                onRefresh = { vm.refreshEntities() },
                onShowOnMap = { vm.showOnMap(it, context) },
                onGoTo = { vm.goTo(it, context) },
                onHistory = { historyFor = it },
                onEditIcon = { iconFor = it },
            )
            Spacer(Modifier.height(16.dp))
        }
    }

    iconFor?.let { e ->
        IconPickerDialog(
            title = "Icon of ${e.name.ifBlank { e.id }}",
            initialIcon = e.icon,
            initialColor = e.color,
            onDismiss = { iconFor = null },
            onConfirm = { icon, color ->
                iconFor = null
                vm.setAppearance(e.id, icon, color)
            },
        )
    }
    historyFor?.let { e ->
        HistorySheet(
            entity = e,
            shown = e.id in historyShown,
            busy = busy,
            osmandInstalled = osmandInstalled,
            onShow = { range -> vm.showHistory(e, range, context); historyFor = null },
            onHide = { vm.hideHistory(e); historyFor = null },
            onRemove = { vm.removeHistory(e); historyFor = null },
            onDismiss = { historyFor = null },
        )
    }
}

// ---------------------------------------------------------------- pieces

@Composable
private fun Label(text: String) {
    Text(text, style = MaterialTheme.typography.labelLarge, modifier = Modifier.width(96.dp))
}

@Composable
private fun InfoRow(label: String, value: String, color: Color = Color.Unspecified) {
    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
        Label(label)
        Text(value, style = MaterialTheme.typography.bodyMedium, color = color)
    }
}

@Composable
private fun CardTitle(text: String) {
    Text(text, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
}

@Composable
private fun PermissionsCard(
    hasLocation: Boolean, hasBackground: Boolean, hasNotifications: Boolean,
    onAskLocation: () -> Unit, onAskBackground: () -> Unit, onAskNotifications: () -> Unit,
) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            CardTitle("Permissions")
            if (!hasLocation) {
                Text("Location is needed to record where this phone is. Nothing is tracked until it is granted.", style = MaterialTheme.typography.bodyMedium)
                Button(onClick = onAskLocation) { Text("Allow location") }
            } else if (!hasBackground) {
                Text(
                    "Choose \"Allow all the time\" so tracking continues after a reboot and while the app is closed. " +
                        "Android only lets the service started at boot read the location with that setting.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Button(onClick = onAskBackground) { Text("Allow all the time") }
            }
            if (hasLocation && !hasNotifications) {
                Text("Notifications show the tracking state in the shade (tracking, paused, queued fixes). The service runs without them, silently.", style = MaterialTheme.typography.bodyMedium)
                OutlinedButton(onClick = onAskNotifications) { Text("Allow notifications") }
            }
        }
    }
}

@Composable
private fun StatusCard(
    running: Boolean, paused: Boolean, error: String?, provider: String?,
    lastFixAt: Long?, lastFixReason: String?, lastAccuracy: Double?,
    pending: Int, syncPhase: SyncPhase, lastSync: Long?, lastSent: Long?, syncError: String?, dropped: Int,
    now: Long, onPause: (Boolean) -> Unit, onSyncNow: () -> Unit,
) {
    val palette = LocalPalette.current
    Card {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    CardTitle("Tracking")
                    val (text, color) = when {
                        error != null -> "Stopped: $error" to palette.danger
                        paused -> "Paused" to palette.warn
                        !running -> "Service not running" to palette.danger
                        else -> "Tracking" + (provider?.let { " ($it)" } ?: "") to palette.ok
                    }
                    Text(text, style = MaterialTheme.typography.bodyLarge, color = color, fontWeight = FontWeight.Medium)
                }
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Switch(checked = paused, onCheckedChange = onPause)
                    Text("Pause", style = MaterialTheme.typography.labelSmall, color = palette.subtle)
                }
            }
            HorizontalDivider(Modifier.padding(vertical = 6.dp))
            InfoRow(
                "Last fix",
                lastFixAt?.let { Ages.formatAgo(now - it) + (lastFixReason?.let { r -> " ($r)" } ?: "") + (lastAccuracy?.let { a -> ", ±${a.toInt()} m" } ?: "") } ?: "none yet",
            )
            InfoRow("Queued", if (pending == 0) "nothing waiting" else "$pending fix${if (pending == 1) "" else "es"}", if (pending > 0) palette.warn else Color.Unspecified)
            InfoRow(
                "Sync",
                when (syncPhase) {
                    SyncPhase.IDLE -> "idle"
                    SyncPhase.SYNCING -> "sending…"
                    SyncPhase.OFFLINE -> "offline — server not reachable"
                    SyncPhase.WRONG_SERVER -> "stopped — a different server answered"
                    SyncPhase.NOT_CONFIGURED -> "not configured"
                },
                when (syncPhase) {
                    SyncPhase.OFFLINE -> palette.warn
                    SyncPhase.WRONG_SERVER -> palette.danger
                    else -> Color.Unspecified
                },
            )
            InfoRow("Last sync", lastSync?.let { Ages.formatAgo(now - it) } ?: "never")
            InfoRow("Last sent", lastSent?.let { "fix of " + Ages.formatTime(it, now) } ?: "nothing yet this session")
            syncError?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
            if (dropped > 0) {
                Text(
                    "$dropped fix${if (dropped == 1) "" else "es"} refused by the server as invalid and dropped since the app started.",
                    style = MaterialTheme.typography.bodySmall, color = palette.warn,
                )
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = onSyncNow, enabled = syncPhase != SyncPhase.SYNCING) { Text("Sync now") }
            }
        }
    }
}

@Composable
private fun OsmAndCard(
    status: OsmAndStatus, packageName: String?, installed: Boolean, lastPush: Long?, error: String?, points: Int,
    now: Long, onOpen: () -> Unit, onPush: () -> Unit,
) {
    val palette = LocalPalette.current
    Card {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            CardTitle("OsmAnd")
            val (text, color) = when {
                !installed -> "Not installed — tracking continues; install OsmAnd to see the map" to palette.warn
                status == OsmAndStatus.CONNECTED -> "Connected" + (packageName?.let { " ($it)" } ?: "") to palette.ok
                status == OsmAndStatus.CONNECTING -> "Connecting…" to palette.subtle
                else -> "Not connected — reconnects on the next poll" to palette.subtle
            }
            Text(text, style = MaterialTheme.typography.bodyLarge, color = color)
            InfoRow("Last push", lastPush?.let { Ages.formatAgo(now - it) + " ($points point${if (points == 1) "" else "s"})" } ?: "nothing yet")
            error?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
            Text(
                "Points live on the \"Tracker\" layer of OsmAnd's map (Configure map → Connected apps).",
                style = MaterialTheme.typography.bodySmall, color = palette.subtle,
            )
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = onPush) { Text("Push now") }
                Spacer(Modifier.width(8.dp))
                Button(onClick = onOpen, enabled = installed) { Text("Open OsmAnd") }
            }
        }
    }
}

@Composable
private fun WrongServerCard(reason: String, pinned: String) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("Different server", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onErrorContainer, fontWeight = FontWeight.Medium)
            Text(reason.replaceFirstChar { it.uppercase() }, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onErrorContainer)
            Text(
                "Nothing is sent to it and the entities are not asked for; the queued fixes wait for the server with identity " +
                    "${ServerIdentity.shortId(pinned)}…. Back on the right network this clears itself. If you rebuilt the server's " +
                    "database on purpose, use Settings → Forget server identity.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onErrorContainer,
            )
        }
    }
}

@Composable
private fun EntitiesSection(
    entities: List<EntityDto>, myId: String, lastPoll: Long?, error: String?, polling: Boolean,
    now: Long, historyShown: Set<String>,
    onRefresh: () -> Unit,
    onShowOnMap: (EntityDto) -> Unit, onGoTo: (EntityDto) -> Unit, onHistory: (EntityDto) -> Unit, onEditIcon: (EntityDto) -> Unit,
) {
    val palette = LocalPalette.current
    Card {
        Column(Modifier.padding(16.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    CardTitle("Entities")
                    Text(
                        if (polling) "Refreshing…" else "Updated " + (lastPoll?.let { Ages.formatAgo(now - it) } ?: "never"),
                        style = MaterialTheme.typography.bodySmall, color = palette.subtle,
                    )
                }
                TextButton(onClick = onRefresh, enabled = !polling) { Text("Refresh") }
            }
            error?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
            if (entities.isEmpty()) {
                Text("No entities yet.", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(vertical = 8.dp))
            }
            for (e in entities) {
                HorizontalDivider(Modifier.padding(vertical = 6.dp), color = palette.rowBorder)
                EntityRow(
                    e, e.id == myId, now, historyShown = e.id in historyShown,
                    onShowOnMap = { onShowOnMap(e) }, onGoTo = { onGoTo(e) }, onHistory = { onHistory(e) }, onEditIcon = { onEditIcon(e) },
                )
            }
        }
    }
}

@Composable
private fun EntityRow(
    e: EntityDto, me: Boolean, now: Long, historyShown: Boolean,
    onShowOnMap: () -> Unit, onGoTo: () -> Unit, onHistory: () -> Unit, onEditIcon: () -> Unit,
) {
    val palette = LocalPalette.current
    val at = PointFormat.fixTime(e)
    val age = at?.let { now - it }
    val stale = age == null || age > PointFormat.STALE_AFTER_MS
    val hasFix = e.latest != null
    var menuOpen by remember { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        // The entity's icon in its colour, as OsmAnd's context menu shows it.
        Icon(
            IconGlyphs.glyph(e.icon),
            contentDescription = null,
            tint = entityTint(e.color, if (me) MaterialTheme.colorScheme.primary else if (stale) palette.subtle else palette.ok),
            modifier = Modifier.size(28.dp),
        )
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                e.name.ifBlank { e.id } + if (me) "  (you)" else "",
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = if (me) FontWeight.Medium else FontWeight.Normal,
            )
            val f = e.latest
            val detail = if (f == null) "no fix yet" else buildList {
                f.accuracy_m?.let { add("±${it.toInt()} m") }
                f.speed_mps?.let { add("${(it * 3.6).toInt()} km/h") }
                f.battery_pct?.let { add("$it%") }
                add(e.source.ifBlank { "unknown" })
                if (historyShown) add("history shown")
            }.joinToString(" · ")
            Text(detail, style = MaterialTheme.typography.bodySmall, color = palette.subtle)
        }
        Text(
            age?.let { Ages.formatAgo(it) } ?: "—",
            style = MaterialTheme.typography.bodyMedium,
            color = if (stale) palette.warn else palette.ok,
        )
        Box {
            IconButton(onClick = { menuOpen = true }) {
                Icon(Icons.Filled.MoreVert, contentDescription = "Actions for ${e.name.ifBlank { e.id }}")
            }
            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                DropdownMenuItem(text = { Text("Show on map") }, enabled = hasFix, onClick = { menuOpen = false; onShowOnMap() })
                DropdownMenuItem(text = { Text("Go to") }, enabled = hasFix, onClick = { menuOpen = false; onGoTo() })
                DropdownMenuItem(text = { Text("History…") }, onClick = { menuOpen = false; onHistory() })
                DropdownMenuItem(text = { Text("Edit icon…") }, onClick = { menuOpen = false; onEditIcon() })
            }
        }
    }
}
