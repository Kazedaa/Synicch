-- Synicch schema.
--
-- Design note: this database owns *decisions* (album membership, trash state,
-- dismissals). Derived data (fingerprints, scores, thumbnails) is a cache that
-- can always be recomputed by rescanning. Keep that split intact -- it is what
-- makes a corrupt database an annoyance rather than a data-loss event.

PRAGMA journal_mode = WAL;
PRAGMA foreign_keys = ON;

-- ---------------------------------------------------------------- settings --

CREATE TABLE IF NOT EXISTS settings (
    key   TEXT PRIMARY KEY,
    value TEXT NOT NULL
);

-- ------------------------------------------------------------------- files --

CREATE TABLE IF NOT EXISTS files (
    id            INTEGER PRIMARY KEY,
    source_id     INTEGER NOT NULL DEFAULT 1,   -- reserved: second device later
    rel_path      TEXT    NOT NULL UNIQUE,      -- relative to camera-backup/
    size          INTEGER NOT NULL,
    mtime         REAL    NOT NULL,

    quick_fp      TEXT,        -- size + 64KB from each end; cheap dedup candidate
    sha256        TEXT,        -- full hash, computed lazily and only when it matters

    kind          TEXT    NOT NULL,             -- photo | video | other
    ext           TEXT,
    width         INTEGER,    -- after EXIF rotation, so the gallery lays out right
    height        INTEGER,
    duration_s    REAL,                         -- video only
    codec         TEXT,       -- h264 plays everywhere; hevc does not
    thumb_at      TEXT,       -- when the grid tile was generated

    -- Timestamps. Cameras write local time with no zone,
    -- so the naive value and the resolved instant are stored separately and the
    -- source is always recorded so trust is visible.
    captured_local  TEXT,      -- naive ISO, exactly what the camera wrote
    captured_offset TEXT,      -- '+05:30' when the file actually carried one
    captured_utc    TEXT,      -- resolved instant, used for all sorting
    ts_source       TEXT,      -- exif_with_offset | exif | filename | mtime

    -- Camera settings. Display only -- nothing here changes what the library
    -- does, and most non-camera files carry none of it.
    camera_make   TEXT,
    camera_model  TEXT,
    f_number      REAL,
    exposure_s    REAL,
    focal_mm      REAL,
    iso           INTEGER,

    -- The keeper hardlink, relative to library/. Once this exists the file
    -- survives leaving the phone: camera-backup follows the phone, this does
    -- not. Everything else under library/ is a rebuildable view.
    archive_path  TEXT,

    state         TEXT    NOT NULL DEFAULT 'active',   -- active | trashed | purged
    trashed_at    TEXT,

    -- Filename with Android's .trashed-/.pending- prefix removed, for display
    -- and for filename-based timestamp parsing.
    display_name  TEXT,
    -- Deleted on the phone, still inside Android's ~30 day grace period. Kept
    -- (keep-forever) but held out of the main timeline.
    phone_trashed INTEGER NOT NULL DEFAULT 0,

    first_seen    TEXT    NOT NULL,
    last_scanned  TEXT    NOT NULL,
    scan_error    TEXT
);

CREATE INDEX IF NOT EXISTS idx_files_captured  ON files(captured_utc DESC);
CREATE INDEX IF NOT EXISTS idx_files_state     ON files(state);
CREATE INDEX IF NOT EXISTS idx_files_quick_fp  ON files(quick_fp);
CREATE INDEX IF NOT EXISTS idx_files_kind      ON files(kind);
CREATE INDEX IF NOT EXISTS idx_files_ptrash    ON files(phone_trashed);
CREATE INDEX IF NOT EXISTS idx_files_thumb     ON files(thumb_at);

-- ------------------------------------------------------------------ albums --

CREATE TABLE IF NOT EXISTS albums (
    id            INTEGER PRIMARY KEY,
    name          TEXT    NOT NULL UNIQUE,
    created_at    TEXT    NOT NULL,
    cover_file_id INTEGER REFERENCES files(id) ON DELETE SET NULL,
    sort_order    INTEGER NOT NULL DEFAULT 0
);

-- Many-to-many on purpose: a photo can be in several albums, and because
-- album folders are hardlinks this costs no extra disk space.
CREATE TABLE IF NOT EXISTS album_members (
    album_id  INTEGER NOT NULL REFERENCES albums(id) ON DELETE CASCADE,
    file_id   INTEGER NOT NULL REFERENCES files(id)  ON DELETE CASCADE,
    added_at  TEXT    NOT NULL,
    source    TEXT    NOT NULL,        -- session | manual
    PRIMARY KEY (album_id, file_id)
);

