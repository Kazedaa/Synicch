"""Turning stored scores into cleanup suggestions.

Nothing here decodes anything -- the expensive work already happened during the
media pass. This is pure SQL over numbers that are already recorded, which is
what makes retuning a threshold instant rather than an overnight rescan.

Nothing here deletes anything either. Every detector only ever produces a
suggestion; the user confirms in the app, it goes to trash, and only a separate
deliberate purge removes bytes.
"""
from __future__ import annotations

from . import db
from .timestamps import now_utc_iso

# Ordered by how much the user should trust them. The app shows them in this
# order, most reliable first.
REASONS = [
    ("phone_trashed", "Deleted on your phone"),
    ("short_video", "Accidental videos"),
    ("blank", "Blank or black"),
    ("dup_exact", "Exact duplicates"),
    ("blurry", "Blurry"),
]


def _flag(conn, file_id: int, reason: str, severity: float | None = None) -> int:
    cur = conn.execute(
        "INSERT INTO flags(file_id, reason, severity, created_at) VALUES(?,?,?,?) "
        "ON CONFLICT(file_id, reason) DO NOTHING",
        (file_id, reason, severity, now_utc_iso()))
    return cur.rowcount


def run(conn) -> dict:
    blur_threshold = float(db.get_setting(conn, "blur_threshold"))
    short_video_max = float(db.get_setting(conn, "short_video_max_s"))
    counts: dict[str, int] = {}

    # -- deleted on the phone, still inside Android's grace period ------------
    counts["phone_trashed"] = sum(
        _flag(conn, r["id"], "phone_trashed")
        for r in conn.execute(
            "SELECT id FROM files WHERE state='active' AND phone_trashed=1"))

    # -- accidental videos: the highest-precision signal available -----------
    counts["short_video"] = sum(
        _flag(conn, r["id"], "short_video", r["duration_s"])
        for r in conn.execute(
            "SELECT id, duration_s FROM files "
            "WHERE state='active' AND kind='video' AND duration_s IS NOT NULL "
            "AND duration_s < ?", (short_video_max,)))

    # -- blank / black / blown out -------------------------------------------
    counts["blank"] = sum(
        _flag(conn, r["id"], "blank", r["luma_std"])
        for r in conn.execute(
            """SELECT f.id, s.luma_std FROM files f JOIN scores s ON s.file_id=f.id
                WHERE f.state='active'
                  AND (s.luma_std < 12 OR s.clipped_frac > 0.75)"""))

    # -- blurry: least reliable, which is exactly why nothing auto-deletes ----
    counts["blurry"] = sum(
        _flag(conn, r["id"], "blurry", r["laplacian_var"])
        for r in conn.execute(
            """SELECT f.id, s.laplacian_var FROM files f JOIN scores s ON s.file_id=f.id
                WHERE f.state='active' AND s.laplacian_var IS NOT NULL
                  AND s.laplacian_var < ?
                  AND f.id NOT IN (SELECT file_id FROM flags WHERE reason='blank')""",
            (blur_threshold,)))

    counts["dup_exact"] = _exact_duplicates(conn)
    return counts


def _exact_duplicates(conn) -> int:
    """Grouped on the cheap fingerprint.

    Note this is a *candidate* grouping only. A full hash is computed before
    anything acts on it -- see api.confirm_duplicates. Nothing destructive ever
    runs on a fingerprint alone.
    """
    conn.execute("DELETE FROM dup_groups WHERE kind='exact'")
    flagged = 0
    rows = conn.execute(
        """SELECT quick_fp, GROUP_CONCAT(id) ids, COUNT(*) n FROM files
            WHERE state='active' AND quick_fp IS NOT NULL
            GROUP BY quick_fp HAVING n > 1""").fetchall()
    for r in rows:
        ids = [int(x) for x in r["ids"].split(",")]
        keeper = min(ids)  # oldest row wins; arbitrary but stable
        cur = conn.execute(
            "INSERT INTO dup_groups(kind, keeper_file_id, created_at) VALUES('exact',?,?)",
            (keeper, now_utc_iso()))
        gid = cur.lastrowid
        for fid in ids:
            conn.execute(
                "INSERT INTO dup_members(group_id, file_id) VALUES(?,?)", (gid, fid))
            if fid != keeper:
                flagged += _flag(conn, fid, "dup_exact")
    return flagged



def groups(conn) -> list[dict]:
    """Cleanup screen contents, most-trustworthy reason first."""
    out = []
    for reason, label in REASONS:
        n = conn.execute(
            "SELECT COUNT(*) FROM flags fl JOIN files f ON f.id = fl.file_id "
            "WHERE fl.reason=? AND fl.dismissed_at IS NULL AND f.state='active'",
            (reason,)).fetchone()[0]
        if n:
            out.append({"reason": reason, "label": label, "count": n})
    return out
