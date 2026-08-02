package com.synicch.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculateCentroidSize
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.lerp
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.lerp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem as ExoItem
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.PlayerView
import coil3.compose.AsyncImage
import com.synicch.data.Album
import com.synicch.data.LocalMedia
import com.synicch.data.MediaItem
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt

private const val MAX_SCALE = 6f
private const val DOUBLE_TAP_SCALE = 2.5f

/** Past this, the 1600px preview is being magnified rather than examined. */
private const val HI_RES_SCALE = 2f

/**
 * Full-bleed viewer.
 *
 * No chrome until you tap - the photo gets the whole screen. Swipe sideways
 * between photos, pinch to zoom, swipe down to dismiss. Deliberately the same
 * gestures Google Photos uses, because that is what hands already expect.
 */
@Composable
fun Viewer(
    items: List<MediaItem>,
    startIndex: Int,
    sourceFor: (MediaItem) -> Any,
    thumbFor: (MediaItem) -> Any,
    originalFor: (MediaItem) -> Any,
    playbackFor: (MediaItem) -> Any,
    albumsFor: (MediaItem) -> List<Album>,
    localFor: (MediaItem) -> LocalMedia.Local?,
    coverFor: (Album) -> Any?,
    onOpenAlbum: (Album) -> Unit,
    onClose: () -> Unit,
    onDelete: (MediaItem) -> Unit,
    onAddToAlbum: (MediaItem) -> Unit,
    onEdit: (MediaItem) -> Unit,
    onShare: (MediaItem) -> Unit,
    onDownload: (MediaItem) -> Unit,
    /** Trash browses the same way; only what the bottom bar offers differs. */
    mode: ViewerMode = ViewerMode.LIBRARY,
    onRestore: (MediaItem) -> Unit = {},
    onDeleteForever: (MediaItem) -> Unit = {},
) {
    if (items.isEmpty()) { onClose(); return }

    val pager = rememberPagerState(
        initialPage = startIndex.coerceIn(0, items.lastIndex),
        pageCount = { items.size },
    )
    var chrome by remember { mutableStateOf(false) }
    // A zoomed photo owns horizontal drags; otherwise panning across a photo
    // would flick to the next one instead.
    var zoomed by remember { mutableStateOf(false) }
    var details by remember { mutableStateOf(false) }
    val current = items.getOrNull(pager.currentPage) ?: items.first()

    // A video's own transport controls take every tap on the surface, so there
    // is no tap left over to reveal the chrome with. Rather than leave a video
    // page with no way back, the bars simply stay up for videos.
    val chromeOn = chrome || current.isVideo

    // The photo moves up out from under the sheet rather than being covered by
    // it, so what you are reading about stays in sight.
    val lift by animateFloatAsState(
        targetValue = if (details) 1f else 0f,
        animationSpec = tween(280),
        label = "detailsLift",
    )

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        HorizontalPager(
            state = pager,
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer {
                    translationY = -size.height * 0.26f * lift
                    scaleX = 1f - 0.12f * lift
                    scaleY = 1f - 0.12f * lift
                    transformOrigin = TransformOrigin(0.5f, 0f)
                },
            userScrollEnabled = !zoomed,
            // A gap so the edge of one photo is never touching the next.
            pageSpacing = 16.dp,
            // Decode the neighbours ahead of time: a swipe should land on a
            // photo, not on black while it loads.
            beyondViewportPageCount = 1,
        ) { page ->
            val item = items[page]
            val active = page == pager.currentPage
            if (item.isVideo) {
                VideoPage(
                    model = playbackFor(item),
                    active = active,
                    onDismiss = onClose,
                    onDetails = { details = true },
                    bottomInset = 72.dp,
                )
            } else {
                ZoomableImage(
                    model = sourceFor(item),
                    placeholder = thumbFor(item),
                    hiRes = originalFor(item),
                    aspect = item.aspect,
                    active = active,
                    onTap = { chrome = !chrome },
                    onZoomChanged = { if (active) zoomed = it },
                    onDismiss = onClose,
                    onDetails = { details = true },
                )
            }
        }

        AnimatedVisibility(chromeOn, enter = fadeIn(), exit = fadeOut(),
            modifier = Modifier.align(Alignment.TopCenter)) {
            Surface(color = Color.Black.copy(alpha = 0.55f)) {
                Row(
                    Modifier.fillMaxWidth().statusBarsPadding().padding(4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconButton(onClose) {
                        Icon(Icons.Default.ArrowBack, "Close", tint = Color.White)
                    }
                    Column(Modifier.weight(1f)) {
                        Text(current.name, color = Color.White,
                            style = MaterialTheme.typography.bodyMedium, maxLines = 1)
                        current.captured?.let {
                            Text(it.replace("T", "  "), color = Color.White.copy(alpha = 0.7f),
                                style = MaterialTheme.typography.labelSmall)
                        }
                    }
                    IconButton({ details = true }) {
                        Icon(Icons.Default.Info, "Details", tint = Color.White)
                    }
                }
            }
        }

        AnimatedVisibility(chromeOn, enter = fadeIn(), exit = fadeOut(),
            modifier = Modifier.align(Alignment.BottomCenter)) {
            Surface(color = Color.Black.copy(alpha = 0.55f)) {
                Row(
                    Modifier.fillMaxWidth().navigationBarsPadding().padding(vertical = 4.dp),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                ) {
                    if (mode == ViewerMode.TRASH) {
                        ViewerAction(Icons.Default.RestoreFromTrash, "Restore") {
                            onRestore(current)
                        }
                        ViewerAction(Icons.Default.DeleteForever, "Delete forever") {
                            onDeleteForever(current)
                        }
                    } else {
                        ViewerAction(Icons.Default.Share, "Share") { onShare(current) }
                        ViewerAction(Icons.Default.AddCircleOutline, "Album") {
                            onAddToAlbum(current)
                        }
                        // Only offered when it is actually missing from the
                        // phone, which is the only time the word means anything.
                        if (!current.localOnly && localFor(current) == null) {
                            ViewerAction(Icons.Default.Download, "Save") {
                                onDownload(current)
                            }
                        } else if (!current.isVideo) {
                            ViewerAction(Icons.Default.Crop, "Edit") { onEdit(current) }
                        }
                        ViewerAction(Icons.Default.DeleteOutline, "Delete") {
                            onDelete(current)
                        }
                    }
                }
            }
        }
    }

    if (details) {
        ModalBottomSheet(
            onDismissRequest = { details = false },
            // No scrim: the photo above the sheet stays exactly as bright as it
            // was. Dimming it would say "this is a dialog over your photo", and
            // this is meant to read as the photo and its details together.
            scrimColor = Color.Transparent,
        ) {
            MediaDetails(current, albumsFor(current), localFor(current), coverFor,
                         onOpenAlbum)
        }
    }
}

