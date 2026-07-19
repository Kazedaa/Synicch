"""Walk the backup folder and record what is there.

This stage deliberately does no image decoding, no thumbnailing and no junk
detection -- it exists to establish ground truth about the library cheaply, and
to be safely re-runnable at any moment.

Nothing here writes to CAMERA_BACKUP. It is read-only to this module.
"""
from __future__ import annotations

import subprocess
import time
from pathlib import Path
from typing import Iterator

from . import config, db
from .fingerprint import quick_fingerprint
from .timestamps import now_utc_iso, resolve

COMMIT_BATCH = 200


def classify(path: Path) -> tuple[str, str]:
    ext = path.suffix.lower()
    if ext in config.PHOTO_EXTS:
        return "photo", ext
    if ext in config.VIDEO_EXTS:
        return "video", ext
    return "other", ext


def should_skip(path: Path, root: Path) -> bool:
    for part in path.relative_to(root).parts:
        if part in config.SKIP_NAMES:
            return True
        if part.startswith(config.SKIP_PREFIXES):
            return True
    if path.name in config.SKIP_NAMES or path.name.startswith(config.SKIP_PREFIXES):
        return True
    # An in-progress Android write. The file is incomplete, so its size,
    # fingerprint and dimensions would all be wrong. It reappears under its real
    # name once finished.
    return path.name.startswith(config.PENDING_PREFIX)


def walk(root: Path) -> Iterator[Path]:
    for p in root.rglob("*"):
        if p.is_file() and not should_skip(p, root):
            yield p


def photo_dimensions(path: Path) -> tuple[int | None, int | None]:
    """Header-only read -- Pillow does not decode pixels just to report size."""
    try:
        from PIL import Image
        with Image.open(path) as img:
            return img.size
    except Exception:
        return None, None


def video_metadata(path: Path) -> tuple[int | None, int | None, float | None]:
    """Dimensions and duration via ffprobe."""
    try:
        out = subprocess.run(
            ["ffprobe", "-v", "error", "-select_streams", "v:0",
             "-show_entries", "stream=width,height:format=duration",
             "-of", "default=noprint_wrappers=1:nokey=0", str(path)],
            capture_output=True, text=True, timeout=30,
        ).stdout
        vals: dict[str, str] = {}
        for line in out.splitlines():
            if "=" in line:
                k, v = line.split("=", 1)
                vals[k.strip()] = v.strip()
        w = int(vals["width"]) if vals.get("width", "N/A").isdigit() else None
        h = int(vals["height"]) if vals.get("height", "N/A").isdigit() else None
        try:
            dur = float(vals.get("duration", ""))
        except ValueError:
            dur = None
        return w, h, dur
    except Exception:
        return None, None, None


