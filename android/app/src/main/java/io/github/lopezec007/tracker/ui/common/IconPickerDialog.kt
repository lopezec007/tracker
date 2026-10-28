// The icon and colour picker shared by Settings ("My icon") and the entity
// list ("Edit icon…"): the curated grid of OsmAnd icons, a free-text field
// for any other OsmAnd drawable name, and the colour palette.
package io.github.lopezec007.tracker.ui.common

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.lopezec007.tracker.osmand.EntityAppearance
import io.github.lopezec007.tracker.ui.theme.LocalPalette

/** The colour an entity's glyph is drawn in: its own, else [fallback]. */
fun entityTint(hex: String, fallback: Color): Color =
    EntityAppearance.parseColor(hex)?.let { Color(it) } ?: fallback

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun IconPickerDialog(
    title: String,
    initialIcon: String,
    initialColor: String,
    onDismiss: () -> Unit,
    onConfirm: (icon: String, color: String) -> Unit,
) {
    var icon by remember { mutableStateOf(initialIcon) }
    var color by remember { mutableStateOf(initialColor) }
    val curated = EntityAppearance.ICONS.any { it.osmandName == icon }
    val iconOk = EntityAppearance.isValidIcon(icon)
    val palette = LocalPalette.current
    val tint = entityTint(color, MaterialTheme.colorScheme.primary)

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Icon", style = MaterialTheme.typography.labelLarge)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    for (c in EntityAppearance.ICONS) {
                        val selected = c.osmandName == icon
                        Surface(
                            onClick = { icon = c.osmandName },
                            shape = MaterialTheme.shapes.small,
                            color = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant,
                            border = if (selected) BorderStroke(2.dp, MaterialTheme.colorScheme.primary) else null,
                            modifier = Modifier.width(60.dp),
                        ) {
                            Column(Modifier.padding(vertical = 6.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                                Icon(IconGlyphs.glyph(c.osmandName), contentDescription = c.label, tint = tint, modifier = Modifier.size(24.dp))
                                Text(c.label, style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center)
                            }
                        }
                    }
                }
                OutlinedTextField(
                    value = if (curated) "" else icon,
                    onValueChange = { icon = it.trim().lowercase() },
                    label = { Text("Other OsmAnd icon name") },
                    singleLine = true,
                    isError = !iconOk,
                    supportingText = {
                        Text(if (iconOk) "Any OsmAnd drawable name (a–z, 0–9, _), e.g. ic_action_horse; shown in OsmAnd's context menu." else "Letters a–z, digits and _ only, up to 64.")
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
                Text("Colour", style = MaterialTheme.typography.labelLarge)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    for (c in EntityAppearance.COLORS) {
                        val selected = c.hex == color
                        val fill = EntityAppearance.parseColor(c.hex)?.let { Color(it) }
                        Box(
                            modifier = Modifier
                                .size(36.dp)
                                .border(if (selected) 3.dp else 1.dp, if (selected) MaterialTheme.colorScheme.onSurface else palette.rowBorder, CircleShape)
                                .padding(3.dp)
                                .background(fill ?: MaterialTheme.colorScheme.surfaceVariant, CircleShape)
                                .clickable { color = c.hex },
                            contentAlignment = Alignment.Center,
                        ) {
                            if (fill == null) {
                                Text("—", style = MaterialTheme.typography.labelSmall, color = palette.subtle)
                            } else if (selected) {
                                Icon(Icons.Filled.Check, contentDescription = c.label, tint = Color.White, modifier = Modifier.size(18.dp))
                            }
                        }
                    }
                }
                Text(
                    "Default colour: blue for you, green for others, grey when stale. A chosen colour is kept even when stale.",
                    style = MaterialTheme.typography.bodySmall, color = palette.subtle,
                )
                Spacer()
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(icon, color) }, enabled = iconOk && EntityAppearance.isValidColor(color)) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun Spacer() {
    Box(Modifier.height(4.dp))
}
