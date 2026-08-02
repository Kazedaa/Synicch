package com.synicch

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.synicch.data.*
import com.synicch.ui.FreeUpState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json

/** Roughly half a minute of waiting before giving up on a pending backup. */
private const val BACKUP_WAIT_TRIES = 12

class AppViewModel(app: Application) : AndroidViewModel(app) {

    val repo = Repo(app)

    val items: StateFlow<List<MediaItem>> get() = repo.items
    val albums: StateFlow<List<Album>> get() = repo.albums
    val localUris: StateFlow<Map<Long, Uri>> get() = repo.localUris
    val notBackedUp: StateFlow<List<LocalMedia.Local>> get() = repo.notBackedUp
    val syncing: StateFlow<Boolean> get() = repo.syncing
    val online: StateFlow<Boolean> get() = repo.online

    private val _paired = MutableStateFlow(false)
    val paired: StateFlow<Boolean> = _paired

    private val _pairError = MutableStateFlow<String?>(null)
    val pairError: StateFlow<String?> = _pairError

    private val _pairing = MutableStateFlow(false)
    val pairing: StateFlow<Boolean> = _pairing

    private val _status = MutableStateFlow<ServerStatus?>(null)
    val status: StateFlow<ServerStatus?> = _status

    private val _cleanupGroups = MutableStateFlow<List<CleanupGroup>>(emptyList())
    val cleanupGroups: StateFlow<List<CleanupGroup>> = _cleanupGroups

    private val _cleanupItems = MutableStateFlow<List<MediaItem>>(emptyList())
    val cleanupItems: StateFlow<List<MediaItem>> = _cleanupItems

    private val _trash = MutableStateFlow<List<TrashItem>>(emptyList())
    val trash: StateFlow<List<TrashItem>> = _trash

    private val _freeUp = MutableStateFlow<FreeUpState>(FreeUpState.Idle)
    val freeUp: StateFlow<FreeUpState> = _freeUp

    private val _toast = MutableStateFlow<String?>(null)
    val toast: StateFlow<String?> = _toast

    /** Uris queued for Android's own delete confirmation dialog. */
    var pendingDelete: List<Uri> = emptyList()
        private set
    private var pendingBytes: Long = 0

    /** The delete half of [moveToTrash], waiting on Android's own dialog. */
    private val _pendingPhoneDelete = MutableStateFlow<List<Uri>>(emptyList())
    val pendingPhoneDelete: StateFlow<List<Uri>> = _pendingPhoneDelete

    /** Server ids already trashed for that request, to undo if it is cancelled. */
    private var trashedForPhoneDelete: List<Long> = emptyList()

    /** Non-null while something slow and blocking is happening. */
    private val _working = MutableStateFlow<String?>(null)
    val working: StateFlow<String?> = _working

    private val json = Json { ignoreUnknownKeys = true }

    init {
        repo.clearShareCache()
        // Photos taken while the app is open should turn up in the timeline
        // without it being reopened.
        repo.watchCameraRoll(viewModelScope)
        viewModelScope.launch {
            repo.loadCredentials()
            _paired.value = repo.paired
            if (repo.paired) {
                repo.loadCached()          // instant, offline, no network
                repo.refreshLocal()
                // An empty cache means a first run, or a cache the app dropped
                // to change its shape. Either way the library has to be pulled
                // before there is anything to show, so do it rather than
                // leaving an empty gallery waiting for someone to press Sync.
                if (repo.items.value.isEmpty()) repo.sync()
                refreshAll()
            }
        }
    }

    fun toastShown() { _toast.value = null }

    // ---------------------------------------------------------- pairing --

    fun pairFromQr(raw: String) {
        val payload = runCatching { json.decodeFromString<PairingPayload>(raw) }.getOrNull()
        if (payload == null) {
            _pairError.value = "That does not look like a Synicch pairing code."
            return
        }
        pair(payload.url, payload.token, payload.fallbacks)
    }

    fun pair(url: String, token: String,
             fallbacks: List<String> = emptyList()) = viewModelScope.launch {
        _pairing.value = true
        _pairError.value = null
        try {
            repo.savePairing(url, token, fallbacks)
            val ok = withContext(Dispatchers.IO) { repo.api.status() }
            if (ok.isFailure) {
                _pairError.value = buildString {
                    append("Could not reach the server.\n\n")
                    append("Tried: ")
                    append((listOf(url) + fallbacks).joinToString(", "))
                    append("\n\nCheck you are on the same network, or that Tailscale is on.")
                }
                repo.unpair()
                return@launch
            }
            _paired.value = true
            _status.value = ok.getOrNull()
            ok.getOrNull()?.let { repo.learnAddresses(it.addresses) }
            repo.sync()
            refreshAll()
        } finally {
            _pairing.value = false
        }
    }