def index(conn, *, root: Path | None = None, verbose: bool = True,
          refresh: bool = False) -> dict:
    """Bring the database in line with what is on disk.

    Idempotent: a file whose size and mtime are unchanged is skipped without
    being read, so re-running costs almost nothing.
    """
    root = root or config.CAMERA_BACKUP
    if not root.is_dir():
        raise SystemExit(f"backup folder not found: {root}")

    tz_name = db.get_setting(conn, "display_timezone")
    started = now_utc_iso()
    t0 = time.monotonic()

    cur = conn.execute(
        "INSERT INTO scan_runs(started_at, mode) VALUES(?, ?)",
        (started, "index-refresh" if refresh else "index"),
    )
    run_id = cur.lastrowid

    known = {
        r["rel_path"]: (r["id"], r["size"], r["mtime"])
        for r in conn.execute("SELECT id, rel_path, size, mtime FROM files")
    }

    seen = added = updated = errors = skipped = 0
    bytes_read = 0
    seen_paths: set[str] = set()

    conn.execute("BEGIN")
    try:
        for path in walk(root):
            seen += 1
            rel = str(path.relative_to(root))
            seen_paths.add(rel)

            try:
                st = path.stat()
            except OSError as e:
                errors += 1
                if verbose:
                    print(f"  ! stat failed {rel}: {e}")
                continue

            prior = known.get(rel)
            if prior and not refresh and prior[1] == st.st_size and abs(prior[2] - st.st_mtime) < 0.001:
                skipped += 1
                conn.execute("UPDATE files SET last_scanned=? WHERE id=?",
                             (now_utc_iso(), prior[0]))
            else:
                display_name, phone_trashed = config.strip_android_prefix(path.name)
                kind, ext = classify(Path(display_name))
                width = height = None
                duration = None
                err = None

                try:
                    fp = quick_fingerprint(path, st.st_size)
                    bytes_read += min(st.st_size, config.FINGERPRINT_CHUNK * 2)

                    if kind == "photo":
                        width, height = photo_dimensions(path)
                    elif kind == "video":
                        width, height, duration = video_metadata(path)

                    ts = resolve(path, tz_name=tz_name, stat_mtime=st.st_mtime,
                                 name=display_name)
                    local, offset, utc, source = ts.as_row()
                except Exception as e:  # never let one bad file stop a scan
                    errors += 1
                    err = f"{type(e).__name__}: {e}"
                    fp = None
                    local = offset = utc = source = None
                    if verbose:
                        print(f"  ! {rel}: {err}")

                if prior:
                    conn.execute(
                        """UPDATE files SET size=?, mtime=?, quick_fp=?, kind=?, ext=?,
                               width=?, height=?, duration_s=?,
                               captured_local=?, captured_offset=?, captured_utc=?,
                               ts_source=?, display_name=?, phone_trashed=?,
                               last_scanned=?, scan_error=?
                           WHERE id=?""",
                        (st.st_size, st.st_mtime, fp, kind, ext, width, height,
                         duration, local, offset, utc, source,
                         display_name, int(phone_trashed),
                         now_utc_iso(), err, prior[0]),
                    )
                    updated += 1
                else:
                    conn.execute(
                        """INSERT INTO files(rel_path, size, mtime, quick_fp, kind, ext,
                               width, height, duration_s,
                               captured_local, captured_offset, captured_utc, ts_source,
                               display_name, phone_trashed,
                               state, first_seen, last_scanned, scan_error)
                           VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?, 'active', ?, ?, ?)""",
                        (rel, st.st_size, st.st_mtime, fp, kind, ext, width, height,
                         duration, local, offset, utc, source,
                         display_name, int(phone_trashed),
                         now_utc_iso(), now_utc_iso(), err),
                    )
                    added += 1

            if seen % COMMIT_BATCH == 0:
                conn.execute("COMMIT")
                conn.execute("BEGIN")
                if verbose:
                    rate = seen / max(time.monotonic() - t0, 0.001)
                    print(f"  {seen:6d} files  ({rate:5.0f}/s)  "
                          f"+{added} new, {updated} changed, {skipped} unchanged")

        conn.execute("COMMIT")
    except BaseException:
        conn.execute("ROLLBACK")
        raise

    # Files the database knows about that are no longer on disk. Under the
    # keep-forever model these are photos deleted from the phone; the library
    # hardlink is what keeps them alive. Counted here, acted on once the
    # library tree exists.
    missing = sorted(set(known) - seen_paths)

    elapsed = time.monotonic() - t0
    conn.execute(
        """UPDATE scan_runs SET finished_at=?, files_seen=?, files_added=?,
               files_updated=?, files_missing=?, errors=?, notes=?
           WHERE id=?""",
        (now_utc_iso(), seen, added, updated, len(missing), errors,
         f"{elapsed:.1f}s, {bytes_read/1e6:.0f}MB read, {skipped} unchanged", run_id),
    )

    return {
        "seen": seen, "added": added, "updated": updated, "skipped": skipped,
        "missing": missing, "errors": errors,
        "elapsed": elapsed, "bytes_read": bytes_read,
    }
