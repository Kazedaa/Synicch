"""Albums, the recording toggle, and the on-disk library tree.

Albums are entirely manual -- there is no date clustering. The only automatic
assignment is the recording toggle, and that works by comparing a photo's
capture time against a window, which is why the app does not need to be running,
online, or even reachable during a trip. Flip it on, go away for a week with no
signal, come home: it sorts correctly on the next scan.

The same property makes it work backwards. Forgetting to flip it on before
leaving is the common case, so setting the dates afterwards is a first-class
path rather than a workaround.
"""
from __future__ import annotations

import datetime as dt
import re
from pathlib import Path
from zoneinfo import ZoneInfo

from . import config, db
from .timestamps import now_utc_iso

_UNSAFE = re.compile(r'[<>:"/\\|?*\x00-\x1f]')


def safe_dirname(name: str) -> str:
    cleaned = _UNSAFE.sub("_", name).strip(". ")
    return cleaned[:120] or "album"


def to_utc(local_iso: str, tz_name: str) -> str:
    """Interpret a naive local timestamp from the app in the library timezone."""
    naive = dt.datetime.fromisoformat(local_iso.replace("Z", "").split("+")[0])
    aware = naive.replace(tzinfo=ZoneInfo(tz_name))
    return aware.astimezone(dt.timezone.utc).replace(microsecond=0).isoformat()


# ------------------------------------------------------------------ albums --

def create(conn, name: str) -> int:
    cur = conn.execute(
        "INSERT INTO albums(name, created_at) VALUES(?, ?)", (name, now_utc_iso()))
    return cur.lastrowid


def rename(conn, album_id: int, name: str) -> None:
    conn.execute("UPDATE albums SET name=? WHERE id=?", (name, album_id))


def delete(conn, album_id: int) -> None:
    """Removes the album only. Photos are never touched -- they fall back to
    Unsorted if this was their last album."""
    conn.execute("DELETE FROM albums WHERE id=?", (album_id,))


def add_members(conn, album_id: int, file_ids: list[int], source: str = "manual") -> int:
    now = now_utc_iso()
    n = 0
    for fid in file_ids:
        cur = conn.execute(
            "INSERT INTO album_members(album_id, file_id, added_at, source) "
            "VALUES(?,?,?,?) ON CONFLICT(album_id, file_id) DO NOTHING",
            (album_id, fid, now, source))
        n += cur.rowcount
    return n


def remove_members(conn, album_id: int, file_ids: list[int]) -> int:
    n = 0
    for fid in file_ids:
        cur = conn.execute(
            "DELETE FROM album_members WHERE album_id=? AND file_id=?", (album_id, fid))
        n += cur.rowcount
    return n


def listing(conn) -> list[dict]:
    rows = conn.execute("""
        SELECT a.id, a.name, a.created_at, a.cover_file_id,
               (SELECT COUNT(*) FROM album_members m
                  JOIN files f ON f.id = m.file_id
                 WHERE m.album_id = a.id AND f.state = 'active') AS count,
               (SELECT s.id FROM sessions s
                 WHERE s.album_id = a.id AND s.active = 1 LIMIT 1) AS recording_id,
               (SELECT m.file_id FROM album_members m
                  JOIN files f ON f.id = m.file_id
                 WHERE m.album_id = a.id AND f.state = 'active'
                 ORDER BY f.captured_utc DESC LIMIT 1) AS newest
        FROM albums a ORDER BY a.created_at DESC
    """).fetchall()
    return [{
        "id": r["id"], "name": r["name"], "created_at": r["created_at"],
        "count": r["count"], "recording": r["recording_id"] is not None,
        "cover": r["cover_file_id"] or r["newest"],
    } for r in rows]


# ---------------------------------------------------------------- sessions --

def start_recording(conn, album_id: int, started_local: str | None = None) -> int:
    """Only one album records at a time -- starting one stops the others.

    A toggle that silently switches itself off would be worse than one you
    forgot about, so there is deliberately no auto-stop. The app shows a
    persistent banner while anything is recording.
    """
    tz = db.get_setting(conn, "display_timezone")
    stop_all(conn)

    if started_local is None:
        local = dt.datetime.now(ZoneInfo(tz)).replace(microsecond=0)
        started_local = local.isoformat()

    cur = conn.execute(
        "INSERT INTO sessions(album_id, started_local, started_utc, active, created_at) "
        "VALUES(?,?,?,1,?)",
        (album_id, started_local, to_utc(started_local, tz), now_utc_iso()))
    return cur.lastrowid


