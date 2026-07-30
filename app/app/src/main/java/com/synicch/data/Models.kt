package com.synicch.data

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class MediaItem(
    val id: Long,
    val name: String = "",
    val kind: String = "photo",
    val w: Int? = null,
    val h: Int? = null,
    val size: Long = 0,
    val captured: String? = null,
    @SerialName("captured_utc") val capturedUtc: String? = null,
    @SerialName("ts_source") val tsSource: String? = null,
    val duration: Double? = null,
    val codec: String? = null,
    val state: String = "active",
    @SerialName("phone_trashed") val phoneTrashed: Boolean = false,
    @SerialName("has_thumb") val hasThumb: Boolean = false,
    /** Server's cheap content fingerprint, used to match a local file to this record. */
    val fp: String? = null,
    val albums: List<Long> = emptyList(),
    val severity: Double? = null,
    val keeper: Boolean = false,
    // Display only, and absent on anything that did not come from a camera.
    val camera: String? = null,
    @SerialName("f_number") val fNumber: Double? = null,
    val exposure: Double? = null,
    val focal: Double? = null,
    val iso: Int? = null,
    /** Where the file sits inside the backup folder on the server. */
    val path: String? = null,
) {
    /** Falls back to 1:1 so the grid can lay out before dimensions are known. */
    val aspect: Float get() = if (w != null && h != null && h > 0) w.toFloat() / h else 1f
    val isVideo: Boolean get() = kind == "video"

    /**
     * On the phone, not yet on the server.
     *
     * Negative ids are minted by [LocalMedia.Local.asItem]; the server only
     * ever issues positive ones. Anything that would call the API checks this
     * first, because there is nothing up there to call about.
     */
    val localOnly: Boolean get() = id < 0
}

@Serializable
data class MediaPage(
    val items: List<MediaItem> = emptyList(),
    @SerialName("next_cursor") val nextCursor: String? = null,
    @SerialName("has_more") val hasMore: Boolean = false,
)

@Serializable
data class Album(
    val id: Long,
    val name: String,
    val count: Int = 0,
    val recording: Boolean = false,
    val cover: Long? = null,
    @SerialName("created_at") val createdAt: String? = null,
)

@Serializable
data class AlbumList(val albums: List<Album> = emptyList())

@Serializable
data class Session(
    val id: Long,
    @SerialName("album_id") val albumId: Long,
    @SerialName("started_local") val startedLocal: String,
    @SerialName("ended_local") val endedLocal: String? = null,
    val active: Int = 0,
)

@Serializable
data class SessionList(val sessions: List<Session> = emptyList())

@Serializable
data class CleanupGroup(val reason: String, val label: String, val count: Int)

@Serializable
data class CleanupGroups(val groups: List<CleanupGroup> = emptyList())

@Serializable
data class CleanupItems(val reason: String, val items: List<MediaItem> = emptyList())

@Serializable
data class TrashItem(
    val id: Long,
    val name: String? = null,
    val size: Long = 0,
    val kind: String = "photo",
    val w: Int? = null,
    val h: Int? = null,
    @SerialName("trashed_at") val trashedAt: String? = null,
    @SerialName("purge_at") val purgeAt: String? = null,
) {
    val aspect: Float get() = if (w != null && h != null && h > 0) w.toFloat() / h else 1f
}

@Serializable
data class TrashList(val items: List<TrashItem> = emptyList())

@Serializable
data class ServerStatus(
    val files: Int = 0,
    val active: Int = 0,
    val thumbnails: Int = 0,
    val indexing: Boolean = false,
    val scanning: Boolean = false,
    val timezone: String = "",
    val disk: Disk = Disk(),
    /** Everything the server can be reached on; kept as DNS-independent fallbacks. */
    val addresses: List<String> = emptyList(),
) {
    @Serializable
    data class Disk(val free: Long = 0, val total: Long = 0)
}

@Serializable
data class PurgeResult(
    val eligible: Int = 0,
    val blocked: Int = 0,
    val purged: Int = 0,
    val freed: Long = 0,
    @SerialName("blocked_paths") val blockedPaths: List<String> = emptyList(),
)

// ---------------------------------------------------------------- free up --

@Serializable
data class FreeupCandidate(val id: Long, val name: String, val size: Long)

@Serializable
data class FreeupCandidates(
    val ready: Boolean = false,
    val reason: String = "",
    val candidates: List<FreeupCandidate> = emptyList(),
)

@Serializable
data class FreeupConfirmed(val id: Long, val name: String? = null, val size: Long = 0)

@Serializable
data class FreeupRejected(val id: Long? = null, val why: String = "")

@Serializable
data class FreeupVerified(
    val ready: Boolean = false,
    val reason: String = "",
    val confirmed: List<FreeupConfirmed> = emptyList(),
    val rejected: List<FreeupRejected> = emptyList(),
    val freed: Long = 0,
)

@Serializable
data class EditState(
    @SerialName("file_id") val fileId: Long = 0,
    val rotate: Int = 0,
    @SerialName("crop_x") val cropX: Double? = null,
    @SerialName("crop_y") val cropY: Double? = null,
    @SerialName("crop_w") val cropW: Double? = null,
    @SerialName("crop_h") val cropH: Double? = null,
)

/** What the pairing QR code contains. Kept short so the code stays scannable. */
@Serializable
data class PairingPayload(
    @SerialName("u") val url: String,
    @SerialName("t") val token: String,
    /** Direct addresses to fall back to when the hostname will not resolve. */
    @SerialName("f") val fallbacks: List<String> = emptyList(),
)
