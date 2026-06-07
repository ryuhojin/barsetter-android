#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
CLIENT_DIR="${BARSETTER_CLIENT_DIR:-/Users/ryuhojin/Barsetter-client}"
ASSETS_DIR="$ROOT_DIR/app/src/main/assets/www"

if [[ ! -d "$CLIENT_DIR" ]]; then
  echo "Barsetter-client was not found: $CLIENT_DIR" >&2
  exit 1
fi

(
  cd "$CLIENT_DIR"
  npm run build
)

rm -rf "$ASSETS_DIR"
mkdir -p "$ASSETS_DIR"
cp -R "$CLIENT_DIR/dist/." "$ASSETS_DIR/"

echo "Copied Barsetter-client dist to $ASSETS_DIR"
