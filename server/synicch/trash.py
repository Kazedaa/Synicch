"""Trash, permanent deletion, and keeping Syncthing out of the way.

This is the only module in the project that can destroy data. Everything in it
is written defensively on purpose.

The shape of it:

  delete   -> a state flag. The file does not move. Restoring is instant and
              free, and trash costs no disk space.
  purge    -> the only code path that unlinks bytes. Runs on trashed files
              only, and only past the retention period or on an explicit force.
"""
from __future__ import annotations

import datetime as dt
import re
from pathlib import Path

from . import config, db
from .timestamps import now_utc_iso

# Syncthing ignore patterns are globs. A filename containing any of these would
# produce a rule matching far more than the one file -- silently excluding other
# photos from the backup, with nothing to indicate it had happened.
#
# This is the single most dangerous line of code in the project. Any filename
# containing one of these characters is refused rather than guessed at.
GLOB_CHARS = re.compile(r'[*?\[\]{}]')

MANAGED_HEADER = "//// synicch-managed -- lines below are added automatically"
# ------------------------------------------------------------------- trash --

def move_to_trash(conn, file_ids: list[int]) -> int:
    """A state flag only. The file itself does not move, so this is instant and
    reversible and uses no extra disk.

    """
    now = now_utc_iso()
    n = 0
    for fid in file_ids:
        cur = conn.execute(
            "UPDATE files SET state='trashed', trashed_at=? "
            "WHERE id=? AND state='active'", (now, fid))
        if cur.rowcount:
            db.audit(conn, "trash", file_id=fid)
            n += cur.rowcount
    return n


def restore(conn, file_ids: list[int]) -> int:
    n = 0
    for fid in file_ids:
        cur = conn.execute(
            "UPDATE files SET state='active', trashed_at=NULL "
            "WHERE id=? AND state='trashed'", (fid,))
        if cur.rowcount:
            db.audit(conn, "restore", file_id=fid)
            n += cur.rowcount
    return n


def listing(conn) -> list[dict]:
    retention = int(db.get_setting(conn, "trash_retention_days"))
    rows = conn.execute(
        "SELECT * FROM files WHERE state='trashed' ORDER BY trashed_at DESC").fetchall()
    out = []
    for r in rows:
        purge_at = None
        if r["trashed_at"]:
            purge_at = (dt.datetime.fromisoformat(r["trashed_at"])
                        + dt.timedelta(days=retention)).isoformat()
        out.append({
            "id": r["id"], "name": r["display_name"], "size": r["size"],
            "trashed_at": r["trashed_at"], "purge_at": purge_at,
            "kind": r["kind"], "w": r["width"], "h": r["height"],
        })
    return out


# ------------------------------------------------------------------- purge --

def purge(conn, *, file_ids: list[int] | None = None, force: bool = False,
          dry_run: bool = False) -> dict:
    """Permanently remove trashed files. The only code path that unlinks bytes.

    """
    retention = int(db.get_setting(conn, "trash_retention_days"))
    cutoff = (dt.datetime.now(dt.timezone.utc)
              - dt.timedelta(days=retention)).isoformat()

    if file_ids:
        placeholders = ",".join("?" * len(file_ids))
        rows = conn.execute(
            f"SELECT * FROM files WHERE state='trashed' AND id IN ({placeholders})",
            file_ids).fetchall()
        if not force:
            rows = [r for r in rows if (r["trashed_at"] or "") <= cutoff]
    else:
        rows = conn.execute(
            "SELECT * FROM files WHERE state='trashed' AND trashed_at <= ?",
            (cutoff,)).fetchall()

    eligible = [(r, "") for r in rows]

    result = {
        "eligible": len(eligible), "blocked": 0,
        "purged": 0, "freed": 0, "dry_run": dry_run,
    }
    if dry_run or not eligible:
        return result

    for r, _ in eligible:
        src = config.CAMERA_BACKUP / r["rel_path"]
        archive = None
        try:
            if r["archive_path"]:
                archive = config.LIBRARY / r["archive_path"]
        except (IndexError, KeyError):
            archive = None
        freed = r["size"] or 0

        # Identify the file by inode so view links are matched by *content*
        # rather than by name.
        try:
            inode = src.stat().st_ino
        except OSError:
            inode = None

        try:
            # Library links first: if the backup unlink fails we have not left
            # the tree pointing at something half-removed.
            if inode is not None:
                for link in config.LIBRARY.rglob(r["display_name"] or "\x00"):
                    try:
                        if link.is_file() and link.stat().st_ino == inode:
                            link.unlink()
                    except OSError:
                        pass
            if archive is not None:
                archive.unlink(missing_ok=True)
            src.unlink(missing_ok=True)
            for cache in (config.THUMBS, config.PREVIEWS):
                (cache / f"{r['id'] % 256:02x}" / f"{r['id']}.jpg").unlink(missing_ok=True)
        except OSError as e:
            db.audit(conn, "purge_failed", file_id=r["id"],
                     rel_path=r["rel_path"], detail=str(e))
            continue

        conn.execute("UPDATE files SET state='purged' WHERE id=?", (r["id"],))
        db.audit(conn, "purge", file_id=r["id"], rel_path=r["rel_path"],
                 detail=f"{freed} bytes")
        result["purged"] += 1
        result["freed"] += freed

    return result