def stop_recording(conn, album_id: int, ended_local: str | None = None) -> None:
    tz = db.get_setting(conn, "display_timezone")
    if ended_local is None:
        ended_local = dt.datetime.now(ZoneInfo(tz)).replace(microsecond=0).isoformat()
    conn.execute(
        "UPDATE sessions SET active=0, ended_local=?, ended_utc=? "
        "WHERE album_id=? AND active=1",
        (ended_local, to_utc(ended_local, tz), album_id))


def stop_all(conn) -> None:
    tz = db.get_setting(conn, "display_timezone")
    now_local = dt.datetime.now(ZoneInfo(tz)).replace(microsecond=0).isoformat()
    conn.execute(
        "UPDATE sessions SET active=0, ended_local=?, ended_utc=? WHERE active=1",
        (now_local, to_utc(now_local, tz)))


def add_past_session(conn, album_id: int, start_local: str, end_local: str) -> int:
    """The 'I forgot to turn it on' path. Same result as having recorded live."""
    tz = db.get_setting(conn, "display_timezone")
    cur = conn.execute(
        "INSERT INTO sessions(album_id, started_local, started_utc, ended_local, "
        "ended_utc, active, created_at) VALUES(?,?,?,?,?,0,?)",
        (album_id, start_local, to_utc(start_local, tz),
         end_local, to_utc(end_local, tz), now_utc_iso()))
    return cur.lastrowid


def edit_session(conn, session_id: int, start_local: str, end_local: str | None) -> None:
    tz = db.get_setting(conn, "display_timezone")
    conn.execute(
        "UPDATE sessions SET started_local=?, started_utc=?, ended_local=?, ended_utc=? "
        "WHERE id=?",
        (start_local, to_utc(start_local, tz), end_local,
         to_utc(end_local, tz) if end_local else None, session_id))


def sessions_for(conn, album_id: int) -> list[dict]:
    return [dict(r) for r in conn.execute(
        "SELECT * FROM sessions WHERE album_id=? ORDER BY started_utc DESC",
        (album_id,))]


def apply_sessions(conn) -> int:
    """Recompute every session-derived membership.

    Rebuilt from scratch each time rather than incrementally, so editing a
    session's dates and re-applying always converges on the right answer.
    Manual memberships are left alone -- only rows with source='session' are
    replaced.
    """
    now = now_utc_iso()
    conn.execute("DELETE FROM album_members WHERE source='session'")
    added = 0
    for s in conn.execute("SELECT * FROM sessions").fetchall():
        end = s["ended_utc"] or now
        cur = conn.execute(
            """INSERT INTO album_members(album_id, file_id, added_at, source)
               SELECT ?, id, ?, 'session' FROM files
                WHERE state='active' AND captured_utc IS NOT NULL
                  AND captured_utc >= ? AND captured_utc <= ?
               ON CONFLICT(album_id, file_id) DO NOTHING""",
            (s["album_id"], now, s["started_utc"], end))
        added += cur.rowcount
    return added


# ------------------------------------------------------------ library tree --

def archive_path_for(file_id: int, name: str) -> str:
    """Sharded so no directory ends up with forty thousand entries in it."""
    safe = _UNSAFE.sub("_", name).strip(". ") or "file"
    return f"{config.ARCHIVE_DIR}/{file_id % 256:02x}/{file_id}_{safe}"


def source_path(row) -> Path | None:
    """Where this file's bytes actually are, or None if they are gone.

    camera-backup first because that is the normal case, then the archive link,
    which is the only copy left once the photo has been deleted from the phone
    and Syncthing has carried that deletion across.
    """
    src = config.CAMERA_BACKUP / row["rel_path"]
    if src.exists():
        return src
    try:
        rel = row["archive_path"]
    except (IndexError, KeyError):
        return None
    if rel:
        arc = config.LIBRARY / rel
        if arc.exists():
            return arc
    return None


