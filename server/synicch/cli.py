"""Command-line entry point."""
from __future__ import annotations

import argparse
import sys
from zoneinfo import ZoneInfo, available_timezones

from . import config, db
from .indexer import index


def _fmt_bytes(n: float) -> str:
    for unit in ("B", "KB", "MB", "GB", "TB"):
        if abs(n) < 1024:
            return f"{n:.1f}{unit}"
        n /= 1024
    return f"{n:.1f}PB"


def cmd_init(args) -> int:
    config.ensure_dirs()
    conn = db.connect()
    db.init_db(conn)
    print(f"database   {config.DB_PATH}")
    print(f"schema     v{db.schema_version(conn)}")
    print(f"backup     {config.CAMERA_BACKUP}"
          f"{'' if config.CAMERA_BACKUP.is_dir() else '   ** NOT FOUND **'}")
    print(f"library    {config.LIBRARY}")
    print(f"timezone   {db.get_setting(conn, 'display_timezone')}")

    # Album folders are hardlinks, which cannot cross filesystems. Catch a
    # misconfiguration here rather than at the first link attempt.
    try:
        a = config.CAMERA_BACKUP.stat().st_dev
        b = config.HOME.stat().st_dev
        if a != b:
            print("\n** WARNING ** backup and library are on different filesystems.")
            print("   Album folders use hardlinks and will fail. Move SYNICCH_HOME.")
        else:
            print("filesystem same as backup -- hardlinks will work")
    except OSError as e:
        print(f"filesystem check failed: {e}")
    conn.close()
    return 0


def cmd_scan(args) -> int:
    config.ensure_dirs()
    return _scan(args)


def _scan(args) -> int:
    conn = db.connect()
    if db.schema_version(conn) == 0:
        db.init_db(conn)

    print(f"scanning {config.CAMERA_BACKUP}")
    result = index(conn, verbose=not args.quiet, refresh=args.refresh)

    print()
    print(f"  seen       {result['seen']}")
    print(f"  added      {result['added']}")
    print(f"  updated    {result['updated']}")
    print(f"  unchanged  {result['skipped']}")
    print(f"  missing    {len(result['missing'])}")
    print(f"  errors     {result['errors']}")
    print(f"  read       {_fmt_bytes(result['bytes_read'])}")
    print(f"  elapsed    {result['elapsed']:.1f}s"
          f"  ({result['seen'] / max(result['elapsed'], 0.001):.0f} files/s)")

    if result["missing"]:
        print(f"\n  {len(result['missing'])} file(s) gone from the backup folder")
        for m in result["missing"][:10]:
            print(f"    - {m}")
        if len(result["missing"]) > 10:
            print(f"    ... and {len(result['missing']) - 10} more")

    if not args.no_media:
        print()
        _run_media(conn, args)

    conn.close()
    return 0


def _run_media(conn, args) -> None:
    from . import processor
    r = processor.run(conn, workers=args.workers, force=getattr(args, "force", False),
                      limit=getattr(args, "limit", None), verbose=not args.quiet,
                      max_seconds=args.max_seconds)
    if r["total"] == 0:
        print("thumbnails up to date")
        return
    print(f"\n  decoded    {r['done']}/{r['total']}")
    print(f"  errors     {r['errors']}")
    print(f"  elapsed    {r['elapsed']:.1f}s"
          f"  ({r['done'] / max(r['elapsed'], 0.001):.1f}/s)")
    if r.get("stopped_early"):
        print("  stopped at the time limit -- re-run to continue")
    size, count = processor.cache_size()
    print(f"  cache      {_fmt_bytes(size)} in {count} files")


def cmd_media(args) -> int:
    config.ensure_dirs()
    conn = db.connect()
    _run_media(conn, args)
    conn.close()
    return 0


