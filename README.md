# Synicch

A self-hosted photo library for one phone and one small home server.

Syncthing copies your camera roll to a machine you own. Synicch indexes what
lands there, generates thumbnails, groups photos into albums, points out the
junk worth deleting, and serves the whole thing to a native Android app.

It is built for a real, modest setup: roughly 40,000 photos, a server that is
a repurposed phone, and a home network. Not a cloud service, not a cluster.

## What it deliberately does not do

No face recognition. No object or scene search. No machine learning of any
kind. Nothing is uploaded anywhere, and no third party sees your library.

That absence is the point rather than a missing feature. The cost of those
conveniences is a service that reads every photo you own, and this project
exists because that trade is not always worth making.

There is also no upload path from the app to the server. Syncthing owns the
backup and is better at it; adding a second way for photos to travel would
mean two systems that can disagree about what you have.

## How it fits together

```
phone camera roll
      |
      |  Syncthing (Receive Only)
      v
camera-backup/ ------> synicch scan ------> synicch.db (SQLite)
                       index, thumbnail,          |
                       score, detect              |
                                                  |
                          library/ hardlink tree  |  synicch serve (:8400)
                          (a generated view)      |          |
                                                             v
                                                    Caddy, HTTPS
                                                             |
                                                             v
                                                    Android app
```

Two processes share one database — a scan and the API — which is why the
database runs in WAL mode. Only one scan runs at a time; a second attempt
exits rather than interleaving with the first.

### The parts

- **`server/`** — Python. Flask for the API, SQLite for storage, Pillow and
  numpy for image work. No OpenCV: the two things needed from it, a blur
  measure and a perceptual hash, are a few lines of numpy each and do not
  justify a 90MB dependency on a phone-sized machine.
- **`app/`** — Android, Kotlin and Jetpack Compose. The only front end.

### Things worth knowing about the storage model

These are load-bearing, and surprising enough to state up front.

- **The Syncthing folder is Receive Only.** Photos travel from the phone to
  the server and never back. Nothing here assumes the server can write toward
  the phone.
- **Every file keeps one hardlink in `library/_archive/`.** The album,
  unsorted and trash folders are views, wiped and rebuilt on each scan. The
  backup folder follows the phone, so it empties when you delete a photo
  there. Only the archive link survives both, and it is what actually holds
  the file. Hardlinks, so it costs no disk space.
- **Exactly one function deletes a file**, and only for items already in
  trash and past their retention. Everything else changes the database or the
  links, never the bytes.
- **"Is this already backed up?" is answered by hashing the bytes**, never by
  filename or size. That question decides what gets deleted off your phone,
  so it gets a real answer.
- **Deleting is reversible for 30 days.** The server is told first, which is
  what creates the keeper link; only once the library holds the file
  independently is the phone's copy released. Android shows its own
  confirmation for that half, and cancelling it puts the item back.

## Requirements

**Server** — Linux, Python 3.12+, SQLite 3.45+, Syncthing, and a reverse proxy
with TLS (Caddy here). `ffmpeg`/`ffprobe` if you have videos. The library
directory must sit on the same filesystem as the Syncthing folder, because
hardlinks cannot cross filesystems; `synicch init` checks and tells you.

**Development** — Android Studio, and a JDK the Android tooling accepts.

## Setting up the server

Point Syncthing at your camera roll first, and set the folder on the server to
**Receive Only** with staggered file versioning. Both matter: Send & Receive
means a deletion on the server propagates back and removes photos from your
phone.

```bash
apt-get install -y python3-venv python3-pip
python3 -m venv /opt/synicch/venv
/opt/synicch/venv/bin/pip install pillow numpy
```

Then from your machine:

```bash
cd server
SYNICCH_HOST=root@your-server ./deploy.sh
ssh root@your-server synicch init
ssh root@your-server synicch scan
```

Paths are configurable through `SYNICCH_CAMERA_BACKUP` and `SYNICCH_HOME`;
the defaults are `/var/lib/filebrowser/data/camera-backup` and
`/var/lib/synicch`.

If the server does other work — this one is also the house's DNS — run scans
politely:

```bash
nice -n 19 ionice -c 3 synicch scan
```

## Commands