def ensure_archive(conn, file_ids: list[int] | None = None) -> dict:
    """Give every known file its keeper link.

    Called before anything that can make the phone's copy disappear, and again
    on every scan for whatever is new. Hardlinks, so a second reference to the
    same bytes costs an inode entry and nothing else.

    Idempotent: a file that already has a live link is skipped without touching
    the disk.
    """
    if file_ids:
        placeholders = ",".join("?" * len(file_ids))
        rows = conn.execute(
            f"SELECT id, rel_path, display_name, archive_path FROM files "
            f"WHERE state != 'purged' AND id IN ({placeholders})", file_ids).fetchall()
    else:
        rows = conn.execute(
            "SELECT id, rel_path, display_name, archive_path FROM files "
            "WHERE state != 'purged'").fetchall()

    linked = already = lost = 0
    for r in rows:
        rel = r["archive_path"]
        if rel and (config.LIBRARY / rel).exists():
            already += 1
            continue

        src = config.CAMERA_BACKUP / r["rel_path"]
        if not src.exists():
            # No backup copy and no archive link: the bytes are already gone
            # from the library. Syncthing's .stversions may still hold them.
            lost += 1
            continue

        dest_rel = archive_path_for(r["id"], r["display_name"] or Path(r["rel_path"]).name)
        dest = config.LIBRARY / dest_rel
        dest.parent.mkdir(parents=True, exist_ok=True)
        try:
            if not dest.exists():
                dest.hardlink_to(src)
            conn.execute("UPDATE files SET archive_path=? WHERE id=?",
                         (dest_rel, r["id"]))
            linked += 1
        except OSError:
            lost += 1

    return {"linked": linked, "already": already, "lost": lost}


def build_tree(conn) -> dict:
    """Regenerate library/ as hardlinks.

    This tree is insurance, not the source of truth -- the database decides
    membership. It exists so that if the app breaks, the database corrupts, or
    this project is abandoned in three years, the albums are still ordinary
    folders that any file manager can open. Hardlinks make it cost nothing.

    The album, unsorted and trash folders are *views* and are rebuilt from
    nothing every time. `_archive` is not one of them and is never touched
    here -- it is what holds the file, and wiping it would turn a rebuild into
    a deletion for anything no longer on the phone.
    """
    root = config.LIBRARY
    root.mkdir(parents=True, exist_ok=True)

    # Keeper links first, so a rebuild can never run before the thing that
    # makes the rebuild safe.
    archive = ensure_archive(conn)

    # Rebuild wholesale. Links are cheap and this converges after renames,
    # deletions and membership changes without tracking what moved.
    for child in root.iterdir():
        if child.name == config.ARCHIVE_DIR:
            continue
        if child.is_dir():
            for f in child.rglob("*"):
                if f.is_file():
                    f.unlink()
            for d in sorted((p for p in child.rglob("*") if p.is_dir()), reverse=True):
                d.rmdir()
            child.rmdir()

    linked = skipped = 0
    used: dict[str, int] = {}

    def link_into(dirname: str, rows) -> None:
        nonlocal linked, skipped
        target = root / dirname
        target.mkdir(parents=True, exist_ok=True)
        for r in rows:
            src = source_path(r)
            if src is None:
                skipped += 1
                continue
            name = r["display_name"] or Path(r["rel_path"]).name
            dest = target / name
            if dest.exists():
                dest = target / f"{dest.stem}_{r['id']}{dest.suffix}"
            try:
                dest.hardlink_to(src)
                linked += 1
            except OSError:
                skipped += 1

    for album in conn.execute("SELECT id, name FROM albums").fetchall():
        base = safe_dirname(album["name"])
        used[base] = used.get(base, 0) + 1
        dirname = base if used[base] == 1 else f"{base}_{album['id']}"
        rows = conn.execute(
            """SELECT f.id, f.rel_path, f.display_name, f.archive_path FROM files f
                 JOIN album_members m ON m.file_id = f.id
                WHERE m.album_id = ? AND f.state = 'active'""",
            (album["id"],)).fetchall()
        link_into(dirname, rows)

    link_into(config.UNSORTED_DIR, conn.execute(
        """SELECT id, rel_path, display_name, archive_path FROM files
            WHERE state='active' AND phone_trashed=0
              AND id NOT IN (SELECT file_id FROM album_members)""").fetchall())

    link_into(config.TRASH_DIR, conn.execute(
        "SELECT id, rel_path, display_name, archive_path FROM files WHERE state='trashed'"
    ).fetchall())

    return {"linked": linked, "skipped": skipped, "archive": archive}
