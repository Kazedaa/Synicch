"""Pairing tokens.

No password screen. `synicch pair` prints a QR code in the terminal; the app
scans it once and stores the token in Android's encrypted keystore. Typing a
password into a phone repeatedly is miserable, and this is both easier and
harder to shoulder-surf.

Only the hash of a token is stored, so the database leaking does not hand
anyone access -- and a token can be revoked without touching the others.
"""
from __future__ import annotations

import hashlib
import json
import secrets

from .timestamps import now_utc_iso

TOKEN_BYTES = 32


def _hash(token: str) -> str:
    return hashlib.sha256(token.encode()).hexdigest()


def create(conn, name: str = "phone") -> str:
    token = secrets.token_urlsafe(TOKEN_BYTES)
    conn.execute(
        "INSERT INTO api_tokens(name, token_hash, created_at) VALUES(?,?,?)",
        (name, _hash(token), now_utc_iso()),
    )
    return token


def verify(conn, token: str | None) -> bool:
    if not token:
        return False
    row = conn.execute(
        "SELECT id FROM api_tokens WHERE token_hash=? AND revoked_at IS NULL",
        (_hash(token),),
    ).fetchone()
    if not row:
        return False
    conn.execute("UPDATE api_tokens SET last_used=? WHERE id=?",
                 (now_utc_iso(), row["id"]))
    return True


def revoke(conn, name: str) -> int:
    cur = conn.execute(
        "UPDATE api_tokens SET revoked_at=? WHERE name=? AND revoked_at IS NULL",
        (now_utc_iso(), name),
    )
    return cur.rowcount


def listing(conn) -> list:
    return conn.execute(
        "SELECT name, created_at, last_used, revoked_at FROM api_tokens ORDER BY id"
    ).fetchall()


def local_addresses() -> list[str]:
    """Direct addresses the app can fall back to when DNS fails.

    A hostname is only as reliable as the resolver behind it, and a home
    network that hands out a public DNS server alongside Pi-hole will
    authoritatively deny local names roughly half the time. Shipping the IPs
    alongside the hostname means one bad resolver cannot take the app offline.
    """
    import re
    import subprocess

    out: list[str] = []

    def add(addr: str) -> None:
        url = f"https://{addr}"
        if addr and url not in out:
            out.append(url)

    # LAN address, discovered the same way the kernel would pick it.
    try:
        r = subprocess.run(["ip", "route", "get", "1.1.1.1"],
                           capture_output=True, text=True, timeout=5).stdout
        m = re.search(r"\bsrc\s+(\d+\.\d+\.\d+\.\d+)", r)
        if m:
            add(m.group(1))
    except Exception:
        pass

    # Tailscale address, so the app keeps working away from home.
    try:
        r = subprocess.run(["tailscale", "ip", "-4"],
                           capture_output=True, text=True, timeout=5).stdout
        for line in r.splitlines():
            if line.strip():
                add(line.strip())
    except Exception:
        pass

    return out


def pairing_payload(url: str, token: str, fallbacks: list[str] | None = None) -> str:
    """Kept compact -- every character makes the QR denser and harder to scan."""
    data: dict = {"u": url, "t": token}
    if fallbacks:
        data["f"] = fallbacks
    return json.dumps(data, separators=(",", ":"))
