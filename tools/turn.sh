#!/usr/bin/env bash
# Turn the emulator camera by right-button drags (harness emu.sh): tools/turn.sh YAW_DEG PITCH_DEG
# Right-drag 0.032 deg per framebuffer px (measured 2026-10-25 on Pico_Emulator_6_1_fresh): drag right = turn
# right, drag up = look up. Relative to the current pose; `tools/turn.sh reset` is not possible: turn back by the
# opposite amounts (the drags are reversible).
set -u
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
export PATH="$PATH:${LOCALAPPDATA}/Android/Sdk/platform-tools"
H="bash $ROOT/tools/harness/emu.sh"
yaw=${1:-0}; pitch=${2:-0}
px() { python -c "import sys; print(int(round(float(sys.argv[1]) / 0.032)))" "$1"; }
step() { # axis sign px
  local total=$2; local s=1; [ "$total" -lt 0 ] && s=-1 && total=$(( -total ))
  while [ "$total" -gt 0 ]; do local d=$total; [ $d -gt 800 ] && d=800
    if [ "$1" = x ]; then $H drag $((1080 - s*d/2)) 1079 $((1080 + s*d/2)) 1079 -Button right >/dev/null 2>&1
    else $H drag 1080 $((1079 + s*d/2)) 1080 $((1079 - s*d/2)) -Button right >/dev/null 2>&1; fi
    total=$(( total - d )); done; }
[ "$yaw" != 0 ] && step x "$(px "$yaw")"
[ "$pitch" != 0 ] && step y "$(px "$pitch")"
sleep 1; $H pose 2>&1 | tail -1 | grep -o "fwd=([^)]*)"
