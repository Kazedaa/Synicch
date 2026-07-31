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

    /*
     * Stay at the top when new photos arrive at the top.
     *
     * A lazy list anchors itself to the item it is showing, so photos inserted
     * above the viewport push the view down rather than appearing: the app
     * opened from cache, the phone's newer photos merged in a moment later, and
     * they landed off-screen above with nothing to say so. Anchoring is right
     * when you are reading halfway down the timeline, so this only pulls back
     * up when you were already at the top.
     */
    val newestKey = entries.firstOrNull()?.keyOf()
    LaunchedEffect(newestKey) {
        if (newestKey != null && listState.firstVisibleItemIndex <= 3) {
            listState.scrollToItem(0)
        }
    }

    Box(
        Modifier
            .fillMaxSize()
            .onSizeChanged { widthPx = it.width.toFloat() }
            .pointerInput(Unit) {
                detectPinch { zoomedIn ->
                    zoom = if (zoomedIn) zoom.zoomIn() else zoom.zoomOut()
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
@Composable
private fun Scrubber(
    listState: LazyListState,
    entries: List<Grid.Entry>,
    metrics: TimelineMetrics,
    modifier: Modifier = Modifier,
) {
    if (entries.size < 2) return

    val density = LocalDensity.current
    val scope = rememberCoroutineScope()
    var dragging by remember { mutableStateOf(false) }
    var trackPx by remember { mutableFloatStateOf(0f) }

    val scrollable = metrics.total.coerceAtLeast(1f)

    // The list index is one ahead of the entry index: the header slot sits at 0.
    val firstEntry = (listState.firstVisibleItemIndex - 1).coerceIn(0, entries.lastIndex)
    val scrolled = metrics.starts[firstEntry] + listState.firstVisibleItemScrollOffset
    val fraction = (scrolled / scrollable).coerceIn(0f, 1f)

    /**
     * Months that have photos, kept as scroll offsets rather than fractions.
     *
     * They are turned into positions with the same divisor the handle uses, so
     * that dragging the handle onto a label lands on that month. Dividing by the
     * content height instead would put every label slightly low, and the last
     * month or two somewhere the handle cannot reach at all.
     */
    val marks = remember(entries, metrics) {
        val out = ArrayList<Pair<Float, String>>()
        var last: String? = null
        entries.forEachIndexed { i, e ->
            if (e is Grid.Entry.Header) {
                val month = e.section.take(7)
                if (month != last) {
                    last = month
                    out.add(metrics.starts[i] to Grid.shortMonth(month))
                }
            }
        }
        out
    }

    fun jumpTo(y: Float) {
        val target = ((y / trackPx).coerceIn(0f, 1f) * scrollable)
        var i = metrics.starts.indexOfFirst { it > target } - 1
        if (i < 0) i = metrics.starts.lastIndex
        val within = (target - metrics.starts[i]).toInt().coerceAtLeast(0)
        scope.launch { listState.scrollToItem(i + 1, within) }
    }

    val visible = dragging || listState.isScrollInProgress
    val alpha by animateFloatAsState(
        targetValue = if (visible) 1f else 0f,
        animationSpec = tween(if (visible) 120 else 500, delayMillis = if (visible) 0 else 700),
        label = "scrubber",
    )
    if (alpha == 0f) return

    Box(
        modifier
            .fillMaxHeight()
            .width(TRACK_WIDTH)
            .graphicsLayer { this.alpha = alpha }
            .onSizeChanged { trackPx = (it.height - with(density) { THUMB_HEIGHT.toPx() })
                .coerceAtLeast(1f) }
    ) {
        // Month ticks. Thinned out as they crowd, because a column of unreadable
        // labels is worse than fewer readable ones.
        val minGap = with(density) { 30.dp.toPx() }
        var lastY = -minGap
        marks.forEach { (offset, label) ->
            val y = (offset / scrollable).coerceIn(0f, 1f) * trackPx
            if (y - lastY >= minGap) {
                lastY = y
                Row(
                    Modifier
                        .offset(y = with(density) { y.toDp() })
                        .align(Alignment.TopEnd)
                        .padding(end = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        label,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.width(6.dp))
                    Box(
                        Modifier
                            .size(width = 9.dp, height = 2.dp)
                            .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
                    )
                }
            }
        }

        // The handle, and - while it is being held - what it is pointing at.
        Row(
            Modifier
                .offset(y = with(density) { (fraction * trackPx).toDp() })
                .align(Alignment.TopEnd),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (dragging) {
                Surface(
                    color = MaterialTheme.colorScheme.primary,
                    shape = RoundedCornerShape(50),
                    shadowElevation = 3.dp,
                ) {
                    Text(
                        currentSection(entries, firstEntry),
                        Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onPrimary,
                    )
                }
                Spacer(Modifier.width(8.dp))
            }
            Surface(
                color = if (dragging) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.surfaceVariant,
                shape = RoundedCornerShape(topStart = 22.dp, bottomStart = 22.dp),
                shadowElevation = 2.dp,
                modifier = Modifier.height(THUMB_HEIGHT).width(28.dp),
            ) {
                Icon(
                    Icons.Default.DragHandle, "Scroll by date",
                    Modifier.padding(4.dp),
                    tint = if (dragging) MaterialTheme.colorScheme.onPrimary
                           else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        // The only part that takes touches. Anywhere else in this column is a
        // photo, and dragging there should scroll the list as it always did.
        Box(
            Modifier
                .align(Alignment.TopEnd)
                .width(GRAB_WIDTH)
                .fillMaxHeight()
                .pointerInput(metrics) {
                    detectDragGestures(
                        onDragStart = { start -> dragging = true; jumpTo(start.y) },
                        onDragEnd = { dragging = false },
                        onDragCancel = { dragging = false },
                    ) { change, _ -> jumpTo(change.position.y) }
                }
        )
    }
}

/** The month heading covering a given entry, searching upwards from it. */
private fun currentSection(entries: List<Grid.Entry>, index: Int): String {
    for (i in index downTo 0) {
        val e = entries[i]
        if (e is Grid.Entry.Header) return Grid.shortMonth(e.section)
    }
    return (entries.firstOrNull() as? Grid.Entry.Header)
        ?.let { Grid.shortMonth(it.section) } ?: ""
}

/**
 * Two-finger pinch, watched ahead of the list underneath it.
 *
 * The events are read on the initial pass because a lazy list treats a
 * two-finger drag as an ordinary scroll and consumes it first - which is why a
 * pinch would otherwise just scroll the timeline instead of changing zoom
 * level. Nothing is consumed until the fingers actually spread or close, so
 * normal scrolling is untouched.
 *
 * [onZoom] is called with true to go finer (year -> month -> day).
 */
private suspend fun PointerInputScope.detectPinch(onZoom: (Boolean) -> Unit) {
    awaitEachGesture {
        awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
        // Zoom is accumulated across the whole gesture rather than acted on per
        // event: one deliberate spread should move exactly one level.
        var accumulated = 1f
        do {
            val event = awaitPointerEvent(PointerEventPass.Initial)
            if (event.changes.count { it.pressed } >= 2) {
                val change = event.calculateZoom()
                if (change != 1f) {
                    accumulated *= change
                    if (accumulated > 1.35f || accumulated < 0.74f) {
                        onZoom(accumulated > 1f)
                        accumulated = 1f
                    }
                    event.changes.forEach { it.consume() }
                }
            }
        } while (event.changes.any { it.pressed })
    }
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
