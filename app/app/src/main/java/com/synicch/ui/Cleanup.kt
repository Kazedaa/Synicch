package com.synicch.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
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
import com.synicch.data.CleanupGroup
import com.synicch.data.MediaItem

private val EXPLANATIONS = mapOf(
    "phone_trashed" to "You deleted these on your phone. They are kept here because " +
        "the server keeps everything, but you can remove them from your library too.",
    "short_video" to "Videos under a second and a half. Almost always taken by accident.",
    "blank" to "Almost entirely one colour -- usually a pocket shot or a covered lens.",
    "dup_exact" to "Byte-for-byte identical copies of the same file.",
    "dup_near" to "Bursts of near-identical shots. The sharpest is marked as the keeper.",
    "blurry" to "Detected as out of focus. This is the least reliable check -- " +
        "deliberate motion blur and shallow depth of field get caught too.",
)

@Composable
fun CleanupScreen(
    groups: List<CleanupGroup>,
    trashCount: Int,
    onOpenGroup: (CleanupGroup) -> Unit,
    onOpenTrash: () -> Unit,
    onRedetect: () -> Unit,
) {
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(12.dp)) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp)) {
                Text("Suggested cleanup", style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f))
                TextButton(onRedetect) { Text("Recheck") }
            }
            Text(
                "Nothing is ever deleted automatically. Everything you confirm goes to " +
                "trash for 30 days first.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 12.dp),
            )
        }

        items(groups, key = { it.reason }) { group ->
            Card(
                Modifier.fillMaxWidth().padding(bottom = 8.dp)
                    .clickable { onOpenGroup(group) },
            ) {
                Column(Modifier.padding(14.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(group.label, fontWeight = FontWeight.Medium,
                            modifier = Modifier.weight(1f))
                        Badge { Text("${group.count}") }
                        Spacer(Modifier.width(8.dp))
                        Icon(Icons.Default.ChevronRight, null)
                    }
                    EXPLANATIONS[group.reason]?.let {
                        Spacer(Modifier.height(4.dp))
                        Text(it, style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }

        if (groups.isEmpty()) {
            item {
                Box(Modifier.fillMaxWidth().padding(40.dp), Alignment.Center) {
                    Text("Nothing to clean up",
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }

        item {
            Card(Modifier.fillMaxWidth().padding(top = 12.dp).clickable { onOpenTrash() }) {
                Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.DeleteOutline, null)
                    Spacer(Modifier.width(12.dp))
                    Text("Trash", Modifier.weight(1f), fontWeight = FontWeight.Medium)
                    if (trashCount > 0) Badge { Text("$trashCount") }
                    Spacer(Modifier.width(8.dp))
                    Icon(Icons.Default.ChevronRight, null)
                }
            }
        }
    }
}

/**
 * One cleanup group.
 *
 * Nothing starts selected -- every deletion is something the user explicitly
 * picked. "Select all" keeps that fast for the obvious groups without making
 * bulk deletion the default.
 */
@Composable
fun CleanupGroupScreen(
    label: String,
    explanation: String?,
    items: List<MediaItem>,
    thumbFor: (MediaItem) -> Any,
    onBack: () -> Unit,
    onDelete: (List<Long>) -> Unit,
    onKeep: (List<Long>) -> Unit,
    onOpen: (MediaItem) -> Unit,
) {
    var selection by remember(items) { mutableStateOf(setOf<Long>()) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (selection.isEmpty()) label else "${selection.size} selected") },
                navigationIcon = {
                    IconButton(onBack) { Icon(Icons.Default.ArrowBack, "Back") }
                },
                actions = {
                    TextButton({
                        selection = if (selection.size == items.size) emptySet()
                                    else items.map { it.id }.toSet()
                    }) {
                        Text(if (selection.size == items.size) "None" else "All")
                    }
                },
            )
        },
        bottomBar = {
            if (selection.isNotEmpty()) {
                BottomAppBar {
                    TextButton(
                        onClick = { onKeep(selection.toList()); selection = emptySet() },
                        modifier = Modifier.weight(1f),
                    ) { Text("Keep (${selection.size})") }
                    Button(
                        onClick = { onDelete(selection.toList()); selection = emptySet() },
                        modifier = Modifier.weight(1f).padding(horizontal = 8.dp),
                    ) { Text("Move to trash") }
                }
            }
        },
    ) { pad ->
        Column(Modifier.padding(pad)) {
            explanation?.let {
                Text(it, Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            LazyVerticalGrid(
                columns = GridCells.Adaptive(108.dp),
                horizontalArrangement = Arrangement.spacedBy(2.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
                modifier = Modifier.fillMaxSize(),
            ) {
                items(items, key = { it.id }) { item ->
                    val picked = item.id in selection
                    Box(
                        Modifier.aspectRatio(1f)
                            .background(MaterialTheme.colorScheme.surfaceVariant)
                            .clickable {
                                selection = if (picked) selection - item.id
                                            else selection + item.id
                            }
                    ) {
                        AsyncImage(
                            model = thumbFor(item), contentDescription = item.name,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxSize()
                                .then(if (picked) Modifier.padding(8.dp)
                                          .clip(RoundedCornerShape(6.dp)) else Modifier),
                        )
                        if (picked) {
                            Icon(Icons.Default.CheckCircle, null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.align(Alignment.TopStart)
                                    .padding(4.dp).size(20.dp))
                        }
                        if (item.keeper) {
                            Surface(
                                color = MaterialTheme.colorScheme.primary,
                                shape = RoundedCornerShape(4.dp),
                                modifier = Modifier.align(Alignment.BottomStart).padding(4.dp),
                            ) {
                                Text("Sharpest", Modifier.padding(horizontal = 4.dp),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onPrimary)
                            }
                        }
                    }
                }
            }
        }
    }
}

fun explanationFor(reason: String): String? = EXPLANATIONS[reason]
