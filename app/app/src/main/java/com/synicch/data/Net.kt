package com.synicch.data

import android.content.Context
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

/**
 * API client.
 *
 * Every call assumes the network may simply not be there. Tailscale over mobile
 * data is not reliable, so callers get a [Result] and the repository falls back
 * to the local cache rather than showing an error screen.
 */
class Api(private val context: Context) {

    /**
     * Addresses to try, in order. The hostname first, then direct IPs.
     *
     * A hostname is only as reliable as the resolver behind it. If the local
     * network hands out a public DNS server alongside the one that knows about
     * `.ngserver`, lookups fail roughly half the time with an authoritative
     * "does not exist" - so the app keeps direct addresses as a fallback and
     * stops depending on DNS being correct.
     */
    var addresses: List<String> = emptyList()
    var token: String = ""

    /** The address that last worked, tried first next time. */
    @Volatile private var working: String? = null

    var baseUrl: String
        get() = working ?: addresses.firstOrNull() ?: ""
        set(value) { addresses = listOf(value); working = null }

    private val json = Json { ignoreUnknownKeys = true; explicitNulls = false }

    private val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            // Short connect timeout so an unreachable server falls back to the
            // cache quickly instead of leaving the UI spinning.
            .connectTimeout(6, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .callTimeout(120, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .build()
    }

    val configured: Boolean get() = addresses.isNotEmpty() && token.isNotBlank()

    /** Working address first, then the rest, so the common case costs nothing. */
    private fun candidates(): List<String> {
        val w = working
        return if (w == null) addresses else listOf(w) + addresses.filter { it != w }
    }

    private fun url(base: String, path: String, params: Map<String, String?>) =
        base.trimEnd('/').plus(path).toHttpUrl().newBuilder().apply {
            params.forEach { (k, v) -> if (v != null) addQueryParameter(k, v) }
        }.build()

    private fun req(base: String, path: String, params: Map<String, String?>) =
        Request.Builder().url(url(base, path, params))
            .header("Authorization", "Bearer $token")

    /**
     * Runs a request against each address until one answers.
     *
     * Only connection-level failures move on to the next address. An HTTP error
     * means the server was reached and said no, so trying another address would
     * just repeat the same answer more slowly.
     */
    private fun <T> attempt(
        path: String,
        params: Map<String, String?> = emptyMap(),
        build: (Request.Builder) -> Request = { it.build() },
        parse: (String) -> T,
    ): Result<T> {
        var last: Throwable = IllegalStateException("no server address configured")
        for (base in candidates()) {
            try {
                val request = build(req(base, path, params))
                client.newCall(request).execute().use { resp ->
                    if (!resp.isSuccessful) {
                        working = base
                        return Result.failure(IllegalStateException("HTTP ${resp.code}"))
                    }
                    val parsed = parse(resp.body!!.string())
                    working = base
                    return Result.success(parsed)
                }
            } catch (e: Throwable) {
                last = e
            }
        }
        return Result.failure(last)
    }

    private inline fun <reified T> call(
        path: String,
        params: Map<String, String?> = emptyMap(),
        noinline build: (Request.Builder) -> Request = { it.build() },
    ): Result<T> = attempt(path, params, build) { json.decodeFromString<T>(it) }

    private fun callOk(
        path: String,
        params: Map<String, String?> = emptyMap(),
        build: (Request.Builder) -> Request = { it.build() },
    ): Result<Unit> = attempt(path, params, build) { }

    private fun body(obj: String) = obj.toRequestBody("application/json".toMediaType())

    // ------------------------------------------------------------ media --
    fun status(): Result<ServerStatus> = call("/api/status")

    fun media(cursor: String? = null, limit: Int = 200, album: Long? = null,
              unsorted: Boolean = false, since: String? = null): Result<MediaPage> =
        call("/api/media", mapOf(
            "cursor" to cursor,
            "limit" to limit.toString(),
            "album" to album?.toString(),
            "unsorted" to if (unsorted) "1" else null,
            "since" to since,
        ))

    /** Image URLs carry the token as a query parameter because Coil's image
     *  requests cannot conveniently set headers per-request. */
    fun thumbUrl(id: Long) = "$baseUrl/api/media/$id/thumb?token=$token"
    fun previewUrl(id: Long) = "$baseUrl/api/media/$id/preview?token=$token"
    fun originalUrl(id: Long) = "$baseUrl/api/media/$id/original?token=$token"

    // ----------------------------------------------------------- albums --

    fun albums(): Result<AlbumList> = call("/api/albums")

    fun createAlbum(name: String): Result<Album> =
        call("/api/albums") { it.post(body("""{"name":${name.q()}}""")).build() }

    fun renameAlbum(id: Long, name: String): Result<Unit> =
        callOk("/api/albums/$id") { it.patch(body("""{"name":${name.q()}}""")).build() }

    /** Which photo stands for the album. Same PATCH as a rename. */
    fun setAlbumCover(id: Long, fileId: Long): Result<Unit> =
        callOk("/api/albums/$id") { it.patch(body("""{"cover":$fileId}""")).build() }

    fun deleteAlbum(id: Long): Result<Unit> =
        callOk("/api/albums/$id") { it.delete().build() }

    fun addToAlbum(id: Long, ids: List<Long>): Result<Unit> =
        callOk("/api/albums/$id/members") { it.post(body("""{"ids":${ids.arr()}}""")).build() }

    fun removeFromAlbum(id: Long, ids: List<Long>): Result<Unit> =
        callOk("/api/albums/$id/members") { it.delete(body("""{"ids":${ids.arr()}}""")).build() }

    fun setRecording(id: Long, on: Boolean): Result<Unit> =
        callOk("/api/albums/$id/recording") { it.post(body("""{"on":$on}""")).build() }

    fun sessions(id: Long): Result<SessionList> = call("/api/albums/$id/sessions")

    /** The "I forgot to turn it on" path: add the window after the fact. */
    fun addSession(id: Long, start: String, end: String): Result<Unit> =
        callOk("/api/albums/$id/sessions") {
            it.post(body("""{"start":${start.q()},"end":${end.q()}}""")).build()
        }

    fun deleteSession(id: Long): Result<Unit> =
        callOk("/api/sessions/$id") { it.delete().build() }

    // ---------------------------------------------------------- cleanup --

    fun cleanupGroups(): Result<CleanupGroups> = call("/api/cleanup/groups")

    fun cleanupItems(reason: String): Result<CleanupItems> = call("/api/cleanup/$reason")

    fun dismiss(ids: List<Long>, reason: String?): Result<Unit> =
        callOk("/api/cleanup/dismiss") {
            it.post(body(
                """{"ids":${ids.arr()}${if (reason != null) ",\"reason\":${reason.q()}" else ""}}"""
            )).build()
        }

    fun detect(): Result<Unit> = callOk("/api/cleanup/detect") { it.post(body("{}")).build() }

    // ------------------------------------------------------------ trash --

    fun trash(): Result<TrashList> = call("/api/trash")

    fun moveToTrash(ids: List<Long>): Result<Unit> =
        callOk("/api/trash") { it.post(body("""{"ids":${ids.arr()}}""")).build() }

    fun restore(ids: List<Long>): Result<Unit> =
        callOk("/api/trash/restore") { it.post(body("""{"ids":${ids.arr()}}""")).build() }

    /** Permanent deletion. Requires force to bypass the retention period. */
    fun purge(ids: List<Long>? = null, force: Boolean = false): Result<PurgeResult> =
        call("/api/trash/purge") {
            it.post(body(
                """{${if (ids != null) "\"ids\":${ids.arr()}," else ""}"force":$force}"""
            )).build()
        }

    // ---------------------------------------------------------- free up --

    fun freeupCandidates(files: List<Pair<String, Long>>): Result<FreeupCandidates> {
        val arr = files.joinToString(",") { """{"name":${it.first.q()},"size":${it.second}}""" }
        return call("/api/freeup/candidates") { it.post(body("""{"files":[$arr]}""")).build() }
    }

    fun freeupVerify(files: List<Pair<Long, String>>): Result<FreeupVerified> {
        val arr = files.joinToString(",") { """{"id":${it.first},"sha256":${it.second.q()}}""" }
        return call("/api/freeup/verify") { it.post(body("""{"files":[$arr]}""")).build() }
    }

    // ------------------------------------------------------------ edits --

    fun getEdit(id: Long): Result<EditState> = call("/api/media/$id/edit")

    fun putEdit(id: Long, rotate: Int, crop: FloatArray?): Result<Unit> {
        val c = crop?.let {
            ""","crop_x":${it[0]},"crop_y":${it[1]},"crop_w":${it[2]},"crop_h":${it[3]}"""
        } ?: ""
        return callOk("/api/media/$id/edit") { it.put(body("""{"rotate":$rotate$c}""")).build() }
    }

    fun resetEdit(id: Long): Result<Unit> = callOk("/api/media/$id/edit") { it.delete().build() }

    fun triggerScan(): Result<Unit> = callOk("/api/scan") { it.post(body("{}")).build() }

    /**
     * Stream a file's original bytes into [sink].
     *
     * Its own loop rather than [attempt], which parses a whole response body
     * into a String - fine for JSON, ruinous for a 40MB video.
     */
    fun downloadOriginal(id: Long, sink: java.io.OutputStream): Result<Long> {
        var last: Throwable = IllegalStateException("no server address configured")
        for (base in candidates()) {
            try {
                client.newCall(req(base, "/api/media/$id/original", emptyMap()).build())
                    .execute().use { resp ->
                        if (!resp.isSuccessful) {
                            working = base
                            return Result.failure(IllegalStateException("HTTP ${resp.code}"))
                        }
                        val copied = resp.body!!.byteStream().use { it.copyTo(sink) }
                        working = base
                        return Result.success(copied)
                    }
            } catch (e: Throwable) {
                last = e
            }
        }
        return Result.failure(last)
    }

    /** Unauthenticated: lets the app check reachability before pairing. */
    fun ping(testUrl: String): Boolean = runCatching {
        client.newCall(Request.Builder().url("${testUrl.trimEnd('/')}/api/ping").build())
            .execute().use { it.isSuccessful }
    }.getOrDefault(false)
}

private fun String.q(): String =
    "\"" + replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n") + "\""

private fun List<Long>.arr(): String = joinToString(",", "[", "]")
