#!/usr/bin/env bash
# Install the music player server on a Raspberry Pi running Debian/Raspbian.
# Run as root: sudo bash install.sh
set -euo pipefail

INSTALL_DIR=/opt/music-player
SERVICE_NAME=music-player-server
SERVICE_USER="${SERVICE_USER:-baanbaan}"
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

if [[ $EUID -ne 0 ]]; then
    echo "Run as root: sudo bash install.sh" >&2
    exit 1
fi

if ! id "$SERVICE_USER" &>/dev/null; then
    echo "User '$SERVICE_USER' does not exist — edit SERVICE_USER in install.sh." >&2
    exit 1
fi

echo "==> Installing system packages"
apt-get update -qq
apt-get install -y python3 python3-venv python3-pip

echo "==> Creating $INSTALL_DIR"
mkdir -p "$INSTALL_DIR/music"
install -m 0644 "$SCRIPT_DIR/server.py" "$INSTALL_DIR/server.py"
install -m 0644 "$SCRIPT_DIR/requirements.txt" "$INSTALL_DIR/requirements.txt"

# Only seed config files if they don't already exist — never clobber live config.
if [[ ! -f "$INSTALL_DIR/config.json" ]]; then
    install -m 0644 "$SCRIPT_DIR/config.json.example" "$INSTALL_DIR/config.json"
    echo "    seeded config.json"
fi
if [[ ! -f "$INSTALL_DIR/hours.json" ]]; then
    install -m 0644 "$SCRIPT_DIR/hours.json.example" "$INSTALL_DIR/hours.json"
    echo "    seeded hours.json"
fi

chown -R "$SERVICE_USER:$SERVICE_USER" "$INSTALL_DIR"

echo "==> Creating Python venv"
sudo -u "$SERVICE_USER" python3 -m venv "$INSTALL_DIR/venv"
sudo -u "$SERVICE_USER" "$INSTALL_DIR/venv/bin/pip" install --upgrade pip --quiet
sudo -u "$SERVICE_USER" "$INSTALL_DIR/venv/bin/pip" install -r "$INSTALL_DIR/requirements.txt" --quiet

echo "==> Installing systemd unit"
install -m 0644 "$SCRIPT_DIR/$SERVICE_NAME.service" "/etc/systemd/system/$SERVICE_NAME.service"
systemctl daemon-reload
systemctl enable "$SERVICE_NAME"
systemctl restart "$SERVICE_NAME"

echo "==> Opening port 8080 if ufw is active"
if command -v ufw &>/dev/null && ufw status | grep -q "Status: active"; then
    ufw allow 8080/tcp
fi

echo
echo "Done. Verify with:"
echo "  systemctl status $SERVICE_NAME"
echo "  curl http://127.0.0.1:8080/health"
echo
echo "Drop MP3s into $INSTALL_DIR/music/ — no restart needed."
echo "Edit hours: sudo nano $INSTALL_DIR/hours.json (hot-reloaded)."
