"""SQLite connection and schema management.

WAL mode is required, not optional: the scanner and the API run as separate
processes and would otherwise block each other on every write.
"""
from __future__ import annotations

import sqlite3
from pathlib import Path
from typing import Any

from . import config

SCHEMA_VERSION = 2
_SCHEMA_SQL = Path(__file__).with_name("schema.sql")

# Applied in order to bring an older database up to date. schema.sql always
# describes the *current* shape, so a fresh database never runs these -- they
# exist only for databases created by an earlier version.
_MIGRATIONS: dict[int, list[str]] = {
    2: [
        "ALTER TABLE files ADD COLUMN display_name TEXT",
        "ALTER TABLE files ADD COLUMN phone_trashed INTEGER NOT NULL DEFAULT 0",
        "CREATE INDEX IF NOT EXISTS idx_files_ptrash ON files(phone_trashed)",
    ],
}


def connect(db_path: Path | None = None, *, readonly: bool = False) -> sqlite3.Connection:
    path = db_path or config.DB_PATH
    path.parent.mkdir(parents=True, exist_ok=True)

    if readonly:
        conn = sqlite3.connect(f"file:{path}?mode=ro", uri=True, timeout=30)
    else:
        conn = sqlite3.connect(path, timeout=30, isolation_level=None)

    conn.row_factory = sqlite3.Row
    conn.execute("PRAGMA journal_mode=WAL")
    conn.execute("PRAGMA foreign_keys=ON")
    conn.execute("PRAGMA synchronous=NORMAL")
    # Two processes share this file; wait rather than fail on a busy lock.
    conn.execute("PRAGMA busy_timeout=30000")
    return conn


def init_db(conn: sqlite3.Connection) -> None:
    conn.executescript(_SCHEMA_SQL.read_text())

    if schema_version(conn) != 0:
        migrate(conn)

    conn.execute(f"PRAGMA user_version = {SCHEMA_VERSION}")
    for key, value in config.DEFAULT_SETTINGS.items():
        conn.execute(
            "INSERT INTO settings(key, value) VALUES(?, ?) "
            "ON CONFLICT(key) DO NOTHING",
            (key, value),
        )


def migrate(conn: sqlite3.Connection) -> list[str]:
    """Bring an existing database up to SCHEMA_VERSION."""
    current = schema_version(conn)
    applied: list[str] = []
    for version in sorted(_MIGRATIONS):
        if version <= current:
            continue
        for stmt in _MIGRATIONS[version]:
            try:
                conn.execute(stmt)
                applied.append(f"v{version}: {stmt.split('(')[0].strip()}")
            except sqlite3.OperationalError as e:
                # A column that already exists, or a table that does not exist
                # yet, are both fine -- schema.sql creates the latter straight
                # after. Anything else is a real problem.
                msg = str(e).lower()
                if "duplicate column" not in msg and "no such table" not in msg:
                    raise
        conn.execute(f"PRAGMA user_version = {version}")
    return applied


def schema_version(conn: sqlite3.Connection) -> int:
    return conn.execute("PRAGMA user_version").fetchone()[0]


def get_setting(conn: sqlite3.Connection, key: str, default: Any = None) -> Any:
    row = conn.execute("SELECT value FROM settings WHERE key=?", (key,)).fetchone()
    if row is None:
        return config.DEFAULT_SETTINGS.get(key, default)
    return row["value"]


def set_setting(conn: sqlite3.Connection, key: str, value: Any) -> None:
    conn.execute(
        "INSERT INTO settings(key, value) VALUES(?, ?) "
        "ON CONFLICT(key) DO UPDATE SET value=excluded.value",
        (key, str(value)),
    )

