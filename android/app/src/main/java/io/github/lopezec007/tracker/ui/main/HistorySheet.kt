// The "History" sheet of an entity: a range, "Show in OsmAnd" (fetch the
// fixes, import them as a GPX track), "Hide" and "Remove".
package io.github.lopezec007.tracker.ui.main

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.github.lopezec007.tracker.data.api.EntityDto
import io.github.lopezec007.tracker.data.sync.HistoryRange
import io.github.lopezec007.tracker.ui.theme.LocalPalette

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HistorySheet(
    entity: EntityDto,
    shown: Boolean,
    busy: Boolean,
    osmandInstalled: Boolean,
    onShow: (HistoryRange) -> Unit,
    onHide: () -> Unit,
    onRemove: () -> Unit,
    onDismiss: () -> Unit,
) {
    var range by remember { mutableStateOf(HistoryRange.DEFAULT) }
    val palette = LocalPalette.current
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.padding(horizontal = 24.dp, vertical = 8.dp)) {
            Text("History of ${entity.name.ifBlank { entity.id }}", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
            Text(
                if (shown) "A track is shown in OsmAnd; \"Show\" replaces it with the range below." else "Fetched from the server and drawn in OsmAnd as a track (Configure map → Tracks).",
                style = MaterialTheme.typography.bodySmall, color = palette.subtle,
            )
            Spacer(Modifier.height(8.dp))
            for (r in HistoryRange.entries) {
                Row(
                    Modifier.fillMaxWidth().selectable(selected = r == range, onClick = { range = r }).padding(vertical = 2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RadioButton(selected = r == range, onClick = { range = r })
                    Text(r.label.replaceFirstChar { it.uppercase() }, style = MaterialTheme.typography.bodyLarge)
                }
            }
            if (!osmandInstalled) {
                Text("OsmAnd is not installed on this phone.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            }
            Spacer(Modifier.height(8.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End), verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = onRemove, enabled = !busy && osmandInstalled) { Text("Remove") }
                OutlinedButton(onClick = onHide, enabled = !busy && osmandInstalled) { Text("Hide") }
                Button(onClick = { onShow(range) }, enabled = !busy && osmandInstalled) { Text(if (busy) "Fetching…" else "Show in OsmAnd") }
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}
