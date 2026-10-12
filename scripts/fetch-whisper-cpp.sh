#!/usr/bin/env bash
# Fetches the pinned whisper.cpp release (MIT) into core/speech/src/main/cpp/whisper.cpp.
# The source is not committed to git; run this once after cloning.
set -euo pipefail
TAG="${WHISPER_CPP_TAG:-v1.9.5}"
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
DEST="$ROOT/core/speech/src/main/cpp/whisper.cpp"
if [ -f "$DEST/include/whisper.h" ]; then echo "whisper.cpp already present at $DEST"; exit 0; fi
TMP="$(mktemp -d)"
echo "Downloading whisper.cpp $TAG ..."
curl -sSL -o "$TMP/whisper.zip" "https://github.com/ggml-org/whisper.cpp/archive/refs/tags/$TAG.zip"
mkdir -p "$TMP/x" && (cd "$TMP/x" && unzip -q ../whisper.zip)
SRC="$(find "$TMP/x" -maxdepth 1 -mindepth 1 -type d | head -1)"
mkdir -p "$DEST"
# Only what the Android build needs: headers, core sources, ggml, license.
cp -r "$SRC/include" "$SRC/src" "$SRC/ggml" "$SRC/LICENSE" "$DEST/"
echo "$TAG" > "$DEST/WHISPER_VERSION.txt"
rm -rf "$TMP"
echo "whisper.cpp $TAG ready at $DEST"
