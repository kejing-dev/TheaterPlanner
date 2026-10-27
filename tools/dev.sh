#!/usr/bin/env bash
# Theater Planner developer helper (Git Bash). Device commands are pinned to the factory lane
# emulator-5564 (AVD PICO_6_1_Factory3) and refuse to run when that serial is another AVD.
#
#   tools/dev.sh build                 assembleDebug            -> .scratch/build.log
#   tools/dev.sh install | launch | stop | relaunch
#   tools/dev.sh cmd NAME [TEXT] [NUMBERS] [ID]   debug broadcast (see app/src/debug/.../DebugCommands.kt)
#   tools/dev.sh state S04             jump to a design state
#   tools/dev.sh log [REGEX] [LINES]   logcat, tag TheaterPlanner
#   tools/dev.sh crash                 last crash lines of the app
DEV=${DEV:-emulator-5564}
AVD=${AVD:-PICO_6_1_Factory3}
PKG=io.embodit.theaterplanner
TAG=TheaterPlanner
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
ADB="${LOCALAPPDATA}/Android/Sdk/platform-tools/adb.exe"
export ANDROID_SERIAL=$DEV ADB_SERIAL=$DEV PICO_CLI_DEVICE=$DEV
export JAVA_HOME='C:\Program Files\Android\Android Studio\jbr'
export PICO_HOME='C:\Users\Admin\AppData\Local\PICO\sdk'
mkdir -p "$ROOT/.scratch"

check() {
  avd="$("$ADB" -s $DEV emu avd name 2>/dev/null | head -1 | tr -d '\r')"
  if [ "$avd" != "$AVD" ]; then echo "WRONG AVD on $DEV: '$avd' (expected $AVD)" >&2; exit 2; fi
}

case "${1:-}" in
  build)
    cd "$ROOT" && powershell -NoProfile -Command "\$env:JAVA_HOME='C:\Program Files\Android\Android Studio\jbr'; .\gradlew.bat assembleDebug --no-daemon --console=plain 2>&1 | Out-File -Encoding utf8 .scratch\build.log"
    grep -E '^e: |error:|BUILD|FAILED' .scratch/build.log | head -40 ;;
  install)
    check; pico-cli app install "$ROOT/app/build/outputs/apk/debug/app-debug.apk" --device $DEV 2>&1 | tail -2 ;;
  launch)
    check; "$ADB" -s $DEV logcat -c; pico-cli app launch $PKG --device $DEV 2>&1 | tail -1 ;;
  stop)
    check; "$ADB" -s $DEV shell am force-stop $PKG; echo stopped ;;
  relaunch)
    check; "$ADB" -s $DEV shell am force-stop $PKG; sleep 1; "$ADB" -s $DEV logcat -c; pico-cli app launch $PKG --device $DEV 2>&1 | tail -1 ;;
  cmd)
    check
    extra=""
    [ -n "${3:-}" ] && extra="$extra --es s '$3'"
    [ -n "${4:-}" ] && extra="$extra --es a '$4'"
    [ -n "${5:-}" ] && extra="$extra --es id '$5'"
    MSYS_NO_PATHCONV=1 "$ADB" -s $DEV shell "am broadcast -a $PKG.DEBUG -p $PKG --es cmd ${2:?cmd NAME} $extra" | tail -1 | tr -d '\r' ;;
  state)
    check; MSYS_NO_PATHCONV=1 "$ADB" -s $DEV shell "am broadcast -a $PKG.DEBUG -p $PKG --es cmd state --es s ${2:?state ID}" | tail -1 | tr -d '\r' ;;
  log)
    check; "$ADB" -s $DEV logcat -d -v time -s $TAG:V 2>/dev/null | grep -E -- "${2:-.}" | tail -n "${3:-200}" | cut -c1-600 ;;
  crash)
    check; "$ADB" -s $DEV logcat -d -v time 2>/dev/null | grep -E "AndroidRuntime|FATAL EXCEPTION|Process: $PKG" | tail -n 40 | cut -c1-400 ;;
  *) echo "usage: dev.sh build|install|launch|stop|relaunch|cmd|state|log|crash" ;;
esac
