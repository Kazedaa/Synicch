package com.synicch.ui

import com.synicch.data.MediaItem

/**
 * Justified row layout, the Google Photos arrangement.
 *
 * Each row is scaled so it fills the width exactly while every photo keeps its
 * own shape. Rows are computed from stored aspect ratios, which means the whole
 * page can be laid out before a single image has loaded - no reflow as they
 * arrive, and correct scroll height for content not yet rendered.
 */
object Grid {

    /**
     * How tall a row of photos should aim to be, **in dp**.
     *
     * Density units, not pixels: the same number has to mean the same physical
     * size on every screen. Feeding raw pixels into the layout is what made the
     * grid come out roughly a third of its intended size on a modern phone.
     *
     * Day is sized so a screenful of portrait phone photos lands at three per
     * row, which is what a gallery is expected to look like. Month and year are
     * overviews - the point there is covering ground, not seeing detail.
     */
    enum class Zoom(val targetHeightDp: Int, val label: String) {
        DAY(180, "Day"),
        MONTH(110, "Month"),
        YEAR(64, "Year");

        fun zoomIn() = when (this) {
            YEAR -> MONTH; MONTH -> DAY; DAY -> DAY
        }

        fun zoomOut() = when (this) {
            DAY -> MONTH; MONTH -> YEAR; YEAR -> YEAR
        }
    }

    data class Row(val items: List<MediaItem>, val height: Float)

    sealed interface Entry {
        /** [ids] is everything under this heading, so a day can be selected in one tap. */
        data class Header(val title: String, val key: String, val ids: List<Long>,
                          val section: String) : Entry
        data class Photos(val row: Row, val key: String) : Entry
    }

    /**
     * Greedy fill: keep adding photos until the row would be shorter than the
     * target, then lock it in at whatever height makes it fit the width exactly.
     */
    fun justify(items: List<MediaItem>, width: Float, targetHeight: Float,
                spacing: Float): List<Row> {
        if (items.isEmpty() || width <= 0f) return emptyList()
        val rows = ArrayList<Row>()
        val current = ArrayList<MediaItem>()
        var aspectSum = 0f

        for (item in items) {
            current.add(item)
            aspectSum += item.aspect.coerceIn(0.2f, 5f)
            val available = width - spacing * (current.size - 1)
            val height = available / aspectSum
            if (height <= targetHeight) {
                rows.add(Row(ArrayList(current), height))
                current.clear()
                aspectSum = 0f
            }
        }
        if (current.isNotEmpty()) {
            val available = width - spacing * (current.size - 1)
            // A trailing row is left at target height rather than stretched, so
            // three holiday photos do not end up enormous.
            rows.add(Row(ArrayList(current), minOf(targetHeight, available / aspectSum)))
        }
        return rows
    }

    /** Section title for an item at a given zoom level. */
    private fun sectionOf(item: MediaItem, zoom: Zoom): String {
        val d = item.captured ?: return "Undated"
        return when (zoom) {
            Zoom.DAY -> d.take(10)
            Zoom.MONTH -> d.take(7)
            Zoom.YEAR -> d.take(4)
        }
    }

    private val MONTHS = arrayOf("January", "February", "March", "April", "May", "June",
        "July", "August", "September", "October", "November", "December")

    fun prettySection(key: String): String = when (key.length) {
        10 -> runCatching {
            val (y, m, d) = key.split("-")
            "${d.toInt()} ${MONTHS[m.toInt() - 1]} $y"
        }.getOrDefault(key)
        7 -> runCatching {
            val (y, m) = key.split("-")
            "${MONTHS[m.toInt() - 1]} $y"
        }.getOrDefault(key)
        4 -> key
        else -> key
    }

    /**
     * Flattened header/row list for a LazyColumn.
     *
     * Flattening rather than nesting is what gives virtualisation: sections
     * scrolled far off screen are torn down and rebuilt on the way back, which
     * a large library needs or the app grinds to a halt.
     */
    fun build(items: List<MediaItem>, width: Float, zoom: Zoom,
              spacing: Float, targetHeight: Float): List<Entry> {
        if (items.isEmpty()) return emptyList()
        val out = ArrayList<Entry>()
        var sectionKey: String? = null
        var bucket = ArrayList<MediaItem>()

        fun flush() {
            if (bucket.isEmpty()) return
            val key = sectionKey!!
            out.add(Entry.Header(prettySection(key), "h_$key", bucket.map { it.id }, key))
            justify(bucket, width, targetHeight, spacing)
                .forEachIndexed { i, row -> out.add(Entry.Photos(row, "r_${key}_$i")) }
            bucket = ArrayList()
        }

        for (item in items) {
            val key = sectionOf(item, zoom)
            if (key != sectionKey) {
                flush()
                sectionKey = key
            }
            bucket.add(item)
        }
        flush()
        return out
    }
}
