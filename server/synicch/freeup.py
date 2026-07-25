"""Verifying that a phone-side original is genuinely safe to delete.

This backs the most dangerous feature in the app -- it deletes originals off the
phone -- so it is deliberately paranoid.

Verification is by *content*. A matching filename and size is not evidence; that
is exactly how you end up deleting the one photo that did not copy properly.
This is the one place a full hash on both sides is worth computing.

Four conditions, all required:
  1. the server holds byte-identical content
  2. Synicch has indexed it into the library, not merely received it
  3. Syncthing reports the folder fully in sync
  4. it is older than a cooling-off period
"""
from __future__ import annotations

import datetime as dt
import re
from pathlib import Path

from . import config, db
from .fingerprint import full_sha256


def syncthing_ready() -> tuple[bool, str]:
    """Condition 3. If the backup is still catching up, nothing is safe yet."""
    try:
        import json
        import urllib.request
        cfg = Path("/var/lib/syncthing/config.xml").read_text()
        key = re.search(r"<apikey>([^<]+)</apikey>", cfg)
        folder = re.search(r'<folder id="([^"]+)"', cfg)
        if not (key and folder):
            return False, "could not read Syncthing configuration"

        req = urllib.request.Request(
            f"http://127.0.0.1:8384/rest/db/status?folder={folder.group(1)}",
            headers={"X-API-Key": key.group(1)})
        d = json.loads(urllib.request.urlopen(req, timeout=10).read())

        if d.get("state") != "idle":
            return False, f"Syncthing is {d.get('state')}, not idle"
        if d.get("needFiles", 0) or d.get("needBytes", 0):
            return False, f"{d.get('needFiles')} file(s) still to sync"
        if d.get("errors", 0):
            return False, f"Syncthing reports {d['errors']} error(s)"
        return True, "in sync"
    except Exception as e:
        return False, f"could not reach Syncthing: {type(e).__name__}"


def server_sha256(conn, row) -> str | None:
    """Full hash of the server's copy, computed once and remembered."""
    if row["sha256"]:
        return row["sha256"]
    from . import albums
    path = config.CAMERA_BACKUP / row["rel_path"]
    if path is None:
        return None
    digest = full_sha256(path)
    conn.execute("UPDATE files SET sha256=? WHERE id=?", (digest, row["id"]))
    return digest


def candidates(conn, local_files: list[dict]) -> dict:
    """Phase one: which of these local files even look eligible?

    Returns the subset worth hashing, so the phone does not waste time and
    battery hashing its entire camera roll.
    """
    ready, why = syncthing_ready()
    cooling = int(db.get_setting(conn, "freeup_cooling_off_days"))
    cutoff = (dt.datetime.now(dt.timezone.utc) - dt.timedelta(days=cooling)).isoformat()

    if not ready:
        return {"ready": False, "reason": why, "candidates": []}

    out = []
    for item in local_files:
        name, size = item.get("name"), item.get("size")
        if not name or size is None:
            continue
        row = conn.execute(
            """SELECT id, rel_path, size, captured_utc, thumb_at, state
                 FROM files
                WHERE display_name = ? AND size = ? AND state = 'active'""",
            (name, size)).fetchone()
        if row is None:
            continue
        # Condition 2: received is not the same as indexed. A photo sitting in
        # the backup folder unscanned is held by one thread only.
        if row["thumb_at"] is None:
            continue
        # Condition 4: nothing recent, ever.
        if (row["captured_utc"] or "") > cutoff:
            continue
        # Ambiguous states do not belong in a bulk delete.
        flagged = conn.execute(
            "SELECT 1 FROM flags WHERE file_id=? AND dismissed_at IS NULL",
            (row["id"],)).fetchone()
        if flagged:
            continue
        out.append({"id": row["id"], "name": name, "size": size})

    return {"ready": True, "reason": why, "candidates": out}


def verify(conn, items: list[dict]) -> dict:
    """Phase two: confirm byte-identical content before anything is deleted.

    `items` carry the SHA-256 the phone computed of its own local file. Only
    files whose hash matches the server's are returned as confirmed.
    """
    ready, why = syncthing_ready()
    if not ready:
        return {"ready": False, "reason": why, "confirmed": [], "rejected": []}

    confirmed, rejected = [], []
    for item in items:
        fid, digest = item.get("id"), (item.get("sha256") or "").lower()
        if not fid or not digest:
            rejected.append({"id": fid, "why": "missing id or hash"})
            continue
        row = conn.execute(
            "SELECT * FROM files WHERE id=? AND state='active'", (fid,)).fetchone()
        if row is None:
            rejected.append({"id": fid, "why": "not in the library"})
            continue
        theirs = server_sha256(conn, row)
        if theirs is None:
            rejected.append({"id": fid, "why": "server copy is missing"})
        elif theirs.lower() != digest:
            rejected.append({"id": fid, "why": "content does not match"})
        else:
            confirmed.append({"id": fid, "name": row["display_name"],
                              "size": row["size"]})
            db.audit(conn, "freeup_verified", file_id=fid,
                     rel_path=row["rel_path"])

    return {"ready": True, "reason": why,
            "confirmed": confirmed, "rejected": rejected,
            "freed": sum(c["size"] or 0 for c in confirmed)}
