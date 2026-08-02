package com.synicch

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import com.synicch.data.Album
import com.synicch.data.LocalMedia
import com.synicch.data.MediaItem
import com.synicch.ui.*

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { SynicchTheme { Root() } }
    }
}

private enum class Tab(
    val label: String,
    val icon: androidx.compose.ui.graphics.vector.ImageVector,
) {
    PHOTOS("Photos", Icons.Default.PhotoLibrary),
    ALBUMS("Albums", Icons.Default.Collections),
    CLEANUP("Cleanup", Icons.Default.CleaningServices),
    SETTINGS("Settings", Icons.Default.Settings),
}

private sealed interface Screen {
    data object Main : Screen
    data class Viewer(val index: Int, val source: List<MediaItem>) : Screen
    data class AlbumDetail(val album: Album) : Screen
    data class AlbumAdd(val album: Album) : Screen
    data object Unsorted : Screen
    data class CleanupGroup(val reason: String, val label: String) : Screen
    data object Trash : Screen
    data class TrashViewer(val index: Int) : Screen
    data object FreeUp : Screen
}

/**
 * A trashed item shown through the ordinary viewer.
 *
 * The file is still on the server - trash is a flag, not a move - so every
 * image endpoint still answers for it and nothing special is needed beyond
 * turning off actions that do not apply.
 */
private fun com.synicch.data.TrashItem.asMediaItem() = MediaItem(
    id = id, name = name ?: "", kind = kind, w = w, h = h, size = size,
)

@Composable
private fun Root(vm: AppViewModel = viewModel()) {
    val paired by vm.paired.collectAsStateWithLifecycle()
    val pairing by vm.pairing.collectAsStateWithLifecycle()
    val pairError by vm.pairError.collectAsStateWithLifecycle()

    val scanner = rememberLauncherForActivityResult(ScanContract()) { result ->
        result.contents?.let { vm.pairFromQr(it) }
    }

    // Reading the camera roll is the entire reason this is a native app, so ask
    // as soon as there is a server to compare it against.
    val permissions = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { vm.refreshLocal() }

    LaunchedEffect(paired) {
        if (paired) permissions.launch(LocalMedia.requiredPermissions())
    }

    if (!paired) {
        PairingScreen(
            onScan = {
                scanner.launch(ScanOptions().apply {
                    setPrompt("Point at the code printed by 'synicch pair'")
                    setBeepEnabled(false)
                    setOrientationLocked(false)
                })
            },
            onManual = { url, token -> vm.pair(url, token) },
            error = pairError,
            busy = pairing,
        )
    } else {
        Paired(vm)
    }
}

