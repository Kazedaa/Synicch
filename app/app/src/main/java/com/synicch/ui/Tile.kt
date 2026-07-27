package com.synicch.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.PlayCircleFilled
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.synicch.data.MediaItem
import kotlin.math.roundToInt

/**
 * One grid tile.
 *
 * `model` is either a local content:// Uri or a server URL. The caller decides
 * which - the tile does not care, which is what keeps local-first display from
 * leaking into every screen.
 */
@Composable
fun Tile(
    item: MediaItem,
    model: Any,
    selected: Boolean,
    isLocal: Boolean,
    selecting: Boolean,
    onOpen: () -> Unit,
    onToggleSelect: () -> Unit,
) {
    Box(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .combinedClickable(
                onClick = { if (selecting) onToggleSelect() else onOpen() },
                onLongClick = onToggleSelect,
            )
    ) {
        AsyncImage(
            model = model,
            contentDescription = item.name,
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .fillMaxSize()
                // Shrinking the selected tile reads as "picked up" without
                // hiding what was selected behind a heavy overlay.
                .then(if (selected) Modifier.scale(0.86f).clip(RoundedCornerShape(6.dp))
                      else Modifier),
        )

        if (item.isVideo) {
            Row(
                Modifier.align(Alignment.BottomEnd).padding(4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                item.duration?.let {
                    Text(
                        formatDuration(it),
                        color = Color.White,
                        style = MaterialTheme.typography.labelSmall,
                    )
                    Spacer(Modifier.width(3.dp))
                }
                Icon(Icons.Default.PlayCircleFilled, null,
                    tint = Color.White, modifier = Modifier.size(16.dp))
            }
        }

        if (selected) {
            Icon(
                Icons.Default.CheckCircle, "Selected",
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.align(Alignment.TopStart).padding(6.dp).size(22.dp),
            )
        }
    }
}

fun formatDuration(seconds: Double): String {
    val total = seconds.roundToInt()
    val m = total / 60
    val s = total % 60
    return "%d:%02d".format(m, s)
}
