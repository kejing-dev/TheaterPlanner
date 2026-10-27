#!/usr/bin/env bash
# Spatial UI audit (taste profile D8 / D9): every UI state at the default heading, turned 90 and 180 degrees
# (re-seated each time), plus "look at the panel, then press" scenarios. Per shot: a screenshot and the app's
# `layout` check (OVERLAP = a nearer panel covers part of another, OFFVIEW = outside a comfortable view,
# OCCLUDED = a speaker / the screen in front of a panel). Output: .scratch/audit/<heading>-<state>.png + .txt
#   tools/ui_audit.sh [HEADINGS...]   default: 0 90 180
set -u
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
DEV=${DEV:-emulator-5564}
PKG=io.embodit.theaterplanner
export PATH="$PATH:${LOCALAPPDATA}/Android/Sdk/platform-tools"
OUT="$ROOT/.scratch/audit"; mkdir -p "$OUT"
cmd() { MSYS_NO_PATHCONV=1 adb -s $DEV shell "am broadcast -a $PKG.DEBUG -p $PKG --es cmd $1 ${2:+--es s $2} ${3:+--es a $3}" >/dev/null 2>&1; }
check() { # name
  cmd layout; sleep 1.2
  adb -s $DEV logcat -d -s TheaterPlanner:V 2>/dev/null | tr -d '\r' | grep -E "LAYOUT (panel=|OVERLAP|OFFVIEW|OCCLUDED|result)" | sed 's/^.*LAYOUT/LAYOUT/' | awk '/LAYOUT panel=/{if(done){print "";done=0}} {print} /LAYOUT result/{done=1}' | tail -n 30 > "$OUT/$1.txt"
  # keep only the last block (the one just asked for)
  python -I -c "import sys;t=open(sys.argv[1],encoding='utf-8').read().strip().split('\n\n')[-1];open(sys.argv[1],'w',encoding='utf-8').write(t+'\n')" "$OUT/$1.txt"
  echo "$1: $(grep -c 'OVERLAP\|OCCLUDED' "$OUT/$1.txt") problems, $(grep -c OFFVIEW "$OUT/$1.txt") offview"
}
shot() { # state name [PRE]
  PRE=${3:-2} A=head bash "$ROOT/tools/qa_shot.sh" "$1" "audit-$2" >/dev/null 2>&1
  mv -f "$ROOT/.scratch/shots/audit-$2.png" "$OUT/$2.png" 2>/dev/null
  check "$2"
}
setup() { # fresh virtual room around the head, seated with the head pose
  cmd snack long; cmd ignore_scan on; cmd rescan; sleep 8; cmd seat; sleep 2; cmd coach_reset; sleep 0.5
}
STATES="${STATES:-S02 S03 S29 S04 S07 S08 S11 S12 S15 S28 S16 S17 S18 S19 S20 S21 S22 S24 S26 S27}"
turned=${START:-0}
for hdg in "${@:-0 90 180}"; do
  for h in $hdg; do
    d=$(( h - turned )); [ "$d" != 0 ] && bash "$ROOT/tools/turn.sh" "-$d" 0 >/dev/null; turned=$h
    setup
    for s in $STATES; do
      pre=2; [ "$s" = S16 ] && pre=7; [ "$s" = S27 ] && pre=4; [ "$s" = S29 ] && pre=8; [ "$s" = S02 ] && pre=2
      shot "$s" "h$h-$s" $pre
      [ "$s" = S19 ] && cmd unavailable off
      [ "$s" = S29 ] && { cmd seat; sleep 1; }
    done
    if [ -z "${NOI:-}" ]; then
    # look at the console, press the sofa key; look at the menu, press Clear all
    cmd state S04 head; sleep 3
    bash "$ROOT/tools/turn.sh" 30 -22 >/dev/null
    cmd ui reseat; sleep 1
    PRE=2 A=head bash "$ROOT/tools/qa_shot.sh" - "audit-h$h-I1" >/dev/null 2>&1; mv -f "$ROOT/.scratch/shots/audit-h$h-I1.png" "$OUT/h$h-I1-sofa.png"; check "h$h-I1-sofa"
    cmd ui seat_cancel; sleep 1; cmd ui more; sleep 1; bash "$ROOT/tools/turn.sh" 0 15 >/dev/null; cmd ui menu_clear; sleep 1
    PRE=2 A=head bash "$ROOT/tools/qa_shot.sh" - "audit-h$h-I2" >/dev/null 2>&1; mv -f "$ROOT/.scratch/shots/audit-h$h-I2.png" "$OUT/h$h-I2-clear.png"; check "h$h-I2-clear"
    cmd ui dialog_cancel; sleep 1
    bash "$ROOT/tools/turn.sh" -30 7 >/dev/null
    # the menu and the room page looked at directly (the emulator shows only about +-29 degrees across)
    for m in S15 S28; do
      cmd state $m head; sleep 3; bash "$ROOT/tools/turn.sh" 30 -2 >/dev/null
      PRE=1 A=head bash "$ROOT/tools/qa_shot.sh" - "audit-h$h-$m-look" >/dev/null 2>&1; mv -f "$ROOT/.scratch/shots/audit-h$h-$m-look.png" "$OUT/h$h-$m-look.png"; check "h$h-$m-look"
      bash "$ROOT/tools/turn.sh" -30 2 >/dev/null
    done
    fi
  done
done
echo DONE
