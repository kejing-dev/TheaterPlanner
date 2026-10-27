# Theater Planner (影院规划)

MR home-theater planning app for PICO OS 6 (v0.1.3). Kotlin, PICO Spatial SDK 6.1.9 (template 6.1), one Mixed Stage
(Full Space only, no Shared Space version), package `io.embodit.theaterplanner`. Sit on your sofa, look at the
TV wall and pinch: the 5.1 / 5.1.2 / 7.1.4 speakers land on your real walls and ceiling at the Dolby angles,
with a green recommended band on each wall and a green zone on the ceiling; drag a speaker along the wall
(or turn it with the controller thumbstick) and it goes red outside its band with a "move right 32 cm" hint.
The screen page puts a 1:1 TV / projection screen on the front wall with the viewing-angle verdict; the
projector hangs from your ceiling at the throw distance; the absorber switch marks the first-reflection points.

Design package (binding): `../spatial-app-factory/apps/theater-planner/design-package.json` (states S01 - S27,
rules, panels, entities, palette, copy). Approved sample: `../spatial-app-factory/apps/theater-planner/sample.html`
(`sample.app.js` holds the reference maths: `recommendLL`, `recommendOH`, `screenGeom`, `projectorGeom`, `absorbers`).
Skeleton D: built from Furniture Fit (`kejing-dev/FurnitureFit`, itself the port of Spatial Ruler
`../spatial-ruler`): panel pool (PanelRig), Stage root, plane colliders + backstop, world anchors, gesture
conversion (DragLine), lazy follow, theme and fonts, debug receiver, thumbstick input.

## Structure

```
app/src/main/java/io/embodit/theaterplanner/
  Main.kt                   DefaultStage { TheaterPlannerTheme { PlannerScreen() } }
  domain/                   pure Kotlin
    Vec3.kt Geometry.kt     vectors, P2 (floor plane, azimuth turns), HeadPose, PanelFacing, PlaneSurface (two-sided ray hits)
    Room.kt                 RoomModel: floor, WALL planes, the largest CEILING (or the assumed one at floor + 2.60)
    Speakers.kt             SpeakerDef (the angle table), Config, Status, SpeakerState, MoveHint
    Seat.kt                 Seat: ear point P, forward f = P -> screen centre, the front wall rule, reseat
    Theater.kt              Theater (land on walls, overhead lat / recommendation, evaluate, move hints, drag, stick),
                            ScreenRules (16:9 size, limits, advice), ProjectorRules, ReflectionRules (mirror method)
    DragLine.kt             system-drag model (Spatial Ruler round 5c)
    Layout.kt               Settings, SavedLayout (relative to the room anchor), JSON codec, Units
  data/Stores.kt            AnchorGateway / LayoutStore interfaces, SharedPreferences store (debug: failNextSave)
  platform/                 TpGraph (services), TpLog, HeadTracker, ControllerStick (thumbstick X), PlaneTracker
                            (debug: hideCeiling), WorldAnchorGateway, LaunchActivity, SpatialApplication
  ui/planner/               PlannerUiState (+ designState S01..S27, status lines), PlannerEvent, PlannerViewModel, PlannerScreen
  ui/planner/scene/         PlannerScene (ECS: one registered System, speakers / bands / zones / screen / projector /
                            absorbers / seat mark, drag, thumbstick turn + snap, panel placement), Views, PanelRig, SceneKit
  ui/planner/components/    SpatialUI panels: ConsolePanel, StripPanels (speaker strip, readout, tags), Overlays
                            (seat prompt, menu, dialog, snackbar, coachmark), PlannerStage (SpatialView + attachments), Strings
  ui/theme/                 TheaterPlannerTheme (PicoTheme + 4 semantic roles + Noto Sans / Noto Sans SC), TpColors, TpType
app/src/debug/              DebugCommandReceiver + DebugCommands (QA hooks)
tools/                      dev.sh, icons_from_sample.py, make_icon.py, subset_cjk_font.py
```