@Composable
private fun Paired(vm: AppViewModel) {
    var tab by remember { mutableStateOf(Tab.PHOTOS) }
    var screen by remember { mutableStateOf<Screen>(Screen.Main) }
    var selection by remember { mutableStateOf(setOf<Long>()) }
    var albumPicker by remember { mutableStateOf<List<Long>?>(null) }
    var newAlbumFor by remember { mutableStateOf<List<Long>?>(null) }
    var renaming by remember { mutableStateOf<Album?>(null) }

    val items by vm.items.collectAsStateWithLifecycle()
    val albums by vm.albums.collectAsStateWithLifecycle()
    val localUris by vm.localUris.collectAsStateWithLifecycle()
    val notBackedUp by vm.notBackedUp.collectAsStateWithLifecycle()
    val syncing by vm.syncing.collectAsStateWithLifecycle()
    val online by vm.online.collectAsStateWithLifecycle()
    val status by vm.status.collectAsStateWithLifecycle()
    val groups by vm.cleanupGroups.collectAsStateWithLifecycle()
    val groupItems by vm.cleanupItems.collectAsStateWithLifecycle()
    val trash by vm.trash.collectAsStateWithLifecycle()
    val freeUp by vm.freeUp.collectAsStateWithLifecycle()
    val toast by vm.toast.collectAsStateWithLifecycle()
    val working by vm.working.collectAsStateWithLifecycle()

    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(toast) {
        toast?.let { snackbar.showSnackbar(it); vm.toastShown() }
    }

    // Android shows its own confirmation before letting a third-party app delete
    // media it did not create. That dialog cannot be bypassed, which is exactly
    // right for the most dangerous action in the app.
    val deleteLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) vm.freeUpDeleted()
        else vm.freeUpCancelled()
    }

    // The phone half of an ordinary delete. Separate launcher from the one
    // above because the two mean different things when they come back empty.
    val trashDeleteLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) vm.phoneDeleteDone()
        else vm.phoneDeleteCancelled()
    }

    val pendingPhoneDelete by vm.pendingPhoneDelete.collectAsStateWithLifecycle()
    LaunchedEffect(pendingPhoneDelete) {
        if (pendingPhoneDelete.isEmpty()) return@LaunchedEffect
        val sender = vm.repo.deleteRequest(pendingPhoneDelete)
        if (sender == null) vm.phoneDeleteCancelled()
        else trashDeleteLauncher.launch(IntentSenderRequest.Builder(sender).build())
    }

    // Everything the delete flow needs to ask about before acting.
    var confirmDelete by remember { mutableStateOf<List<MediaItem>?>(null) }
    var confirmForever by remember { mutableStateOf<MediaItem?>(null) }

    val context = LocalContext.current
    fun shareNow(targets: List<MediaItem>) {
        vm.share(targets) { uris, mime ->
            val intent = if (uris.size == 1) {
                Intent(Intent.ACTION_SEND).apply {
                    type = mime
                    putExtra(Intent.EXTRA_STREAM, uris.first())
                }
            } else {
                Intent(Intent.ACTION_SEND_MULTIPLE).apply {
                    type = mime
                    putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(uris))
                }
            }
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            context.startActivity(Intent.createChooser(intent, "Share"))
        }
    }

    // A photo taken with the app in the background should be there on return,
    // even if the content observer missed it.
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle) {
        val obs = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) vm.refreshLocal()
        }
        lifecycle.addObserver(obs)
        onDispose { lifecycle.removeObserver(obs) }
    }

    fun requestDelete(targets: List<MediaItem>) {
        if (targets.isEmpty()) return
        val unsaved = targets.filter { it.localOnly }
        if (unsaved.isEmpty()) vm.moveToTrash(targets.map { it.id })
        else confirmDelete = targets
    }

    // Navigation is plain state rather than a nav graph, so the system back
    // button has to be told about it. Without this, backing out of the viewer
    // or the trash left the app entirely.
    BackHandler(enabled = screen !is Screen.Main || selection.isNotEmpty()) {
        val here = screen
        when {
            selection.isNotEmpty() -> selection = emptySet()
            here is Screen.TrashViewer -> screen = Screen.Trash
            here is Screen.AlbumAdd -> screen = Screen.AlbumDetail(here.album)
            else -> screen = Screen.Main
        }
    }

    val thumbFor: (MediaItem) -> Any = { vm.repo.sourceFor(it, full = false) }

    // Where each list had got to. A LazyColumn's position belongs to the
    // composable that owns it, and opening a photo takes the whole timeline out
    // of the tree -- so without somewhere to park it, coming back from the
    // viewer dropped you at the top of two years of photos. Keyed per screen,
    // so an album and the main timeline do not share one position.
    val listPositions = rememberSaveableStateHolder()

    // One root, and every screen a branch inside it rather than an early
    // return. The returns meant anything declared after them - the album
    // picker, the snackbar - simply did not exist while the viewer was open:
    // tapping "Album" set the state and nothing appeared until you had backed
    // all the way out to the timeline.
    Box(Modifier.fillMaxSize()) {
    when (val s = screen) {
        is Screen.Viewer -> {
            Viewer(
                items = s.source,
                startIndex = s.index,
                sourceFor = { vm.repo.sourceFor(it, full = true) },
                thumbFor = thumbFor,
                originalFor = { vm.repo.originalFor(it) },
                playbackFor = { vm.repo.playbackUrl(it) },
                albumsFor = { item -> albums.filter { it.id in item.albums } },
                localFor = { vm.repo.local.info(it) },
                coverFor = { album -> album.cover?.let { vm.repo.api.thumbUrl(it) } },
                onClose = { screen = Screen.Main },
                onDelete = { requestDelete(listOf(it)); screen = Screen.Main },
                onAddToAlbum = { albumPicker = listOf(it.id) },
                onEdit = { },
                onShare = { shareNow(listOf(it)) },
                onDownload = { vm.download(listOf(it)) },
            )
        }

        is Screen.AlbumAdd -> {
            AddToAlbumScreen(
                albumName = s.album.name,
                candidates = items.filter { s.album.id !in it.albums },
                localIds = localUris.keys,
                thumbFor = thumbFor,
                onBack = { screen = Screen.AlbumDetail(s.album) },
                onAdd = { ids ->
                    vm.addToAlbum(s.album.id, ids)
                    screen = Screen.AlbumDetail(s.album)
                },
            )
        }

        is Screen.CleanupGroup -> {
            CleanupGroupScreen(
                label = s.label,
                explanation = explanationFor(s.reason),
                items = groupItems,
                thumbFor = thumbFor,
                onBack = { screen = Screen.Main },
                onDelete = { vm.moveToTrash(it, s.reason) },
                onKeep = { vm.keep(it, s.reason) },
                onOpen = { },
            )
        }

        is Screen.Trash -> {
            TrashScreen(
                items = trash,
                thumbFor = { vm.repo.api.thumbUrl(it) },
                onBack = { screen = Screen.Main },
                onOpen = { screen = Screen.TrashViewer(it) },
                onRestore = { vm.restore(it) },
                onDeleteForever = { vm.deleteForever(it) },
            )
        }

        is Screen.TrashViewer -> {
            val asItems = trash.map { it.asMediaItem() }
            Viewer(
                items = asItems,
                startIndex = s.index,
                sourceFor = { vm.repo.api.previewUrl(it.id) },
                thumbFor = { vm.repo.api.thumbUrl(it.id) },
                originalFor = { vm.repo.api.originalUrl(it.id) },
                playbackFor = { vm.repo.api.originalUrl(it.id) },
                albumsFor = { emptyList() },
                localFor = { null },
                coverFor = { null },
                onClose = { screen = Screen.Trash },
                onDelete = { },
                onAddToAlbum = { },
                onEdit = { },
                onShare = { },
                onDownload = { },
                mode = ViewerMode.TRASH,
                onRestore = { item ->
                    vm.restore(listOf(item.id))
                    screen = Screen.Trash
                },
                onDeleteForever = { confirmForever = it },
            )
        }

        is Screen.FreeUp -> {
            FreeUpSpaceScreen(
                state = freeUp,
                onBack = { vm.resetFreeUp(); screen = Screen.Main },
                onStart = { vm.startFreeUp() },
                onConfirm = {
                    vm.repo.deleteRequest(vm.pendingDelete)?.let {
                        deleteLauncher.launch(IntentSenderRequest.Builder(it).build())
                    }
                },
            )
        }

        else -> {
            val visible: List<MediaItem> = when (val cur = screen) {
                is Screen.AlbumDetail -> items.filter { cur.album.id in it.albums }
                is Screen.Unsorted -> items.filter { it.albums.isEmpty() }
                else -> items
            }
            val subScreen = screen !is Screen.Main

            // One saved position per list, not one for "the timeline": going
            // back from an album should not move where the main timeline was.
            val positionKey = when (val cur = screen) {
                is Screen.AlbumDetail -> "album-${cur.album.id}"
                is Screen.Unsorted -> "unsorted"
                else -> "tab-${tab.name}"
            }

            listPositions.SaveableStateProvider(positionKey) {
            Scaffold(
                topBar = {
                    if (selection.isNotEmpty()) {
                        TopAppBar(
                            title = { Text("${selection.size} selected") },
                            navigationIcon = {
                                IconButton({ selection = emptySet() }) {
                                    Icon(Icons.Default.Close, "Clear selection")
                                }
                            },
                            actions = {
                                IconButton({
                                    shareNow(items.filter { it.id in selection })
                                    selection = emptySet()
                                }) { Icon(Icons.Default.Share, "Share") }
                                IconButton({ albumPicker = selection.toList() }) {
                                    Icon(Icons.Default.AddCircleOutline, "Add to album")
                                }
                                IconButton({
                                    requestDelete(items.filter { it.id in selection })
                                    selection = emptySet()
                                }) { Icon(Icons.Default.DeleteOutline, "Move to trash") }
                            },
                        )
                    } else if (subScreen) {
                        // Read the album back out of the live list rather than
                        // trusting the copy the navigation state is carrying:
                        // that snapshot was taken when the screen opened, so a
                        // rename would not show until you backed out and in.
                        val here = (screen as? Screen.AlbumDetail)?.album
                            ?.let { nav -> albums.firstOrNull { it.id == nav.id } ?: nav }
                        TopAppBar(
                            title = { Text(here?.name ?: "Unsorted") },
                            navigationIcon = {
                                IconButton({ screen = Screen.Main }) {
                                    Icon(Icons.Default.ArrowBack, "Back")
                                }
                            },
                            actions = {
                                if (here != null) {
                                    IconButton({ renaming = here }) {
                                        Icon(Icons.Default.Edit, "Rename album")
                                    }
                                }
                            },
                        )
                    }
                },
                floatingActionButton = {
                    val album = (screen as? Screen.AlbumDetail)?.album
                    if (album != null && selection.isEmpty()) {
                        ExtendedFloatingActionButton(
                            onClick = { screen = Screen.AlbumAdd(album) },
                            icon = { Icon(Icons.Default.AddPhotoAlternate, null) },
                            text = { Text("Add photos") },
                        )
                    }
                },
                bottomBar = {
                    if (!subScreen) {
                        NavigationBar {
                            Tab.entries.forEach { t ->
                                NavigationBarItem(
                                    selected = tab == t,
                                    onClick = { tab = t },
                                    icon = { Icon(t.icon, t.label) },
                                    label = { Text(t.label) },
                                )
                            }
                        }
                    }
                },
            ) { pad ->
                Box(Modifier.padding(pad).fillMaxSize()) {
                    if (subScreen || tab == Tab.PHOTOS) {
                        Timeline(
                            items = visible,
                            selection = selection,
                            localIds = localUris.keys,
                            thumbFor = thumbFor,
                            onOpen = { item ->
                                screen = Screen.Viewer(visible.indexOf(item), visible)
                            },
                            onToggleSelect = { item ->
                                selection = if (item.id in selection) selection - item.id
                                            else selection + item.id
                            },
                            // A whole day at once: already-complete means clear it,
                            // anything else means take the lot.
                            onSelectSection = { ids ->
                                selection = if (ids.all { it in selection }) selection - ids.toSet()
                                            else selection + ids
                            },
                            header = {
                                if (!subScreen) {
                                    albums.firstOrNull { it.recording }?.let { rec ->
                                        RecordingBanner(rec, 0) { vm.toggleRecording(rec) }
                                    }
                                }
                            },
                        )
                        if (visible.isEmpty()) {
                            Box(Modifier.fillMaxSize(), Alignment.Center) {
                                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                    if (syncing) {
                                        CircularProgressIndicator()
                                        Spacer(Modifier.height(12.dp))
                                        Text("Loading your library")
                                    } else {
                                        Text("Nothing here yet")
                                    }
                                }
                            }
                        }
                    } else when (tab) {
                        Tab.ALBUMS -> AlbumsScreen(
                            albums = albums,
                            unsortedCount = items.count { it.albums.isEmpty() },
                            coverUrl = { vm.repo.api.thumbUrl(it) },
                            onOpen = { screen = Screen.AlbumDetail(it) },
                            onOpenUnsorted = { screen = Screen.Unsorted },
                            onCreate = { newAlbumFor = emptyList() },
                            onToggleRecording = { vm.toggleRecording(it) },
                            onDelete = { vm.deleteAlbum(it.id) },
                        )

                        Tab.CLEANUP -> CleanupScreen(
                            groups = groups,
                            trashCount = trash.size,
                            onOpenGroup = {
                                vm.loadCleanupGroup(it.reason)
                                screen = Screen.CleanupGroup(it.reason, it.label)
                            },
                            onOpenTrash = { screen = Screen.Trash },
                            onRedetect = { vm.redetect() },
                        )

                        Tab.SETTINGS -> SettingsScreen(
                            status = status,
                            online = online,
                            localCount = localUris.size,
                            lastSync = vm.repo.lastSync(),
                            onSync = { vm.sync() },
                            onScanNow = { vm.scanNow() },
                            onFreeUpSpace = { vm.resetFreeUp(); screen = Screen.FreeUp },
                            onUnpair = { vm.unpair() },
                        )

                        else -> Unit
                    }
                }
            }
            }
        }
    }

    // Slow work says so with a hairline at the top of the screen rather than a
    // dialog. A modal that blocks the app to announce a download is a modal
    // that exists for the app's benefit, not the reader's.
    if (working != null) {
        LinearProgressIndicator(
            Modifier.fillMaxWidth().align(Alignment.TopCenter).statusBarsPadding(),
        )
    }

    SnackbarHost(
        snackbar,
        Modifier.align(Alignment.BottomCenter).navigationBarsPadding(),
    )
    }

    albumPicker?.let { ids ->
        AlbumPickerDialog(
            albums = albums,
            onDismiss = { albumPicker = null },
            onPick = { album ->
                vm.addToAlbum(album.id, ids)
                albumPicker = null
                selection = emptySet()
            },
            onCreateNew = { typed ->
                albumPicker = null
                if (typed.isBlank()) newAlbumFor = ids
                else { vm.createAlbum(typed, ids); selection = emptySet() }
            },
        )
    }

    confirmDelete?.let { targets ->
        DeleteNotBackedUpDialog(
            unsaved = targets.count { it.localOnly },
            total = targets.size,
            onDismiss = { confirmDelete = null },
            onBackupFirst = { confirmDelete = null; vm.backupThenDelete(targets) },
            onDeleteAnyway = {
                confirmDelete = null
                // The backed-up half still goes through trash; only the part
                // the server has never seen is actually being destroyed.
                val known = targets.filter { !it.localOnly }
                if (known.isNotEmpty()) vm.moveToTrash(known.map { it.id })
                vm.deleteLocalOnly(targets.filter { it.localOnly })
            },
        )
    }

    confirmForever?.let { item ->
        AlertDialog(
            onDismissRequest = { confirmForever = null },
            icon = { Icon(Icons.Default.Warning, null,
                tint = MaterialTheme.colorScheme.error) },
            title = { Text("Delete permanently?") },
            text = {
                Text("${item.name} is removed from the server for good. " +
                     "This cannot be undone.")
            },
            confirmButton = {
                TextButton({
                    vm.deleteForever(listOf(item.id))
                    confirmForever = null
                    screen = Screen.Trash
                }) { Text("Delete forever") }
            },
            dismissButton = {
                TextButton({ confirmForever = null }) { Text("Cancel") }
            },
        )
    }

    renaming?.let { album ->
        RenameAlbumDialog(
            current = album.name,
            onDismiss = { renaming = null },
            onRename = { name ->
                if (name != album.name) vm.renameAlbum(album.id, name)
                renaming = null
            },
        )
    }

    newAlbumFor?.let { ids ->
        NewAlbumDialog(
            onDismiss = { newAlbumFor = null },
            onCreate = { name ->
                vm.createAlbum(name, ids)
                newAlbumFor = null
                selection = emptySet()
            },
        )
    }
}

