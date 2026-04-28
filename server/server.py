"""Music player LAN server.

Serves an MP3 inventory and business hours to the Android client running on
the restaurant's POS tablet. See README.md for the API contract.
"""

from __future__ import annotations

import hashlib
import json
import logging
from datetime import datetime, timezone
from pathlib import Path
from threading import Lock

from flask import Flask, abort, jsonify, send_from_directory

BASE_DIR = Path(__file__).resolve().parent
CONFIG_PATH = BASE_DIR / "config.json"
HOURS_PATH = BASE_DIR / "hours.json"

logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s %(message)s")
log = logging.getLogger("music-server")

app = Flask(__name__)


def load_config() -> dict:
    with CONFIG_PATH.open() as f:
        return json.load(f)


def load_hours() -> dict:
    with HOURS_PATH.open() as f:
        return json.load(f)


_hash_cache: dict[str, tuple[float, int, str]] = {}
_hash_lock = Lock()


def file_sha1(path: Path, size: int, mtime: float) -> str:
    """Return cached SHA-1 for a file, recomputing only when size/mtime change."""
    key = str(path)
    with _hash_lock:
        cached = _hash_cache.get(key)
        if cached and cached[0] == mtime and cached[1] == size:
            return cached[2]
    h = hashlib.sha1()
    with path.open("rb") as f:
        for chunk in iter(lambda: f.read(65536), b""):
            h.update(chunk)
    digest = h.hexdigest()
    with _hash_lock:
        _hash_cache[key] = (mtime, size, digest)
    return digest


@app.get("/list")
def list_tracks():
    music_dir = Path(load_config()["music_dir"])
    if not music_dir.is_dir():
        log.error("music_dir does not exist: %s", music_dir)
        abort(500, description="music_dir not found")
    tracks = []
    for p in sorted(music_dir.glob("*.mp3")):
        stat = p.stat()
        tracks.append(
            {
                "name": p.name,
                "size": stat.st_size,
                "sha1": file_sha1(p, stat.st_size, stat.st_mtime),
            }
        )
    return jsonify(
        {
            "generated_at": datetime.now(timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ"),
            "tracks": tracks,
        }
    )


@app.get("/tracks/<path:name>")
def get_track(name: str):
    if "/" in name or "\\" in name or name.startswith("."):
        abort(400)
    music_dir = Path(load_config()["music_dir"])
    if not (music_dir / name).is_file():
        abort(404)
    return send_from_directory(
        music_dir,
        name,
        mimetype="audio/mpeg",
        conditional=True,
    )


@app.get("/hours")
def get_hours():
    return jsonify(load_hours())


@app.get("/health")
def health():
    return jsonify({"ok": True})


if __name__ == "__main__":
    cfg = load_config()
    app.run(host="0.0.0.0", port=cfg.get("port", 8080))