One direction of data: panel controls and scene taps / drags -> `PlannerViewModel.onEvent` -> `PlannerUiState` ->
`PlannerScene.render` (entities, panels). Per-frame work (drag, thumbstick, live status colours, sound line,
arrow, snap target, seat mark, gaze preview, lazy follow) is in `PlannerScene.onFrame` only; the only Compose
state it writes is `panelShown` and the `readout` pill (at most 10 Hz).

## Rules of the room (what the code does)

- Sit (S03): pinch anywhere (surface, backstop, speaker) or "就坐这儿" -> P = head, f from the gaze: a WALL hit
  within 25 degrees of the gaze puts the screen at the foot of the perpendicular; oblique -> gaze hit; no wall ->
  the screen floats 2.5 m ahead. Reseat (v0.1.3, taste profile D9) is a sit from the head of this
  moment like the first one (position + level heading, never the old screen, the world origin or the launch
  pose): the screen goes onto the wall ahead; the speakers turn with the user (same azimuths / overhead offsets
  from the new seat, `PlannerViewModel.transplant`); an untouched virtual room is re-centred around the head.
- Listener-level speaker: wall hit from P at ear height along its azimuth (<= 8 m), retreated 0.12 m along the
  wall normal; no wall -> floating at the mean L / R distance with a dashed stand (S25). C sits 0.20 m under the
  screen's lower edge. Green in range (0.5 degree margin), orange nearer than 0.5 m, red out of range.
- Overhead: lateral = mean L / R lateral offset at their recommended azimuths (clamped 0.3 m from the side
  walls), refreshed only on sit / reseat / reset all; forward = (ceiling - ear) / tan(angle); clamped 0.2 m
  inside the ceiling polygon. Green when the side-view angle is in range and the lateral offset is within 0.3 m.
- Drag: system drag -> pointing line (DragLine) -> hit on any plane -> azimuth -> re-land on the wall (height
  kept); overhead: hit on the ceiling. Nothing hit keeps the last frame. Release writes the position.
- Thumbstick (taste profile D): stick X turns the selected / dragged speaker about the seat's vertical axis
  (dead zone 0.15, up to 60 deg/s), i.e. along the wall. Nothing snaps while the stick is pushed (QA round 1,
  q1-1: a speaker at its recommended spot could never leave it); when the stick is let go (150 ms in the dead
  zone) the position is judged once: within 3 degrees (15 cm overhead) of the recommended spot it snaps there
  (`STICK snapped` / `SNAP` log). A system drag snaps live, since its targets are absolute. The dashed amber frame
  at the recommended spot is the snap target; the strip's third line says 「摇杆左右转动」when nothing more urgent
  claims it.
- Screen: 16:9, width = D x 0.0254 x 16 / sqrt(337); centre at ear + 0.05 m, lower edge >= 0.5 m, upper edge <=
  ceiling - 0.05; view angle 2 atan(w / 2d), 30 - 40 degrees good, "use best" = 36 degrees; limits by the wall's
  width (2 x nearer side - 0.2) and the room height; TV 32 - 115", projector 60 - 200".
- Projector: throw = ratio x width, hung 0.15 m under the ceiling on the screen's centre line; red (S14) when
  the throw is more than the rear wall distance - 0.25 m; "no back wall" when none is hit.
- Absorbers: mirror method for L / C / R against the left / right walls (hit from P along +-right) and, in 5.1,
  the real ceiling; the point must lie inside the plane polygon.
- Persistence: one world anchor at the floor under the seat; everything saved relative to it 0.5 s after a
  change; restore within 10 s of launch (S16) or "last layout isn't here" (S17). Anchor creation or a failed
  write -> "not saved" with Retry (S27).

## The room: scanned or virtual (v0.1.2, taste profile D7)

The app never waits for a scan. `PlannerUiState.room` is the room every rule works on; it comes from one of two
sources (`RoomSource`):

