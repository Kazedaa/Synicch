package com.synicch.data

import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import java.io.File
import java.security.MessageDigest

/**
 * The phone's own camera roll.
 *
 * This is the whole reason Synicch is a native app rather than a web page:
 * browsers have no API for reading local photos on Android. When a photo is
 * still on the device it can be shown instantly with no network at all, which
 * matters because Tailscale is not always reachable.
 */
class LocalMedia(private val context: Context) {

    data class Local(
        val uri: Uri,
        val id: Long,
        val name: String,
        val size: Long,
        val dateTaken: Long,
        val path: String?,
        val width: Int?,
        val height: Int?,
        val isVideo: Boolean,
    ) {
        /**
         * The same file as a timeline entry, for photos the server has not got
         * yet.
         *
         * The id is negated. Server ids are positive, so a negative one is
         * unambiguous, sorts into no server range, and every call that would
         * reach the API can refuse it on sight rather than discovering halfway
         * through that this photo does not exist up there.
         */
        fun asItem(): MediaItem {
            val instant = java.time.Instant.ofEpochMilli(
                if (dateTaken > 0) dateTaken else System.currentTimeMillis())
            val local = java.time.LocalDateTime.ofInstant(
                instant, java.time.ZoneId.systemDefault())
            return MediaItem(
                id = -id,
                name = name,
                kind = if (isVideo) "video" else "photo",
                w = width, h = height, size = size,
                captured = local.withNano(0).toString(),
                capturedUtc = instant.atZone(java.time.ZoneOffset.UTC)
                    .withNano(0).toOffsetDateTime().toString(),
                tsSource = "phone",
            )
        }
    }

    /**
     * Matching a local file to a server record is real logic, not a lookup.
     *
     * Filename alone is not safe - names repeat across devices and apps. Name
     * plus exact byte size is a strong pair, and anything ambiguous falls back
     * to fetching from the server. Silently showing the wrong photo is much
     * worse than a slow load.
     */
    private val byKey = HashMap<String, Local>()

    fun key(name: String, size: Long) = "$name/$size"

    fun scan(): List<Local> {
        val out = ArrayList<Local>()
        byKey.clear()

        val projection = arrayOf(
            MediaStore.MediaColumns._ID,
            MediaStore.MediaColumns.DISPLAY_NAME,
            MediaStore.MediaColumns.SIZE,
            MediaStore.MediaColumns.DATE_TAKEN,
            MediaStore.MediaColumns.DATA,
            MediaStore.MediaColumns.WIDTH,
            MediaStore.MediaColumns.HEIGHT,
        )

        for ((collection, isImage) in listOf(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI to true,
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI to false,
        )) {
            runCatching {
                context.contentResolver.query(
                    collection, projection,
                    // Camera only. Screenshots, downloads and messaging media
                    // are not backed up, so they must not appear here either.
                    //
                    // Android's own renamed files are excluded outright: a
                    // .trashed- copy is something the user already deleted, and
                    // its prefixed name never matches the server record, so
                    // leaving it in would have every deleted photo reappear as
                    // "not backed up yet".
                    "${MediaStore.MediaColumns.DATA} LIKE ?" +
                        " AND ${MediaStore.MediaColumns.DISPLAY_NAME} NOT LIKE '.trashed-%'" +
                        " AND ${MediaStore.MediaColumns.DISPLAY_NAME} NOT LIKE '.pending-%'",
                    arrayOf("%/DCIM/%"),
                    "${MediaStore.MediaColumns.DATE_TAKEN} DESC"
                )?.use { c ->
                    val idc = c.getColumnIndexOrThrow(MediaStore.MediaColumns._ID)
                    val namec = c.getColumnIndexOrThrow(MediaStore.MediaColumns.DISPLAY_NAME)
                    val sizec = c.getColumnIndexOrThrow(MediaStore.MediaColumns.SIZE)
                    val datec = c.getColumnIndexOrThrow(MediaStore.MediaColumns.DATE_TAKEN)
                    val pathc = c.getColumnIndex(MediaStore.MediaColumns.DATA)
                    val wc = c.getColumnIndex(MediaStore.MediaColumns.WIDTH)
                    val hc = c.getColumnIndex(MediaStore.MediaColumns.HEIGHT)
                    while (c.moveToNext()) {
                        val id = c.getLong(idc)
                        val local = Local(
                            uri = ContentUris.withAppendedId(collection, id),
                            id = id,
                            name = c.getString(namec) ?: continue,
                            size = c.getLong(sizec),
                            dateTaken = c.getLong(datec),
                            path = if (pathc >= 0) c.getString(pathc) else null,
                            width = if (wc >= 0 && !c.isNull(wc)) c.getInt(wc) else null,
                            height = if (hc >= 0 && !c.isNull(hc)) c.getInt(hc) else null,
                            isVideo = !isImage,
                        )
                        out.add(local)
                        byKey[key(local.name, local.size)] = local
                    }
                }
            }
        }
        return out
    }

    /** The local file for a server record, or null if it is server-only. */
    fun match(item: MediaItem): Uri? = byKey[key(item.name, item.size)]?.uri

    /** The same match, with the phone-side path and size for the details sheet. */
    fun info(item: MediaItem): Local? = byKey[key(item.name, item.size)]

    fun matchAll(items: List<MediaItem>): Map<Long, Uri> {
        val out = HashMap<Long, Uri>()
        items.forEach { m -> byKey[key(m.name, m.size)]?.let { out[m.id] = it.uri } }
        return out
    }

    /** Local files the server has never seen - "not backed up yet". */
    fun notBackedUp(serverItems: List<MediaItem>): List<Local> {
        val known = serverItems.map { key(it.name, it.size) }.toHashSet()
        return byKey.values.filter { key(it.name, it.size) !in known }
    }

    /**
     * Full SHA-256 of a local file.
     *
     * Only used before deleting an original off the phone. That decision is
     * never made on a filename or a size - it is made on the actual bytes.
     */
    fun sha256(uri: Uri): String? = runCatching {
        val digest = MessageDigest.getInstance("SHA-256")
        context.contentResolver.openInputStream(uri)!!.use { input ->
            val buf = ByteArray(1 shl 20)
            while (true) {
                val n = input.read(buf)
                if (n <= 0) break
                digest.update(buf, 0, n)
            }
        }
        digest.digest().joinToString("") { "%02x".format(it) }
    }.getOrNull()

    fun uriFor(name: String, size: Long): Uri? = byKey[key(name, size)]?.uri

    fun localsFor(ids: List<Pair<String, Long>>): List<Uri> =
        ids.mapNotNull { byKey[key(it.first, it.second)]?.uri }

    fun totalBytes(): Long = byKey.values.sumOf { it.size }

    fun exists(uri: Uri): Boolean = runCatching {
        context.contentResolver.openAssetFileDescriptor(uri, "r")?.use { true } ?: false
    }.getOrDefault(false)

    companion object {
        /** Permissions differ before and after Android 13. */
        fun requiredPermissions(): Array<String> =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
                arrayOf(
                    android.Manifest.permission.READ_MEDIA_IMAGES,
                    android.Manifest.permission.READ_MEDIA_VIDEO,
                )
            else
                arrayOf(android.Manifest.permission.READ_EXTERNAL_STORAGE)
    }
}