/**
 * The one delete that cannot be undone.
 *
 * Everything else in this app goes to trash and waits thirty days. A photo the
 * server has never received has no trash to go to, so this is the only place
 * where pressing delete really does mean gone - and it says so rather than
 * quietly doing it.
 */
@Composable
private fun DeleteNotBackedUpDialog(
    unsaved: Int,
    total: Int,
    onDismiss: () -> Unit,
    onBackupFirst: () -> Unit,
    onDeleteAnyway: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Default.Warning, null,
            tint = MaterialTheme.colorScheme.error) },
        title = {
            Text(if (unsaved == 1) "This one is not backed up"
                 else "$unsaved of these are not backed up")
        },
        text = {
            Column {
                Text(
                    if (unsaved == total)
                        "The server has never seen this. Deleting it now removes the " +
                        "only copy, and there is no trash to get it back from."
                    else
                        "$unsaved of $total have not reached the server yet. The rest " +
                        "go to trash as usual and can be restored for 30 days."
                )
                Spacer(Modifier.height(12.dp))
                Text(
                    "Backing up first asks the server to look now. Syncthing does the " +
                    "sending, so it only works if this phone is on the same network " +
                    "or Tailscale is up.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            TextButton(onBackupFirst) { Text("Back up, then delete") }
        },
        dismissButton = {
            Row {
                TextButton(onDismiss) { Text("Cancel") }
                TextButton(
                    onClick = onDeleteAnyway,
                    colors = ButtonDefaults.textButtonColors(
                        contentColor = MaterialTheme.colorScheme.error),
                ) { Text("Delete anyway") }
            }
        },
    )
}

