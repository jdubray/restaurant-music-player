# Music Player Server

Tiny HTTP server for the restaurant music player. Runs on the Pi at
`192.168.1.248`, serves the contents of a music folder and the configured
business hours to the Android client on the POS tablet.

The Pi is the source of truth: drop new MP3s into the music folder, delete
old ones, and the tablet's morning sync brings itself into line.

## API

Base URL: `http://192.168.1.248:8080`

### `GET /list`

Returns the current track inventory.

```json
{
  "generated_at": "2026-04-27T13:00:00Z",
  "tracks": [
    { "name": "smoky-blue-1.mp3", "size": 4821331, "sha1": "ab12..." },
    { "name": "smoky-blue-2.mp3", "size": 4502118, "sha1": "cd34..." }
  ]
}
```

- `name` is the filename used to download the track.
- `sha1` lets the client detect that a file was re-uploaded under the same
  name (e.g. you regenerated a track in Suno).
- `size` is bytes, useful for download progress.

### `GET /tracks/<name>`

Streams the MP3. Supports HTTP `Range` so the client can resume interrupted
downloads. `Content-Type: audio/mpeg`. Returns `404` if the file no longer
exists, `400` for any name containing path separators or starting with `.`.

### `GET /hours`

Returns the configured weekly schedule.

```json
{
  "timezone": "America/Los_Angeles",
  "enabled": true,
  "schedule": {
    "mon": { "start": "11:00", "end": "22:00" },
    "tue": { "start": "11:00", "end": "22:00" },
    "wed": { "start": "11:00", "end": "22:00" },
    "thu": { "start": "11:00", "end": "22:00" },
    "fri": { "start": "11:00", "end": "23:00" },
    "sat": { "start": "11:00", "end": "23:00" },
    "sun": null
  }
}
```

- `timezone` is an IANA name; it is authoritative — the tablet's clock is
  not trusted for the schedule.
- `enabled: false` keeps the client silent regardless of the schedule —
  useful for a closed-for-holiday day.
- `schedule` keys are `mon`..`sun`. A value of `null` means closed that day.
- `start`/`end` are local times (24h `HH:MM`).
- If `end <= start`, the window crosses midnight (e.g. `start: "17:00",
  end: "02:00"` plays from 5pm Friday until 2am Saturday).

### `GET /health`

`{ "ok": true }`. Used by the client to detect that the server is reachable
before attempting a sync.

## Files on the Pi

```
/opt/music-player/
├── server.py
├── config.json         # paths and listen port (one-time setup)
├── hours.json          # business hours (edit this when hours change)
└── music/              # drop MP3s here
```

`hours.json` is hot-reloaded on every `/hours` request, so editing it takes
effect immediately — no restart needed.

## Install on the Pi

```bash
sudo bash install.sh
```

This will:
1. Copy the server to `/opt/music-player/`.
2. Create the `music/` folder if missing.
3. Install Flask in a venv.
4. Install and enable the `music-player-server` systemd unit.
5. Open port 8080 in `ufw` if it's active.

After install, verify with:

```bash
curl http://192.168.1.248:8080/health
curl http://192.168.1.248:8080/hours
curl http://192.168.1.248:8080/list
```

## Updating tracks

Just `scp` MP3s into `/opt/music-player/music/` (or use whatever you prefer —
Samba, SFTP, a shared folder). The next `/list` call reflects the change.
The tablet syncs at 04:00 each morning by default.

## Editing hours

```bash
sudo nano /opt/music-player/hours.json
```

Save and you're done — no restart.
