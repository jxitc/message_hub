#!/usr/bin/env bash
#
# android_client/e2e/phone_to_mh_e2e.sh
#
# End-to-end: phone (MessageHub app) -> Message Hub server -> DB, with API key auth.
# Uses the DEBUG backdoor (simulate_sms / simulate_notification) to inject a message
# on the phone, then checks the SERVER DB for the injected rows and asserts the
# message content is CLEAN (no emoji / "SMS Message" / "Notification" prefix) and
# that message_metadata carries the structured fields (source, phone_number,
# app_name, title, ...).
#
# Prereqs: phone connected over USB (debug build installed), server up,
#          SSH key at $MH_SSH_KEY.
#
# Usage:
#   ./phone_to_mh_e2e.sh
# Overrides:
#   MH_ADB=/path/to/adb  MH_SERVER=188.166.172.192  MH_SSH_KEY=~/.mh_deploy/id_ed25519
#   MH_REMOTE_DIR=/opt/message_hub  MH_PKG=com.jxitc.messagehub
#
set -euo pipefail

ADB="${MH_ADB:-/Users/jxitc/mypro/tools/android-sdk/platform-tools/adb}"
SERVER="${MH_SERVER:-188.166.172.192}"
SSH_KEY="${MH_SSH_KEY:-$HOME/mypro/.mh_deploy/id_ed25519}"
KNOWN_HOSTS="${MH_KNOWN_HOSTS:-$HOME/mypro/.mh_deploy/known_hosts}"
REMOTE_DIR="${MH_REMOTE_DIR:-/opt/message_hub}"
PKG="${MH_PKG:-com.jxitc.messagehub}"
PUBLIC="${MH_PUBLIC:-https://mh.jxitc.com}"
RECEIVER="$PKG/.receiver.DebugCommandReceiver"
SSH_OPTS=(-i "$SSH_KEY" -o StrictHostKeyChecking=accept-new -o UserKnownHostsFile="$KNOWN_HOSTS")

# unique (no-space) markers
SMS_MARK="E2E_SMS_$(date +%H%M%S)"
NOTI_TITLE="WeChat"
NOTI_MARK="E2E_NOTI_$(date +%H%M%S)"

log() { echo -e "\033[1;34m[E2E]\033[0m $*"; }
ok()   { echo -e "\033[1;32m  PASS\033[0m $*"; }
bad()  { echo -e "\033[1;31m  FAIL\033[0m $*"; }

log "Preflight"
"$ADB" devices | grep -q "$(echo device)" || { echo "no device"; exit 1; }
curl -fsS "$PUBLIC/health" >/dev/null || { echo "server not healthy"; exit 1; }
log "device + server OK"

log "Launch app foreground + send simulate_sms/simulate_notification"
"$ADB" shell monkey -p "$PKG" -c android.intent.category.LAUNCHER 1 >/dev/null 2>&1 || true
sleep 2
"$ADB" shell am broadcast -n "$RECEIVER" -a "$PKG.DEBUG_COMMAND" --es cmd simulate_sms --es sender +8613900000000 --es body "$SMS_MARK" >/dev/null 2>&1
"$ADB" shell am broadcast -n "$RECEIVER" -a "$PKG.DEBUG_COMMAND" --es cmd simulate_notification --es pkg com.tencent.mm --es title "$NOTI_TITLE" --es content "$NOTI_MARK" >/dev/null 2>&1
sleep 1
"$ADB" shell am broadcast -n "$RECEIVER" -a "$PKG.DEBUG_COMMAND" --es cmd sync >/dev/null 2>&1
log "waiting for upload..."
sleep 4

log "Query server DB to verify clean content + structured metadata"
RESULT=$(ssh "${SSH_OPTS[@]}" "root@$SERVER" 'bash -s' <<REMOTE
cd "$REMOTE_DIR"
venv/bin/python - "$SMS_MARK" "$NOTI_MARK" "$NOTI_TITLE" <<'PY'
import sys, sqlite3
sms_mark, noti_mark, noti_title = sys.argv[1], sys.argv[2], sys.argv[3]
c = sqlite3.connect("instance/message_hub.db")
def get(mark):
    rows = c.execute("select type,sender,content,message_metadata from messages where content like ? order by received_at desc limit 1", ("%"+mark+"%",)).fetchall()
    return rows[0] if rows else None

def check(label, mark, want_type, want_sender_has, want_meta_keys, want_content):
    row = get(mark)
    if not row:
        print(f"{label}:NO_ROW"); return
    mtype, sender, content, meta = row
    import json as _j
    try: meta = _j.loads(meta) if meta else {}
    except Exception: meta = {}
    problems = []
    if mtype != want_type: problems.append(f"type={mtype}")
    if want_sender_has and sender not in want_sender_has: problems.append(f"sender={sender}")
    for k in want_meta_keys:
        if k not in meta: problems.append(f"missing_meta={k}")
    # content must be exactly the clean string (no emoji/prefix)
    if content != want_content: problems.append(f"content={content!r}")
    # hard guard: no emoji / known labels in content
    for bad_word in ("\U0001F514", "\U0001F4F1", "SMS Message", "Notification", "From:", "Received:"):
        if bad_word in content: problems.append(f"contains={bad_word}")
    print(f"{label}:{'OK' if not problems else 'FAIL:'+','.join(problems)}")

# SMS content should be exactly the marker (clean), sender = phone number
check("SMS", sms_mark, "SMS", ["+8613900000000"], ["source","phone_number"], sms_mark)
# Notification content should be "TITLE\nBODY" (clean), sender = app name, meta has title
check("NOTI", noti_mark, "PUSH_NOTIFICATION", ["微信","wechat","WeChat"], ["source","app_name","title","package_name","notification_id"], f"{noti_title}\n{noti_mark}")
PY
REMOTE
)

echo "$RESULT"
echo
if echo "$RESULT" | grep -q "FAIL\|NO_ROW"; then
  echo "e2e: FAILED"; exit 1
else
  echo "e2e: PASS"; exit 0
fi