- **SCAN**: plane detection found a FLOOR and a WALL within 5 s of S02 (the v0.1.1 path, unchanged).
- **VIRTUAL** (`domain/VirtualRoom.kt`): otherwise, after 5 s (`SCAN_TIMEOUT_MS`), a 4.0 x 5.0 x 2.70 m box around
  the head, front wall ahead, the viewer 60 % back; turned into the same planes (FLOOR, 4 WALLs, CEILING with ids
  `vr-*`) so every rule runs unchanged. Stage y = 0 is the physical ground (SDK doc "Coordinate space of Stage"),
  so the default floor is 0. Snackbar "没扫到房间 · 先用虚拟房间" (S29). While nothing has been touched, sitting
  re-centres the box on the seat. A scanned floor / ceiling snaps the box's floor height / height once per new
  value ("扫到就贴上"); walls are never taken from a partial scan while the virtual room is in use.
- Menu -> 房间 (S28): Virtual / Scanned switch (Scanned only once a floor and a wall exist), width / length /
  height in 10 cm steps (length grows backwards, the screen wall stays), move 10 cm left / right / ahead / back
  along the current view, turn 2 degrees, "按座位摆正". All from the seat (taste profile B17).
- Any room change refits the layout (`PlannerViewModel.refit`): the seat keeps ear point and front, the screen
  goes onto the new front wall, listener speakers re-land at their azimuth (taken at the wall point, not at the
  12 cm stand-off, else every edit creeps outwards), overhead speakers keep their offset on the new ceiling.
- The scene's tap colliders follow `state.room` (not the tracker); `RoomBoxView` draws the box edges and a 1 m
  floor grid while the virtual room is in use.
- The saved layout carries the virtual room relative to the anchor (`SavedRoom`), restored with it.
- Headset finding 2026-10-26 (PB324XJGL2090081G, office): the scan gives FLOOR, CEILING (4.1 m), TABLE, SCREEN,
  CHAIR, UNKNOWN planes but **no WALL**, which is why v0.1.1 stayed in S02 forever.

## UI rules

- All 2D UI is SpatialUI inside `PicoTheme`. No Material / Material3 (gate: `../spatial-app-factory/bin/material-grep.txt`).
- Every panel is a pre-built Stage attachment panel (AlertDialog / SnackbarHost do not work in a Stage).
- Icons are vector drawables made from the sample's icon set by `tools/icons_from_sample.py`.
- Copy in `res/values` / `res/values-zh`; read through `rs()` (`LocalTpResources`) so the language switches live.
  After changing zh copy run `python tools/subset_cjk_font.py`.
- Launcher icon: `res/drawable/ic_launcher_foreground.xml` on velvet `#6E1F33`; `tools/make_icon.py` writes the
  3D icon layers and the 512 export (`../spatial-app-factory/apps/theater-planner/icon.png`).

## Build, run (Git Bash; device commands are pinned to emulator-5564 / AVD PICO_6_1_Factory3 on pc1)

```bash
tools/dev.sh build && tools/dev.sh install && tools/dev.sh launch
tools/qa_shot.sh S04               # jump to a design state and capture it -> .scratch/shots/S04.png
tools/turn.sh -116 0               # turn the emulator camera (yaw, pitch in degrees; right-button drags)
tools/dev.sh log 'STATE|CMD|SEAT|PLACE|SNAP' 40
tools/dev.sh crash
```

JAVA_HOME = `C:\Program Files\Android\Android Studio\jbr` (JBR 17); Gradle runs through PowerShell (`gradlew.bat`),
`local.properties` holds `sdk.dir` with forward slashes.

**Capturing on the emulator (2026-10-25 findings).** The idle emulator renders at about 1 fps and a pooled panel is
drawn only a few frames after it is shown: a screenshot taken right after a state jump misses the speaker strip,
tags and menu content. `tools/qa_shot.sh` therefore un-throttles the emulator (`tools/harness/emu.sh fast on`, a
posted focus message, 60 fps) for 6 s before capturing with the emulator console (`adb emu screenrecord
screenshot`, a complete 2160 x 2158 frame; `pico-cli capture screenshot` cut the lower part off). Pass the seat as
`A=x,y,z,yaw` (default `0,1.65,0,0`, facing -Z) so a state jump does not re-sit along the current gaze after the
camera was turned. `tools/turn.sh` turns the camera 0.032 degrees per framebuffer pixel of a right-button drag;
states behind the seat (S09, S23, S24) need yaw about -120 .. -150, the ceiling states (S05, S06, S10) pitch +55,
S14 (the projector at the rear wall) yaw 180 and pitch +35. Debug `hidemode` s=park|auto switches how hidden
panels are stowed (auto = holder off, the product setting, verified to come back).

