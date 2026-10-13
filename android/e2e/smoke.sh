#!/usr/bin/env bash
# Walks the Android app through the fake harness on an emulator, as
# web/e2e/smoke.mjs does the web client: pairing by deep link, a new worktree
# session, the fake keywords, images, changes and revert, the terminal, a
# tablet's sidebar, the dark theme. Saves screenshots to $SCRATCH/shots and
# removes the server at the end, which unpairs it. Never point it at the
# developer's rad.
#
#   export ANDROID_HOME=… SCRATCH=…    # $SCRATCH/radhome: a scratch rad on 127.0.0.1:7499 with fake: true
#   android/e2e/smoke.sh               # $SCRATCH/root/proj: a git repo under its roots
#
# PORT names another port the scratch rad listens on, RAD another binary.
#
# An emulator must be running (adb devices). Installs the debug build first.
set -euo pipefail
cd "$(dirname "$0")/../.."
. android/e2e/lib.sh
RAD="${RAD:-server/bin/rad}"
PROJECT="${PROJECT:-proj}"
PORT="${PORT:-7499}"
radctl() { RAD_HOME="$SCRATCH/radhome" "$RAD" "$@"; }

fail() {
  echo "FAILED: $*" >&2
  shot failure > /dev/null
  exit 1
}
step() { echo "== $*"; }
# The session's worktree: where the agent and the shell write.
worktree() { dirname "$(find "$SCRATCH/radhome/data/worktrees" -name smoke.txt -newer "$SCRATCH/.smoke-start" | head -1)"; }

touch "$SCRATCH/.smoke-start"
"$A" reverse tcp:$PORT tcp:$PORT > /dev/null
for k in window_animation_scale transition_animation_scale animator_duration_scale; do "$A" shell settings put global $k 0; done
restore() {
  for k in window_animation_scale transition_animation_scale animator_duration_scale; do "$A" shell settings put global $k 1; done
  if [ -n "${WIDE:-}" ]; then "$A" shell wm size reset; "$A" shell wm density reset; fi
}
trap restore EXIT

step "install and pair"
(cd android && ./gradlew -q :app:installDebug)
"$A" shell am force-stop "$PKG"
"$A" shell am start -W -n "$PKG/dev.remoteagent.app.MainActivity" > /dev/null
wait_for 'text=Servers' 8 || wait_for 'id=new-session' 8 || fail "app didn't start"
devices_before=$(radctl devices | grep -c . || true)
open_link "$(radctl pair --print-url)"
wait_for 'id=pair' 10 && tap 'id=pair' || fail "no pairing sheet"
wait_for 'id=new-session' 15 || fail "pairing didn't open the server"
shot 01-server

step "new worktree session"
tap 'id=new-session'
wait_for 'id=project-picker' 10 || fail "no new session sheet"
tap 'id=project-picker'; sleep 0.5
tap "text=$PROJECT" || fail "no project $PROJECT (add it once with Add a folder…)"
sleep 0.5
tap 'id=harness-fake' || fail "no fake harness (fake: true in the scratch config)"
sleep 0.3
tap 'text=Isolated git worktree'; sleep 0.3
"$A" shell input swipe 540 1800 540 500 300; sleep 0.5   # the prompt, at the foot of the form
fill 'id=new-prompt' 'write smoke.txt'
shot 02-new-session
tap 'id=start'
wait_for 'id=approval' 20 || fail "no approval for write"
shot 03-approval
tap 'text=Allow'
wait_for 'contains=Echo: write smoke.txt' 15 || fail "no echo after allowing"
[ -f "$(worktree)/smoke.txt" ] || fail "the agent's file isn't in a worktree"

step "question"
fill 'id=composer' 'question'; tap 'id=send'
wait_for 'text=Blue' 15 || fail "no question"
tap 'text=Blue'; sleep 0.3; tap 'text=Submit'
wait_for 'contains=answer' 15 || fail "no answer echoed"

step "deny with a reason"
type_text 'write denied.txt'; tap 'id=send'
wait_for 'text=Deny' 15 && tap 'text=Deny'
wait_for 'text=Reason (optional)' 5 || fail "no reason dialog"
fill 'text=Tell the agent why' 'not this one'; tap 'text=Deny'
wait_for 'contains=not this one' 10 || fail "the reason isn't shown"

step "stop a slow turn"
fill 'id=composer' 'slow a b c d e f g h i j k l m n o p'; xy=$(find_view 'id=send'); "$A" shell input tap $xy
sleep 0.4; tap 'desc=Stop' || fail "no stop button"
wait_for 'text=Interrupted' 15 || fail "the turn wasn't interrupted"

step "fail"
type_text 'fail now'; tap 'id=send'
wait_for 'text=Failed' 15 || fail "no failed turn"

step "pictures"
type_text 'picture'; tap 'id=send'; sleep 3
type_text 'screenshot'; tap 'id=send'; sleep 3
[ "$(texts 'desc=Image' | grep -c .)" -ge 2 ] || fail "images missing"
shot 04-images

step "/compact"
hide_keyboard; tap 'id=session-menu'; sleep 0.5; tap 'text=/compact'
wait_for 'text=Context compacted' 15 || fail "no compact notice"

step "changes and revert"
hide_keyboard; tap 'id=session-menu'; sleep 0.5; tap 'text=Changes'
wait_for 'id=file-row' 10 || fail "no changes"
shot 05-changes
picture=$(ls "$(worktree)"/picture-*.png | head -1)
[ -f "$picture" ] || fail "the picture isn't in the worktree"
tap 'id=revert'; sleep 0.5; tap 'text=Before turn 2'; sleep 0.5; tap 'text=Revert to before turn 2'
wait_for 'id=reverted' 10 || fail "no revert"
[ ! -e "$picture" ] || fail "revert left $(basename "$picture")"
tap 'desc=Done'; sleep 1

step "terminal"
hide_keyboard; tap 'id=session-menu'; sleep 0.5; tap 'text=Terminal'; sleep 3
fill 'id=terminal' 'expr 40 + 2 > t42.txt'; "$A" shell input keyevent KEYCODE_ENTER; sleep 1.5
[ "$(cat "$(worktree)/t42.txt" 2>/dev/null)" = 42 ] || fail "the shell didn't write t42.txt"
shot 06-terminal
tap 'desc=Hide Terminal'; sleep 0.5

step "tablet"
# Wide, every server's sessions are in a sidebar beside the open session.
WIDE=1; "$A" shell wm size 2560x1600; "$A" shell wm density 320
wait_for 'id=server-header' 10 || fail "no sidebar on a tablet"
[ "$(texts 'text=write smoke.txt' | grep -c .)" -ge 2 ] || fail "the session isn't open beside the sidebar"
shot 07-tablet
# Narrow again, back from the session goes to its server's sessions (below).
"$A" shell wm size reset; "$A" shell wm density reset; WIDE=; sleep 2

step "dark theme"
tap 'desc=Back'; sleep 1
tap 'desc=More'; sleep 0.5; tap 'text=Settings'; sleep 1
tap 'text=Dark'; sleep 2
shot 08-settings-dark
tap 'text=System'; sleep 2
"$A" shell input keyevent KEYCODE_BACK; sleep 1

step "remove the server"
tap 'desc=More'; sleep 0.5; tap 'text=Remove server'; sleep 0.5; tap 'text=Remove'; sleep 2
[ "$(radctl devices | grep -c . || true)" -lt "$((devices_before + 1))" ] || fail "the device is still paired"
echo "ok: screenshots in $SHOTS"
