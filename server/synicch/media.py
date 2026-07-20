"""Thumbnails, previews, and the numbers junk detection later runs on.

Everything here comes out of a *single* decode per photo. Decoding is the
expensive part of a scan, so the thumbnail, the oriented dimensions and every
detection score are all produced from the same pass. Adding a detector later
should never mean re-reading the library.

No OpenCV: variance-of-Laplacian and a perceptual hash are a handful of numpy
lines, which is not worth a 90MB dependency on a phone.
"""
from __future__ import annotations

import subprocess
from pathlib import Path

import numpy as np
from PIL import Image

from . import config

# Bump when a score's meaning changes, so stale rows can be recomputed without
# guessing which ones are stale.
ALGO_VERSION = 1

THUMB_MAX = 400        # grid tile
PREVIEW_MAX = 1600     # lightbox
THUMB_QUALITY = 78
PREVIEW_QUALITY = 85


def _shard(file_id: int) -> str:
    return f"{file_id % 256:02x}"


def thumb_path(file_id: int) -> Path:
    return config.THUMBS / _shard(file_id) / f"{file_id}.jpg"


def preview_path(file_id: int) -> Path:
    return config.PREVIEWS / _shard(file_id) / f"{file_id}.jpg"


def _open_oriented(path: Path, target: int) -> Image.Image:
    """Open at roughly `target` pixels.

    draft() uses the JPEG format's own scaling to decode at 1/2, 1/4 or 1/8
    size, which is several times faster than decoding fully and then resizing.
    """
    img = Image.open(path)
    try:
        img.draft("RGB", (target, target))
    except Exception:
        pass  # draft is JPEG-only; harmless elsewhere
    return img.convert("RGB")


def dhash(img: Image.Image) -> str:
    """64-bit perceptual fingerprint: is each pixel brighter than its neighbour."""
    small = img.convert("L").resize((9, 8), Image.LANCZOS)
    a = np.asarray(small, dtype=np.int16)
    bits = (a[:, 1:] > a[:, :-1]).flatten()
    value = 0
    for bit in bits:
        value = (value << 1) | int(bit)
    return f"{value:016x}"


def hamming(a: str, b: str) -> int:
    return bin(int(a, 16) ^ int(b, 16)).count("1")


def _scores(img: Image.Image) -> dict:
    """Sharpness, brightness and clipping, all from one grayscale array."""
    g = img.convert("L")
    a = np.asarray(g, dtype=np.float32)

    if a.shape[0] < 3 or a.shape[1] < 3:
        return {"laplacian_var": 0.0, "luma_mean": float(a.mean()) if a.size else 0.0,
                "luma_std": 0.0, "clipped_frac": 0.0}

    # 4-neighbour Laplacian. High variance means lots of strong edges, i.e. in
    # focus; a blurred image has little to vary.
    lap = (-4.0 * a[1:-1, 1:-1]
           + a[:-2, 1:-1] + a[2:, 1:-1]
           + a[1:-1, :-2] + a[1:-1, 2:])

    clipped = float(((a <= 2) | (a >= 253)).mean())
    return {
        "laplacian_var": float(lap.var()),
        "luma_mean": float(a.mean()),
        "luma_std": float(a.std()),
        "clipped_frac": clipped,
    }


def _write_jpeg(img: Image.Image, dest: Path, max_edge: int, quality: int) -> None:
    dest.parent.mkdir(parents=True, exist_ok=True)
    out = img.copy()
    out.thumbnail((max_edge, max_edge), Image.LANCZOS)
    tmp = dest.with_suffix(".tmp")
    out.save(tmp, "JPEG", quality=quality, optimize=True, progressive=True)
    tmp.replace(dest)  # atomic: a reader never sees a half-written thumbnail


def process_photo(path: Path, file_id: int) -> dict:
    """Thumbnail + oriented dimensions + all scores, from one decode."""
    img = _open_oriented(path, PREVIEW_MAX)
    try:
        width, height = img.size
        result = _scores(img)
        result["dhash"] = dhash(img)
        result["width"] = width
        result["height"] = height
        _write_jpeg(img, thumb_path(file_id), THUMB_MAX, THUMB_QUALITY)
        return result
    finally:
        img.close()


def apply_edits(img: Image.Image, edit) -> Image.Image:
    """Crop and rotation, applied at render time.

    Crop is stored as fractions of the image rather than pixels, so it stays
    correct regardless of what resolution this particular render happens to be.
    """
    if edit is None:
        return img
    out = img
    cx, cy = edit["crop_x"], edit["crop_y"]
    cw, ch = edit["crop_w"], edit["crop_h"]
    if None not in (cx, cy, cw, ch) and cw > 0 and ch > 0:
        w, h = out.size
        box = (max(0, int(cx * w)), max(0, int(cy * h)),
               min(w, int((cx + cw) * w)), min(h, int((cy + ch) * h)))
        if box[2] > box[0] and box[3] > box[1]:
            out = out.crop(box)
    rotate = edit["rotate"] or 0
    if rotate:
        out = out.rotate(-rotate, expand=True)  # stored clockwise, PIL is anticlockwise
    return out


def make_preview(path: Path, file_id: int, edit=None) -> Path:
    """Larger render for the lightbox. On demand -- generating these for the
    whole library up front would waste gigabytes on photos never opened."""
    dest = preview_path(file_id)
    if dest.exists():
        return dest
    img = _open_oriented(path, PREVIEW_MAX)
    try:
        _write_jpeg(apply_edits(img, edit), dest, PREVIEW_MAX, PREVIEW_QUALITY)
    finally:
        img.close()
    return dest


def video_poster(path: Path, file_id: int, duration: float | None) -> dict:
    """Grab a frame for the grid tile.

    Seeks ~1s in, or to the midpoint of anything shorter, since the first frame
    of a video is often black.
    """
    dest = thumb_path(file_id)
    dest.parent.mkdir(parents=True, exist_ok=True)
    seek = 1.0 if (duration or 0) > 2 else max((duration or 0) / 2, 0)
    tmp = dest.with_suffix(".tmp")
    try:
        subprocess.run(
            ["ffmpeg", "-y", "-loglevel", "error",
             "-ss", f"{seek:.2f}", "-i", str(path),
             "-frames:v", "1", "-vf", f"scale='min({THUMB_MAX},iw)':-2",
             "-q:v", "4", str(tmp)],
            check=True, capture_output=True, timeout=60,
        )
        tmp.replace(dest)
    except Exception:
        tmp.unlink(missing_ok=True)
        return {}

    try:
        with Image.open(dest) as img:
            rgb = img.convert("RGB")
            out = _scores(rgb)
            out["dhash"] = dhash(rgb)
            return out
    except Exception:
        return {}


def video_codec(path: Path) -> str | None:
    """H.264 plays in every browser; HEVC does not, and transcoding is not
    viable on this hardware. Recorded so the app can warn rather than fail."""
    try:
        out = subprocess.run(
            ["ffprobe", "-v", "error", "-select_streams", "v:0",
             "-show_entries", "stream=codec_name", "-of", "csv=p=0", str(path)],
            capture_output=True, text=True, timeout=30,
        ).stdout.strip()
        return out or None
    except Exception:
        return None
