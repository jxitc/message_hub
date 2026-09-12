#!/usr/bin/env bash
# publish-apk.sh — build the Android app and publish it for over-the-air update.
#
#   ./scripts/publish-apk.sh                 # build + upload
#   ./scripts/publish-apk.sh --no-build      # upload the existing APK
#
# What it does:
#   1. (optional) ./gradlew assembleDebug
#   2. read versionName/versionCode from the APK with aapt2
#   3. scp the APK + a latest.json metadata file into the server's
#      instance/releases/ (that directory is excluded from the rsync deploy,
#      so `--delete` never removes published builds)
#
# The phone app polls GET /api/v1/releases/latest-info, compares version_code
# with its own, and offers an in-app update when the server has something newer.
#
# Requires the Android toolchain + the deploy SSH key (see docs/crash-reporting-and-ota.md).
set -euo pipefail

REPO="$(cd "$(dirname "$0")/.." && pwd)"
TOOLCHAIN="${MH_TOOLCHAIN:-$HOME/mypro/toolchain}"
SERVER="${MH_SERVER:-188.166.172.192}"
SSH_USER="${MH_SSH_USER:-root}"
REMOTE_DIR="${MH_REMOTE_DIR:-/opt/message_hub}"
SSH_KEY="${MH_SSH_KEY:-$HOME/mypro/.mh_deploy/id_ed25519}"
KNOWN_HOSTS="${MH_KNOWN_HOSTS:-$HOME/mypro/.mh_deploy/known_hosts}"

APP_DIR="$REPO/android_client/MessageHub"
APK="$APP_DIR/app/build/outputs/apk/debug/app-debug.apk"
AAPT2="$TOOLCHAIN/android-sdk/build-tools/36.0.0/aapt2"
REMOTE_NAME="${MH_APK_NAME:-messagehub-debug.apk}"

DO_BUILD=1
[ "${1:-}" = "--no-build" ] && DO_BUILD=0

# ---- 1) build -----------------------------------------------------------------
if [ "$DO_BUILD" = 1 ]; then
  export JAVA_HOME="$TOOLCHAIN/jdk-17"
  export ANDROID_HOME="$TOOLCHAIN/android-sdk"
  export ANDROID_SDK_ROOT="$TOOLCHAIN/android-sdk"
  export ANDROID_USER_HOME="$TOOLCHAIN/android-user-home"
  export GRADLE_USER_HOME="$TOOLCHAIN/gradle-home"
  export HOME="$TOOLCHAIN/home"          # keep AGP's .android inside the workspace
  mkdir -p "$HOME" "$GRADLE_USER_HOME" "$ANDROID_USER_HOME"
  echo "==> building (assembleDebug)"
  (cd "$APP_DIR" && ./gradlew --no-daemon assembleDebug -q)
fi

[ -f "$APK" ] || { echo "ERROR: APK not found: $APK"; exit 1; }

# ---- 2) read version ----------------------------------------------------------
if [ -x "$AAPT2" ]; then
  BADGING="$("$AAPT2" dump badging "$APK" 2>/dev/null || true)"
  VERSION_NAME="$(printf '%s\n' "$BADGING" | sed -n "s/^package:.*versionName='\([^']*\)'.*/\1/p" | head -1)"
  VERSION_CODE="$(printf '%s\n' "$BADGING" | sed -n "s/^package:.*versionCode='\([^']*\)'.*/\1/p" | head -1)"
fi
VERSION_NAME="${VERSION_NAME:-unknown}"
VERSION_CODE="${VERSION_CODE:-0}"
SIZE_BYTES="$(stat -f%z "$APK" 2>/dev/null || stat -c%s "$APK")"
NOTES="${MH_NOTES:-}"

echo "==> version: $VERSION_NAME (code $VERSION_CODE), $(echo "scale=1; $SIZE_BYTES/1048576" | bc) MB"

# ---- 3) upload ----------------------------------------------------------------
echo "==> publishing to $SERVER:$REMOTE_DIR/instance/releases/"
ssh -i "$SSH_KEY" -o StrictHostKeyChecking=accept-new -o UserKnownHostsFile="$KNOWN_HOSTS" \
    "$SSH_USER@$SERVER" "mkdir -p '$REMOTE_DIR/instance/releases'"

scp -q -i "$SSH_KEY" -o StrictHostKeyChecking=accept-new -o UserKnownHostsFile="$KNOWN_HOSTS" \
    "$APK" "$SSH_USER@$SERVER:$REMOTE_DIR/instance/releases/$REMOTE_NAME"

# metadata the app compares against
META="$(mktemp)"
python3 - "$META" <<PY
import json, sys, time
json.dump({
    "version_name": "$VERSION_NAME",
    "version_code": int("$VERSION_CODE"),
    "filename": "$REMOTE_NAME",
    "size_bytes": int("$SIZE_BYTES"),
    "uploaded_at": time.time(),
    "notes": """$NOTES""".strip(),
}, open(sys.argv[1], "w"), ensure_ascii=False, indent=2)
PY
scp -q -i "$SSH_KEY" -o StrictHostKeyChecking=accept-new -o UserKnownHostsFile="$KNOWN_HOSTS" \
    "$META" "$SSH_USER@$SERVER:$REMOTE_DIR/instance/releases/latest.json"
rm -f "$META"

# ---- 4) verify ----------------------------------------------------------------
echo "==> verifying over the public URL"
curl -fsS --max-time 20 "https://mh.jxitc.com/api/v1/releases/latest-info" 2>/dev/null \
  | python3 -c "import sys,json; d=json.load(sys.stdin); print('   published:', d.get('version_name'), '(code', d.get('version_code'), ')', d.get('size_mb'), 'MB')" \
  || echo "   (could not read back the metadata — check Cloudflare/DNS)"

echo
echo "DONE. Phones can update from: https://mh.jxitc.com/api/v1/releases/latest"
