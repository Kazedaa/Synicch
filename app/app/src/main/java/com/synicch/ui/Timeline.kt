package com.synicch.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.synicch.data.MediaItem
import kotlinx.coroutines.launch

private val SPACING = 2.dp

@Composable
fun Timeline(
    items: List<MediaItem>,
    selection: Set<Long>,
    localIds: Set<Long>,
    thumbFor: (MediaItem) -> Any,
    onOpen: (MediaItem) -> Unit,
    onToggleSelect: (MediaItem) -> Unit,
    onSelectSection: (List<Long>) -> Unit = {},
    /** Picker mode: a tap picks rather than opens, and the day handles are up. */
    picking: Boolean = false,
    header: @Composable () -> Unit = {},
) {
    val selecting = picking || selection.isNotEmpty()
    var zoom by remember { mutableStateOf(Grid.Zoom.DAY) }
    val listState = rememberLazyListState()
    val density = LocalDensity.current
    var widthPx by remember { mutableFloatStateOf(0f) }

    // Recomputed only when the inputs actually change; at 25k photos this is
    // far too expensive to redo on every recomposition.
    val entries = remember(items, zoom, widthPx, density) {
        if (widthPx <= 0f) emptyList()
        else Grid.build(
            items,
            widthPx,
            zoom,
            with(density) { SPACING.toPx() },
            // Row heights are in dp, the layout is in pixels. Converting here
            // keeps Grid free of any Compose dependency.
            with(density) { zoom.targetHeightDp.dp.toPx() },
        )
    }

    /*
     * Every entry's height, and where each one starts.
     *
     * The layout already decided these when it justified the rows, so the scroll
     * bar can work in real pixels rather than pretending every item is the same
     * size. That is the difference between a handle that tracks the content and
     * one that drifts further out of step the further you scroll.
     */
    val metrics = remember(entries, density) {
        val headerPx = with(density) { 46.dp.toPx() }
        val spacingPx = with(density) { SPACING.toPx() }
        val starts = FloatArray(entries.size)
        var running = 0f
        entries.forEachIndexed { i, e ->
            starts[i] = running
            running += when (e) {
                is Grid.Entry.Header -> headerPx
                is Grid.Entry.Photos -> e.row.height + spacingPx
            }
        }
        TimelineMetrics(starts, running)
    }

    Box(
        Modifier
            .fillMaxSize()
            .onSizeChanged { widthPx = it.width.toFloat() }
            .pointerInput(Unit) {
                detectTransformGestures { _, _, gestureZoom, _ ->
                    if (gestureZoom > 1.35f) zoom = zoom.zoomIn()
                    else if (gestureZoom < 0.74f) zoom = zoom.zoomOut()
                }
            }
    ) {
        LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
            item(key = "top") { header() }

            items(entries, key = { it.keyOf() }) { entry ->
                when (entry) {
                    is Grid.Entry.Header -> SectionHeader(
                        title = entry.title,
                        zoom = zoom,
                        count = entry.ids.size,
                        selecting = selecting,
                        allSelected = entry.ids.all { it in selection },
                        onSelectAll = { onSelectSection(entry.ids) },
                    )
                    is Grid.Entry.Photos -> PhotoRow(
                        row = entry.row,
                        selection = selection,
                        localIds = localIds,
                        selecting = selecting,
                        thumbFor = thumbFor,
                        onOpen = onOpen,
                        onToggleSelect = onToggleSelect,
                    )
                }
            }

            item(key = "bottom") { Spacer(Modifier.height(96.dp)) }
        }

        Scrubber(
            listState = listState,
            entries = entries,
            metrics = metrics,
            modifier = Modifier.align(Alignment.CenterEnd),
        )

        // Only shown while pinching, so it never clutters normal browsing.
        AnimatedVisibility(
            visible = zoom != Grid.Zoom.DAY,
            modifier = Modifier.align(Alignment.TopEnd).padding(12.dp),
        ) {
            Surface(
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.9f),
                shape = RoundedCornerShape(50),
            ) {
                Text(zoom.label, Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                    style = MaterialTheme.typography.labelMedium)
            }
        }
    }
}

