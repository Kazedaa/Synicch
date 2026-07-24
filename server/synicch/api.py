"""HTTP API.

Deliberately client-agnostic: plain HTTP and JSON with no Android assumptions,
so the web client discussed for later is additive work rather than a rewrite.

The API never does heavy work inline. Scans are launched as a detached process
and the request returns immediately -- a request that blocks for three minutes
on a phone server is a request that times out.
"""
from __future__ import annotations

import os
import subprocess
import sys
from functools import wraps
from pathlib import Path

from flask import Flask, abort, g, jsonify, request, send_file

from . import auth, config, db, media
from .timestamps import now_utc_iso

app = Flask(__name__)
app.config["JSON_SORT_KEYS"] = False

# A year. Thumbnails are content-addressed by file id and only change when the
# decode pass reruns, which also changes Last-Modified.
IMMUTABLE = "private, max-age=31536000, immutable"
PAGE_SIZE = 200
MAX_PAGE = 1000


def get_db():
    if "db" not in g:
        g.db = db.connect()
    return g.db


@app.teardown_appcontext
def _close_db(_exc):
    conn = g.pop("db", None)
    if conn is not None:
        conn.close()


def require_token(fn):
    @wraps(fn)
    def wrapper(*a, **kw):
        header = request.headers.get("Authorization", "")
        token = header[7:] if header.startswith("Bearer ") else None
        # Convenience for <img> tags, which cannot set headers.
        token = token or request.args.get("token")
        if not auth.verify(get_db(), token):
            abort(401)
        return fn(*a, **kw)
    return wrapper


# ------------------------------------------------------------------ helpers --

def _file_row(fid: int):
    row = get_db().execute("SELECT * FROM files WHERE id=?", (fid,)).fetchone()
    if row is None:
        abort(404)
    return row


def _abs_path(row) -> Path:
    """Where the bytes are.

    Syncthing's copy is the only one, so this is simply where it landed.
    """
    return config.CAMERA_BACKUP / row["rel_path"]


def _album_map(rows) -> dict[int, list[int]]:
    """Album membership for a page of files, in one query rather than N."""
    ids = [r["id"] for r in rows]
    if not ids:
        return {}
    placeholders = ",".join("?" * len(ids))
    out: dict[int, list[int]] = {}
    for m in get_db().execute(
            f"SELECT file_id, album_id FROM album_members WHERE file_id IN ({placeholders})",
            ids):
        out.setdefault(m["file_id"], []).append(m["album_id"])
    return out


def _serialize(r, albums: list[int] | None = None) -> dict:
    base = {"albums": albums or []}
    return base | {
        "id": r["id"],
        "name": r["display_name"] or Path(r["rel_path"]).name,
        "kind": r["kind"],
        "w": r["width"],
        "h": r["height"],
        "size": r["size"],
        "captured": r["captured_local"],
        "captured_utc": r["captured_utc"],
        "ts_source": r["ts_source"],
        "duration": r["duration_s"],
        "codec": r["codec"],
        "state": r["state"],
        "phone_trashed": bool(r["phone_trashed"]),
        "has_thumb": r["thumb_at"] is not None,
        # Lets the app decide a local file on the phone is this same photo and
        # skip the download entirely.
        "fp": r["quick_fp"],
        "path": r["rel_path"],
    }


# ------------------------------------------------------------------- routes --

@app.get("/api/ping")
def ping():
    """Unauthenticated, so the app can check reachability before pairing."""
    return jsonify({"ok": True, "service": "synicch"})