## Debug commands (debug build only)

`adb -s emulator-5564 shell "am broadcast -a io.embodit.theaterplanner.DEBUG -p io.embodit.theaterplanner --es cmd NAME [--es s TEXT] [--es a NUMBERS] [--es id ID] [--ef az N] [--ef x N] [--ef y N] [--ef z N] [--ef yaw N]"`
or `tools/dev.sh cmd NAME [TEXT] [NUMBERS] [ID]`. Internal ids everywhere (7.1.4 side surrounds are `Lss` / `Rss`,
shown as Ls / Rs).

| cmd | arguments | effect |
|---|---|---|
| `state` | s=S01..S29 | jump to that design state; without `s` prints the `STATE` line |
| `seat` | a=x,y,z[,yaw] or --ef x/y/z/yaw (none: the head pose) | sit there (yaw in degrees about the vertical, 0 = looking along -Z) |
| `config` | s=5.1 \| 5.1.2 \| 7.1.4 | switch the configuration |
| `select` s=id · `tap` | | select a speaker / tap the empty room (deselect, close the menu) |
| `drag` | s=id (or --es id) + --ef az N (listener) or --ef x N --ef z N (overhead); a=az or a=x,z | move the speaker there and write it |
| `page` s=spk\|scr · `tv` · `projector` | | pages and screen modes |
| `size` a=D · `ratio` a=r · `absorbers` s=on\|off · `reseat` · `reset` [s=id] | | |
| `stick` | a=x,ms | the thumbstick reads x for ms (emulator QA of the turn + snap) |
| `ui` | s=control | press a control by name (below) |
| `lang` s=system\|zh\|en · `unit` s=m\|ft | | settings |
| `coach_reset` · `restore_timeout` · `restore` | | S21 / S24 again · S17 · re-run the restore (S16) |
| `rescan` · `ignore_scan` s=on\|off (no planes at all) · `room` s=virtual\|scan · `room_page` s=on\|off · `room_size` a=w,l,h · `room_move` a=right,ahead,turnDeg · `room_recenter` | | the room (S28 / S29) |
| `no_ceiling` s=on\|off · `lost` s=on\|off · `unavailable` s=on\|off · `save_fail` · `fail_next_anchor` | | S10 / S18 / S19 / S27 |
| `snack` s=long\|normal · `clear` | | timed snackbars stay 60 s · clear without the dialog |
| `dump` · `pose` · `panels` · `planes` · `bounds` | | reading back |

Control names: `menu_room room_back room_virtual room_scan room_w_minus room_w_plus room_l_minus room_l_plus
room_h_minus room_h_plus room_left room_right room_ahead room_backward room_turn_left room_turn_right room_recenter` (room page) ·
`page_speakers page_screen reseat more cfg_5_1 cfg_5_1_2 cfg_7_1_4 absorbers reset_all size_minus
size_plus use_recommended ratio_minus ratio_plus` (console) · `strip_reset` (speakerStrip) · `seat_here seat_cancel`
(seatPanel) · `lang_system lang_zh lang_en unit_m unit_ft menu_clear menu_exit` (menu) · `dialog_cancel dialog_confirm
blocked_exit` (dialog) · `snackbar_action` (snack) · `coach_ok` (coach).
Panel ids: `console seatPanel speakerStrip readout sizeTag menu dialog snack coach tag0 tag1 abs0..abs8`.

## QA screenshot list (one per design state; `tools/dev.sh state Sxx`, wait ~8 s for the panels)

