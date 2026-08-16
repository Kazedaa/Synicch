# Synicch

A self-hosted photo library built for your own server and Android phone.

Synicch takes back control of your photos. It gives you the smooth, native gallery experience of a cloud service, but keeps your data entirely on your own hardware. 

It is built for modest setups: keeping your photos private without relying on big tech cloud services.

## Features

- **Fast Native Android App**: Built with Kotlin and Jetpack Compose for a smooth scrolling timeline and fluid photo viewing.
- **Automatic Syncing**: Uses Syncthing to reliably copy your camera roll to your server in the background.
- **Smart Cleanup**: Automatically detects blurry photos, screenshots, and junk, offering them up in a "Cleanup" section so you can easily free up space on your phone.
- **Privacy First**: No face recognition. No object or scene search. No machine learning models scanning your life. Nothing is uploaded anywhere, and no third party sees your library.
- **Safe Deletion**: Deleting a photo from the app is reversible for 30 days before it is permanently removed from the server.
- **Organized Albums**: Easily group your photos into albums to keep track of trips and events.

## Why Synicch?

The absence of AI features is the point rather than a missing feature. The cost of those conveniences in mainstream apps is a service that reads and analyzes every photo you own. Synicch exists because that trade is not always worth making.

There is also no built-in upload path in the app. Syncthing owns the backup and is better at it; adding a second way for photos to travel would mean two systems that can disagree about what you have.

---

## Installation & Setup

**Requirements:**
- **Server**: Linux, Python 3.12+, SQLite 3.45+, Syncthing, and a reverse proxy with TLS (like Caddy or Nginx). `ffmpeg`/`ffprobe` if you have videos.
- **Build**: Android Studio and a recent JDK.

### 1. Server Setup

Point Syncthing at your camera roll first, and set the folder on the server to **Receive Only** with staggered file versioning. The library directory must sit on the same filesystem as the Syncthing folder, because Synicch relies on hardlinks to save disk space.

```bash
apt-get install -y python3-venv python3-pip
python3 -m venv /opt/synicch/venv
/opt/synicch/venv/bin/pip install pillow numpy
```

Then deploy from your machine:

```bash
cd server
SYNICCH_HOST=root@your-server ./deploy.sh
ssh root@your-server synicch init
ssh root@your-server synicch scan
```

Paths are configurable through `SYNICCH_CAMERA_BACKUP` and `SYNICCH_HOME`.

### 2. Building and Pairing the App

The app trusts your reverse proxy's certificate and nothing else. You must supply your CA certificate:

```bash
scp root@your-server:/path/to/your/ca/root.crt \
    app/app/src/main/res/raw/caddy_ca.crt
```

The default server name is `synicch.local`. Change it in `app/app/src/main/res/xml/network_security_config.xml` and `app/src/main/java/com/synicch/ui/Pairing.kt` if yours differs.

```bash
cd app
./gradlew :app:assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

To connect the app to your server, generate a pairing QR code:

```bash
ssh root@your-server synicch pair --name phone
```

Scan the QR code in the app. The token is shown once and cannot be recovered — pair again if you lose it.

---

## Server Commands

Synicch is managed via a command-line interface on your server:

```
synicch init                      create directories and database, run migrations
synicch scan                      index, then generate thumbnails
synicch scan --refresh            reprocess everything
synicch status                    library summary
synicch detect                    recompute cleanup suggestions
synicch purge --dry-run           show what expired trash would be removed
synicch serve                     run the API
synicch pair --name phone         print a QR code to pair a device
synicch tokens [--revoke phone]   list or revoke paired devices
synicch settings [key value]      show or change settings
```

---

## Technical Details

### Architecture

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
                                                    Reverse Proxy (e.g. Caddy)
                                                             |
                                                             v
                                                    Android app
```

Two processes share one database — a scan and the API — which is why the database runs in WAL mode. Only one scan runs at a time; a second attempt exits rather than interleaving with the first.

### Storage Model

- **The Syncthing folder is Receive Only.** Photos travel from the phone to the server and never back. Nothing here assumes the server can write toward the phone.
- **Every file keeps one hardlink in `library/_archive/`.** The album, unsorted, and trash folders are views, wiped and rebuilt on each scan. 
- **"Is this already backed up?" is answered by hashing the bytes**, never by filename or size. That question decides what gets deleted off your phone.

### The API

Runs on `127.0.0.1:8400` and expects a reverse proxy in front of it. Every endpoint needs `Authorization: Bearer <token>` except `/api/ping`. The image endpoints also accept `?token=`, because the app's image loader cannot set a header per request.

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

### How timestamps work

Cameras write local wall-clock time with no timezone attached. Each file therefore stores what the camera literally wrote, the resolved UTC instant used for sorting, and where that came from:

| Source | Meaning |
|---|---|
| `exif_with_offset` | The photo carried a real timezone. Unambiguous. |
| `exif` | EXIF time, interpreted in your `display_timezone` setting. |
| `filename` | Parsed from names like `IMG_20250101_194627610_HDR.jpg`. Survives EXIF being stripped. |
| `mtime` | Filesystem time. Least trustworthy. |

Changing timezone is a setting plus a refresh:

```bash
synicch settings display_timezone America/Los_Angeles
synicch scan --refresh
```

### Android's renamed files

Android does not delete media immediately, it renames it, and both cases need handling:

- **`.pending-<ts>-NAME`** — a write still in progress. Skipped entirely.
- **`.trashed-<ts>-NAME`** — deleted by you, held about 30 days before Android removes it for good. Indexed and kept, because a photo you deleted from your phone is exactly what a keep-forever backup is for, but held out of the main timeline and offered under Cleanup instead.