def cmd_status(args) -> int:
    conn = db.connect(readonly=True)
    total, active = conn.execute(
        "SELECT COUNT(*), SUM(state='active') FROM files").fetchone()
    print(f"files          {total}   ({active or 0} active)")

    print("\nby kind")
    for r in conn.execute(
            "SELECT kind, COUNT(*) n, SUM(size) b FROM files GROUP BY kind ORDER BY n DESC"):
        print(f"  {r['kind']:8} {r['n']:6}  {_fmt_bytes(r['b'] or 0)}")

    ptrash, ptrash_b = conn.execute(
        "SELECT COUNT(*), COALESCE(SUM(size),0) FROM files WHERE phone_trashed=1"
    ).fetchone()
    if ptrash:
        print(f"\nphone trash    {ptrash}  {_fmt_bytes(ptrash_b)}"
              f"   (deleted on the phone, kept here)")

    print("\ntimestamp source")
    for r in conn.execute(
            "SELECT ts_source, COUNT(*) n FROM files GROUP BY ts_source ORDER BY n DESC"):
        print(f"  {str(r['ts_source']):18} {r['n']:6}")

    row = conn.execute(
        "SELECT MIN(captured_utc) a, MAX(captured_utc) b FROM files "
        "WHERE captured_utc IS NOT NULL").fetchone()
    if row and row["a"]:
        print(f"\ndate range     {row['a'][:10]}  ..  {row['b'][:10]}")

    dupes = conn.execute(
        "SELECT COUNT(*) FROM (SELECT quick_fp FROM files WHERE quick_fp IS NOT NULL "
        "GROUP BY quick_fp HAVING COUNT(*) > 1)").fetchone()[0]
    print(f"dup candidates {dupes}   (fingerprint matches, unconfirmed)")

    errs = conn.execute(
        "SELECT COUNT(*) FROM files WHERE scan_error IS NOT NULL").fetchone()[0]
    if errs:
        print(f"scan errors    {errs}")

    thumbed = conn.execute(
        "SELECT COUNT(*) FROM files WHERE thumb_at IS NOT NULL").fetchone()[0]
    scored = conn.execute("SELECT COUNT(*) FROM scores").fetchone()[0]
    print(f"\nthumbnails     {thumbed}/{total}")
    print(f"scored         {scored}/{total}")
    if scored:
        r = conn.execute(
            "SELECT MIN(laplacian_var) lo, AVG(laplacian_var) av, "
            "MAX(laplacian_var) hi FROM scores WHERE laplacian_var IS NOT NULL"
        ).fetchone()
        print(f"  sharpness    min {r['lo']:.0f}  avg {r['av']:.0f}  max {r['hi']:.0f}"
              f"   (calibrate blur_threshold against this)")

    print(f"\ntimezone       {db.get_setting(conn, 'display_timezone')}")
    last = conn.execute(
        "SELECT * FROM scan_runs ORDER BY id DESC LIMIT 1").fetchone()
    if last:
        print(f"last scan      {last['started_at']}  ({last['notes'] or ''})")
    conn.close()
    return 0


def cmd_settings(args) -> int:
    conn = db.connect()
    if args.key and args.value is not None:
        if args.key == "display_timezone":
            if args.value not in available_timezones():
                print(f"unknown timezone: {args.value}", file=sys.stderr)
                print("expected an IANA name, e.g. Asia/Kolkata, America/Los_Angeles",
                      file=sys.stderr)
                return 1
            ZoneInfo(args.value)
        db.set_setting(conn, args.key, args.value)
        print(f"{args.key} = {args.value}")
        if args.key == "display_timezone":
            print("\nNote: existing photos keep the UTC instant resolved when they")
            print("were scanned. Run 'synicch scan --refresh' to recompute them.")
    else:
        for r in conn.execute("SELECT key, value FROM settings ORDER BY key"):
            print(f"  {r['key']:26} {r['value']}")
    conn.close()
    return 0


def main(argv=None) -> int:
    p = argparse.ArgumentParser(prog="synicch", description="Self-hosted photo library")
    sub = p.add_subparsers(dest="cmd", required=True)

    sub.add_parser("init", help="create directories and database").set_defaults(fn=cmd_init)

    s = sub.add_parser("scan", help="index the backup folder, then make thumbnails")
    s.add_argument("--refresh", action="store_true",
                   help="reprocess every file, even unchanged ones")
    s.add_argument("--no-media", action="store_true",
                   help="index only; skip the decode pass")
    s.add_argument("--workers", type=int, default=3)
    s.add_argument("--max-seconds", type=float, default=None,
                   help="stop the decode pass after this long (resumable)")
    s.add_argument("-q", "--quiet", action="store_true")
    s.set_defaults(fn=cmd_scan)

    s = sub.add_parser("media", help="generate thumbnails and detection scores")
    s.add_argument("--force", action="store_true", help="redo files already done")
    s.add_argument("--workers", type=int, default=3)
    s.add_argument("--limit", type=int, default=None)
    s.add_argument("--max-seconds", type=float, default=None)
    s.add_argument("-q", "--quiet", action="store_true")
    s.set_defaults(fn=cmd_media)

    sub.add_parser("status", help="library summary").set_defaults(fn=cmd_status)

    s = sub.add_parser("settings", help="show or change a setting")
    s.add_argument("key", nargs="?")
    s.add_argument("value", nargs="?")
    s.set_defaults(fn=cmd_settings)

    args = p.parse_args(argv)
    return args.fn(args)


if __name__ == "__main__":
    raise SystemExit(main())