@app.get("/")
def index():
    """A status page, not a gallery.

    The client is the Android app; there is no web interface. But a bare 404
    here is useless to anyone who types the address into a browser, so this
    says what the service is and whether it is healthy.

    Deliberately unauthenticated and deliberately shows no library contents --
    it reveals nothing that reaching the host does not already reveal.
    """
    from . import __version__
    conn = get_db()
    scanning = False
    try:
        from .lock import scan_lock
        with scan_lock() as free:
            scanning = not free
    except Exception:
        pass
    tz = db.get_setting(conn, "display_timezone")

    return f"""<!doctype html>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1">
<title>Synicch</title>
<style>
  :root {{ color-scheme: dark light; }}
  body {{ font: 16px/1.6 system-ui, sans-serif; max-width: 34rem;
         margin: 12vh auto; padding: 0 1.5rem; }}
  h1 {{ font-size: 1.6rem; margin: 0 0 .2rem; }}
  .sub {{ opacity: .65; margin: 0 0 2rem; }}
  .ok {{ color: #3fb950; font-weight: 600; }}
  dl {{ display: grid; grid-template-columns: auto 1fr; gap: .4rem 1.5rem;
        margin: 0 0 2rem; }}
  dt {{ opacity: .65; }}
  dd {{ margin: 0; }}
  code {{ background: rgba(128,128,128,.18); padding: .15rem .45rem;
          border-radius: 4px; }}
  .note {{ border-left: 3px solid rgba(128,128,128,.35); padding-left: 1rem;
           opacity: .8; font-size: .93rem; }}
</style>
<h1>Synicch</h1>
<p class="sub">Self-hosted photo library</p>

<dl>
  <dt>Status</dt><dd class="ok">running</dd>
  <dt>Version</dt><dd>{__version__}</dd>
  <dt>Timezone</dt><dd>{tz}</dd>
  <dt>Scan</dt><dd>{"running now" if scanning else "idle"}</dd>
</dl>

<p class="note">
  There is no web gallery &mdash; the client is the Android app. This address
  serves the app's API. To connect a device, run <code>synicch pair</code> on
  the server and scan the QR code it prints.
</p>
"""


def _reachable_addresses(conn) -> list[str]:
    from . import auth
    primary = db.get_setting(conn, "public_url", "https://photos.ngserver")
    return [primary] + [a for a in auth.local_addresses() if a != primary]


@app.get("/api/status")
@require_token
def status():
    conn = get_db()
    total, active = conn.execute(
        "SELECT COUNT(*), SUM(state='active') FROM files").fetchone()
    thumbed = conn.execute(
        "SELECT COUNT(*) FROM files WHERE thumb_at IS NOT NULL").fetchone()[0]
    last = conn.execute(
        "SELECT * FROM scan_runs ORDER BY id DESC LIMIT 1").fetchone()

    import shutil
    from .lock import scan_lock
    usage = shutil.disk_usage(config.HOME)

    # Taking the lock non-blocking and immediately dropping it is the cheapest
    # honest answer to "is a scan running right now".
    with scan_lock() as free:
        scanning = not free

    return jsonify({
        "files": total,
        "active": active or 0,
        "thumbnails": thumbed,
        "indexing": thumbed < (active or 0),
        "scanning": scanning,
        "timezone": db.get_setting(conn, "display_timezone"),
        "last_scan": {
            "started": last["started_at"], "finished": last["finished_at"],
            "seen": last["files_seen"], "added": last["files_added"],
            "notes": last["notes"],
        } if last else None,
        "disk": {"free": usage.free, "total": usage.total},
        # Every address this server can be reached on. The app keeps these as
        # fallbacks so a broken DNS resolver cannot take it offline, and picks
        # up changes automatically if an IP moves.
        "addresses": _reachable_addresses(conn),
        "server_time": now_utc_iso(),
    })


@app.get("/api/media")
@require_token
def media_list():
    """Newest first, cursor-paginated.

    The cursor is (captured_utc, id) rather than an offset -- an offset would
    silently skip or repeat items whenever a scan inserts rows mid-pagination.
    """
    conn = get_db()
    limit = min(int(request.args.get("limit", PAGE_SIZE)), MAX_PAGE)
    cursor = request.args.get("cursor")
    since = request.args.get("since")
    include_trashed = request.args.get("include_phone_trashed") == "1"

    album_id = request.args.get("album")
    unsorted = request.args.get("unsorted") == "1"

    where = ["state = 'active'"]
    params: list = []
    if not include_trashed:
        where.append("phone_trashed = 0")
    if since:
        where.append("last_scanned > ?")
        params.append(since)
    if album_id:
        where.append("id IN (SELECT file_id FROM album_members WHERE album_id = ?)")
        params.append(int(album_id))
    if unsorted:
        # Not a real album -- a query. It therefore cannot drift out of sync
        # with reality the way a stored "unsorted" flag would.
        where.append("id NOT IN (SELECT file_id FROM album_members)")
    if cursor:
        try:
            c_utc, c_id = cursor.rsplit("|", 1)
            where.append("(captured_utc < ? OR (captured_utc = ? AND id < ?))")
            params += [c_utc, c_utc, int(c_id)]
        except ValueError:
            abort(400, "bad cursor")

    rows = conn.execute(
        f"SELECT * FROM files WHERE {' AND '.join(where)} "
        f"ORDER BY captured_utc DESC, id DESC LIMIT ?",
        (*params, limit + 1),
    ).fetchall()

    has_more = len(rows) > limit
    rows = rows[:limit]
    next_cursor = (f"{rows[-1]['captured_utc']}|{rows[-1]['id']}"
                   if rows and has_more else None)

    amap = _album_map(rows)
    return jsonify({
        "items": [_serialize(r, amap.get(r["id"])) for r in rows],
        "next_cursor": next_cursor,
        "has_more": has_more,
    })


