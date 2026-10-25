package com.arkhome.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.arkhome.app.data.HaEntity

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ClimateList(climates: List<HaEntity>, onOpen: (HaEntity) -> Unit) {
    if (climates.isEmpty()) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(
                "No climate entities on this server.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        return
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        items(climates, key = { it.entityId }) { entity ->
            Card(
                modifier = Modifier.fillMaxWidth(),
                onClick = { onOpen(entity) },
            ) {
                Column(Modifier.padding(16.dp)) {
                    Text(entity.friendlyName, style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(4.dp))
                    Text(
                        climateSummary(entity),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ClimateSheet(
    entity: HaEntity,
    onDismiss: () -> Unit,
    onHvacMode: (String) -> Unit,
    onTemperature: (Double) -> Unit,
    onFanMode: (String) -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    val minTemp = entity.attributes.optDouble("min_temp", 7.0).orDefault(7.0)
    val maxTemp = entity.attributes.optDouble("max_temp", 35.0).orDefault(35.0)
    val step = entity.attributes.optDouble("target_temp_step", 0.5).orDefault(0.5)
    val serverTarget = entity.attributes.optDouble("temperature").takeIf { !it.isNaN() }
    val current = entity.attributes.optDouble("current_temperature").takeIf { !it.isNaN() }

    // Local echo so the stepper feels immediate; it re-syncs whenever the
    // server reports a different target.
    var target by remember(entity.entityId) { mutableStateOf(serverTarget) }
    LaunchedEffect(serverTarget) {
        if (serverTarget != null) target = serverTarget
    }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(start = 20.dp, end = 20.dp, bottom = 28.dp),
        ) {
            Text(entity.friendlyName, style = MaterialTheme.typography.headlineSmall)
            Spacer(Modifier.height(4.dp))
            Text(
                buildString {
                    append("Mode: ${entity.state}")
                    if (current != null) append("   Now: ${formatTemp(current)}°")
                },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            if (target != null) {
                Spacer(Modifier.height(24.dp))
                Text("Target", style = MaterialTheme.typography.labelLarge)
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    FilledIconButton(
                        onClick = {
                            val next = ((target ?: minTemp) - step).coerceIn(minTemp, maxTemp)
                            target = next
                            onTemperature(next)
                        },
                        modifier = Modifier.size(48.dp),
                    ) {
                        Icon(Icons.Filled.Remove, contentDescription = "Lower target")
                    }
                    Text(
                        text = "${formatTemp(target ?: 0.0)}°",
                        style = MaterialTheme.typography.displaySmall,
                        fontWeight = FontWeight.Medium,
                        modifier = Modifier
                            .weight(1f)
                            .padding(horizontal = 16.dp),
                    )
                    FilledIconButton(
                        onClick = {
                            val next = ((target ?: minTemp) + step).coerceIn(minTemp, maxTemp)
                            target = next
                            onTemperature(next)
                        },
                        modifier = Modifier.size(48.dp),
                    ) {
                        Icon(Icons.Filled.Add, contentDescription = "Raise target")
                    }
                }
            }

            val modes = entity.attributeStrings("hvac_modes")
            if (modes.isNotEmpty()) {
                Spacer(Modifier.height(24.dp))
                Text("Mode", style = MaterialTheme.typography.labelLarge)
                Spacer(Modifier.height(8.dp))
                ChipRow(
                    options = modes,
                    selected = entity.state,
                    onSelect = onHvacMode,
                )
            }

            val fanModes = entity.attributeStrings("fan_modes")
            if (fanModes.isNotEmpty()) {
                Spacer(Modifier.height(20.dp))
                Text("Fan", style = MaterialTheme.typography.labelLarge)
                Spacer(Modifier.height(8.dp))
                ChipRow(
                    options = fanModes,
                    selected = entity.attributes.optString("fan_mode"),
                    onSelect = onFanMode,
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun ChipRow(
    options: List<String>,
    selected: String?,
    onSelect: (String) -> Unit,
) {
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        options.forEach { option ->
            FilterChip(
                selected = option == selected,
                onClick = { onSelect(option) },
                label = { Text(option.replace('_', ' ')) },
            )
        }
    }
}

private fun Double.orDefault(fallback: Double): Double = if (isNaN()) fallback else this