/**
 * Pick an album, by typing.
 *
 * A flat list works at two albums and stops working somewhere around fifteen,
 * which is a year of trips. Filtering as you type scales with the library, and
 * typing a name that does not exist yet offers to create it - the two things
 * anyone does here are "put this in Goa" and "start an album called Goa", and
 * they should not be different journeys.
 */
@Composable
private fun AlbumPickerDialog(
    albums: List<Album>,
    onDismiss: () -> Unit,
    onPick: (Album) -> Unit,
    onCreateNew: (String) -> Unit,
) {
    var query by remember { mutableStateOf("") }
    val matches = remember(albums, query) {
        if (query.isBlank()) albums
        else albums.filter { it.name.contains(query.trim(), ignoreCase = true) }
    }
    val exact = albums.any { it.name.equals(query.trim(), ignoreCase = true) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add to album") },
        text = {
            Column {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    label = { Text("Search albums") },
                    leadingIcon = { Icon(Icons.Default.Search, null) },
                    trailingIcon = {
                        if (query.isNotEmpty()) {
                            IconButton({ query = "" }) {
                                Icon(Icons.Default.Close, "Clear")
                            }
                        }
                    },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))

                if (matches.isEmpty()) {
                    Text(
                        if (albums.isEmpty()) "No albums yet."
                        else "Nothing matches \"${query.trim()}\".",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(vertical = 12.dp),
                    )
                } else {
                    LazyColumn(Modifier.heightIn(max = 280.dp)) {
                        items(matches, key = { it.id }) { album ->
                            ListItem(
                                headlineContent = { Text(album.name) },
                                supportingContent = { Text("${album.count} photos") },
                                leadingContent = {
                                    if (album.recording) {
                                        Icon(Icons.Default.FiberManualRecord, "Recording",
                                            tint = MaterialTheme.colorScheme.error,
                                            modifier = Modifier.size(14.dp))
                                    }
                                },
                                modifier = Modifier.clickable { onPick(album) },
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onCreateNew(query.trim()) },
                enabled = query.isBlank() || !exact,
            ) {
                Text(if (query.isBlank()) "New album" else "Create \"${query.trim()}\"")
            }
        },
        dismissButton = { TextButton(onDismiss) { Text("Cancel") } },
    )
}

/**
 * Rename, opened from the album's own screen.
 *
 * Starts with the current name selected-in rather than blank: renaming is
 * usually a correction to what is already there, and a cleared field would mean
 * retyping a name you only wanted to fix one letter of.
 */
@Composable
private fun RenameAlbumDialog(
    current: String,
    onDismiss: () -> Unit,
    onRename: (String) -> Unit,
) {
    var name by remember { mutableStateOf(current) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Rename album") },
        text = {
            OutlinedTextField(
                value = name, onValueChange = { name = it },
                label = { Text("Album name") }, singleLine = true,
            )
        },
        confirmButton = {
            TextButton({ onRename(name.trim()) }, enabled = name.isNotBlank()) {
                Text("Rename")
            }
        },
        dismissButton = { TextButton(onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun NewAlbumDialog(onDismiss: () -> Unit, onCreate: (String) -> Unit) {
    var name by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("New album") },
        text = {
            OutlinedTextField(
                value = name, onValueChange = { name = it },
                label = { Text("Album name") }, singleLine = true,
            )
        },
        confirmButton = {
            TextButton({ onCreate(name.trim()) }, enabled = name.isNotBlank()) {
                Text("Create")
            }
        },
        dismissButton = { TextButton(onDismiss) { Text("Cancel") } },
    )
}
