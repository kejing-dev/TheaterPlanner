<p align="center"><img src="docs/icon.png" width="128" alt="Theater Planner icon"></p>

# Theater Planner | 影院规划

**坐到你的沙发上捏一下，5.1 / 5.1.2 / 7.1.4 音箱该装哪、电视多大、投影机吊哪、吸音板贴哪，直接亮在你家墙和天花板上。**

**Sit on your sofa and pinch: where each 5.1 / 5.1.2 / 7.1.4 speaker goes, how big the TV should be, where the projector hangs and where the absorbers stick — drawn straight onto your real walls and ceiling.**

PICO OS 6 · PICO Spatial SDK 6.1 · Kotlin / SpatialUI · package `io.embodit.theaterplanner` · v0.1.3

> **v0.1.3：模拟器 QA 通过，并已在 PICO 头显（Swan B3110）上试用（Simulator QA passed and tried on a PICO headset）**

![影院规划 Theater Planner 海报](docs/poster-2560x1440.jpg)

> 演示截图来自原生模拟器（PICO Emulator 6.1），由调试广播驱动跳转到各设计状态；**非真实手势录像**。
> Screenshots were taken on the native PICO emulator, driven by debug broadcasts that jump to each design state; **not a recording of real hand gestures.**

---

## 中文

### 这是什么

装修或买音响之前，坐在自家客厅沙发上把家庭影院规划一遍。你坐下时的头就是听音位，耳高自动得到；每只音箱按杜比推荐角度落到你家真实的墙或天花板上，墙上画绿色推荐带、天花板画绿区，转头就能看到。拖动音箱沿墙滑动，出了推荐带会变红并提示「往右 32 cm」。屏幕页把 1:1 的电视或投影幕放到前墙上并给出视角判断，投影机按投射比吊在你的天花板上，吸音板开关标出第一反射点。

### 安装与启动

```bash
adb install -r TheaterPlanner-v0.1.3.apk
pico-cli app launch io.embodit.theaterplanner
```