/**
 * What the library actually knows about one file.
 *
 * `ts_source` is spelled out rather than shown raw because how a timestamp was
 * arrived at is the difference between a date you can trust and a guess - and
 * a photo sitting in the wrong place on the timeline is explained here.
 *
 * Two sizes are shown when both apply. The phone's copy and the server's are
 * the same bytes, but seeing both is the quickest way to confirm that a photo
 * really is backed up before freeing up space.
 */
@Composable
private fun MediaDetails(
    item: MediaItem,
    albums: List<Album>,
    local: LocalMedia.Local?,
    coverFor: (Album) -> Any?,
    onOpenAlbum: (Album) -> Unit,
) {
    Column(
        Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .navigationBarsPadding()
            .padding(start = 20.dp, end = 20.dp, bottom = 24.dp),
    ) {
        Text(prettyDateTime(item.captured) ?: item.name,
            style = MaterialTheme.typography.titleMedium)
        timestampNote(item.tsSource)?.let {
            Spacer(Modifier.height(4.dp))
            Text(it, style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }

        if (albums.isNotEmpty()) {
            SectionTitle("Albums")
            albums.forEach { album ->
                // The sheet already names the albums a photo is in; being told
                // where something lives and not being able to go there is the
                // kind of dead end you only notice by trying it.
                Row(
                    Modifier.fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .clickable { onOpenAlbum(album) }
                        .padding(vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    val cover = coverFor(album)
                    if (cover != null) {
                        AsyncImage(cover, null, contentScale = ContentScale.Crop,
                            modifier = Modifier.size(56.dp)
                                .clip(RoundedCornerShape(10.dp)))
                    } else {
                        Box(Modifier.size(56.dp).clip(RoundedCornerShape(10.dp))
                            .background(MaterialTheme.colorScheme.surfaceVariant))
                    }
                    Spacer(Modifier.width(14.dp))
                    Column {
                        Text(album.name, style = MaterialTheme.typography.bodyMedium)
                        Text("${album.count} items",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }

        SectionTitle("Details")
        Surface(
            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
            shape = RoundedCornerShape(14.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(Modifier.padding(vertical = 4.dp)) {
                if (item.localOnly) {
                    DetailRow(
                        Icons.Default.CloudUpload,
                        "Not backed up yet",
                        "Waiting for Syncthing to carry it to the server",
                    )
                } else {
                    DetailRow(
                        Icons.Default.CloudDone,
                        "In the library  -  ${formatBytes(item.size)}",
                        item.path,
                    )
                }

                item.camera?.let { camera ->
                    DetailRow(Icons.Default.CameraAlt, camera, exposureLine(item))
                }

                DetailRow(
                    if (item.isVideo) Icons.Default.Videocam else Icons.Default.Image,
                    item.name,
                    listOfNotNull(
                        megapixels(item.w, item.h),
                        if (item.w != null && item.h != null) "${item.w} x ${item.h}" else null,
                        item.duration?.let { formatDuration(it) },
                        item.codec?.uppercase(),
                    ).joinToString("  -  ").ifEmpty { null },
                )

                if (local != null) {
                    DetailRow(
                        Icons.Default.PhoneAndroid,
                        "On this phone  -  ${formatBytes(local.size)}",
                        local.path?.substringBeforeLast('/'),
                    )
                } else {
                    DetailRow(
                        Icons.Default.CloudQueue,
                        "Not on this phone",
                        "Held by the server only",
                    )
                }

                if (item.phoneTrashed) {
                    DetailRow(Icons.Default.DeleteOutline, "Deleted on the phone",
                        "Kept here - the server keeps everything")
                }
            }
        }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(text, Modifier.padding(top = 22.dp, bottom = 6.dp),
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun DetailRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    value: String,
    note: String?,
) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp)) {
        Icon(icon, null, Modifier.size(20.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.width(16.dp))
        Column {
            Text(value, style = MaterialTheme.typography.bodyMedium)
            note?.let {
                Text(it, style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

private fun timestampNote(source: String?): String? = when (source) {
    "phone" -> "From this phone, straight out of the camera"
    "exif_with_offset" -> "From the camera, timezone included"
    "exif" -> "From the camera, read in your timezone"
    "filename" -> "Read from the filename"
    "mtime" -> "File modified time - the least reliable source"
    else -> null
}

/** "Sun, 12 Jul 2026  -  12:11 pm" from the naive timestamp the camera wrote. */
private fun prettyDateTime(captured: String?): String? = runCatching {
    val t = LocalDateTime.parse(captured!!.take(19))
    t.format(DateTimeFormatter.ofPattern("EEE, d MMM yyyy")) + "  -  " +
        t.format(DateTimeFormatter.ofPattern("h:mm a")).lowercase()
}.getOrNull()

private fun megapixels(w: Int?, h: Int?): String? {
    if (w == null || h == null || w <= 0 || h <= 0) return null
    return "%.1f MP".format(w.toLong() * h / 1_000_000.0)
}

/** The row a photographer reads first: aperture, shutter, focal length, ISO. */
private fun exposureLine(item: MediaItem): String? = listOfNotNull(
    item.fNumber?.let { "f/" + trimNumber(it) },
    item.exposure?.let {
        if (it >= 1.0) "${trimNumber(it)}s" else "1/${(1.0 / it).roundToInt()}s"
    },
    // Two decimals here specifically: phone lenses are 5.56mm, and rounding
    // that to 5.6 loses the one digit that distinguishes the cameras.
    item.focal?.let { "${trimNumber(it, 2)} mm" },
    item.iso?.let { "ISO $it" },
).joinToString("   ").ifEmpty { null }

private fun trimNumber(v: Double, decimals: Int = 1): String =
    "%.${decimals}f".format(v).trimEnd('0').trimEnd('.')

/**
 * One action in the viewer's bottom bar.
 *
 * It had no feedback at all: no ripple, and an interaction source rebuilt on
 * every recomposition so nothing could have tracked a press anyway. A control
 * that gives nothing back leaves you wondering whether the tap registered,
 * which on a Delete button is a genuinely bad moment.
 */
@Composable
private fun ViewerAction(icon: androidx.compose.ui.graphics.vector.ImageVector,
                         label: String, onClick: () -> Unit) {
    val interactions = remember { MutableInteractionSource() }
    val pressed by interactions.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed) 0.86f else 1f,
        animationSpec = tween(90),
        label = "actionPress",
    )

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .clip(RoundedCornerShape(14.dp))
            .clickable(
                interactionSource = interactions,
                indication = ripple(color = Color.White),
                onClick = onClick,
            )
            .padding(horizontal = 14.dp, vertical = 8.dp)
            .graphicsLayer { scaleX = scale; scaleY = scale },
    ) {
        Icon(icon, label, tint = Color.White)
        Text(label, color = Color.White, style = MaterialTheme.typography.labelSmall,
             maxLines = 1)
    }
}

/** What the viewer is looking at, which decides what it can do about it. */
enum class ViewerMode { LIBRARY, TRASH }

/**
 * One photo, zoomable.
 *
 * Three things make this feel like a real photo viewer rather than an image in
 * a box:
 *
 * - **Zoom happens around the fingers**, not around the middle of the screen,
 *   so the detail being pinched at stays under the hand.
 * - **Panning stops at the edges of the photo.** The visible size is worked out
 *   from the stored aspect ratio, so a zoomed photo cannot be dragged off into
 *   empty space and lost.
 * - **Sharpness follows the zoom.** The 1600px preview is plenty at fit size and
 *   visibly soft at 3x, so the original is layered on top once magnified past
 *   the point where the preview runs out of detail.
 *
 * At fit size the first movement of a drag decides who owns it: sideways is the
 * pager's, up and down are this view's. Without that, every horizontal swipe
 * also nudged the photo down towards being dismissed, which is what made moving
 * through the gallery feel unsteady.
 */
@Composable
private fun ZoomableImage(
    model: Any,
    placeholder: Any,
    hiRes: Any,
    aspect: Float,
    active: Boolean,
    onTap: () -> Unit,
    onZoomChanged: (Boolean) -> Unit,
    onDismiss: () -> Unit,
    onDetails: () -> Unit,
) {
    var scale by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    /** Vertical travel of a swipe-to-close, kept apart from the pan offset. */
    var closing by remember { mutableStateOf(Offset.Zero) }
    var boxW by remember { mutableFloatStateOf(0f) }
    var boxH by remember { mutableFloatStateOf(0f) }
    var wantHiRes by remember { mutableStateOf(false) }
    var springBack by remember { mutableIntStateOf(0) }
    var animateTo by remember { mutableStateOf<Pair<Float, Offset>?>(null) }

    val dismissAt = with(LocalDensity.current) { 110.dp.toPx() }

    // Leaving a photo zoomed in means coming back to it later mid-zoom, and
    // swiping onward from a magnified detail rather than from the photo.
    LaunchedEffect(active) {
        if (!active) {
            scale = 1f; offset = Offset.Zero; closing = Offset.Zero; wantHiRes = false
        }
    }

    /** How far the photo may be dragged before its own edge reaches the screen. */
    fun limit(s: Float): Offset {
        if (boxW <= 0f || boxH <= 0f) return Offset.Zero
        val fitsWidth = aspect > boxW / boxH
        val w = if (fitsWidth) boxW else boxH * aspect
        val h = if (fitsWidth) boxW / aspect else boxH
        return Offset(max(0f, (w * s - boxW) / 2f), max(0f, (h * s - boxH) / 2f))
    }

    fun clamp(o: Offset, s: Float): Offset {
        val l = limit(s)
        return Offset(o.x.coerceIn(-l.x, l.x), o.y.coerceIn(-l.y, l.y))
    }

    /** Offset that keeps the content under [focus] fixed while scaling to [to]. */
    fun focused(focus: Offset, to: Float): Offset =
        focus + (offset - focus) * (to / scale)

    LaunchedEffect(springBack) {
        if (springBack == 0) return@LaunchedEffect
        val from = closing
        animate(1f, 0f, animationSpec = tween(180)) { f, _ -> closing = from * f }
    }

    LaunchedEffect(animateTo) {
        val target = animateTo ?: return@LaunchedEffect
        val fromScale = scale
        val fromOffset = offset
        animate(0f, 1f, animationSpec = tween(220)) { f, _ ->
            scale = lerp(fromScale, target.first, f)
            offset = lerp(fromOffset, target.second, f)
        }
        if (scale > HI_RES_SCALE) wantHiRes = true
        onZoomChanged(scale > 1f)
        animateTo = null
    }

    Box(
        Modifier
            .fillMaxSize()
            .onSizeChanged { boxW = it.width.toFloat(); boxH = it.height.toFloat() }
            .pointerInput(Unit) {
                detectTapGestures(
                    onTap = { onTap() },
                    onDoubleTap = { tap ->
                        // Toggle between fit and 2.5x, centred on what was
                        // tapped rather than on the middle of the screen.
                        val focus = tap - Offset(boxW / 2f, boxH / 2f)
                        animateTo = if (scale > 1f) 1f to Offset.Zero
                        else DOUBLE_TAP_SCALE to
                            clamp(focused(focus, DOUBLE_TAP_SCALE), DOUBLE_TAP_SCALE)
                    },
                )
            }
            .pointerInput(Unit) {
                // Who this drag belongs to: 0 not decided yet, 1 this photo,
                // -1 the pager underneath. Decided once, then held for the rest
                // of the gesture so a wandering finger cannot hand it back.
                var owner = 0
                detectTransforms(
                    onGesture = { centroid, pan, gestureZoom ->
                        if (animateTo != null) return@detectTransforms false

                        val focus = centroid - Offset(boxW / 2f, boxH / 2f)
                        val next = (scale * gestureZoom).coerceIn(1f, MAX_SCALE)
                        if (next != scale) {
                            offset = focused(focus, next)
                            scale = next
                            if (next > HI_RES_SCALE) wantHiRes = true
                            owner = 1                 // a pinch is never a swipe
                        }

                        if (scale > 1f) {
                            offset = clamp(offset + pan, scale)
                            closing = Offset.Zero
                            owner = 1
                        } else if (owner != -1) {
                            // At fit size the photo has nowhere to pan to, so a
                            // vertical drag means dismiss or details instead.
                            offset = Offset.Zero
                            val moved = closing + pan
                            if (owner == 0) {
                                owner = if (abs(moved.x) > abs(moved.y)) -1 else 1
                            }
                            closing = if (owner == 1) Offset(0f, moved.y) else Offset.Zero
                        }

                        onZoomChanged(scale > 1f)
                        owner == 1
                    },
                    onEnd = {
                        if (scale <= 1f) {
                            when {
                                closing.y > dismissAt -> onDismiss()
                                closing.y < -dismissAt -> { onDetails(); springBack++ }
                                else -> springBack++
                            }
                        } else {
                            offset = clamp(offset, scale)
                        }
                        owner = 0
                    },
                )
            },
        contentAlignment = Alignment.Center,
    ) {
        // Only a downward drag is a dismissal, so only that one shrinks and
        // fades. Upward is a peek at the details and drags against resistance.
        val progress =
            if (boxH > 0f && closing.y > 0f) (closing.y / (boxH * 0.6f)).coerceIn(0f, 1f)
            else 0f
        val travel = if (closing.y < 0f) closing.y * 0.45f else closing.y

        Box(
            Modifier
                .fillMaxSize()
                .graphicsLayer {
                    // Shrinking and fading as it is dragged away is what makes
                    // the gesture readable before you have committed to it.
                    val shrink = 1f - 0.25f * progress
                    scaleX = scale * shrink
                    scaleY = scale * shrink
                    translationX = offset.x
                    translationY = offset.y + travel
                    alpha = 1f - 0.55f * progress
                },
            contentAlignment = Alignment.Center,
        ) {
            // Layered rather than swapped: each better version paints over the
            // one below when it arrives, so the photo is never blank and never
            // flickers back to nothing while the next size loads.
            if (placeholder != model) {
                AsyncImage(
                    model = placeholder,
                    contentDescription = null,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxSize(),
                )
            }
            AsyncImage(
                model = model,
                contentDescription = null,
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxSize(),
            )
            if (wantHiRes && hiRes != model) {
                AsyncImage(
                    model = hiRes,
                    contentDescription = null,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
    }
}

/**
 * Pinch and drag, with an end-of-gesture callback.
 *
 * Compose's own `detectTransformGestures` has no way to say "the fingers came
 * off", which is exactly when a viewer has to decide whether a downward drag
 * was a dismissal or something to spring back from. Slop handling matches the
 * original so that taps still reach the tap detector alongside it.
 *
 * [onGesture] returns whether it wants the movement. Anything it declines is
 * left unconsumed and reaches the pager behind, which is how a sideways swipe
 * still turns the page.
 */
private suspend fun PointerInputScope.detectTransforms(
    onGesture: (centroid: Offset, pan: Offset, zoom: Float) -> Boolean,
    onEnd: () -> Unit,
) {
    awaitEachGesture {
        var zoom = 1f
        var pan = Offset.Zero
        var pastSlop = false
        val slop = viewConfiguration.touchSlop

        awaitFirstDown(requireUnconsumed = false)
        do {
            val event = awaitPointerEvent()
            val canceled = event.changes.any { it.isConsumed }
            if (!canceled) {
                val zoomChange = event.calculateZoom()
                val panChange = event.calculatePan()

                if (!pastSlop) {
                    zoom *= zoomChange
                    pan += panChange
                    val size = event.calculateCentroidSize(useCurrent = false)
                    if (abs(1 - zoom) * size > slop || pan.getDistance() > slop) {
                        pastSlop = true
                    }
                }

                if (pastSlop) {
                    val centroid = event.calculateCentroid(useCurrent = false)
                    val claimed = zoomChange != 1f || panChange != Offset.Zero
                    if (claimed && onGesture(centroid, panChange, zoomChange)) {
                        event.changes.forEach { if (it.positionChanged()) it.consume() }
                    }
                }
            }
        } while (!canceled && event.changes.any { it.pressed })

        onEnd()
    }
}

/**
 * A video page.
 *
 * The vertical gestures have to be read *before* the player view underneath,
 * which swallows touches for its own controls - otherwise swiping up on a
 * video would do nothing while the same swipe on a photo opened its details.
 * Only clearly vertical movement is taken, so scrubbing, the pager and every
 * tap on the transport controls are left alone.
 */
@androidx.annotation.OptIn(UnstableApi::class)
@Composable
private fun VideoPage(
    model: Any,
    active: Boolean,
    onDismiss: () -> Unit,
    onDetails: () -> Unit,
    bottomInset: Dp,
) {
    val context = LocalContext.current
    val dismissAt = with(LocalDensity.current) { 110.dp.toPx() }
    var drag by remember { mutableFloatStateOf(0f) }
    var settle by remember { mutableIntStateOf(0) }

    LaunchedEffect(settle) {
        if (settle == 0) return@LaunchedEffect
        val from = drag
        animate(1f, 0f, animationSpec = tween(180)) { f, _ -> drag = from * f }
    }
    val player = remember {
        ExoPlayer.Builder(context).build().apply {
            setMediaItem(
                when (model) {
                    is android.net.Uri -> ExoItem.fromUri(model)
                    else -> ExoItem.fromUri(model.toString())
                }
            )
            prepare()
        }
    }

    // Only the visible page plays; otherwise swiping through a gallery would
    // start every video it passed.
    LaunchedEffect(active) { player.playWhenReady = active }
    DisposableEffect(Unit) { onDispose { player.release() } }

    Box(
        Modifier
            .fillMaxSize()
            .pointerInput(Unit) {
                detectVerticalDrag(
                    onDrag = { dy -> drag += dy },
                    onEnd = {
                        when {
                            drag > dismissAt -> onDismiss()
                            drag < -dismissAt -> { onDetails(); settle++ }
                            else -> settle++
                        }
                    },
                )
            }
    ) {
        AndroidView(
            factory = { ctx ->
                PlayerView(ctx).apply {
                    this.player = player
                    useController = true       // pause, scrub, speed
                    setShowNextButton(false)
                    setShowPreviousButton(false)
                }
            },
            modifier = Modifier
                .fillMaxSize()
                // Keep the transport controls clear of the action bar below.
                .padding(bottom = bottomInset)
                .graphicsLayer {
                    translationY = if (drag < 0f) drag * 0.45f else drag
                    val progress =
                        if (drag > 0f) (drag / (size.height * 0.6f)).coerceIn(0f, 1f) else 0f
                    val shrink = 1f - 0.25f * progress
                    scaleX = shrink; scaleY = shrink
                    alpha = 1f - 0.55f * progress
                },
        )
    }
}

/**
 * Vertical drags only, claimed ahead of whatever is underneath.
 *
 * Read on the initial pass so an embedded view cannot consume the gesture
 * first, but nothing is taken until the movement is past touch slop *and*
 * more vertical than horizontal - which leaves sideways swipes to the pager
 * and short presses to the view's own controls.
 */
private suspend fun PointerInputScope.detectVerticalDrag(
    onDrag: (Float) -> Unit,
    onEnd: () -> Unit,
) {
    awaitEachGesture {
        awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
        val slop = viewConfiguration.touchSlop
        var total = Offset.Zero
        var claimed = false
        var declined = false
        var moved = false

        do {
            val event = awaitPointerEvent(PointerEventPass.Initial)
            val pan = event.calculatePan()
            total += pan
            if (total.getDistance() > slop) moved = true

            if (!claimed && !declined && moved) {
                if (abs(total.y) > abs(total.x)) claimed = true else declined = true
            }
            if (claimed) {
                onDrag(pan.y)
                event.changes.forEach { if (it.positionChanged()) it.consume() }
            }
        } while (event.changes.any { it.pressed })

        if (claimed) onEnd()
    }
}
