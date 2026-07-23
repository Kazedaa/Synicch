"""A single-holder lock for scans.

Three things can start a scan -- the nightly scheduler, the app's "Scan now"
button, and a person over SSH. Two at once would fight over SQLite writes and
decode the same files twice, so they take a lock instead.

flock is used rather than a PID file because the kernel releases it
automatically if the process is killed, so a crashed scan cannot leave a stale
lock that blocks every future one.
"""
from __future__ import annotations

import fcntl
import os
from contextlib import contextmanager
from pathlib import Path

from . import config


@contextmanager
def scan_lock(blocking: bool = False):
    """Yields True if the lock was acquired, False if another scan holds it."""
    config.HOME.mkdir(parents=True, exist_ok=True)
    path = Path(config.HOME) / "scan.lock"
    fd = os.open(path, os.O_CREAT | os.O_RDWR, 0o644)
    acquired = False
    try:
        flags = fcntl.LOCK_EX if blocking else fcntl.LOCK_EX | fcntl.LOCK_NB
        try:
            fcntl.flock(fd, flags)
            acquired = True
            os.ftruncate(fd, 0)
            os.write(fd, f"{os.getpid()}\n".encode())
        except BlockingIOError:
            acquired = False
        yield acquired
    finally:
        if acquired:
            fcntl.flock(fd, fcntl.LOCK_UN)
        os.close(fd)