APK 在 [Releases](https://github.com/kejing-dev/TheaterPlanner/releases) 页面下载（仓库不含 APK）。需要 PICO OS 6 头显或 PICO Emulator 6.1，并授予平面识别 / 空间锚点权限。

### 容器与骨架

- **容器**：只有 Full Space · `STAGE_MIXED`（透视），没有 Shared Space 版本。音箱、屏幕、吸音板都要落在真实的墙和天花板上，依赖平面识别（FLOOR / WALL / CEILING）、头部位姿和世界锚点，这些只在 Full Space 可用。
- **骨架 D**（参考空间尺 Spatial Ruler → 家具试摆 Furniture Fit）：预建面板池、Stage 根实体、平面碰撞体与后挡板、世界锚点、手势转换（DragLine）、懒跟随、主题与字体、调试接收器、摇杆输入。所有 3D 计算（落点、角度、反射点、投影位置）在纯 Kotlin domain 层，ECS 只读结果。
- **状态**：设计包 S01–S27 共 27 个状态，全部可由调试广播直接跳转并截图。

### 截图

v0.1.3 高清截图（PICO 模拟器 6.1 高清模式，简体中文界面，2560×1440）：

| | |
|---|---|
| ![音箱落在墙上](docs/shot-01.jpg) | ![天花板顶置区](docs/shot-02.jpg) |
| ![出了推荐带变红](docs/shot-03.jpg) | ![电视视角判断](docs/shot-04.jpg) |
| ![投影机与投射比](docs/shot-05.jpg) | ![虚拟房间](docs/shot-06.jpg) |
| ![房间里的物件](docs/shot-07.jpg) | |

v0.1.0 截图：

| | |
|---|---|
| ![5.1.2：仰头看天花板上的两块绿区](docs/S05-layout512-ceiling.jpg) | ![拖动中：读数牌实时显示角度](docs/S08-dragging.jpg) |
| 5.1.2 — 仰头看天花板上的顶置音箱绿区 | 拖动中 — 读数牌实时显示角度与偏移 |
| ![超出范围：音箱变红，提示往右移](docs/S09-out-of-range.jpg) | ![吸音板：墙上的蓝色方框标出第一反射点](docs/S11-absorbers.jpg) |
| 超出范围 — 音箱变红，提示「往右 … cm」 | 吸音板 — 蓝色 60 cm 方框标出第一反射点 |
| ![屏幕·电视：前墙上 1:1 的电视与视角判断](docs/S12-screen-tv.jpg) | ![屏幕·投影：幕布、吊装投影机与光锥](docs/S13-screen-projector.jpg) |
| 屏幕·电视 — 1:1 电视矩形与「视角 36° · 合适」 | 屏幕·投影 — 幕布、吊装投影机与投射比 |

### v0.1.1 摇杆复验（QA 第 2 轮）

v0.1.0 里，音箱停在推荐位时推摇杆纹丝不动（每帧刚转一点就被吸回推荐位）。v0.1.1 改成：推摇杆期间不吸附，松手后才判断一次，离推荐位 3° 以内才吸回。

| | |
|---|---|
| ![从推荐位推开：左前音箱移出画面，原位留下琥珀色虚线框](docs/S07-stick-away.jpg) | ![推回推荐位附近松手：吸回原位，5 只都在推荐范围](docs/S07-stick-return.jpg) |
| 从推荐位推开 — 左前音箱 −26.6° → −44.7°，原位留下虚线框 | 推回 3° 以内松手 — 吸回 −26.6°，「5 只都在推荐范围」 |

启动器图标：

<img src="docs/launcher.jpg" width="480" alt="PICO 启动器中的应用图标">

### 版本

- **v0.1.3**：所有弹出层（坐下面板、清除确认框、提示条）都比主面板更近、自动避开主面板和菜单，主面板也会给房间里的标签让开（25 项 UI × 3 个朝向排查全部通过）；重新坐下改成按头显当前位置和朝向来放内容，转身后再坐，屏幕和音箱都出现在新的正前方，不会跑到身后。拖动改跟手柄射线；屏幕可沿墙拖，离座位至少 0.8 m，走近屏幕 / 音箱 / 投影机会变半透明；菜单新增「房间里的物件」：屏幕移到正前方 / 隐藏，投影机、吸声板、音箱可隐藏 / 关闭 / 复位。
- **v0.1.2**：扫不到房间也能用——5 秒内没扫到地面和墙就直接进一个 4 × 5 × 2.7 m 的虚拟房间（之前在头显上一直卡在「正在扫描」：真机只扫到地面、天花板和家具，没有墙）。菜单 → 房间 里调长宽高、平移、转向、按座位摆正，全程坐着就能做；扫到真实地面 / 天花板会自动贴上；音箱、屏幕、投影机都按这个房间算，改房间后会跟着重新落位。
- **v0.1.1**：修好摇杆从推荐位推不动的问题（QA 第 1 轮打回的唯一一条）；推动期间不吸附、松手再判一次，并去掉了每帧 0.1° 的漂移。QA 第 2 轮在模拟器上复查通过。
- v0.1.0：首个版本。

### 已知问题

- 中文字体子集缺「绕」「侧」两字（音箱名「左环绕」「左侧环绕」等），这两个字会回退到系统字体，字形与其余文字略有差异。
- HOME 键在模拟器上先弹出系统「Back to Home」确认框，再退出应用。
- 截图均来自模拟器（调试广播驱动）；手柄射线拖动、真实捏合已在头显上试用，没有逐项录屏。
- 中置音箱的推荐范围显示成「推荐 5–5°」；7.1.4 在后墙很近（1.2 m）的房间里，后顶两只始终是红色（房间本身放不下）。

---

## English

### What it is

Plan a home theater from your own sofa before drilling holes or buying speakers. Your head when you sit down is the listening position (ear height comes for free). Each speaker of a 5.1 / 5.1.2 / 7.1.4 layout lands on your real wall or ceiling at the Dolby recommended angle, with a green recommended band on each wall and a green zone on the ceiling. Drag a speaker along the wall; it turns red outside its band with a "move right 32 cm" hint. The screen page puts a 1:1 TV or projection screen on the front wall with a viewing-angle verdict, hangs the projector from your ceiling at the throw distance, and the absorber switch marks the first-reflection points.

### Install and launch

```bash
adb install -r TheaterPlanner-v0.1.3.apk
pico-cli app launch io.embodit.theaterplanner
```

Download the APK from the [Releases](https://github.com/kejing-dev/TheaterPlanner/releases) page (the repository itself does not ship an APK). Needs a PICO OS 6 headset or PICO Emulator 6.1 with plane-detection / spatial-anchor permission.

### Container and skeleton

- **Container**: Full Space only, `STAGE_MIXED` (passthrough); no Shared Space version. Speakers, screen and absorbers must land on real walls and ceiling, which needs plane detection (FLOOR / WALL / CEILING), head pose and world anchors, all Full Space only.
- **Skeleton D** (from Spatial Ruler → Furniture Fit): pre-built panel pool, Stage root, plane colliders + backstop, world anchors, gesture conversion (DragLine), lazy follow, theme and fonts, debug receiver, thumbstick input. All 3D maths (landing points, angles, reflection points, projector position) lives in a pure Kotlin domain layer; the ECS only reads the results.
- **States**: 27 design states S01–S27, each reachable and capturable through debug broadcasts.

### Screenshots

See the gallery above: 5.1.2 ceiling zones, dragging with the live readout, out-of-range (red) hint, absorbers, TV screen with the viewing-angle verdict, and the projector with its throw distance. The v0.1.1 thumbstick re-check pair shows a speaker pushed away from its recommended spot (−26.6° → −44.7°) and snapping back on release within 3°.

### Versions

- **v0.1.3**: every pop-up (sit prompt, clear confirmation, snackbars) sits nearer than the main panel and steps clear of it and the menu, and the main panel steps aside for labels in the room (25 UI items x 3 headings audited, all pass); reseating now uses the headset's current position and heading, so after turning round the screen and speakers appear ahead, never behind. Drags follow the controller ray; the screen can be dragged along its wall and stays at least 0.8 m from the seat; the screen, speakers and projector turn see-through when you walk into them; menu -> Objects in the room moves the screen in front of you or hides / switches off the screen, projector, absorbers and speakers.
- **v0.1.2**: never stuck scanning. If no floor and wall are found within 5 s, a 4 x 5 x 2.7 m virtual room is used right away (on the headset the scan returned floor, ceiling and furniture but no walls, so v0.1.1 waited forever). Menu -> Room sets width / length / height, nudges and turns the box to line it up with the real walls, all from the seat; a scanned floor / ceiling snaps on by itself; speakers, screen and projector follow the room.
- **v0.1.1**: fixes the thumbstick doing nothing on a speaker at its recommended spot (the only finding QA round 1 sent back): no snapping while the stick is pushed, one snap check on release, and no more 0.1° per-frame drift. Re-checked and passed in QA round 2 on the emulator.
- v0.1.0: first build.

### Known issues

- The subset Chinese font lacks the glyphs 「绕」 and 「侧」 (used in speaker names such as 左环绕 / 左侧环绕); those two characters fall back to the system font and look slightly different.
- On the emulator the HOME key first shows the system "Back to Home" confirmation before the app exits.
- All screenshots come from the emulator (driven by debug broadcasts); controller-ray drags and real pinches were tried on the headset but not recorded item by item.
- The centre speaker's range reads 「推荐 5–5°」; in 7.1.4 with a back wall only 1.2 m away the two rear-top speakers stay red (the room cannot fit them).

## License

Private repository; all rights reserved. 私有仓库，保留所有权利。
