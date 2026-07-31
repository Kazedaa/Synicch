package com.synicch.data

import android.content.ContentValues
import android.content.Context
import android.content.IntentSender
import android.net.Uri
import android.provider.MediaStore
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private val Context.prefs by preferencesDataStore("synicch")
private val KEY_URL = stringPreferencesKey("url")
private val KEY_TOKEN = stringPreferencesKey("token")
private val KEY_FALLBACKS = stringPreferencesKey("fallbacks")

/**
 * Single source of truth for the UI.
 *
 * Reads always come from the local cache first so the gallery renders instantly
 * and works with the network down; a refresh then updates in the background.
 */
class Repo(private val context: Context) {

    val api = Api(context)
    val cache = Cache(context)
    val local = LocalMedia(context)

    /**
     * The timeline is two sources merged, not one.
     *
     * Photos the server has are the library. Photos only the phone has are the
     * last few minutes of your life that Syncthing has not carried across yet,
     * and leaving them out is what made a video taken thirty seconds ago look
     * like it had not been taken at all.
     */
    private var serverItems: List<MediaItem> = emptyList()
    private var phoneOnlyItems: List<MediaItem> = emptyList()

    private val _items = MutableStateFlow<List<MediaItem>>(emptyList())
    val items: StateFlow<List<MediaItem>> = _items

    /**
     * Rebuild the timeline from both halves.
     *
     * The phone-only half is re-checked against the server list here rather
     * than trusted as computed. The camera roll and the library load
     * independently, so whichever arrives first would otherwise decide: reading
     * the roll before the cache had loaded made every photo look unbacked, and
     * the timeline came out holding all of them twice. Filtering at the point
     * of merge makes the result the same whatever order they land in, and drops
     * a pending photo the instant its server record shows up.
     */
    private fun remerge() {
        val known = serverItems.mapTo(HashSet()) { local.key(it.name, it.size) }
        val pending = phoneOnlyItems.filter { local.key(it.name, it.size) !in known }
        _items.value = (serverItems + pending)
            .sortedByDescending { it.capturedUtc ?: it.captured ?: "" }
    }

    /** The server's record for a phone-only item, once one exists. */
    fun serverMatch(item: MediaItem): MediaItem? =
        serverItems.firstOrNull { it.name == item.name && it.size == item.size }

    private val _albums = MutableStateFlow<List<Album>>(emptyList())
    val albums: StateFlow<List<Album>> = _albums

    private val _localUris = MutableStateFlow<Map<Long, Uri>>(emptyMap())
    val localUris: StateFlow<Map<Long, Uri>> = _localUris

    private val _notBackedUp = MutableStateFlow<List<LocalMedia.Local>>(emptyList())
    val notBackedUp: StateFlow<List<LocalMedia.Local>> = _notBackedUp

    private val _syncing = MutableStateFlow(false)
    val syncing: StateFlow<Boolean> = _syncing

    private val _online = MutableStateFlow(false)
    val online: StateFlow<Boolean> = _online

    val paired: Boolean get() = api.configured

    // ------------------------------------------------- watching the camera --

    private var observer: android.database.ContentObserver? = null
    private var rescanJob: kotlinx.coroutines.Job? = null

    /**
     * Notice photos as they are taken.
     *
     * Without this the timeline only learned about a new photo when the app was
     * next opened, which for a gallery sitting open beside the camera is the
     * wrong answer. MediaStore fires several notifications for one capture - the
     * pending entry, the write, the final rename - so the rescan is debounced
     * rather than run on each.
     */
    fun watchCameraRoll(scope: kotlinx.coroutines.CoroutineScope) {
        if (observer != null) return
        val obs = object : android.database.ContentObserver(
            android.os.Handler(android.os.Looper.getMainLooper())
        ) {
            override fun onChange(selfChange: Boolean, uri: Uri?) {
                rescanJob?.cancel()
                rescanJob = scope.launch {
                    kotlinx.coroutines.delay(700)
                    refreshLocal()
                }
            }
        }
        observer = obs
        listOf(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
        ).forEach { context.contentResolver.registerContentObserver(it, true, obs) }
    }

    fun stopWatching() {
        observer?.let { context.contentResolver.unregisterContentObserver(it) }
        observer = null
        rescanJob?.cancel()
    }

    suspend fun loadCredentials() = withContext(Dispatchers.IO) {
        val p = context.prefs.data.first()
        val primary = p[KEY_URL] ?: ""
        val extra = p[KEY_FALLBACKS]?.split("|")?.filter { it.isNotBlank() } ?: emptyList()
        api.addresses = (listOf(primary) + extra).filter { it.isNotBlank() }
        api.token = p[KEY_TOKEN] ?: ""
    }