    fun unpair() = viewModelScope.launch {
        repo.unpair()
        _paired.value = false
        _status.value = null
    }

    // ------------------------------------------------------------- sync --

    fun sync() = viewModelScope.launch {
        repo.sync()
        refreshAll()
    }

    fun scanNow() = viewModelScope.launch {
        withContext(Dispatchers.IO) { repo.api.triggerScan() }
        _toast.value = "Scan started on the server"
    }

    fun refreshLocal() = viewModelScope.launch { repo.refreshLocal() }

    private fun refreshAll() = viewModelScope.launch {
        withContext(Dispatchers.IO) {
            repo.api.status().onSuccess {
                _status.value = it
                repo.learnAddresses(it.addresses)
            }
            repo.api.cleanupGroups().onSuccess { _cleanupGroups.value = it.groups }
            repo.api.trash().onSuccess { _trash.value = it.items }
        }
        repo.refreshAlbums()
    }

    // ----------------------------------------------------------- albums --

    fun createAlbum(name: String, thenAdd: List<Long> = emptyList()) =
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                repo.api.createAlbum(name).onSuccess { album ->
                    if (thenAdd.isNotEmpty()) repo.api.addToAlbum(album.id, thenAdd)
                }
            }
            repo.refreshAlbums()
            repo.sync()
            _toast.value = "Album created"
        }

    fun addToAlbum(albumId: Long, ids: List<Long>) = viewModelScope.launch {
        // Albums live on the server, so a photo it has never seen cannot join
        // one. Skipped rather than refused: the rest of the selection still goes.
        val known = ids.filter { it > 0 }
        if (known.isEmpty()) {
            _toast.value = "Not backed up yet - albums need the server"
            return@launch
        }
        withContext(Dispatchers.IO) { repo.api.addToAlbum(albumId, known) }
        repo.sync()
        _toast.value =
            if (known.size == ids.size) "Added ${known.size} to album"
            else "Added ${known.size}; ${ids.size - known.size} not backed up yet"
    }

    fun removeFromAlbum(albumId: Long, ids: List<Long>) = viewModelScope.launch {
        withContext(Dispatchers.IO) { repo.api.removeFromAlbum(albumId, ids) }
        repo.sync()
    }

    fun deleteAlbum(albumId: Long) = viewModelScope.launch {
        withContext(Dispatchers.IO) { repo.api.deleteAlbum(albumId) }
        repo.refreshAlbums()
        repo.sync()
        _toast.value = "Album deleted. Photos were not touched."
    }

    fun setAlbumCover(albumId: Long, fileId: Long) = viewModelScope.launch {
        withContext(Dispatchers.IO) { repo.api.setAlbumCover(albumId, fileId) }
        // Only the album list carries covers, so there is nothing to re-pull
        // about the photo itself.
        repo.refreshAlbums()
        _toast.value = "Album cover set"
    }

    fun renameAlbum(albumId: Long, name: String) = viewModelScope.launch {
        withContext(Dispatchers.IO) { repo.api.renameAlbum(albumId, name) }
        repo.refreshAlbums()
    }

    fun toggleRecording(album: Album) = viewModelScope.launch {
        withContext(Dispatchers.IO) { repo.api.setRecording(album.id, !album.recording) }
        repo.refreshAlbums()
        repo.sync()
        _toast.value = if (album.recording) "Stopped recording"
                       else "Recording to ${album.name}"
    }

    /** The "I forgot to turn it on" path. */
    fun addPastSession(albumId: Long, start: String, end: String) = viewModelScope.launch {
        withContext(Dispatchers.IO) { repo.api.addSession(albumId, start, end) }
        repo.refreshAlbums()
        repo.sync()
        _toast.value = "Dates applied"
    }

    // ---------------------------------------------------------- cleanup --

    fun loadCleanupGroup(reason: String) = viewModelScope.launch {
        _cleanupItems.value = emptyList()
        withContext(Dispatchers.IO) {
            repo.api.cleanupItems(reason).onSuccess { _cleanupItems.value = it.items }
        }
    }

    fun redetect() = viewModelScope.launch {
        withContext(Dispatchers.IO) { repo.api.detect() }
        refreshAll()
        _toast.value = "Rechecked"
    }

    fun keep(ids: List<Long>, reason: String?) = viewModelScope.launch {
        withContext(Dispatchers.IO) { repo.api.dismiss(ids, reason) }
        reason?.let { loadCleanupGroup(it) }
        refreshAll()
        _toast.value = "Kept ${ids.size}"
    }

    /**
     * Delete, as the word is normally understood.
     *
     * The photo leaves the phone and becomes trash on the server, where it sits
     * for its retention period before anything is destroyed. Order matters: the
     * server is told first, because that is what creates the keeper link. Only
     * once the library holds the file independently is it safe to let the
     * phone's copy go.
     *
     * Android shows its own confirmation for the phone half, and that dialog
     * cannot be bypassed by this app. If it is dismissed, the server-side trash
     * is undone as well - a cancelled delete has to mean nothing happened.
     */
    fun moveToTrash(ids: List<Long>, reason: String? = null) = viewModelScope.launch {
        // Resolved before anything else happens. Trashing removes these from
        // the server's active list, so the sync that follows drops them from
        // the local-file map too - looking the phone's copies up afterwards
        // found nothing, and the delete silently did half its job.
        val uris = ids.mapNotNull { repo.localUris.value[it] }

        val serverIds = ids.filter { it > 0 }
        if (serverIds.isNotEmpty()) {
            withContext(Dispatchers.IO) { repo.api.moveToTrash(serverIds) }
        }
        reason?.let { loadCleanupGroup(it) }
        repo.sync()
        refreshAll()

        if (uris.isEmpty()) {
            _toast.value = "Moved ${serverIds.size} to trash"
        } else {
            trashedForPhoneDelete = serverIds
            _pendingPhoneDelete.value = uris
        }
    }

    /** Only on the phone, so there is no trash to fall back to. */
    fun deleteLocalOnly(items: List<MediaItem>) = viewModelScope.launch {
        val uris = items.mapNotNull { repo.localUris.value[it.id] }
        if (uris.isEmpty()) return@launch
        trashedForPhoneDelete = emptyList()
        _pendingPhoneDelete.value = uris
    }

    /**
     * Wait for the backup to land, then delete.
     *
     * The app deliberately has no upload path - Syncthing owns the backup, and
     * a second one would only disagree with it. What this can do is ask the
     * server to look now, and check whether the file has arrived. If it has
     * not, nothing is deleted and it says so.
     */
    fun backupThenDelete(items: List<MediaItem>) = viewModelScope.launch {
        _working.value = "Asking the server to look for these"
        withContext(Dispatchers.IO) { repo.api.triggerScan() }

        repeat(BACKUP_WAIT_TRIES) { attempt ->
            delay(2500)
            _working.value = "Waiting for the backup to arrive (${attempt + 1})"
            repo.sync()
            val matched = items.mapNotNull { repo.serverMatch(it) }
            if (matched.size == items.size) {
                _working.value = null
                moveToTrash(matched.map { it.id })
                return@launch
            }
        }

        _working.value = null
        _toast.value = "Not on the server yet. Nothing was deleted - " +
            "check Syncthing is running on this phone, then try again."
    }

    // ------------------------------------------------ getting files back --

    fun download(items: List<MediaItem>) = viewModelScope.launch {
        val wanted = items.filter { !it.localOnly && repo.localUris.value[it.id] == null }
        if (wanted.isEmpty()) {
            _toast.value = "Already on this phone"
            return@launch
        }
        var done = 0
        wanted.forEachIndexed { i, item ->
            _working.value =
                if (wanted.size == 1) "Downloading ${item.name}"
                else "Downloading ${i + 1} of ${wanted.size}"
            if (repo.download(item) != null) done++
        }
        _working.value = null
        _toast.value =
            if (done == wanted.size) "Saved $done back to your camera roll"
            else "Saved $done of ${wanted.size} - the rest could not be fetched"
    }

    /**
     * Hand files to another app.
     *
     * Anything the phone already holds is shared straight from the camera roll.
     * Anything it does not is fetched into a staging directory first, so a photo
     * that only lives on the server can still be sent without downloading it
     * into the gallery.
     */
    fun share(items: List<MediaItem>, onReady: (List<Uri>, String) -> Unit) =
        viewModelScope.launch {
            if (items.isEmpty()) return@launch
            val needsFetch = items.count {
                repo.localUris.value[it.id] == null && !it.localOnly
            }
            if (needsFetch > 0) _working.value = "Fetching $needsFetch from the server"

            val uris = items.mapNotNull { repo.shareUri(it) }
            _working.value = null

            if (uris.isEmpty()) {
                _toast.value = "Could not prepare that for sharing"
                return@launch
            }
            if (uris.size < items.size) {
                _toast.value = "Sharing ${uris.size} of ${items.size}"
            }
            val mime = items.map { repo.mimeOf(it) }.distinct()
                .singleOrNull() ?: if (items.all { it.isVideo }) "video/*" else "*/*"
            onReady(uris, mime)
        }

    fun phoneDeleteDone() {
        _pendingPhoneDelete.value = emptyList()
        trashedForPhoneDelete = emptyList()
        _toast.value = "Deleted"
        refreshLocal()
    }

    /** Dismissing Android's dialog has to undo the server half as well. */
    fun phoneDeleteCancelled() = viewModelScope.launch {
        _pendingPhoneDelete.value = emptyList()
        val ids = trashedForPhoneDelete
        trashedForPhoneDelete = emptyList()
        if (ids.isNotEmpty()) {
            withContext(Dispatchers.IO) { repo.api.restore(ids) }
            repo.sync()
            refreshAll()
        }
        _toast.value = "Nothing was deleted"
    }

    fun restore(ids: List<Long>) = viewModelScope.launch {
        withContext(Dispatchers.IO) { repo.api.restore(ids) }
        repo.sync()
        refreshAll()
        _toast.value = "Restored ${ids.size}"
    }

    fun deleteForever(ids: List<Long>) = viewModelScope.launch {
        val result = withContext(Dispatchers.IO) { repo.api.purge(ids, force = true) }
        repo.sync()
        refreshAll()
        result.onSuccess { r ->
            _toast.value = if (r.blocked > 0)
                "Deleted ${r.purged}. ${r.blocked} could not be removed safely -- " +
                "see reports/purge-blocked.txt on the server."
            else "Deleted ${r.purged} permanently"
        }
    }

    // -------------------------------------------------------- free up --

    fun startFreeUp() = viewModelScope.launch {
        _freeUp.value = FreeUpState.Checking("Reading this phone's camera roll")
        val locals = withContext(Dispatchers.IO) { repo.local.scan() }

        _freeUp.value = FreeUpState.Checking("Asking the server what is eligible")
        val candidates = withContext(Dispatchers.IO) {
            repo.api.freeupCandidates(locals.map { it.name to it.size })
        }.getOrNull()

        if (candidates == null) {
            _freeUp.value = FreeUpState.Blocked("Could not reach the server.")
            return@launch
        }
        if (!candidates.ready) {
            _freeUp.value = FreeUpState.Blocked(candidates.reason)
            return@launch
        }
        if (candidates.candidates.isEmpty()) {
            _freeUp.value = FreeUpState.Ready(0, 0, 0)
            return@launch
        }

        // Only the candidates get hashed -- hashing the whole camera roll would
        // waste a lot of time and battery for no benefit.
        _freeUp.value = FreeUpState.Checking(
            "Verifying ${candidates.candidates.size} files byte by byte")
        val hashes = withContext(Dispatchers.IO) {
            candidates.candidates.mapNotNull { c ->
                val uri = repo.local.uriFor(c.name, c.size) ?: return@mapNotNull null
                repo.local.sha256(uri)?.let { c.id to it }
            }
        }

        val verified = withContext(Dispatchers.IO) { repo.api.freeupVerify(hashes) }.getOrNull()
        if (verified == null || !verified.ready) {
            _freeUp.value = FreeUpState.Blocked(
                verified?.reason ?: "Verification failed.")
            return@launch
        }

        pendingDelete = verified.confirmed.mapNotNull { c ->
            c.name?.let { repo.local.uriFor(it, c.size) }
        }
        pendingBytes = verified.freed
        _freeUp.value = FreeUpState.Ready(
            pendingDelete.size, verified.freed, verified.rejected.size)
    }

    fun freeUpDeleted() {
        _freeUp.value = FreeUpState.Done(pendingDelete.size, pendingBytes)
        pendingDelete = emptyList()
        refreshLocal()
    }

    fun freeUpCancelled() {
        _freeUp.value = FreeUpState.Idle
        pendingDelete = emptyList()
    }

    fun resetFreeUp() { _freeUp.value = FreeUpState.Idle }

    override fun onCleared() {
        repo.stopWatching()
        super.onCleared()
    }
}