CREATE INDEX IF NOT EXISTS idx_members_file ON album_members(file_id);

-- The "record this trip" toggle. Membership is decided by comparing a photo's
-- capture time against these windows, which is why the app does not need to be
-- running during the trip.
CREATE TABLE IF NOT EXISTS sessions (
    id            INTEGER PRIMARY KEY,
    album_id      INTEGER NOT NULL REFERENCES albums(id) ON DELETE CASCADE,
    started_local TEXT    NOT NULL,
    started_utc   TEXT    NOT NULL,
    ended_local   TEXT,
    ended_utc     TEXT,
    active        INTEGER NOT NULL DEFAULT 1,
    created_at    TEXT    NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_sessions_active ON sessions(active);

-- ------------------------------------------------------- detection results --

-- algo_version lets a threshold change invalidate cleanly, and storing raw
-- scores (not just pass/fail) means retuning is a query rather than a rescan.
CREATE TABLE IF NOT EXISTS scores (
    file_id       INTEGER PRIMARY KEY REFERENCES files(id) ON DELETE CASCADE,
    algo_version  INTEGER NOT NULL,
    laplacian_var REAL,
    luma_mean     REAL,
    luma_std      REAL,
    clipped_frac  REAL,
    dhash         TEXT,
    black_frac    REAL,
    freeze_frac   REAL,
    computed_at   TEXT NOT NULL
);

CREATE TABLE IF NOT EXISTS flags (
    id           INTEGER PRIMARY KEY,
    file_id      INTEGER NOT NULL REFERENCES files(id) ON DELETE CASCADE,
    reason       TEXT    NOT NULL,   -- short_video | blank | blurry | dup_exact | ...
    severity     REAL,
    created_at   TEXT    NOT NULL,
    dismissed_at TEXT,
    UNIQUE (file_id, reason)
);

CREATE INDEX IF NOT EXISTS idx_flags_reason ON flags(reason, dismissed_at);

CREATE TABLE IF NOT EXISTS dup_groups (
    id            INTEGER PRIMARY KEY,
    kind          TEXT    NOT NULL,   -- exact | near
    keeper_file_id INTEGER REFERENCES files(id) ON DELETE SET NULL,
    created_at    TEXT    NOT NULL
);

CREATE TABLE IF NOT EXISTS dup_members (
    group_id INTEGER NOT NULL REFERENCES dup_groups(id) ON DELETE CASCADE,
    file_id  INTEGER NOT NULL REFERENCES files(id) ON DELETE CASCADE,
    PRIMARY KEY (group_id, file_id)
);

-- ------------------------------------------------------------------ edits ---

-- Crop and rotation are stored as numbers and applied at render time. The
-- original file is never modified, so an edit costs no storage, cannot corrupt
-- anything, and reverting is just deleting the row.
CREATE TABLE IF NOT EXISTS edits (
    file_id    INTEGER PRIMARY KEY REFERENCES files(id) ON DELETE CASCADE,
    rotate     INTEGER NOT NULL DEFAULT 0,   -- 0 | 90 | 180 | 270, clockwise
    crop_x     REAL,                          -- all four are fractions of the
    crop_y     REAL,                          -- image, so they survive the
    crop_w     REAL,                          -- source being re-encoded at a
    crop_h     REAL,                          -- different resolution
    updated_at TEXT NOT NULL
);

-- ------------------------------------------------------------------- auth ---

-- Only the hash is stored: a leaked database does not hand out access, and any
-- single device can be revoked without disturbing the others.
CREATE TABLE IF NOT EXISTS api_tokens (
    id         INTEGER PRIMARY KEY,
    name       TEXT NOT NULL,
    token_hash TEXT NOT NULL UNIQUE,
    created_at TEXT NOT NULL,
    last_used  TEXT,
    revoked_at TEXT
);

-- ------------------------------------------------------------------ audit ---

-- Append-only record of anything that touched bytes on disk. This is the paper
-- trail for the one code path that can destroy data.
CREATE TABLE IF NOT EXISTS audit (
    id        INTEGER PRIMARY KEY,
    at        TEXT NOT NULL,
    action    TEXT NOT NULL,
    file_id   INTEGER,
    rel_path  TEXT,
    detail    TEXT
);

CREATE TABLE IF NOT EXISTS scan_runs (
    id           INTEGER PRIMARY KEY,
    started_at   TEXT NOT NULL,
    finished_at  TEXT,
    mode         TEXT,
    files_seen   INTEGER DEFAULT 0,
    files_added  INTEGER DEFAULT 0,
    files_updated INTEGER DEFAULT 0,
    files_missing INTEGER DEFAULT 0,
    errors       INTEGER DEFAULT 0,
    notes        TEXT
);