S01 starting (console spinner) · S02 scanning snackbar · S03 seat prompt + dashed 16:9 gaze frame · S04 5.1 all
green · S05 5.1.2 (look up: two pucks + zones) · S06 7.1.4 (level + up) · S07 L selected (strip, sound line, snap
frame) · S08 L dragged to -35 (readout red, arrow) · S09 Ls at -131 red · S10 no ceiling (dashed grid, orange status)
· S11 absorbers (blue frames, L/C/R plates) · S12 screen · TV · S13 projector + cone · S14 projector too far (red) ·
S15 menu · S16 restored snackbar (after ~5 s) · S17 elsewhere snackbar · S18 tracking lost (grey faces) · S19
unavailable dialog · S20 clear dialog · S21 coachmark beside L · S22 reseat prompt · S23 seat at the rear wall,
Lrs selected · S24 rear coachmark · S25 floating speaker with a stand (needs an opening behind the seat) · S26
"standing?" snackbar · S27 "not saved" snackbar · S28 menu room page · S29 nothing scanned -> virtual room snackbar
+ box (S29 turns `ignore_scan` on; `ignore_scan off` and `room scan` go back to the scanned room). `STATE Sxx ...` in the log lists every speaker's id, angle,
lateral offset, distance and colour for the angle-table check.

## Panel layout (v0.1.3, taste profile D8 / D9)

- Everything is placed from the head of this moment, position + level heading only (`HeadFrame.at`).
- Pop-ups (seat prompt, dialog, snackbar) sit at `POPUP_M` 0.48 m, nearer than the 0.55 m console, scaled to keep
  their designed angular size, facing the user, and moved the least angle that clears the console, the menu and
  higher-priority pop-ups (`popupRest` -> `PanelLayout.clear`; priority dialog > seat prompt > snackbar). The seat
  prompt is opened from the console's sofa key and Clear all from the menu, so the user looks at those then.
- The console re-parks in front of the new heading on sit / reseat, and slides clear when it would cover a label
  in the room (strip, readout, tags, size tag, absorber tags, coach; `LAYOUT console yields to ...`).
- Labels by a speaker stand `LABEL_GAP_M` (0.15 m) towards the viewer; the drag readout sits beside the speaker.
- No hover tag while the menu or a dialog is up.
- QA: debug `layout` logs every shown panel's angular box and `OVERLAP` / `OCCLUDED` / `OFFVIEW`;
  `tools/ui_audit.sh` shoots 20 states + 4 interaction scenes at headings 0 / 90 / 180 (re-seated each time) into
  `.scratch/audit/` (about 20 min; `STATES=... NOI=1 START=<current heading>` for a subset). The emulator shows only
  about +-29 degrees across, so the console's right edge is cut in default shots; the `-look` shots turn to it.
  Report: `../spatial-app-factory/apps/theater-planner/build/ui-audit.md`.
- Unit tests: `app/src/test/.../domain/HeadFrameTest.kt`; `gradlew testDebugUnitTest`.

## Known gaps / notes for QA

- The seat prompt's blank glass does not count as a pinch (only "就坐这儿" and pinches in the room do): a press on
  the panel would also fire before the Cancel key while reseating.
- A band that crosses a wall corner is drawn as one diagonal strip between the two sampled points on either side
  (4 samples per band), not split at the corner (visible on the emulator's side walls for the side surrounds).
- The console follows the heading only once it is more than 55 degrees off (design): after a full turn around it
  can rest on the left side of the view until the head turns away again.
- The sound line of the selected speaker runs from 0.6 m in front of the ear point to the speaker: seen from the
  seat itself it is foreshortened to a dot; move the head sideways to see it.
- In this emulator room the front-left wall is a CURTAIN plane, so L dragged past about -33 degrees floats ("no
  wall", S25); the recommended positions all find walls.
- Verified on emulator-5560 on 2026-10-25: all 27 states captured (`.scratch/shots/S01..S27.png`), 30 menu open /
  close + page switch cycles without a black glass panel, the launcher shows the velvet icon. Not verified: the
  controller thumbstick (the emulator's controller is DEVICE_NOT_SUPPORTED in eye mode; `stick` debug command and
  `tools/harness/emu_mode.ps1 -Mode right` for controller mode), real pinches on the strip / console keys through
  the harness, a real headset.
