"""Nightly scan, run from inside the API process.

Deliberately not cron. There is no init system in this chroot, so cron would be
one more daemon to keep alive and make boot-persistent -- and cron thinks in
UTC, which would mean recomputing the schedule by hand every time the timezone
setting changes. A thread reads the setting on every cycle instead, so moving
between countries is genuinely just the dropdown.
"""
from __future__ import annotations

import datetime as dt
import subprocess
import sys
import threading
import time
from pathlib import Path
from zoneinfo import ZoneInfo

from . import db

CHECK_INTERVAL = 60.0


def next_run(now_utc: dt.datetime, tz_name: str, hour: int) -> dt.datetime:
    """The next occurrence of `hour` local time, as a UTC instant."""
    tz = ZoneInfo(tz_name)
    local = now_utc.astimezone(tz)
    target = local.replace(hour=hour, minute=0, second=0, microsecond=0)
    if target <= local:
        target += dt.timedelta(days=1)
    return target.astimezone(dt.timezone.utc)


def _run_scan() -> None:
    root = str(Path(__file__).resolve().parent.parent)
    subprocess.run(
        ["nice", "-n", "19", "ionice", "-c", "3",
         sys.executable, "-m", "synicch.cli", "scan", "-q"],
        env={"PATH": "/usr/local/bin:/usr/bin:/bin", "PYTHONPATH": root},
        stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL,
        check=False,
    )


def _loop(stop: threading.Event) -> None:
    scheduled: dt.datetime | None = None
    tz_seen = hour_seen = None

    while not stop.is_set():
        try:
            conn = db.connect()
            tz_name = db.get_setting(conn, "display_timezone")
            hour = int(db.get_setting(conn, "scan_hour_local"))
            conn.close()

            now = dt.datetime.now(dt.timezone.utc)

            # Recompute if the settings changed under us, or on first pass.
            if scheduled is None or tz_name != tz_seen or hour != hour_seen:
                scheduled = next_run(now, tz_name, hour)
                tz_seen, hour_seen = tz_name, hour
                print(f"[scheduler] next scan {scheduled.isoformat()} "
                      f"({hour}:00 {tz_name})", flush=True)

            if now >= scheduled:
                print(f"[scheduler] running scan at {now.isoformat()}", flush=True)
                _run_scan()
                scheduled = next_run(dt.datetime.now(dt.timezone.utc), tz_name, hour)
                print(f"[scheduler] next scan {scheduled.isoformat()}", flush=True)
        except Exception as e:  # a scheduler crash must not take the API with it
            print(f"[scheduler] error: {type(e).__name__}: {e}", flush=True)

        stop.wait(CHECK_INTERVAL)


def start() -> threading.Event:
    stop = threading.Event()
    threading.Thread(target=_loop, args=(stop,), daemon=True,
                     name="synicch-scheduler").start()
    return stop
