"""Paths and runtime settings.

Paths can be overridden with SYNICCH_* environment variables so the whole thing
can be exercised on a dev machine without touching the server.
"""
from __future__ import annotations

import os
from pathlib import Path

# The Syncthing target. Read-only to Synicch except for .stignore and the
# permanent-purge path, which appends to .stignore before unlinking. Nothing
# else in this codebase may write here.
CAMERA_BACKUP = Path(os.environ.get(
    "SYNICCH_CAMERA_BACKUP", "/var/lib/filebrowser/data/camera-backup"))

# Everything Synicch owns. Must be on the same filesystem as CAMERA_BACKUP --
# album folders are hardlinks, and hardlinks cannot cross filesystems.
HOME = Path(os.environ.get("SYNICCH_HOME", "/var/lib/synicch"))

DB_PATH = HOME / "synicch.db"
LIBRARY = HOME / "library"
CACHE = HOME / "cache"
THUMBS = CACHE / "thumbs"
PREVIEWS = CACHE / "preview"
REPORTS = HOME / "reports"

UNSORTED_DIR = "_unsorted"
TRASH_DIR = "_trash"

# Files Syncthing manages or leaves behind; never index these.
SKIP_NAMES = {".stfolder", ".stignore", ".stversions", ".stglobalignore"}
SKIP_PREFIXES = (".syncthing.", "~syncthing~")


PHOTO_EXTS = {
    ".jpg", ".jpeg", ".png", ".heic", ".heif", ".webp",
    ".gif", ".bmp", ".tif", ".tiff", ".dng",
}
VIDEO_EXTS = {
    ".mp4", ".mov", ".mkv", ".3gp", ".avi", ".webm", ".m4v", ".mts",
}

# Cheap dedup candidate: file size plus this many bytes from each end. Reading
# 128KB per file instead of the whole thing is the difference between 2.5GB and
# 70GB of disk reads on a full library pass.
FINGERPRINT_CHUNK = 64 * 1024

# Timestamps outside this range are not trusted -- WhatsApp media and broken
# downloads produce 1970 and 2036 dates constantly.
MIN_PLAUSIBLE_YEAR = 2005

DEFAULT_SETTINGS = {
    "display_timezone": "Asia/Kolkata",
    "scan_hour_local": "3",
    "trash_retention_days": "30",
    "freeup_cooling_off_days": "7",
    # Calibrate against the real camera once detection exists -- this default is
    # not universal and is expected to move.
    "blur_threshold": "100.0",
    "short_video_max_s": "1.5",
    "schema_note": "settings are strings; callers cast",
}


def ensure_dirs() -> None:
    for d in (HOME, LIBRARY, CACHE, THUMBS, PREVIEWS, REPORTS,
              LIBRARY / UNSORTED_DIR, LIBRARY / TRASH_DIR):
        d.mkdir(parents=True, exist_ok=True)