```
synicch init                      create directories and database, run migrations
synicch scan                      index, then generate thumbnails
synicch scan --no-media           index only, skip decoding
synicch scan --refresh            reprocess everything
synicch media [--force]           thumbnails and scores only
synicch status                    library summary
synicch detect                    recompute cleanup suggestions
synicch tree                      rebuild the library folder tree
synicch purge --dry-run           show what expired trash would be removed
synicch serve                     run the API
synicch pair --name phone         print a QR code to pair a device
synicch tokens [--revoke phone]   list or revoke paired devices
synicch settings [key value]      show or change settings
```

Scans are idempotent: a file whose size and modified time have not changed is
skipped without being read, so re-running costs almost nothing.

## The API

Runs on `127.0.0.1:8400` and expects a reverse proxy in front of it. Every
endpoint needs `Authorization: Bearer <token>` except `/api/ping`. The image
endpoints also accept `?token=`, because the app's image loader cannot set a
header per request.

```
GET  /api/ping                     reachability, no auth
GET  /api/status                   counts, disk, last scan, scanning flag
GET  /api/media?limit=&cursor=     newest first, cursor-paginated
GET  /api/media/<id>
GET  /api/media/<id>/thumb         400px
GET  /api/media/<id>/preview       1600px, generated on first request
GET  /api/media/<id>/original      supports range requests
POST /api/scan                     launches a scan, returns immediately
```

Paging uses a `(captured_utc, id)` cursor rather than an offset, so a scan
inserting rows while you are scrolling cannot make the app skip or repeat a
photo.

## Building the app

The app trusts your reverse proxy's certificate and nothing else — not even
the public certificate authorities. So you have to supply it:

```bash
scp root@your-server:/var/lib/caddy/.local/share/caddy/pki/authorities/local/root.crt \
    app/app/src/main/res/raw/caddy_ca.crt
```

That file is gitignored, so your certificate stays yours -- which is why
`res/raw` looks empty in a fresh clone. See `caddy_ca.crt.example`.

The default server name is `photos.ngserver`. Change it in
`app/app/src/main/res/xml/network_security_config.xml` and
`app/src/main/java/com/synicch/ui/Pairing.kt` if yours differs — the security
config scopes trust to that domain, so it has to match.

```bash
cd app
./gradlew :app:assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

`gradle.properties` points the build at Android Studio's bundled JDK rather
than the system one, because Android's build tooling lags behind current JDK
releases. Change the path to match your install, or remove the line if your
system Java is already old enough.

Then pair:

```bash
ssh root@your-server synicch pair --name phone
```

Scan the QR code. The token is shown once and cannot be recovered — pair
again if you lose it.

## How timestamps work

This is the part most likely to surprise you. Cameras write local wall-clock
time with no timezone attached, so "when was this taken" has no single
answer. Each file therefore stores what the camera literally wrote, the
resolved UTC instant used for sorting, and where that came from:

| Source | Meaning |
|---|---|
| `exif_with_offset` | The photo carried a real timezone. Unambiguous. |
| `exif` | EXIF time, interpreted in your `display_timezone` setting. |
| `filename` | Parsed from names like `IMG_20250101_194627610_HDR.jpg`. Survives EXIF being stripped. |
| `mtime` | Filesystem time. Least trustworthy. |

Changing timezone is a setting plus a refresh, and only affects photos whose
camera did not record a real offset:

```bash
synicch settings display_timezone America/Los_Angeles
synicch scan --refresh
```

## Android's renamed files

Android does not delete media immediately, it renames it, and both cases need
handling:

- **`.pending-<ts>-NAME`** — a write still in progress. Skipped entirely; its
  size and fingerprint would be wrong. It reappears under its real name when
  the write finishes.
- **`.trashed-<ts>-NAME`** — deleted by you, held about 30 days before Android
  removes it for good. Indexed and kept, because a photo you deleted from your
  phone is exactly what a keep-forever backup is for, but held out of the main
  timeline and offered under Cleanup instead.

## Status

Working and in daily use on the setup it was built for. It has been exercised
against a real library of roughly 2,400 photos, not a synthetic one.

There is no automated test suite. Verification is by running the CLI against a
real library and checking the results, which is honest about what it is.

Videos are supported throughout and the code paths exist, but the library it
was developed against had none at the time, so that half has had far less
real-world exercise than the photo path.