@app.get("/api/media/<int:fid>")
@require_token
def media_one(fid: int):
    row = _file_row(fid)
    return jsonify(_serialize(row, _album_map([row]).get(fid)))


@app.get("/api/media/<int:fid>/thumb")
@require_token
def media_thumb(fid: int):
    _file_row(fid)
    path = media.thumb_path(fid)
    if not path.exists():
        abort(404, "not generated yet")
    resp = send_file(path, mimetype="image/jpeg", conditional=True)
    resp.headers["Cache-Control"] = IMMUTABLE
    return resp


@app.get("/api/media/<int:fid>/preview")
@require_token
def media_preview(fid: int):
    """Generated on the first request and cached.

    Doing these up front for the whole library would burn gigabytes on photos
    that never get opened.
    """
    row = _file_row(fid)
    if row["kind"] != "photo":
        return media_thumb(fid)
    src = _abs_path(row)
    if not src.exists():
        abort(410, "original no longer present")
    edit = get_db().execute(
        "SELECT * FROM edits WHERE file_id=?", (fid,)).fetchone()
    try:
        path = media.make_preview(src, fid, edit)
    except Exception as e:
        abort(500, f"preview failed: {e}")
    resp = send_file(path, mimetype="image/jpeg", conditional=True)
    resp.headers["Cache-Control"] = IMMUTABLE
    return resp


@app.get("/api/media/<int:fid>/original")
@require_token
def media_original(fid: int):
    """Range requests are handled by send_file, which video seeking needs."""
    row = _file_row(fid)
    path = _abs_path(row)
    if not path.exists():
        abort(410, "original no longer present")
    resp = send_file(path, conditional=True,
                     download_name=row["display_name"] or path.name)
    resp.headers["Cache-Control"] = IMMUTABLE
    return resp


@app.post("/api/scan")
@require_token
def trigger_scan():
    """Detached, niced, and returns immediately."""
    cmd = ["nice", "-n", "19", "ionice", "-c", "3",
           sys.executable, "-m", "synicch.cli", "scan", "-q"]
    env = {**os.environ, "PYTHONPATH": str(Path(__file__).resolve().parent.parent)}
    subprocess.Popen(cmd, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL,
                     start_new_session=True, env=env)
    return jsonify({"started": True})


# ------------------------------------------------------------------ albums --

@app.get("/api/albums")
@require_token
def albums_list():
    from . import albums
    return jsonify({"albums": albums.listing(get_db())})


@app.post("/api/albums")
@require_token
def albums_create():
    from . import albums
    name = (request.json or {}).get("name", "").strip()
    if not name:
        abort(400, "name required")
    conn = get_db()
    try:
        aid = albums.create(conn, name)
    except Exception:
        abort(409, "an album with that name already exists")
    return jsonify({"id": aid, "name": name})


@app.patch("/api/albums/<int:aid>")
@require_token
def albums_update(aid: int):
    from . import albums
    body = request.json or {}
    conn = get_db()
    if "name" in body:
        albums.rename(conn, aid, body["name"].strip())
    if "cover" in body:
        conn.execute("UPDATE albums SET cover_file_id=? WHERE id=?", (body["cover"], aid))
    return jsonify({"ok": True})


@app.delete("/api/albums/<int:aid>")
@require_token
def albums_delete(aid: int):
    """Removes the album only. Photos are never touched."""
    from . import albums
    albums.delete(get_db(), aid)
    return jsonify({"ok": True})


@app.post("/api/albums/<int:aid>/members")
@require_token
def albums_add(aid: int):
    from . import albums
    ids = (request.json or {}).get("ids", [])
    return jsonify({"added": albums.add_members(get_db(), aid, ids)})


@app.delete("/api/albums/<int:aid>/members")
@require_token
def albums_remove(aid: int):
    from . import albums
    ids = (request.json or {}).get("ids", [])
    return jsonify({"removed": albums.remove_members(get_db(), aid, ids)})


