// Settings: the server and its pinned identity, the name, this phone's icon
// and colour, the collection intervals and distance, the entity poll
// interval, the OsmAnd routing profile, and About.
package io.github.lopezec007.tracker.ui.settings

import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.lopezec007.tracker.BuildConfig
import io.github.lopezec007.tracker.data.sync.ServerIdentity
import io.github.lopezec007.tracker.osmand.EntityAppearance
import io.github.lopezec007.tracker.prefs.AppPrefs
import io.github.lopezec007.tracker.ui.TrackerViewModel
import io.github.lopezec007.tracker.ui.common.IconGlyphs
import io.github.lopezec007.tracker.ui.common.IconPickerDialog
import io.github.lopezec007.tracker.ui.common.entityTint
import io.github.lopezec007.tracker.ui.nav.Nav
import io.github.lopezec007.tracker.ui.nav.Screen
import io.github.lopezec007.tracker.ui.theme.LocalPalette

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(vm: TrackerViewModel, nav: Nav) {
    val prefs by vm.prefs.collectAsStateWithLifecycle()
    val p = prefs ?: return
    val sync by vm.sync.collectAsStateWithLifecycle()
    val poll by vm.poll.collectAsStateWithLifecycle()
    val busy by vm.busy.collectAsStateWithLifecycle()
    val me = poll.entities.firstOrNull { it.id == p.entityId }
    var pickIcon by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
                navigationIcon = {
                    IconButton(onClick = { nav.pop() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { inner ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(inner)
                .verticalScroll(rememberScrollState()),
        ) {
            SectionTitle("Server")
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(p.serverUrl.ifBlank { "not set" }, style = MaterialTheme.typography.bodyLarge)
                    Text(
                        sync.serverVersion?.let { "Tracker $it" } ?: "version unknown (not reached yet)",
                        style = MaterialTheme.typography.bodySmall,
                        color = LocalPalette.current.subtle,
                    )
                }
                OutlinedButton(onClick = { nav.push(Screen.Setup) }) { Text("Change…") }
            }
            Hint("Changing the address drops any fixes still queued for the old server.")
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text("Server identity", style = MaterialTheme.typography.bodyLarge)
                    Text(
                        if (p.pinnedServerId.isBlank()) "not pinned yet — the first server that answers at the address is remembered"
                        else p.pinnedServerId,
                        style = MaterialTheme.typography.bodySmall,
                        color = LocalPalette.current.subtle,
                    )
                }
                OutlinedButton(onClick = { vm.forgetServerIdentity() }, enabled = p.pinnedServerId.isNotBlank()) { Text("Forget") }
            }
            Hint(
                "Only the server with this identity is ever sent anything; a different machine answering at the same address " +
                    "(another network, or a rebuilt server database) is ignored and the fixes wait. Forget it only when you rebuilt " +
                    "the server on purpose: the next one that answers is pinned.",
            )

            HorizontalDivider()
            SectionTitle("Name")
            TextSetting(
                label = "Display name",
                value = p.name,
                hint = "The label of this phone on everyone's map. Renaming keeps the phone's identity (${p.entityId.ifBlank { "…" }}).",
                valid = { AppPrefs.cleanName(it).isNotBlank() },
                onCommit = { vm.setName(it) },
            )

            HorizontalDivider()
            SectionTitle("My icon")
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    IconGlyphs.glyph(me?.icon.orEmpty()),
                    contentDescription = null,
                    tint = entityTint(me?.color.orEmpty(), MaterialTheme.colorScheme.primary),
                    modifier = Modifier.size(32.dp),
                )
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        when {
                            me == null -> "unknown until the server has been reached"
                            me.icon.isEmpty() && me.color.isEmpty() -> "default icon and colour"
                            else -> listOfNotNull(
                                me.icon.takeIf { it.isNotEmpty() }?.let { EntityAppearance.iconLabel(it) },
                                me.color.takeIf { it.isNotEmpty() }?.let { c -> EntityAppearance.COLORS.firstOrNull { it.hex.equals(c, ignoreCase = true) }?.label ?: c },
                            ).joinToString(", ")
                        },
                        style = MaterialTheme.typography.bodyLarge,
                    )
                    Text("Shown on everyone's map and in OsmAnd's context menu; anyone can change anyone's icon from the entity list.", style = MaterialTheme.typography.bodySmall, color = LocalPalette.current.subtle)
                }
                OutlinedButton(onClick = { pickIcon = true }, enabled = me != null && !busy) { Text("Change…") }
            }

            HorizontalDivider()
            SectionTitle("Location")
            NumberSetting(
                label = "Update interval (seconds)",
                value = p.updateIntervalSec,
                hint = "A fix is recorded when this much time has passed and the phone has moved the minimum distance. ${minutes(p.updateIntervalSec)}",
                min = AppPrefs.MIN_UPDATE_INTERVAL_SEC, max = AppPrefs.MAX_SECONDS,
                onCommit = { vm.setUpdateIntervalSec(it) },
            )
            NumberSetting(
                label = "Minimum distance (metres)",
                value = p.minDistanceM,
                hint = "How far the phone must have moved since the last fix.",
                min = 0, max = 100_000,
                onCommit = { vm.setMinDistanceM(it) },
            )
            NumberSetting(
                label = "Heartbeat (seconds)",
                value = p.heartbeatSec,
                hint = "A fix is recorded this often even when the phone has not moved, so it still shows as alive. ${minutes(p.heartbeatSec)}",
                min = AppPrefs.MIN_HEARTBEAT_SEC, max = AppPrefs.MAX_SECONDS,
                onCommit = { vm.setHeartbeatSec(it) },
            )
            SwitchSetting(
                label = "GPS only",
                hint = "Off: the platform's fused provider when the phone has one (it may add Wi-Fi and cell positions), else GPS. On: GPS always.",
                checked = p.gpsOnly,
                onChange = { vm.setGpsOnly(it) },
            )

            HorizontalDivider()
            SectionTitle("Entities")
            NumberSetting(
                label = "Poll interval (seconds)",
                value = p.pollSec,
                hint = "How often the latest fix of every entity is fetched and pushed into OsmAnd. ${minutes(p.pollSec)}",
                min = AppPrefs.MIN_POLL_SEC, max = AppPrefs.MAX_SECONDS,
                onCommit = { vm.setPollSec(it) },
            )

            HorizontalDivider()
            SectionTitle("OsmAnd")
            Text("Routing profile for \"Go to\"", style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp))
            Row(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                for (profile in AppPrefs.ROUTING_PROFILES) {
                    FilterChip(
                        selected = p.routingProfile == profile,
                        onClick = { vm.setRoutingProfile(profile) },
                        label = { Text(profile.replaceFirstChar { it.uppercase() }) },
                    )
                }
            }
            Hint("OsmAnd's own profile of that name is used; the route starts from this phone's position.")

            HorizontalDivider()
            AboutSection()
            Spacer(Modifier.height(24.dp))
        }
    }

    if (pickIcon && me != null) {
        IconPickerDialog(
            title = "My icon",
            initialIcon = me.icon,
            initialColor = me.color,
            onDismiss = { pickIcon = false },
            onConfirm = { icon, color ->
                pickIcon = false
                vm.setAppearance(me.id, icon, color)
            },
        )
    }
}

