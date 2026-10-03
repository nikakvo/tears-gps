#!/usr/bin/env bash
# Tears GPS FOSS build script.
#
# Usage: ./build.sh [--icon N | --keep-icon]
#   --icon N      use image number N from ./icons without asking
#   --keep-icon   do not ask, keep the current icon
#
# Icons: put square-ish images (png/jpg/jpeg/webp) into ./icons (or set ICON_DIR).
# The chosen image is centre-cropped to a square and written as the adaptive icon foreground
# for every density (app/src/foss/res). Needs ImageMagick.
#
# Output: $OUT_DIR/Tears_GPS-<version>-foss.apk (OUT_DIR defaults to ./dist)
set -euo pipefail

ICON_CHOICE=""
KEEP_ICON=0
cd "$(dirname "$0")"
ICON_DIR="${ICON_DIR:-$PWD/icons}"
OUT_DIR="${OUT_DIR:-$PWD/dist}"
RES_ROOT="app/src/foss/res"

while [ $# -gt 0 ]; do
  case "$1" in
    --icon) shift; ICON_CHOICE="${1:-}" ;;
    --keep-icon) KEEP_ICON=1 ;;
    *) echo "Unknown argument '$1'" >&2; exit 1 ;;
  esac
  shift
done

apply_icon() {
  local img="$1" im
  if command -v magick >/dev/null 2>&1; then im="magick"
  elif command -v convert >/dev/null 2>&1; then im="convert"
  else
    echo "ImageMagick is not installed (e.g. sudo apt install imagemagick)" >&2
    exit 1
  fi
  local d px
  for d in mdpi:108 hdpi:162 xhdpi:216 xxhdpi:324 xxxhdpi:432; do
    px="${d#*:}"
    local res="$RES_ROOT/drawable-${d%%:*}"
    mkdir -p "$res"
    "$im" "$img" -auto-orient -resize "${px}x${px}^" -gravity center -extent "${px}x${px}" \
      -strip "$res/toolkit_adaptive_fg.png"
    "$im" -size "${px}x${px}" xc:black -strip "$res/toolkit_adaptive_bg.png"
  done
  basename "$img" > "$ICON_DIR/.current"
  echo "==> Icon: $(basename "$img")"
}

if [ -d "$ICON_DIR" ] && [ "$KEEP_ICON" -eq 0 ]; then
  mapfile -t ICONS < <(find "$ICON_DIR" -maxdepth 1 -type f \
    \( -iname '*.png' -o -iname '*.jpg' -o -iname '*.jpeg' -o -iname '*.webp' \) | sort)
  if [ "${#ICONS[@]}" -gt 0 ]; then
    CURRENT="$(cat "$ICON_DIR/.current" 2>/dev/null || true)"
    if [ -z "$ICON_CHOICE" ] && [ -t 0 ]; then
      echo "==> ${#ICONS[@]} icon(s) found in $ICON_DIR:"
      for i in "${!ICONS[@]}"; do
        name="$(basename "${ICONS[$i]}")"
        mark=""; [ "$name" = "$CURRENT" ] && mark="  (current)"
        printf '  %2d) %s%s\n' "$((i + 1))" "$name" "$mark"
      done
      read -rp "Icon number [Enter = keep current]: " ICON_CHOICE
    fi
    if [ -n "$ICON_CHOICE" ]; then
      if [[ "$ICON_CHOICE" =~ ^[0-9]+$ ]] && [ "$ICON_CHOICE" -ge 1 ] && [ "$ICON_CHOICE" -le "${#ICONS[@]}" ]; then
        apply_icon "${ICONS[$((ICON_CHOICE - 1))]}"
      else
        echo "Invalid icon number '$ICON_CHOICE'" >&2
        exit 1
      fi
    fi
  fi
fi

VERSION="$(sed -n "s/^def tagName = '\(.*\)'/\1/p" app/build.gradle)"
echo "==> Tears GPS FOSS $VERSION"

./gradlew --quiet assembleFossRelease

APK="app/build/outputs/apk/foss/release/app-foss-arm64-v8a-release.apk"
if [ ! -f "$APK" ]; then
  echo "Build finished but $APK was not found" >&2
  exit 1
fi
mkdir -p "$OUT_DIR"
DEST="$OUT_DIR/Tears_GPS-$VERSION-foss.apk"
cp "$APK" "$DEST"
echo "==> $DEST ($(du -h "$DEST" | cut -f1))"
