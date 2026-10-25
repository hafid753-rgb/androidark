package com.arkhome.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AcUnit
import androidx.compose.material.icons.filled.Blinds
import androidx.compose.material.icons.filled.DeviceThermostat
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Lightbulb
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Logout
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.material.icons.filled.Power
import androidx.compose.material.icons.filled.Sensors
import androidx.compose.material.icons.filled.Thermostat
import androidx.compose.material.icons.filled.Toys
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material.icons.filled.WaterDrop
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.arkhome.app.data.ConnStatus
import com.arkhome.app.data.HaEntity

private enum class Tab(val label: String, val icon: ImageVector) {
    Home("Home", Icons.Filled.Home),
    Cameras("Cameras", Icons.Filled.Videocam),
    Climate("Climate", Icons.Filled.Thermostat),
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(vm: ArkViewModel) {
    val status by vm.status.collectAsState()
    val rooms by vm.rooms.collectAsState()
    val cameras by vm.cameras.collectAsState()
    val climates by vm.climates.collectAsState()
    val error by vm.error.collectAsState()

    var tab by remember { mutableStateOf(Tab.Home) }
    var climateSheetFor by remember { mutableStateOf<String?>(null) }
    val snackbarHost = remember { SnackbarHostState() }

    LaunchedEffect(error) {
        val message = error ?: return@LaunchedEffect
        snackbarHost.showSnackbar(message)
        vm.clearError()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(tab.label) },
                actions = {
                    ConnectionChip(status)
                    IconButton(onClick = { vm.signOut() }) {
                        Icon(Icons.Filled.Logout, contentDescription = "Sign out")
                    }
                },
            )
        },
        bottomBar = {
            NavigationBar {
                Tab.entries.forEach { entry ->
                    NavigationBarItem(
                        selected = tab == entry,
                        onClick = { tab = entry },
                        icon = { Icon(entry.icon, contentDescription = entry.label) },
                        label = { Text(entry.label) },
                    )
                }
            }
        },
        snackbarHost = {
            SnackbarHost(snackbarHost) { data -> Snackbar(snackbarData = data) }
        },
    ) { padding ->
        Box(Modifier.padding(padding).fillMaxSize()) {
            when (tab) {
                Tab.Home -> RoomList(
                    rooms = rooms,
                    status = status,
                    onToggle = vm::toggle,
                    onOpenClimate = { climateSheetFor = it.entityId },
                    onRetry = vm::retry,
                )

                Tab.Cameras -> CameraList(cameras = cameras, vm = vm)

                Tab.Climate -> ClimateList(
                    climates = climates,
                    onOpen = { climateSheetFor = it.entityId },
                )
            }
        }
    }

    val sheetEntity = climateSheetFor?.let { id ->
        climates.firstOrNull { it.entityId == id }
    }
    if (sheetEntity != null) {
        ClimateSheet(
            entity = sheetEntity,
            onDismiss = { climateSheetFor = null },
            onHvacMode = { vm.setHvacMode(sheetEntity, it) },
            onTemperature = { vm.setTemperature(sheetEntity, it) },
            onFanMode = { vm.setFanMode(sheetEntity, it) },
        )
    }
}

@Composable
private fun RoomList(
    rooms: List<RoomSection>,
    status: ConnStatus,
    onToggle: (HaEntity) -> Unit,
    onOpenClimate: (HaEntity) -> Unit,
    onRetry: () -> Unit,
) {
    if (rooms.isEmpty()) {
        ConnectionPlaceholder(status, onRetry)
        return
    }

    LazyColumn(Modifier.fillMaxSize()) {
        rooms.forEach { room ->
            item(key = "header-${room.name}") {
                Text(
                    text = room.name,
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 20.dp, bottom = 6.dp),
                )
            }
            items(room.entities, key = { it.entityId }) { entity ->
                EntityRow(
                    entity = entity,
                    onToggle = { onToggle(entity) },
                    onOpenClimate = { onOpenClimate(entity) },
                )
                HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant)
            }
        }
    }
}

