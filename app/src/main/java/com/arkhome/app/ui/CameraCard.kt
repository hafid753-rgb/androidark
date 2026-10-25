package com.arkhome.app.ui

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import com.arkhome.app.data.HaEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

private const val SNAPSHOT_INTERVAL_MS = 5_000L

@Composable
fun CameraList(cameras: List<HaEntity>, vm: ArkViewModel) {
    if (cameras.isEmpty()) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(
                "No camera entities on this server.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        return
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        items(cameras, key = { it.entityId }) { camera ->
            CameraCard(entity = camera, vm = vm)
        }
    }
}

@Composable
private fun CameraCard(entity: HaEntity, vm: ArkViewModel) {
    // Keep the last good frame on screen while the next one loads, so the card
    // does not flicker between refreshes - same behaviour as the iOS card.
    var frame by remember(entity.entityId) { mutableStateOf<ImageBitmap?>(null) }
    var failed by remember(entity.entityId) { mutableStateOf(false) }

    LaunchedEffect(entity.entityId) {
        while (true) {
            val bytes = vm.client()?.cameraSnapshot(entity.entityId)
            if (bytes != null && bytes.isNotEmpty()) {
                val decoded = withContext(Dispatchers.Default) {
                    runCatching { BitmapFactory.decodeByteArray(bytes, 0, bytes.size) }.getOrNull()
                }
                if (decoded != null) {
                    frame = decoded.asImageBitmap()
                    failed = false
                } else {
                    failed = frame == null
                }
            } else {
                failed = frame == null
            }
            delay(SNAPSHOT_INTERVAL_MS)
        }
    }

    Card(Modifier.fillMaxWidth()) {
        Column {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(16f / 9f),
                contentAlignment = Alignment.Center,
            ) {
                val current = frame
                when {
                    current != null -> Image(
                        bitmap = current,
                        contentDescription = entity.friendlyName,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize(),
                    )

                    failed -> Text(
                        "No frame available",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )

                    else -> CircularProgressIndicator()
                }
            }
            Text(
                text = entity.friendlyName,
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.padding(12.dp),
            )
        }
    }
}
