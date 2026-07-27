package com.synicch.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.synicch.data.ServerStatus
import com.synicch.data.TrashItem

@Composable
fun SettingsScreen(
    status: ServerStatus?,
    online: Boolean,
    localCount: Int,
    lastSync: Long,
    onSync: () -> Unit,
    onScanNow: () -> Unit,
    onFreeUpSpace: () -> Unit,
    onUnpair: () -> Unit,
) {
    var confirmUnpair by remember { mutableStateOf(false) }

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 90.dp)) {
        item {
            Column(Modifier.padding(16.dp)) {
                Text("Server", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        if (online) Icons.Default.CloudDone else Icons.Default.CloudOff,
                        null,
                        tint = if (online) MaterialTheme.colorScheme.primary
                               else MaterialTheme.colorScheme.error,
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(if (online) "Connected" else "Offline - showing cached library")
                }
            }
        }

        if (status != null) {
            item {
                Column(Modifier.padding(horizontal = 16.dp)) {
                    InfoRow("Photos and videos", "${status.active}")
                    InfoRow("Thumbnails ready", "${status.thumbnails}")
                    InfoRow("Timezone", status.timezone)
                    InfoRow("Free space on server", formatBytes(status.disk.free))
                    if (status.indexing) InfoRow("Indexing", "in progress")
                    if (status.scanning) InfoRow("Scanning", "running now")
                }
            }
        }

        item {
            Column(Modifier.padding(16.dp)) {
                InfoRow("On this phone", "$localCount")
                InfoRow("Last sync", if (lastSync == 0L) "never" else relativeTime(lastSync))
            }
        }

        item { HorizontalDivider() }

        item { ActionRow(Icons.Default.Sync, "Sync now",
            "Pull the latest library metadata", onSync) }
        item { ActionRow(Icons.Default.Refresh, "Scan server",
            "Look for newly backed-up photos", onScanNow) }

        item { HorizontalDivider() }

        item {
            ActionRow(
                Icons.Default.CleaningServices,
                "Free up space on this phone",
                "Delete originals that are safely backed up",
                onFreeUpSpace,
            )
        }

        item { HorizontalDivider() }

        item {
            ActionRow(Icons.Default.LinkOff, "Unpair this device",
                "Forget the server and clear the local cache") { confirmUnpair = true }
        }
    }

    if (confirmUnpair) {
        AlertDialog(
            onDismissRequest = { confirmUnpair = false },
            title = { Text("Unpair?") },
            text = {
                Text("This clears the cached library from the phone. Your photos on the " +
                     "server and on this phone are not touched.")
            },
            confirmButton = {
                TextButton({ confirmUnpair = false; onUnpair() }) { Text("Unpair") }
            },
            dismissButton = {
                TextButton({ confirmUnpair = false }) { Text("Cancel") }
            },
        )
    }
}

@Composable
private fun InfoRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun ActionRow(icon: androidx.compose.ui.graphics.vector.ImageVector,
                      title: String, subtitle: String, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null)
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) {
            Text(title, fontWeight = FontWeight.Medium)
            Text(subtitle, style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

// ------------------------------------------------------------------ trash --

@Composable
fun TrashScreen(
    items: List<TrashItem>,
    thumbFor: (Long) -> Any,
    onBack: () -> Unit,
    onOpen: (Int) -> Unit,
    onRestore: (List<Long>) -> Unit,
    onDeleteForever: (List<Long>) -> Unit,
) {
    var selection by remember(items) { mutableStateOf(setOf<Long>()) }
    var confirm by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (selection.isEmpty()) "Trash" else "${selection.size} selected") },
                navigationIcon = { IconButton(onBack) {
                    Icon(Icons.Default.ArrowBack, "Back") } },
                actions = {
                },
            )
        },
        bottomBar = {
            if (selection.isNotEmpty()) {
                BottomAppBar {
                    TextButton({ onRestore(selection.toList()); selection = emptySet() },
                        Modifier.weight(1f)) { Text("Restore") }
                    Button({ confirm = true },
                        Modifier.weight(1f).padding(horizontal = 8.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.error)) {
                        Text("Delete forever")
                    }
                }
            }
        },
    ) { pad ->
        Column(Modifier.padding(pad)) {
            Text(
                "Items here are deleted permanently after 30 days. Until then you can " +
                "put them back.",
                Modifier.padding(14.dp),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (items.isEmpty()) {
                Box(Modifier.fillMaxSize(), Alignment.Center) {
                    Text("Trash is empty",
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            LazyVerticalGrid(
                columns = GridCells.Adaptive(108.dp),
                horizontalArrangement = Arrangement.spacedBy(2.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                itemsIndexed(items, key = { _, it -> it.id }) { index, item ->
                    val picked = item.id in selection
                    Box(
                        Modifier.aspectRatio(1f)
                            .background(MaterialTheme.colorScheme.surfaceVariant)
                            .clickable {
                                selection = if (picked) selection - item.id
                                            else selection + item.id
                            }
                    ) {
                        AsyncImage(thumbFor(item.id), item.name,
                            Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                        if (picked) {
                            Icon(Icons.Default.CheckCircle, null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.align(Alignment.TopStart)
                                    .padding(4.dp).size(20.dp))
                        }
                        item.purgeAt?.let {
                            Surface(color = Color.Black.copy(alpha = 0.55f),
                                modifier = Modifier.align(Alignment.BottomStart)) {
                                Text(it.take(10), Modifier.padding(horizontal = 4.dp),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = Color.White)
                            }
                        }
                    }
                }
            }
        }
    }

    if (confirm) {
        AlertDialog(
            onDismissRequest = { confirm = false },
            icon = { Icon(Icons.Default.Warning, null,
                tint = MaterialTheme.colorScheme.error) },
            title = { Text("Delete ${selection.size} permanently?") },
            text = { Text("This removes them from the server for good. It cannot be undone.") },
            confirmButton = {
                TextButton({
                    confirm = false
                    onDeleteForever(selection.toList())
                    selection = emptySet()
                }) { Text("Delete forever") }
            },
            dismissButton = { TextButton({ confirm = false }) { Text("Cancel") } },
        )
    }
}

fun formatBytes(n: Long): String {
    if (n <= 0) return "0 B"
    val units = arrayOf("B", "KB", "MB", "GB", "TB")
    var v = n.toDouble()
    var i = 0
    while (v >= 1024 && i < units.lastIndex) { v /= 1024; i++ }
    return "%.1f %s".format(v, units[i])
}

fun relativeTime(millis: Long): String {
    val diff = (System.currentTimeMillis() - millis) / 1000
    return when {
        diff < 60 -> "just now"
        diff < 3600 -> "${diff / 60} min ago"
        diff < 86400 -> "${diff / 3600} h ago"
        else -> "${diff / 86400} d ago"
    }
}