/** Where each entry begins, and how tall the whole timeline is, in pixels. */
private class TimelineMetrics(val starts: FloatArray, val total: Float)

/**
 * Wide enough for the date bubble to sit beside the handle. The column itself
 * takes no touches - only the narrow strip at its edge does - so the photos
 * underneath are still perfectly tappable.
 */
private val TRACK_WIDTH = 240.dp
private val GRAB_WIDTH = 44.dp
private val THUMB_HEIGHT = 44.dp

/**
 * The date scroll bar.
 *
 * Forty thousand photos is a scrollbar you cannot use and a timeline you cannot
 * navigate: dragging to "sometime last spring" by feel means a dozen attempts.
 * The months are drawn on the track itself, so the gesture is aimed at a date
 * rather than at a proportion of an unknown total.
 *
 * It stays out of the way - invisible until the list moves, gone again a moment
 * after it stops.
 */
private fun currentSection(entries: List<Grid.Entry>, index: Int): String {
    for (i in index downTo 0) {
        val e = entries[i]
        if (e is Grid.Entry.Header) return Grid.shortMonth(e.section)
    }
    return (entries.firstOrNull() as? Grid.Entry.Header)
        ?.let { Grid.shortMonth(it.section) } ?: ""
}

private fun Grid.Entry.keyOf(): String = when (this) {
    is Grid.Entry.Header -> key
    is Grid.Entry.Photos -> key
}

/**
 * A date heading, and - once selecting has started - the handle for taking
 * everything under it.
 *
 * The circle and the count stay hidden while simply browsing. Ordinary
 * scrolling through a gallery is the common case by a wide margin, and a
 * control on every heading that is only useful mid-selection is clutter the
 * rest of the time.
 */
@Composable
private fun SectionHeader(
    title: String,
    zoom: Grid.Zoom,
    count: Int,
    selecting: Boolean,
    allSelected: Boolean,
    onSelectAll: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().padding(start = 12.dp, end = 4.dp,
            top = if (zoom == Grid.Zoom.YEAR) 20.dp else 14.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            title,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
            fontSize = if (zoom == Grid.Zoom.YEAR) 20.sp else 15.sp,
        )
        Spacer(Modifier.weight(1f))
        AnimatedVisibility(selecting, enter = fadeIn(), exit = fadeOut()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "$count",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                IconButton(onSelectAll, Modifier.size(34.dp)) {
                    Icon(
                        if (allSelected) Icons.Default.CheckCircle
                        else Icons.Default.RadioButtonUnchecked,
                        if (allSelected) "Deselect $title" else "Select $title",
                        modifier = Modifier.size(19.dp),
                        tint = if (allSelected) MaterialTheme.colorScheme.primary
                               else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun PhotoRow(
    row: Grid.Row,
    selection: Set<Long>,
    localIds: Set<Long>,
    selecting: Boolean,
    thumbFor: (MediaItem) -> Any,
    onOpen: (MediaItem) -> Unit,
    onToggleSelect: (MediaItem) -> Unit,
) {
    val density = LocalDensity.current
    Row(
        Modifier
            .fillMaxWidth()
            .height(with(density) { row.height.toDp() })
            .padding(bottom = SPACING),
        horizontalArrangement = Arrangement.spacedBy(SPACING),
    ) {
        row.items.forEach { item ->
            Box(Modifier.weight(item.aspect.coerceIn(0.2f, 5f))) {
                Tile(
                    item = item,
                    model = thumbFor(item),
                    selected = item.id in selection,
                    isLocal = item.id in localIds,
                    selecting = selecting,
                    onOpen = { onOpen(item) },
                    onToggleSelect = { onToggleSelect(item) },
                )
            }
        }
    }
}
