"""The decode pass: thumbnails and detection scores, in parallel.

Split from indexing on purpose. Indexing is cheap and reads ~128KB per file;
this reads and decodes whole images and is by far the expensive part of a scan.
Keeping them separate means the library can be re-indexed in seconds without
paying for decoding, and that a decode pass can be interrupted and resumed.

Workers are separate processes (Pillow releases little to the GIL) but only the
parent touches SQLite -- passing results back rather than sharing a connection
avoids write contention entirely.
"""
from __future__ import annotations

import time
from concurrent.futures import ProcessPoolExecutor, as_completed
from pathlib import Path

from . import config, media
from .timestamps import now_utc_iso

# Three, not one per core. Sustained all-core load on a phone throttles hard,
# and this box is also the house's DNS server.
DEFAULT_WORKERS = 3
COMMIT_BATCH = 100


def _task(args: tuple) -> tuple:
    """Runs in a worker process. Returns (file_id, results, error)."""
    file_id, path_str, kind, duration = args
    path = Path(path_str)
    try:
        if kind == "photo":
            return file_id, media.process_photo(path, file_id), None
        if kind == "video":
            result = media.video_poster(path, file_id, duration)
            result["codec"] = media.video_codec(path)
            return file_id, result, None
        return file_id, {}, "unsupported kind"
    except Exception as e:
        return file_id, {}, f"{type(e).__name__}: {e}"


def pending(conn, *, force: bool = False) -> list[tuple]:
    if force:
        where = "f.state = 'active' AND f.kind IN ('photo','video')"
        params: tuple = ()
    else:
        where = ("f.state = 'active' AND f.kind IN ('photo','video') "
                 "AND (f.thumb_at IS NULL OR s.algo_version IS NULL OR s.algo_version < ?)")
        params = (media.ALGO_VERSION,)

    rows = conn.execute(
        f"""SELECT f.id, f.rel_path, f.archive_path, f.kind, f.duration_s
            FROM files f LEFT JOIN scores s ON s.file_id = f.id
            WHERE {where}
            ORDER BY f.captured_utc DESC""",   # newest first: the app becomes
        params,                                # useful before the pass finishes
    ).fetchall()

    from . import albums
    out = []
    for r in rows:
        # A photo already gone from the phone still has to be thumbnailable,
        # and by then the library's keeper link is the only copy.
        path = albums.source_path(r)
        if path is not None:
            out.append((r["id"], str(path), r["kind"], r["duration_s"]))
    return out


def _store(conn, file_id: int, result: dict, error: str | None) -> None:
    now = now_utc_iso()
    if error or not result:
        conn.execute("UPDATE files SET scan_error=? WHERE id=?",
                     (error or "no result", file_id))
        return

    sets, vals = ["thumb_at=?", "scan_error=NULL"], [now]
    if result.get("width"):
        sets += ["width=?", "height=?"]
        vals += [result["width"], result["height"]]
    if result.get("codec"):
        sets.append("codec=?")
        vals.append(result["codec"])
    vals.append(file_id)
    conn.execute(f"UPDATE files SET {', '.join(sets)} WHERE id=?", vals)

    conn.execute(
        """INSERT INTO scores(file_id, algo_version, laplacian_var, luma_mean,
               luma_std, clipped_frac, dhash, computed_at)
           VALUES(?,?,?,?,?,?,?,?)
           ON CONFLICT(file_id) DO UPDATE SET
               algo_version=excluded.algo_version,
               laplacian_var=excluded.laplacian_var,
               luma_mean=excluded.luma_mean,
               luma_std=excluded.luma_std,
               clipped_frac=excluded.clipped_frac,
               dhash=excluded.dhash,
               computed_at=excluded.computed_at""",
        (file_id, media.ALGO_VERSION, result.get("laplacian_var"),
         result.get("luma_mean"), result.get("luma_std"),
         result.get("clipped_frac"), result.get("dhash"), now),
    )


def run(conn, *, workers: int = DEFAULT_WORKERS, force: bool = False,
        limit: int | None = None, verbose: bool = True,
        max_seconds: float | None = None) -> dict:
    tasks = pending(conn, force=force)
    if limit:
        tasks = tasks[:limit]

    total = len(tasks)
    if not total:
        return {"total": 0, "done": 0, "errors": 0, "elapsed": 0.0}

    config.THUMBS.mkdir(parents=True, exist_ok=True)
    if verbose:
        print(f"decoding {total} file(s) with {workers} worker(s)")

    done = errors = 0
    t0 = time.monotonic()
    stopped_early = False

    conn.execute("BEGIN")
    try:
        with ProcessPoolExecutor(max_workers=workers) as pool:
            futures = {pool.submit(_task, t): t[0] for t in tasks}
            for fut in as_completed(futures):
                file_id, result, error = fut.result()
                _store(conn, file_id, result, error)
                done += 1
                if error:
                    errors += 1
                    if verbose:
                        print(f"  ! id={file_id}: {error}")

                if done % COMMIT_BATCH == 0:
                    conn.execute("COMMIT")
                    conn.execute("BEGIN")
                    if verbose:
                        rate = done / max(time.monotonic() - t0, 0.001)
                        eta = (total - done) / max(rate, 0.001)
                        print(f"  {done}/{total}  ({rate:.1f}/s, ~{eta:.0f}s left)")

                if max_seconds and time.monotonic() - t0 > max_seconds:
                    stopped_early = True
                    for f in futures:
                        f.cancel()
                    break
        conn.execute("COMMIT")
    except BaseException:
        conn.execute("ROLLBACK")
        raise

    return {
        "total": total, "done": done, "errors": errors,
        "elapsed": time.monotonic() - t0, "stopped_early": stopped_early,
    }


def cache_size() -> tuple[int, int]:
    """(bytes, file count) across thumbnails and previews."""
    total = count = 0
    for root in (config.THUMBS, config.PREVIEWS):
        for p in root.rglob("*.jpg"):
            try:
                total += p.stat().st_size
                count += 1
            except OSError:
                pass
    return total, count
