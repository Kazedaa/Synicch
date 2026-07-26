package com.synicch.data

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

/**
 * Local metadata cache.
 *
 * Plain SQLite rather than Room: the schema is one table and a couple of lookup
 * tables, and avoiding an annotation processor keeps the build simple - which
 * matters more here than the ergonomics would.
 *
 * The point of this cache is that the gallery must browse at full speed with
 * the network completely down. Tailscale over mobile data is not dependable, so
 * "offline" is the assumed state rather than an error case.
 */
class Cache(context: Context) : SQLiteOpenHelper(context, "synicch.db", null, 1) {

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("""
            CREATE TABLE media(
              id INTEGER PRIMARY KEY, name TEXT, kind TEXT,
              w INTEGER, h INTEGER, size INTEGER,
              captured TEXT, captured_utc TEXT, duration REAL, codec TEXT,
              state TEXT, phone_trashed INTEGER, has_thumb INTEGER,
              fp TEXT, albums TEXT,
              camera TEXT, f_number REAL, exposure REAL, focal REAL, iso INTEGER,
              path TEXT
            )""")
        db.execSQL("CREATE INDEX idx_captured ON media(captured_utc DESC)")
        db.execSQL("CREATE TABLE albums(id INTEGER PRIMARY KEY, name TEXT, count INTEGER, recording INTEGER, cover INTEGER)")
        db.execSQL("CREATE TABLE meta(k TEXT PRIMARY KEY, v TEXT)")
    }

    override fun onUpgrade(db: SQLiteDatabase, old: Int, new: Int) {
        db.execSQL("DROP TABLE IF EXISTS media")
        db.execSQL("DROP TABLE IF EXISTS albums")
        db.execSQL("DROP TABLE IF EXISTS meta")
        onCreate(db)
    }

    fun putMedia(items: List<MediaItem>) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            items.forEach { m ->
                db.insertWithOnConflict("media", null, ContentValues().apply {
                    put("id", m.id); put("name", m.name); put("kind", m.kind)
                    put("w", m.w); put("h", m.h); put("size", m.size)
                    put("captured", m.captured); put("captured_utc", m.capturedUtc)
                    put("duration", m.duration); put("codec", m.codec)
                    put("camera", m.camera); put("f_number", m.fNumber)
                    put("exposure", m.exposure); put("focal", m.focal)
                    put("iso", m.iso); put("path", m.path)
                    put("state", m.state)
                    put("phone_trashed", if (m.phoneTrashed) 1 else 0)
                    put("has_thumb", if (m.hasThumb) 1 else 0)
                    put("fp", m.fp)
                    put("albums", m.albums.joinToString(","))
                }, SQLiteDatabase.CONFLICT_REPLACE)
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    fun media(album: Long? = null, unsorted: Boolean = false): List<MediaItem> {
        val where = StringBuilder("state='active' AND phone_trashed=0")
        if (album != null) where.append(" AND (',' || albums || ',') LIKE '%,$album,%'")
        if (unsorted) where.append(" AND (albums IS NULL OR albums='')")
        val out = ArrayList<MediaItem>()
        readableDatabase.rawQuery(
            "SELECT * FROM media WHERE $where ORDER BY captured_utc DESC", null
        ).use { c ->
            while (c.moveToNext()) out.add(c.toItem())
        }
        return out
    }

    fun removeMissing(keepIds: Set<Long>) {
        if (keepIds.isEmpty()) return
        writableDatabase.execSQL(
            "DELETE FROM media WHERE id NOT IN (${keepIds.joinToString(",")})")
    }

    fun putAlbums(list: List<Album>) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            db.delete("albums", null, null)
            list.forEach { a ->
                db.insert("albums", null, ContentValues().apply {
                    put("id", a.id); put("name", a.name); put("count", a.count)
                    put("recording", if (a.recording) 1 else 0); put("cover", a.cover)
                })
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    fun albums(): List<Album> {
        val out = ArrayList<Album>()
        readableDatabase.rawQuery("SELECT * FROM albums ORDER BY name", null).use { c ->
            while (c.moveToNext()) out.add(Album(
                id = c.getLong(0), name = c.getString(1), count = c.getInt(2),
                recording = c.getInt(3) == 1,
                cover = if (c.isNull(4)) null else c.getLong(4)))
        }
        return out
    }

    fun meta(key: String): String? =
        readableDatabase.rawQuery("SELECT v FROM meta WHERE k=?", arrayOf(key)).use {
            if (it.moveToFirst()) it.getString(0) else null
        }

    fun setMeta(key: String, value: String) {
        writableDatabase.insertWithOnConflict("meta", null, ContentValues().apply {
            put("k", key); put("v", value)
        }, SQLiteDatabase.CONFLICT_REPLACE)
    }

    fun count(): Int = readableDatabase.rawQuery(
        "SELECT COUNT(*) FROM media WHERE state='active' AND phone_trashed=0", null
    ).use { if (it.moveToFirst()) it.getInt(0) else 0 }

    fun clear() {
        writableDatabase.apply {
            execSQL("DELETE FROM media"); execSQL("DELETE FROM albums"); execSQL("DELETE FROM meta")
        }
    }

    private fun android.database.Cursor.toItem(): MediaItem {
        fun s(n: String) = getColumnIndex(n).let { if (it < 0 || isNull(it)) null else getString(it) }
        fun i(n: String) = getColumnIndex(n).let { if (it < 0 || isNull(it)) null else getInt(it) }
        fun l(n: String) = getColumnIndex(n).let { if (it < 0 || isNull(it)) 0L else getLong(it) }
        fun d(n: String) = getColumnIndex(n).let { if (it < 0 || isNull(it)) null else getDouble(it) }
        return MediaItem(
            id = l("id"), name = s("name") ?: "", kind = s("kind") ?: "photo",
            w = i("w"), h = i("h"), size = l("size"),
            captured = s("captured"), capturedUtc = s("captured_utc"),
            duration = d("duration"), codec = s("codec"),
            camera = s("camera"), fNumber = d("f_number"),
            exposure = d("exposure"), focal = d("focal"),
            iso = i("iso"), path = s("path"),
            state = s("state") ?: "active",
            phoneTrashed = (i("phone_trashed") ?: 0) == 1,
            hasThumb = (i("has_thumb") ?: 0) == 1,
            fp = s("fp"),
            albums = s("albums")?.split(",")?.filter { it.isNotBlank() }?.map { it.toLong() }
                ?: emptyList(),
        )
    }
}
