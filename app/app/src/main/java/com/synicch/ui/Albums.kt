package com.synicch.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.synicch.data.Album
import com.synicch.data.MediaItem

@Composable
fun AlbumsScreen(
    albums: List<Album>,
    unsortedCount: Int,
    coverUrl: (Long) -> String,
    onOpen: (Album) -> Unit,
    onOpenUnsorted: () -> Unit,
    onCreate: () -> Unit,
    onToggleRecording: (Album) -> Unit,
) {

    Box(Modifier.fillMaxSize()) {
        LazyVerticalGrid(
            columns = GridCells.Fixed(2),
            contentPadding = PaddingValues(12.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
            modifier = Modifier.fillMaxSize(),
        ) {
            item {
                UnsortedCard(unsortedCount, onOpenUnsorted)
            }
            items(albums, key = { it.id }) { album ->
                AlbumCard(
                    album = album,
                    coverUrl = coverUrl,
                    onOpen = { onOpen(album) },
                    onToggleRecording = { onToggleRecording(album) },
                    onLongPress = { confirmDelete = album },
                )
            }
            item { Spacer(Modifier.height(90.dp)) }
        }

        ExtendedFloatingActionButton(
            onClick = onCreate,
            icon = { Icon(Icons.Default.Add, null) },
            text = { Text("New album") },
            modifier = Modifier.align(Alignment.BottomEnd).padding(20.dp),
        )
    }

    confirmDelete?.let { album ->
        AlertDialog(
            onDismissRequest = { confirmDelete = null },
            icon = { Icon(Icons.Default.DeleteOutline, null) },
            title = { Text("Delete \"${album.name}\"?") },
            text = {
                Text(
                    "The album is removed. The ${album.count} photos in it are not " +
                    "touched - anything that was only in this album goes back to " +
                    "Unsorted."
                )
            },
            confirmButton = {
                TextButton({ onDelete(album); confirmDelete = null }) {
                    Text("Delete album")
                }
            },
            dismissButton = {
                TextButton({ confirmDelete = null }) { Text("Cancel") }
            },
        )
    }
}

@Composable
private fun UnsortedCard(count: Int, onClick: () -> Unit) {
    Card(Modifier.aspectRatio(1f).clickable(onClick = onClick)) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(Icons.Default.PhotoLibrary, null,
                    Modifier.size(40.dp), tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.height(8.dp))
                Text("Unsorted", fontWeight = FontWeight.Medium)
                Text("$count photos", style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun AlbumCard(
    album: Album,
    coverUrl: (Long) -> String,
    onOpen: () -> Unit,
    onToggleRecording: () -> Unit,
) {
    Card(
        Modifier.aspectRatio(1f).clickable(onClick = onOpen)
    ) {
        Box(Modifier.fillMaxSize()) {
            if (album.cover != null) {
                AsyncImage(
                    model = coverUrl(album.cover),
                    contentDescription = album.name,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            } else {
                Box(Modifier.fillMaxSize()
                    .background(MaterialTheme.colorScheme.surfaceVariant))
            }

            Box(
                Modifier.align(Alignment.BottomStart).fillMaxWidth()
                    .background(Color.Black.copy(alpha = 0.5f)).padding(8.dp)
            ) {
                Column {
                    Text(album.name, color = Color.White, maxLines = 1,
                        fontWeight = FontWeight.Medium,
                        style = MaterialTheme.typography.bodyMedium)
                    Text("${album.count} photos", color = Color.White.copy(alpha = 0.8f),
                        style = MaterialTheme.typography.labelSmall)
                }
            }

            // The recording state has to be obvious from the album grid --
            // "did I leave it on?" is the main failure mode of this feature.
            FilledIconToggleButton(
                checked = album.recording,
                onCheckedChange = { onToggleRecording() },
                modifier = Modifier.align(Alignment.TopEnd).padding(6.dp).size(34.dp),
            ) {
                Icon(
                    if (album.recording) Icons.Default.FiberManualRecord
                    else Icons.Default.RadioButtonUnchecked,
                    if (album.recording) "Stop recording" else "Start recording",
                    Modifier.size(18.dp),
                )
            }
        }
    }
}

/**
 * The "Add photos" picker, opened from inside an album.
 *
 * Filing works in both directions on purpose: reaching for one photo in the
 * timeline and reaching for an album to fill are different jobs. This is the
 * second one, so it shows only what is not already in the album - a picker that
 * lists photos you cannot add is just noise.
 *
 * It is the same timeline as the Photos tab, in picking mode. Choosing photos
 * means recognising them by when they were taken, and a picker with its own
 * flat grid and no dates would be a worse version of the view you already know
 * how to read.
 */
@Composable
fun RecordingBanner(album: Album, days: Int, onStop: () -> Unit) {
    Surface(
        color = MaterialTheme.colorScheme.primaryContainer,
        modifier = Modifier.fillMaxWidth().padding(8.dp),
        shape = RoundedCornerShape(12.dp),
    ) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.FiberManualRecord, null,
                tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text("Recording to ${album.name}",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium)
                Text(
                    if (days >= 7) "Running for $days days -- still on?"
                    else "New photos join this album",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            TextButton(onStop) { Text("Stop") }
        }
    }
}
