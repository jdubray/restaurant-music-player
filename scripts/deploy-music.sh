#!/usr/bin/env bash
# Sync new MP3s in music/ to the Pi over SSH.
#
# Default is dry-run; pass --apply to actually transfer.
# Default is "ignore existing" (safe: never overwrites a file already on the Pi).
# Pass --update to re-sync files whose size/mtime differ.
#
# Run from WSL, or wrap with `wsl -- bash scripts/deploy-music.sh ...` from Windows.
#
# Usage:
#   bash scripts/deploy-music.sh             # dry-run, ignore existing
#   bash scripts/deploy-music.sh --apply     # push new files
#   bash scripts/deploy-music.sh --update    # dry-run, re-sync changed files too
#   bash scripts/deploy-music.sh --apply --update

set -euo pipefail

REMOTE="${MUSIC_REMOTE:-baanbaan@192.168.1.248}"
REMOTE_DIR="${MUSIC_REMOTE_DIR:-/opt/music-player/music/}"

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
LOCAL_DIR="$SCRIPT_DIR/../music/"

apply=0
update=0
for arg in "$@"; do
    case "$arg" in
        --apply)  apply=1 ;;
        --update) update=1 ;;
        -h|--help)
            sed -n '2,15p' "$0" | sed 's/^# \{0,1\}//'
            exit 0
            ;;
        *)
            echo "unknown arg: $arg" >&2
            exit 2
            ;;
    esac
done

if [[ ! -d "$LOCAL_DIR" ]]; then
    echo "local music dir not found: $LOCAL_DIR" >&2
    exit 1
fi

flags=(-av --human-readable)
if [[ $update -eq 0 ]]; then
    flags+=(--ignore-existing)
fi
if [[ $apply -eq 0 ]]; then
    flags+=(--dry-run)
fi

echo "==> $([[ $apply -eq 1 ]] && echo APPLY || echo DRY-RUN): rsync ${LOCAL_DIR} -> ${REMOTE}:${REMOTE_DIR}"
echo "    mode: $([[ $update -eq 1 ]] && echo "update changed files" || echo "ignore existing")"
echo

rsync "${flags[@]}" --include='*.mp3' --exclude='*' \
    "$LOCAL_DIR" "$REMOTE:$REMOTE_DIR"

if [[ $apply -eq 1 ]]; then
    echo
    echo "==> verifying"
    local_count=$(find "$LOCAL_DIR" -maxdepth 1 -name '*.mp3' | wc -l)
    remote_count=$(ssh "$REMOTE" "ls $REMOTE_DIR*.mp3 2>/dev/null | wc -l")
    served_count=$(ssh "$REMOTE" "curl -fsS http://127.0.0.1:8080/list | python3 -c 'import sys,json; print(len(json.load(sys.stdin)))'" 2>/dev/null || echo "?")
    echo "    local:  $local_count mp3s"
    echo "    pi fs:  $remote_count mp3s"
    echo "    /list:  $served_count tracks"
fi