private fun minutes(sec: Int): String = when {
    sec % 3600 == 0 -> "= ${sec / 3600} h"
    sec >= 60 -> "= ${sec / 60} min" + if (sec % 60 != 0) " ${sec % 60} s" else ""
    else -> ""
}

// ---------------------------------------------------------------- shared bits

@Composable
private fun SectionTitle(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleMedium,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 16.dp, top = 16.dp, end = 16.dp, bottom = 4.dp),
    )
}

@Composable
private fun Hint(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        color = LocalPalette.current.subtle,
        modifier = modifier.padding(horizontal = 16.dp, vertical = 4.dp),
    )
}

/** A text field committed on Done or when focus leaves it; invalid input is kept in the field and flagged. */
@Composable
private fun TextSetting(label: String, value: String, hint: String, valid: (String) -> Boolean, onCommit: (String) -> Unit) {
    var text by remember(value) { mutableStateOf(value) }
    val focus = LocalFocusManager.current
    val ok = valid(text)
    fun commit() {
        if (ok && text != value) onCommit(text)
    }
    OutlinedTextField(
        value = text,
        onValueChange = { text = it },
        label = { Text(label) },
        singleLine = true,
        isError = !ok,
        supportingText = { Text(hint) },
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
        keyboardActions = KeyboardActions(onDone = { commit(); focus.clearFocus() }),
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp)
            .onFocusChanged { if (!it.isFocused) commit() },
    )
}

@Composable
private fun NumberSetting(label: String, value: Int, hint: String, min: Int, max: Int, onCommit: (Int) -> Unit) {
    TextSetting(
        label = label,
        value = value.toString(),
        hint = hint,
        valid = { it.toIntOrNull()?.let { n -> n in min..max } == true },
        onCommit = { it.toIntOrNull()?.let(onCommit) },
    )
}

@Composable
private fun SwitchSetting(label: String, hint: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.bodyLarge)
            Text(hint, style = MaterialTheme.typography.bodySmall, color = LocalPalette.current.subtle)
        }
        Spacer(Modifier.width(12.dp))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
private fun AboutSection() {
    SectionTitle("About")
    Column(Modifier.padding(horizontal = 16.dp, vertical = 6.dp)) {
        Text("Tracker for Android", style = MaterialTheme.typography.bodyLarge)
        Text("Version ${BuildConfig.VERSION_NAME}", style = MaterialTheme.typography.bodySmall, color = LocalPalette.current.subtle)
        Spacer(Modifier.height(4.dp))
        Text(
            "This app only connects to the server address above, and to OsmAnd on this phone.",
            style = MaterialTheme.typography.bodySmall,
            color = LocalPalette.current.subtle,
        )
    }
}