    suspend fun savePairing(url: String, token: String,
                            fallbacks: List<String> = emptyList()) =
        withContext(Dispatchers.IO) {
            context.prefs.edit {
                it[KEY_URL] = url
                it[KEY_TOKEN] = token
                it[KEY_FALLBACKS] = fallbacks.joinToString("|")
            }
            api.addresses = (listOf(url) + fallbacks).filter { it.isNotBlank() }
            api.token = token
        }

    suspend fun unpair() = withContext(Dispatchers.IO) {
        context.prefs.edit {
            it.remove(KEY_URL); it.remove(KEY_TOKEN); it.remove(KEY_FALLBACKS)
        }
        api.addresses = emptyList(); api.token = ""
        cache.clear()
        serverItems = emptyList()
        phoneOnlyItems = emptyList()
        remerge()
    }

    /** Load from cache. Instant, works offline, no network touched. */
    suspend fun loadCached() = withContext(Dispatchers.IO) {
        serverItems = cache.media()
        remerge()
        _albums.value = cache.albums()
    }

    /** Read the phone's own camera roll and match it against the library. */
    suspend fun refreshLocal() = withContext(Dispatchers.IO) {
        runCatching {
            local.scan()
            val pending = local.notBackedUp(serverItems)
            _notBackedUp.value = pending
            phoneOnlyItems = pending.map { it.asItem() }
            remerge()
            // Both halves need a local file to draw from: the server's records
            // where the phone still holds a copy, and the phone-only ones,
            // which have nothing else to draw from at all.
            _localUris.value = local.matchAll(serverItems) +
                pending.associate { -it.id to it.uri }
        }
    }

    /**
     * Pull the full library metadata.
     *
     * Paged rather than all at once so the first screenful appears quickly, and
     * so a dropped connection halfway through still leaves the cache better off
     * than it was.
     */
    suspend fun sync(onProgress: (Int) -> Unit = {}) = withContext(Dispatchers.IO) {
        if (!api.configured) return@withContext
        _syncing.value = true
        try {
            val seen = HashSet<Long>()
            var cursor: String? = null
            var total = 0
            while (true) {
                val page = api.media(cursor = cursor, limit = 500).getOrElse {
                    _online.value = false
                    return@withContext
                }
                _online.value = true
                cache.putMedia(page.items)
                page.items.forEach { seen.add(it.id) }
                total += page.items.size
                onProgress(total)
                serverItems = cache.media()
                remerge()
                cursor = page.nextCursor
                if (!page.hasMore || cursor == null) break
            }
            // Anything the server no longer lists has been purged; drop it
            // rather than leaving a tile that 404s.
            cache.removeMissing(seen)
            serverItems = cache.media()
            remerge()
            api.albums().onSuccess { cache.putAlbums(it.albums); _albums.value = it.albums }
            cache.setMeta("last_sync", System.currentTimeMillis().toString())
            refreshLocal()
        } finally {
            _syncing.value = false
        }
    }

    /**
     * Adopt the address list the server advertises.
     *
     * However the device was paired - QR or typed by hand - it ends up knowing
     * every way to reach the server, and keeps up if one changes.
     */
    suspend fun learnAddresses(advertised: List<String>) = withContext(Dispatchers.IO) {
        if (advertised.isEmpty()) return@withContext
        val merged = (advertised + api.addresses).distinct().filter { it.isNotBlank() }
        if (merged != api.addresses) {
            api.addresses = merged
            context.prefs.edit {
                it[KEY_URL] = merged.first()
                it[KEY_FALLBACKS] = merged.drop(1).joinToString("|")
            }
        }
    }

    suspend fun refreshAlbums() = withContext(Dispatchers.IO) {
        api.albums().onSuccess { cache.putAlbums(it.albums); _albums.value = it.albums }
    }

    fun lastSync(): Long = cache.meta("last_sync")?.toLongOrNull() ?: 0

    /**
     * Where to load a photo's pixels from.
     *
     * Local storage when the phone still has the file - instant and no network.
     * Otherwise the server. This is the payoff for being a native app.
     */
    fun sourceFor(item: MediaItem, full: Boolean): Any =
        _localUris.value[item.id] ?: if (full) api.previewUrl(item.id) else api.thumbUrl(item.id)

    /**
     * Full-resolution pixels.
     *
     * Video playback always wants these; a photo only once it has been zoomed
     * past the point where the 1600px preview has any detail left to show.
     */
    fun originalFor(item: MediaItem): Any =
        _localUris.value[item.id] ?: api.originalUrl(item.id)

    fun playbackUrl(item: MediaItem): Any = originalFor(item)

    fun deleteRequest(uris: List<Uri>): IntentSender? = runCatching {
        MediaStore.createDeleteRequest(context.contentResolver, uris).intentSender
    }.getOrNull()
}