@app.get("/api/albums/<int:aid>/sessions")
@require_token
def sessions_list(aid: int):
    from . import albums
    return jsonify({"sessions": albums.sessions_for(get_db(), aid)})


@app.post("/api/albums/<int:aid>/recording")
@require_token
def recording_toggle(aid: int):
    """Start or stop the trip recorder.

    Only one album records at a time. Membership is applied immediately so the
    app sees the effect at once, even though the nightly scan would have caught
    it anyway.
    """
    from . import albums
    body = request.json or {}
    conn = get_db()
    if body.get("on"):
        sid = albums.start_recording(conn, aid, body.get("start"))
    else:
        albums.stop_recording(conn, aid, body.get("end"))
        sid = None
    albums.apply_sessions(conn)
    return jsonify({"session": sid, "recording": bool(body.get("on"))})


@app.post("/api/albums/<int:aid>/sessions")
@require_token
def session_add(aid: int):
    """The 'I forgot to turn it on' path -- add a window after the fact."""
    from . import albums
    body = request.json or {}
    start, end = body.get("start"), body.get("end")
    if not start or not end:
        abort(400, "start and end required (local time, ISO)")
    conn = get_db()
    sid = albums.add_past_session(conn, aid, start, end)
    added = albums.apply_sessions(conn)
    return jsonify({"session": sid, "members": added})


@app.patch("/api/sessions/<int:sid>")
@require_token
def session_edit(sid: int):
    from . import albums
    body = request.json or {}
    conn = get_db()
    albums.edit_session(conn, sid, body["start"], body.get("end"))
    albums.apply_sessions(conn)
    return jsonify({"ok": True})


@app.delete("/api/sessions/<int:sid>")
@require_token
def session_delete(sid: int):
    from . import albums
    conn = get_db()
    conn.execute("DELETE FROM sessions WHERE id=?", (sid,))
    albums.apply_sessions(conn)
    return jsonify({"ok": True})


# ------------------------------------------------------------------- edits --

@app.get("/api/media/<int:fid>/edit")
@require_token
def edit_get(fid: int):
    row = get_db().execute("SELECT * FROM edits WHERE file_id=?", (fid,)).fetchone()
    return jsonify(dict(row) if row else {"file_id": fid, "rotate": 0})


@app.put("/api/media/<int:fid>/edit")
@require_token
def edit_put(fid: int):
    """Crop and rotation are stored as numbers, never applied to the original.

    Reverting is deleting the row, and no edit can ever corrupt a photo.
    """
    _file_row(fid)
    b = request.json or {}
    conn = get_db()
    rotate = int(b.get("rotate", 0)) % 360
    if rotate not in (0, 90, 180, 270):
        abort(400, "rotate must be 0, 90, 180 or 270")

    conn.execute(
        """INSERT INTO edits(file_id, rotate, crop_x, crop_y, crop_w, crop_h, updated_at)
           VALUES(?,?,?,?,?,?,?)
           ON CONFLICT(file_id) DO UPDATE SET
             rotate=excluded.rotate, crop_x=excluded.crop_x, crop_y=excluded.crop_y,
             crop_w=excluded.crop_w, crop_h=excluded.crop_h,
             updated_at=excluded.updated_at""",
        (fid, rotate, b.get("crop_x"), b.get("crop_y"),
         b.get("crop_w"), b.get("crop_h"), now_utc_iso()))
    # The cached render is no longer valid.
    media.preview_path(fid).unlink(missing_ok=True)
    return jsonify({"ok": True})


@app.delete("/api/media/<int:fid>/edit")
@require_token
def edit_reset(fid: int):
    get_db().execute("DELETE FROM edits WHERE file_id=?", (fid,))
    media.preview_path(fid).unlink(missing_ok=True)
    return jsonify({"ok": True})


@app.errorhandler(401)
def _unauthorized(_e):
    return jsonify({"error": "unauthorized"}), 401


@app.errorhandler(404)
def _not_found(e):
    return jsonify({"error": str(getattr(e, "description", "not found"))}), 404


def serve(host: str = "127.0.0.1", port: int = 8400) -> None:
    from waitress import serve as waitress_serve
    from . import scheduler

    scheduler.start()
    print(f"synicch api on {host}:{port}", flush=True)
    # Threads, not processes: one user, and streaming a video holds a thread.
    waitress_serve(app, host=host, port=port, threads=8)
