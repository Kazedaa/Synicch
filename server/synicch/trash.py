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
STIGNORE_WARN_AT = 5000


def _stignore_path() -> Path:
    return config.CAMERA_BACKUP / ".stignore"


def is_safe_to_ignore(rel_path: str) -> tuple[bool, str]:
    """Whether this path can be expressed as a Syncthing ignore rule safely."""
    name = Path(rel_path).name
    if GLOB_CHARS.search(rel_path):
        return False, "filename contains glob characters (* ? [ ] { })"
    if name.startswith(("!", "#")):
        return False, "filename starts with a Syncthing pattern prefix (! or #)"
    if "\n" in rel_path or "\r" in rel_path:
        return False, "filename contains a newline"
    return True, ""


def read_managed(conn=None) -> list[str]:
    path = _stignore_path()
    if not path.exists():
        return []
    lines = path.read_text().splitlines()
    if MANAGED_HEADER not in lines:
        return []
    return [ln for ln in lines[lines.index(MANAGED_HEADER) + 1:]
            if ln and not ln.startswith("//")]


def add_ignores(rel_paths: list[str]) -> int:
    """Append to the managed block, atomically.

    Written before the files are removed, never after: otherwise there is a
    window where Syncthing sees a deletion it has not been told to ignore.
    """
    path = _stignore_path()
    existing = path.read_text().splitlines() if path.exists() else []

    if MANAGED_HEADER not in existing:
        existing += ["", MANAGED_HEADER]

    have = set(existing)
    added = 0
    for rel in rel_paths:
        entry = "/" + rel  # anchor to the folder root, not any subdirectory
        if entry not in have:
            existing.append(entry)
            have.add(entry)
            added += 1

    tmp = path.with_suffix(".stignore.tmp")
    tmp.write_text("\n".join(existing) + "\n")
    tmp.replace(path)
    return added


def rescan_syncthing() -> bool:
    """Ask Syncthing to re-read .stignore immediately rather than at its own pace."""
    try:
        import urllib.request
        cfg = Path("/var/lib/syncthing/config.xml").read_text()
        key = re.search(r"<apikey>([^<]+)</apikey>", cfg)
        folder = re.search(r'<folder id="([^"]+)"', cfg)
        if not (key and folder):
            return False
        req = urllib.request.Request(
            f"http://127.0.0.1:8384/rest/db/scan?folder={folder.group(1)}",
            method="POST", headers={"X-API-Key": key.group(1)})
        urllib.request.urlopen(req, timeout=10).read()
        return True
    except Exception:
        return False


# ------------------------------------------------------------------- trash --

def move_to_trash(conn, file_ids: list[int]) -> int:
    """A state flag only. The file itself does not move, so this is instant and
    reversible and uses no extra disk.

    The keeper link is made first. The app deletes the phone's copy straight
    after trashing, Syncthing carries that deletion to camera-backup, and
    without a link already in place the file would be gone within minutes
    rather than after the retention period.
    """
    from . import albums
    albums.ensure_archive(conn, file_ids)

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

    Order matters and is not negotiable:
      1. refuse anything whose name cannot be safely expressed as an ignore rule
      2. write the ignore rules
      3. tell Syncthing to re-read them
      4. only then remove the files

    Doing (4) before (2) leaves a window where Syncthing sees an unexplained
    deletion, reports the folder as out of sync, and offers a "Revert Local
    Changes" button that would re-download everything just purged.
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

    eligible, blocked = [], []
    for r in rows:
        ok, why = is_safe_to_ignore(r["rel_path"])
        (eligible if ok else blocked).append((r, why))

    if blocked:
        report = config.REPORTS / "purge-blocked.txt"
        report.parent.mkdir(parents=True, exist_ok=True)
        with report.open("a") as fh:
            for r, why in blocked:
                fh.write(f"{now_utc_iso()}\t{r['rel_path']}\t{why}\n")

    result = {
        "eligible": len(eligible), "blocked": len(blocked),
        "purged": 0, "freed": 0, "dry_run": dry_run,
        "blocked_paths": [r["rel_path"] for r, _ in blocked][:20],
    }
    if dry_run or not eligible:
        return result

    add_ignores([r["rel_path"] for r, _ in eligible])
    rescan_syncthing()

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

    result["stignore_entries"] = len(read_managed())
    result["stignore_warn"] = result["stignore_entries"] > STIGNORE_WARN_AT
    return result
