"""Camera settings, read from EXIF.

Display only. Nothing in the library's behaviour depends on any of it, so a
file carrying none of it is entirely normal rather than an error -- screenshots,
downloads and anything that has been through a messaging app arrive stripped.

Header-only, like the rest of indexing: no pixels are decoded to get this.
Timestamps deliberately stay in `timestamps.py`, because those *are* load
bearing and have their own rules about trust.
"""
from __future__ import annotations

from pathlib import Path

# IFD0
_MAKE = 271             # 0x010F
_MODEL = 272            # 0x0110

# Exif IFD (0x8769)
_EXPOSURE_TIME = 33434  # 0x829A
_F_NUMBER = 33437       # 0x829D
_ISO = 34855            # 0x8827
_FOCAL_LENGTH = 37386   # 0x920A

FIELDS = ("camera_make", "camera_model", "f_number", "exposure_s", "focal_mm", "iso")


def _text(value) -> str | None:
    if value is None:
        return None
    s = value.decode("ascii", "ignore") if isinstance(value, bytes) else str(value)
    s = s.strip().rstrip("\x00").strip()
    return s or None


def _positive(value) -> float | None:
    """EXIF rationals arrive as fractions; anything zero or absurd is dropped."""
    try:
        f = float(value)
    except (TypeError, ValueError):
        return None
    if f != f or f in (float("inf"), float("-inf")) or f <= 0:
        return None
    return round(f, 6)


def read(path: Path) -> dict:
    """Never raises. A missing or malformed tag is simply absent."""
    out: dict[str, object | None] = dict.fromkeys(FIELDS)
    try:
        from PIL import Image
        with Image.open(path) as img:
            exif = img.getexif()
            if not exif:
                return out

            out["camera_make"] = _text(exif.get(_MAKE))
            out["camera_model"] = _text(exif.get(_MODEL))

            try:
                sub = exif.get_ifd(0x8769)
            except Exception:
                sub = {}

            out["f_number"] = _positive(sub.get(_F_NUMBER))
            out["exposure_s"] = _positive(sub.get(_EXPOSURE_TIME))
            out["focal_mm"] = _positive(sub.get(_FOCAL_LENGTH))

            iso = sub.get(_ISO)
            # Some cameras write a list here rather than a single value.
            if isinstance(iso, (list, tuple)):
                iso = iso[0] if iso else None
            try:
                out["iso"] = int(iso) if iso is not None else None
            except (TypeError, ValueError):
                out["iso"] = None
    except Exception:
        pass
    return out


def label(make: str | None, model: str | None) -> str | None:
    """One readable camera name.

    Makers repeat themselves -- "motorola" plus "motorola edge 50 fusion" would
    read as a stutter -- so the make is dropped when the model already says it.
    """
    make = (make or "").strip()
    model = (model or "").strip()
    if not model:
        return make or None
    if make and make.lower() not in model.lower():
        return f"{make} {model}"
    return model
