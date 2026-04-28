# Restaurant Music Player (Android)

Single-screen Android app for the POS tablet. Pulls a track inventory from
the LAN server every morning, keeps the local cache in sync, and plays a
shuffled stream during business hours.

## Architecture

- **Application (`App.kt`)** — schedules the daily sync, kicks off an
  immediate sync on first launch, and starts the playback service.
- **`PlaybackService`** — Media3 `MediaSessionService` hosting an
  `ExoPlayer`. Reconciles play / pause against business hours (cached from
  `/hours`) and a short-lived user override (manual play / pause from the
  UI). The override expires automatically at the next hours boundary, so
  the schedule resumes the next day without intervention.
- **`SyncWorker`** — daily WorkManager job at 04:00 local time. Fetches
  `/list` and `/hours`, downloads missing tracks, deletes locals not on
  the server or with a stale SHA-1.
- **`BootReceiver`** — restarts the service and rescheds the daily worker
  on device boot.
- **`MainActivity`** — Compose UI: status, big play/pause, server URL
  field, "sync now", battery-optimization shortcut.

The Pi's `/hours` is the authoritative schedule (including timezone). The
tablet's clock is only used to evaluate "is *now* inside the configured
window in that timezone?".

## Building

Open `android/` in **Android Studio Ladybug (2024.2.1) or newer** and let
it sync Gradle. To build a debug APK from the command line you'll need a
local copy of the Gradle wrapper jar:

```bash
# In android/, one-time wrapper bootstrap (needs a system Gradle 8.10+)
gradle wrapper --gradle-version 8.10.2

# Then build:
./gradlew :app:assembleRelease
```

The unsigned-but-debug-signed release APK lands at
`app/build/outputs/apk/release/app-release.apk`.

> The release config uses the debug signing key by default — fine for
> sideloading onto a POS tablet, not for Play Store distribution.

## Installing on the POS tablet

1. Enable **Developer options → USB debugging** on the tablet.
2. `adb install -r app-release.apk`
3. Open the app once. It will:
   - Request notification permission (tap allow — needed for the playback
     notification).
   - Run an initial sync; tracks land in app-private storage.
4. Open battery settings via the in-app button and choose **Don't
   optimize** for the app, otherwise Android may kill the playback
   service overnight.
5. Confirm the server URL matches the Pi (default
   `http://192.168.1.248:8080`).

## Day-to-day

- **Drop new MP3s on the Pi** → next 04:00 sync (or tap **Sync now**) and
  the tablet picks them up.
- **Edit hours on the Pi** → `sudo nano /opt/music-player/hours.json` →
  picked up by the next sync (or the tablet's hourly hours fetch on the
  next boundary).
- **Pause for a private event** → tap **Pause** in the app. It stays
  paused until the next open/close boundary, then auto-resumes the
  schedule.
- **Play after hours** → tap **Play**. Same auto-clear at next boundary.

## Storage

Tracks live in `Context.filesDir/music/` (app-private). Uninstalling the
app removes them.
