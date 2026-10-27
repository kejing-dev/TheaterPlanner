#!/usr/bin/env bash
# QA q1-1 re-check: the thumbstick from the recommended spot (S07). Three cases, each captured:
#   1. S07-stick-away  : L at its recommended spot, stick x=-0.7 for 0.7 s -> L must have left (about 17 deg of turn, still in view)
#   2. S07-stick-else  : stick x=+0.7 for 0.5 s -> L stops where it was let go (outside the 3 deg snap zone)
#   3. S07-stick-snap  : back at the recommended spot, a small push x=-0.3 for 1.0 s (about -2 deg) -> on release it snaps back
# Prints the STATE / STICK / SNAP lines after each case. Needs the app in LAYOUT with the default seat (tools/qa_shot.sh S04 first).
set -u
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
DEV=${DEV:-emulator-5564}; PKG=io.embodit.theaterplanner
export PATH="$PATH:${LOCALAPPDATA}/Android/Sdk/platform-tools"
H="bash $ROOT/tools/harness/emu.sh"
cmd() { MSYS_NO_PATHCONV=1 adb -s $DEV shell "am broadcast -a $PKG.DEBUG -p $PKG --es cmd $1 ${2:+--es s $2} ${3:+--es a $3}" >/dev/null 2>&1; }
lines() { adb -s $DEV logcat -d -s TheaterPlanner:V 2>/dev/null | grep -E "STICK|SNAP|PLACE" | sed 's/.*TheaterPlanner: //' | tail -6; adb -s $DEV logcat -d -s TheaterPlanner:V 2>/dev/null | grep -E "STATE S" | tail -1 | grep -o "| L [^|]*"; }
push() { # x ms name
  adb -s $DEV logcat -c; $H fast on >/dev/null 2>&1; sleep 1; cmd stick "" "$1,$2"; sleep "$(python -c "print($2/1000.0 + 1.5)")"
  echo "--- $3 (stick x=$1 for $2 ms) ---"; lines
  sleep 3; bash "$ROOT/tools/qa_shot.sh" - "$3" >/dev/null; echo "shot $ROOT/.scratch/shots/$3.png"; }
cmd state S07 0,1.65,0,0; sleep 4
push -0.7 700 S07-stick-away
push 0.7 500 S07-stick-else
cmd state S07 0,1.65,0,0; sleep 4
push -0.3 1000 S07-stick-snap
