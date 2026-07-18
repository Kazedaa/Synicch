"""Cheap content fingerprints.

Hashing every file whole means reading the entire library off disk, which is
the dominant cost of a full pass. Instead: file size plus a chunk from each end.
That reads ~128KB per file rather than several megabytes -- roughly a 25x
reduction across a large library, for the same practical result.

A full SHA-256 is computed only when something is about to *act* on two files
being identical, or when verifying before deleting an original off the phone.
Nothing destructive ever runs on a fingerprint alone.
"""
from __future__ import annotations

import hashlib
from pathlib import Path

from .config import FINGERPRINT_CHUNK


def quick_fingerprint(path: Path, size: int | None = None,
                      chunk: int = FINGERPRINT_CHUNK) -> str:
    """Size plus the first and last `chunk` bytes, hashed together.

    Size is mixed in so two files sharing head and tail but differing in the
    middle length can never collide.
    """
    if size is None:
        size = path.stat().st_size

    h = hashlib.sha256()
    h.update(str(size).encode())

    with path.open("rb") as f:
        head = f.read(chunk)
        h.update(head)
        if size > chunk * 2:
            f.seek(-chunk, 2)
            h.update(f.read(chunk))
        elif size > chunk:
            # Small file: the tail overlaps the head, so just take what is left
            # rather than double-counting bytes already hashed.
            f.seek(chunk)
            h.update(f.read())

    return h.hexdigest()


def full_sha256(path: Path, buf_size: int = 1024 * 1024) -> str:
    """Complete hash. Only call this when a decision depends on certainty."""
    h = hashlib.sha256()
    with path.open("rb") as f:
        while True:
            block = f.read(buf_size)
            if not block:
                break
            h.update(block)
    return h.hexdigest()