@Composable
private fun EntityRow(
    entity: HaEntity,
    onToggle: () -> Unit,
    onOpenClimate: () -> Unit,
) {
    val toggleable = entity.domain in HaEntity.TOGGLE_DOMAINS && entity.isAvailable
    val climate = entity.domain in HaEntity.CLIMATE_DOMAINS

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .then(
                when {
                    climate -> Modifier.clickable(onClick = onOpenClimate)
                    toggleable -> Modifier.clickable(onClick = onToggle)
                    else -> Modifier
                }
            )
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = iconFor(entity),
            contentDescription = null,
            tint = if (entity.isOn) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
            modifier = Modifier.size(22.dp),
        )
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(entity.friendlyName, style = MaterialTheme.typography.bodyLarge)
            if (!entity.isAvailable) {
                Text(
                    "Unavailable",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
        Spacer(Modifier.width(12.dp))

        when {
            toggleable -> Switch(checked = entity.isOn, onCheckedChange = { onToggle() })
            climate -> Text(
                text = climateSummary(entity),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            else -> Text(
                text = entity.displayState,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

internal fun climateSummary(entity: HaEntity): String {
    val target = entity.attributes.optDouble("temperature")
    val current = entity.attributes.optDouble("current_temperature")
    val parts = mutableListOf(entity.state)
    if (!target.isNaN()) parts += "→ ${formatTemp(target)}°"
    if (!current.isNaN()) parts += "(${formatTemp(current)}°)"
    return parts.joinToString(" ")
}

internal fun formatTemp(value: Double): String =
    if (value == value.toLong().toDouble()) value.toLong().toString() else String.format("%.1f", value)

private fun iconFor(entity: HaEntity): ImageVector = when (entity.domain) {
    "light" -> Icons.Filled.Lightbulb
    "switch", "input_boolean" -> Icons.Filled.Power
    "fan" -> Icons.Filled.Toys
    "climate" -> Icons.Filled.AcUnit
    "cover" -> Icons.Filled.Blinds
    "lock" -> Icons.Filled.Lock
    "media_player" -> Icons.Filled.PlayCircle
    "humidifier" -> Icons.Filled.WaterDrop
    "camera" -> Icons.Filled.Videocam
    "sensor" -> if (entity.deviceClass == "temperature") {
        Icons.Filled.DeviceThermostat
    } else {
        Icons.Filled.Sensors
    }
    else -> Icons.Filled.Sensors
}

@Composable
private fun ConnectionChip(status: ConnStatus) {
    val (color, label) = when (status) {
        ConnStatus.Connected -> MaterialTheme.colorScheme.primary to "Live"
        ConnStatus.Connecting -> MaterialTheme.colorScheme.secondary to "Connecting"
        ConnStatus.AuthFailed -> MaterialTheme.colorScheme.error to "Token"
        ConnStatus.Disconnected -> MaterialTheme.colorScheme.error to "Offline"
    }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.padding(end = 4.dp),
    ) {
        Box(
            Modifier
                .size(8.dp)
                .clip(CircleShape)
                .background(color)
        )
        Spacer(Modifier.width(6.dp))
        Text(label, style = MaterialTheme.typography.labelMedium)
    }
}

@Composable
private fun ConnectionPlaceholder(status: ConnStatus, onRetry: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        when (status) {
            ConnStatus.Connecting -> {
                CircularProgressIndicator()
                Spacer(Modifier.height(16.dp))
                Text("Connecting to ARK HOME…")
            }

            ConnStatus.AuthFailed -> {
                Text("The access token was rejected.", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(8.dp))
                Text(
                    "Create a new long-lived token in Home Assistant and sign in again.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            else -> {
                Text("Not connected.", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(8.dp))
                TextButton(onClick = onRetry) { Text("Retry now") }
            }
        }
    }
}
