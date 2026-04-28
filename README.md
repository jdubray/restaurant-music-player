# Restaurant Music Player

A two-piece system for playing AI-generated jazz on the restaurant sound
system. The Pi on the LAN owns the music library; the Android POS tablet
runs a kiosk-style player that syncs from the Pi every morning and plays
on a schedule.

```
   ┌──────────────────────┐                    ┌──────────────────────┐
   │  workstation         │   scp new tracks   │  Pi @ 192.168.1.248  │
   │  music/  (kebab-case)│ ─────────────────▶ │  /opt/music-player/  │
   └──────────────────────┘                    │    music/   (mp3s)   │
                                               │    hours.json        │
                                               │    server.py (Flask) │
                                               └──────────┬───────────┘
                                                          │ HTTP :8080
                                                          │  /list /hours
                                                          │  /tracks/<n>
                                                          ▼
                                               ┌──────────────────────┐
                                               │  POS tablet (Android)│
                                               │  04:00 daily sync    │
                                               │  shuffle 11:00–22:00 │
                                               └──────────────────────┘
```

## Layout

| Directory  | What it is |
|------------|------------|
| `server/`  | Flask + gunicorn LAN server. Deploys to a Raspberry Pi via `install.sh`. See [`server/README.md`](server/README.md). |
| `android/` | Kotlin + Media3 + WorkManager app for the POS tablet. See [`android/README.md`](android/README.md). |
| `music/`   | Local staging folder for MP3s before they're scp'd to the Pi. Filenames are kebab-case (`anfa-after-hours.mp3`). |

## Daily workflow

**Add tracks** — drop new MP3s into `music/`, then push to the Pi:

```bash
scp music/*.mp3 baanbaan@192.168.1.248:/opt/music-player/music/
```

The tablet picks them up at the next 04:00 sync (or tap **Sync now** in
the app).

**Remove tracks** — `ssh baanbaan@192.168.1.248 'rm /opt/music-player/music/<name>.mp3'`.
The tablet removes its local copy on the next sync.

**Change hours** — edit `/opt/music-player/hours.json` on the Pi (no
sudo, `baanbaan` owns it). Hot-reloaded; no restart. The tablet's player
re-reads the schedule on the next sync or hours boundary.

```bash
ssh baanbaan@192.168.1.248
nano /opt/music-player/hours.json   # e.g. set "mon": null to close Mondays
```

**Pause / resume in the moment** — use the play/pause button in the
tablet app. The override clears at the next open/close boundary, so the
schedule resumes the next day on its own.

## First-time setup

### Pi (server)

```bash
scp -r server/ baanbaan@192.168.1.248:~/music-player-deploy
ssh baanbaan@192.168.1.248 "cd ~/music-player-deploy && sudo bash install.sh"
```

This creates `/opt/music-player/`, installs Flask in a venv, and enables
the `music-player-server` systemd unit on `0.0.0.0:8080`.

### POS tablet (Android client)

1. Build the APK: open `android/` in Android Studio Ladybug+ or run
   `./gradlew :app:assembleRelease` (needs the Gradle wrapper jar — see
   [`android/README.md`](android/README.md)).
2. `adb install -r app/build/outputs/apk/release/app-release.apk`.
3. Launch once, grant the notification prompt, tap the
   **battery settings** button and choose **Don't optimize** for the
   app, then verify the server URL is `http://192.168.1.248:8080`.
4. The app auto-starts on every boot and syncs daily at 04:00.

## Storage

- **Pi:** `/opt/music-player/music/` (no quota; check `df -h /opt`).
- **Tablet:** `Context.filesDir/music/` (app-private; uninstalling the
  app frees it).

## Critical-process safety

The Pi also runs the restaurant POS and `marketing-engine.service`
(bun procs on `:3000` / `:3100`). The music server is fully isolated:
binds `:8080`, runs as `baanbaan`, lives under `/opt/music-player/`,
and ships its own systemd unit. It does not depend on or share state
with either critical service.
