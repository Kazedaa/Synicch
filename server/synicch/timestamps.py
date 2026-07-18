"""Working out when a photo was actually taken.

The hard part is that cameras write local wall-clock time with no timezone
attached -- just "18:45", floating. The server runs UTC. Comparing those
directly puts evening photos on the wrong day and slices album boundaries
through the middle of afternoons.

So every file records three things: what the camera literally wrote, the real
UTC instant, and which source that came from -- so the trustworthiness of any
given timestamp is always visible rather than assumed.
"""
from __future__ import annotations

import datetime as dt
import re
from pathlib import Path
from zoneinfo import ZoneInfo

from . import config

# EXIF tag numbers (Exif IFD, 0x8769)
_DATETIME_ORIGINAL = 36867     # 0x9003
_DATETIME_DIGITIZED = 36868    # 0x9004
_OFFSET_TIME_ORIGINAL = 36881  # 0x9011 -- present on newer Android, absent on most
_OFFSET_TIME = 36880           # 0x9010
_DATETIME_IFD0 = 306           # 0x0132

# IMG_20250101_194627610_HDR.jpg / VID_20260506_030317_823.mp4 / PXL_ / Screenshot_
_FILENAME_TS = re.compile(r"(?:^|[^0-9])(\d{8})[_-](\d{6})(\d{0,3})(?:[^0-9]|$)")

_EXIF_DT_FORMATS = ("%Y:%m:%d %H:%M:%S", "%Y-%m-%d %H:%M:%S")


def now_utc_iso() -> str:
    return dt.datetime.now(dt.timezone.utc).replace(microsecond=0).isoformat()


class Resolved:
    """The four timestamp columns for one file."""

    __slots__ = ("local", "offset", "utc", "source")

    def __init__(self, local: dt.datetime, offset: str | None,
                 utc: dt.datetime, source: str):
        self.local = local
        self.offset = offset
        self.utc = utc
        self.source = source

    def as_row(self) -> tuple[str, str | None, str, str]:
        return (
            self.local.replace(microsecond=0).isoformat(),
            self.offset,
            self.utc.replace(microsecond=0).isoformat(),
            self.source,
        )

    def __repr__(self) -> str:
        return f"<Resolved {self.local.isoformat()} {self.source}>"


def _plausible(d: dt.datetime) -> bool:
    """Reject the 1970s and the future.

    WhatsApp media and interrupted downloads produce these constantly, and a
    single bogus date is enough to create a garbage album.
    """
    if d.year < config.MIN_PLAUSIBLE_YEAR:
        return False
    now = dt.datetime.now(dt.timezone.utc)
    naive_now = now.replace(tzinfo=None)
    ref = d.replace(tzinfo=None) if d.tzinfo else d
    return ref <= naive_now + dt.timedelta(days=1)


def _parse_exif_datetime(value) -> dt.datetime | None:
    if not value:
        return None
    text = value.decode("ascii", "ignore") if isinstance(value, bytes) else str(value)
    text = text.strip().rstrip("\x00").strip()
    if not text or text.startswith("0000"):
        return None
    for fmt in _EXIF_DT_FORMATS:
        try:
            return dt.datetime.strptime(text[:19], fmt)
        except ValueError:
            continue
    return None


def _parse_offset(value) -> str | None:
    """EXIF OffsetTimeOriginal, e.g. '+05:30'."""
    if not value:
        return None
    text = value.decode("ascii", "ignore") if isinstance(value, bytes) else str(value)
    text = text.strip().rstrip("\x00").strip()
    if re.fullmatch(r"[+-]\d{2}:\d{2}", text):
        return text
    return None


def _offset_to_tz(offset: str) -> dt.timezone:
    sign = 1 if offset[0] == "+" else -1
    hours, minutes = int(offset[1:3]), int(offset[4:6])
    return dt.timezone(sign * dt.timedelta(hours=hours, minutes=minutes))


def from_exif(path: Path) -> tuple[dt.datetime | None, str | None]:
    """Returns (naive local datetime, offset string or None)."""
    try:
        from PIL import Image
        with Image.open(path) as img:
            exif = img.getexif()
            if not exif:
                return None, None
            try:
                sub = exif.get_ifd(0x8769)
            except Exception:
                sub = {}

            raw = sub.get(_DATETIME_ORIGINAL) or sub.get(_DATETIME_DIGITIZED)
            offset = _parse_offset(
                sub.get(_OFFSET_TIME_ORIGINAL) or sub.get(_OFFSET_TIME))
            if raw is None:
                raw = exif.get(_DATETIME_IFD0)
            parsed = _parse_exif_datetime(raw)
            return parsed, offset
    except Exception:
        return None, None


def from_filename(name: str) -> dt.datetime | None:
    """Motorola and Pixel both put the timestamp straight in the filename.

    Useful well beyond a fallback: it is the only source that survives EXIF
    being stripped, which happens to anything that has been through a messaging
    app.
    """
    m = _FILENAME_TS.search(name)
    if not m:
        return None
    date_s, time_s = m.group(1), m.group(2)
    try:
        return dt.datetime.strptime(date_s + time_s, "%Y%m%d%H%M%S")
    except ValueError:
        return None


def resolve(path: Path, *, tz_name: str, stat_mtime: float,
            name: str | None = None) -> Resolved:
    """Best available capture time, with its provenance recorded.

    Order of preference:
      1. EXIF with a real timezone offset -- unambiguous, needs no assumption
      2. EXIF without one -- interpreted in the configured display timezone
      3. Filename -- same treatment
      4. Filesystem mtime -- an absolute instant, converted back to local

    Note that (4) is fundamentally different from (2) and (3): mtime is a real
    UTC instant while the others are floating wall-clock readings. Mixing them
    without converting is the subtle bug that scrambles sort order.

    `name` overrides the filename used for step (3) -- callers pass the version
    with Android's .trashed-/.pending- prefix stripped, since that prefix
    carries its own unrelated timestamp.
    """
    tz = ZoneInfo(tz_name)

    exif_dt, exif_offset = from_exif(path)
    if exif_dt and _plausible(exif_dt):
        if exif_offset:
            tzinfo = _offset_to_tz(exif_offset)
            aware = exif_dt.replace(tzinfo=tzinfo)
            return Resolved(exif_dt, exif_offset,
                            aware.astimezone(dt.timezone.utc), "exif_with_offset")
        aware = exif_dt.replace(tzinfo=tz)
        return Resolved(exif_dt, None,
                        aware.astimezone(dt.timezone.utc), "exif")

    name_dt = from_filename(name or path.name)
    if name_dt and _plausible(name_dt):
        aware = name_dt.replace(tzinfo=tz)
        return Resolved(name_dt, None,
                        aware.astimezone(dt.timezone.utc), "filename")

    utc = dt.datetime.fromtimestamp(stat_mtime, dt.timezone.utc)
    local = utc.astimezone(tz).replace(tzinfo=None)
    return Resolved(local, None, utc, "mtime")
