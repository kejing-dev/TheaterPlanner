package io.embodit.theaterplanner.debug

import android.content.Context
import android.os.Handler
import android.os.Looper
import io.embodit.theaterplanner.domain.Config
import io.embodit.theaterplanner.domain.HeadPose
import io.embodit.theaterplanner.domain.LanguagePref
import io.embodit.theaterplanner.domain.LengthUnit
import io.embodit.theaterplanner.domain.Quat4
import io.embodit.theaterplanner.domain.RoomSource
import io.embodit.theaterplanner.domain.ScreenMode
import io.embodit.theaterplanner.domain.Vec3
import io.embodit.theaterplanner.domain.VirtualRoom
import io.embodit.theaterplanner.platform.TpGraph
import io.embodit.theaterplanner.platform.TpLog
import io.embodit.theaterplanner.ui.planner.Page
import io.embodit.theaterplanner.ui.planner.Phase
import io.embodit.theaterplanner.ui.planner.PlannerEvent
import io.embodit.theaterplanner.ui.planner.PlannerViewModel
import io.embodit.theaterplanner.ui.planner.RestoreState
import io.embodit.theaterplanner.ui.planner.SnackKind
import io.embodit.theaterplanner.ui.planner.debug.Probe
import io.embodit.theaterplanner.ui.planner.scene.PlannerScene
import java.io.File
import java.util.Locale

/**
 * Test hooks of the debug build (design `qaHooks`). One `CMD` log line (tag `TheaterPlanner`) answers
 * every command, and every state change logs `STATE Sxx ...` with each speaker's id, angle, lateral
 * offset, distance and colour.
 *
 *   adb -s <serial> shell "am broadcast -a io.embodit.theaterplanner.DEBUG -p io.embodit.theaterplanner --es cmd NAME [--es s TEXT] [--es a NUMBERS] [--es id ID] [--ef az N] [--ef x N] [--ef y N] [--ef z N] [--ef yaw N]"
 *
 * Designed states: `state` s=S01..S27 jumps to that state (the floor and a wall must be known for
 * S03 onwards; the head pose is the seat unless `seat` was given numbers).
 *
 * Driving: `seat` [x,y,z,yaw] · `config` s=5.1|5.1.2|7.1.4 · `select` s=id · `tap` · `drag` s=id a=az
 * (listener) or a=x,z (overhead) · `page` s=spk|scr · `tv` · `projector` · `ratio` a=r · `size` a=D ·
 * `absorbers` s=on|off · `reseat` · `reset` [s=id] · `stick` a=x,ms · `ui` s=control · `lang` s=system|zh|en ·
 * `unit` s=m|ft · `coach_reset` · `restore_timeout` · `restore` · `no_ceiling` s=on|off · `lost` s=on|off ·
 * `unavailable` s=on|off · `save_fail` · `fail_next_anchor` · `snack` s=long|normal · `clear`
 *
 * Room (taste profile D7): `rescan` · `ignore_scan` s=on|off (no planes at all) · `room` s=virtual|scan ·
 * `room_page` s=on|off · `room_size` a=w,l,h · `room_move` a=right,ahead,turnDeg · `room_recenter`
 *
 * Reading back: `state` (no s) · `dump` · `pose` · `panels` · `planes` · `bounds` · `layout` (who covers whom, taste profile D8)
 */
internal object DebugCommands {
    private val main = Handler(Looper.getMainLooper())

    fun run(context: Context, cmd: String, s: String?, a: String?, id: String?, floats: Map<String, Float>) {
        val host = Probe.host
        if (host == null) {
            TpLog.w("CMD ignored: no Stage")
            return
        }
        val vm = host.viewModel()
        val scene = host.scene()
        val nums = a?.split(',')?.mapNotNull { it.trim().toFloatOrNull() } ?: emptyList()
        val target = id ?: s
        when (cmd) {
            "state" -> if (s == null || s.isBlank()) TpLog.i(vm.stateLine()) else state(vm, scene, s, nums, floats)
            "seat" -> seat(vm, scene, nums, floats)
            "config" -> Config.byTag(s ?: "5.1")?.let { vm.onEvent(PlannerEvent.ConfigSelected(it)) }
            "select" -> target?.let { vm.onEvent(PlannerEvent.SpeakerTapped(it)) }
            "tap" -> vm.onEvent(PlannerEvent.EmptyTapped)
            "drag" -> {
                val sid = id ?: s ?: vm.state.value.selected ?: return
                val az = floats["az"] ?: nums.getOrNull(0).takeIf { nums.size == 1 }
                val x = floats["x"] ?: nums.getOrNull(0).takeIf { nums.size >= 2 }
                val z = floats["z"] ?: nums.getOrNull(1)
                vm.onEvent(PlannerEvent.SpeakerTapped(sid))
                TpLog.i("CMD ${scene.debugMove(sid, az, x, z)}")
            }
            "page" -> vm.onEvent(PlannerEvent.PageSelected(if (s == "scr" || s == "screen") Page.SCREEN else Page.SPEAKERS))
            "tv" -> {
                vm.onEvent(PlannerEvent.PageSelected(Page.SCREEN))
                vm.onEvent(PlannerEvent.ModeSelected(ScreenMode.TV))
            }
            "projector" -> {
                vm.onEvent(PlannerEvent.PageSelected(Page.SCREEN))
                vm.onEvent(PlannerEvent.ModeSelected(ScreenMode.PROJECTOR))
            }
            "ratio" -> nums.firstOrNull()?.let { r -> vm.debugSet { it.copy(ratio = r.coerceIn(0.8f, 2.5f)) } }
            "size" -> nums.firstOrNull()?.let { d -> vm.debugSet { it.copy(inches = d.toInt()) } }
            "absorbers" -> vm.onEvent(PlannerEvent.AbsorbersToggled(s != "off"))
            "reseat" -> vm.onEvent(PlannerEvent.ReseatClicked)
            "reset" -> if (target != null) vm.onEvent(PlannerEvent.ResetOne(target)) else vm.onEvent(PlannerEvent.ResetAll)
            "stick" -> TpLog.i("CMD ${scene.debugStick(nums.getOrNull(0) ?: 0f, (nums.getOrNull(1) ?: 1000f).toLong())}")
            "ui" -> ui(vm, s ?: "")
            "lang" -> LanguagePref.entries.firstOrNull { it.tag == s }?.let { vm.onEvent(PlannerEvent.LanguageSelected(it)) }
            "unit" -> LengthUnit.entries.firstOrNull { it.tag == s }?.let { vm.onEvent(PlannerEvent.UnitSelected(it)) }
            "coach_reset" -> vm.debugCoachReset()
            "restore_timeout" -> vm.debugRestoreTimeout()
            "restore" -> vm.debugRestore()
            "no_ceiling" -> TpGraph.planes.hideCeiling = s != "off"
            "ignore_scan" -> TpGraph.planes.hideAll = s != "off"
            "rescan" -> vm.debugRescan()
            "room" -> vm.onEvent(PlannerEvent.RoomSourceSelected(if (s == "scan") RoomSource.SCAN else RoomSource.VIRTUAL))
            "room_page" -> {
                if (!vm.state.value.menuOpen && s != "off") vm.onEvent(PlannerEvent.MoreClicked)
                vm.onEvent(PlannerEvent.RoomPageShown(s != "off"))
            }
            "room_size" -> vm.debugRoomSize(nums.getOrNull(0), nums.getOrNull(1), nums.getOrNull(2))
            "room_move" -> vm.onEvent(PlannerEvent.RoomMoved(nums.getOrNull(0) ?: 0f, nums.getOrNull(1) ?: 0f, nums.getOrNull(2) ?: 0f, scene.headPose()))
            "room_recenter" -> vm.onEvent(PlannerEvent.RoomRecentered(scene.headPose()))
            "lost" -> TpGraph.head.forceLost = s != "off"
            "unavailable" -> vm.debugSet { it.copy(blocked = s != "off", phase = if (s != "off") Phase.UNAVAILABLE else if (it.seat != null) Phase.LAYOUT else Phase.SEAT, menuOpen = false) }
            "save_fail" -> {
                TpGraph.store(context).failNextSave = true
                vm.debugSaveFail()
            }
            "fail_next_anchor" -> TpGraph.anchors.failNext = true
            "snack" -> vm.longSnacks = s == "long"
            "clear" -> vm.onEvent(PlannerEvent.ClearConfirmed)
            "dump" -> {
                val line = vm.stateLine()
                runCatching { File(context.filesDir, "dump.txt").writeText(line) }
                TpLog.i("DUMP $line")
            }
            "hidemode" -> TpLog.i("CMD ${scene.debugHideMode(s ?: "auto")}")
            "pose" -> TpLog.i(scene.poseLine())
            "layout" -> scene.layoutLines().forEach { TpLog.i(it) }
            "panels" -> scene.panelLines().forEach { TpLog.i(it) }
            "planes" -> scene.planeLines().forEach { TpLog.i(it) }
            "bounds" ->
                listOf("console", "speakerStrip", "seatPanel", "menu", "dialog", "snack", "coach", "readout").forEach { panel ->
                    Probe.controlsOf(panel).forEach { (name, b) -> TpLog.i(String.format(Locale.US, "CTRL panel=%s name=%s cx=%.1f cy=%.1f w=%.1f h=%.1f", panel, name, b.cx, b.cy, b.w, b.h)) }
                }
            else -> TpLog.w("CMD unknown '$cmd'")
        }
    }

    private fun ui(vm: PlannerViewModel, name: String) {
        val e: PlannerEvent? =
            when {
                name == "reseat" -> PlannerEvent.ReseatClicked
                name == "seat_here" -> Probe.host?.scene()?.headPose()?.let { PlannerEvent.SeatHere(it) }
                name == "seat_cancel" -> PlannerEvent.ReseatCancel
                name == "more" -> PlannerEvent.MoreClicked
                name.startsWith("page_") -> PlannerEvent.PageSelected(if (name.endsWith("screen")) Page.SCREEN else Page.SPEAKERS)
                name.startsWith("cfg_") -> Config.byTag(name.removePrefix("cfg_").replace('_', '.'))?.let { PlannerEvent.ConfigSelected(it) }
                name.startsWith("mode_") -> PlannerEvent.ModeSelected(ScreenMode.byTag(name.removePrefix("mode_")))
                name == "absorbers" -> PlannerEvent.AbsorbersToggled(!vm.state.value.absorbersOn)
                name == "reset_all" -> PlannerEvent.ResetAll
                name == "size_minus" -> PlannerEvent.SizeStepped(-1)
                name == "size_plus" -> PlannerEvent.SizeStepped(1)
                name == "use_recommended" -> PlannerEvent.UseRecommended
                name == "ratio_minus" -> PlannerEvent.RatioStepped(-1)
                name == "ratio_plus" -> PlannerEvent.RatioStepped(1)
                name == "strip_reset" -> vm.state.value.selected?.let { PlannerEvent.ResetOne(it) }
                name.startsWith("lang_") -> LanguagePref.entries.firstOrNull { it.tag == name.removePrefix("lang_") }?.let { PlannerEvent.LanguageSelected(it) }
                name.startsWith("unit_") -> LengthUnit.entries.firstOrNull { it.tag == name.removePrefix("unit_") }?.let { PlannerEvent.UnitSelected(it) }
                name == "menu_clear" -> PlannerEvent.ClearAllClicked
                name == "menu_exit" -> PlannerEvent.ExitClicked
                name == "menu_room" -> PlannerEvent.RoomPageShown(true)
                name == "room_back" -> PlannerEvent.RoomPageShown(false)
                name == "room_virtual" -> PlannerEvent.RoomSourceSelected(RoomSource.VIRTUAL)
                name == "room_scan" -> PlannerEvent.RoomSourceSelected(RoomSource.SCAN)
                name.startsWith("room_") && (name.endsWith("_minus") || name.endsWith("_plus")) -> {
                    val d = if (name.endsWith("_plus")) VirtualRoom.SIZE_STEP_M else -VirtualRoom.SIZE_STEP_M
                    when (name.substring(5, 6)) {
                        "w" -> PlannerEvent.RoomResized(d, 0f, 0f)
                        "l" -> PlannerEvent.RoomResized(0f, d, 0f)
                        else -> PlannerEvent.RoomResized(0f, 0f, d)
                    }
                }
                name == "room_left" -> PlannerEvent.RoomMoved(-VirtualRoom.MOVE_STEP_M, 0f, 0f, Probe.host?.scene()?.headPose())
                name == "room_right" -> PlannerEvent.RoomMoved(VirtualRoom.MOVE_STEP_M, 0f, 0f, Probe.host?.scene()?.headPose())
                name == "room_ahead" -> PlannerEvent.RoomMoved(0f, VirtualRoom.MOVE_STEP_M, 0f, Probe.host?.scene()?.headPose())
                name == "room_backward" -> PlannerEvent.RoomMoved(0f, -VirtualRoom.MOVE_STEP_M, 0f, Probe.host?.scene()?.headPose())
                name == "room_turn_left" -> PlannerEvent.RoomMoved(0f, 0f, -VirtualRoom.TURN_STEP_DEG, Probe.host?.scene()?.headPose())
                name == "room_turn_right" -> PlannerEvent.RoomMoved(0f, 0f, VirtualRoom.TURN_STEP_DEG, Probe.host?.scene()?.headPose())
                name == "room_recenter" -> PlannerEvent.RoomRecentered(Probe.host?.scene()?.headPose())
                name == "dialog_cancel" -> PlannerEvent.DialogDismissed
                name == "dialog_confirm" -> PlannerEvent.ClearConfirmed
                name == "blocked_exit" -> PlannerEvent.BlockedExit
                name == "snackbar_action" -> PlannerEvent.SnackActionClicked
                name == "coach_ok" -> if (vm.state.value.rearCoach != null) PlannerEvent.RearCoachDismissed else PlannerEvent.CoachDismissed
                else -> null
            }
        if (e == null) TpLog.w("CMD ui unknown '$name'") else vm.onEvent(e)
    }

    /** Sit at the head pose, or at [x, y, z, yaw] (Stage metres, yaw in degrees about the vertical, 0 = looking along -Z). */
    private fun seat(vm: PlannerViewModel, scene: PlannerScene, nums: List<Float>, floats: Map<String, Float>) {
        val x = floats["x"] ?: nums.getOrNull(0)
        val y = floats["y"] ?: nums.getOrNull(1)
        val z = floats["z"] ?: nums.getOrNull(2)
        val yaw = floats["yaw"] ?: nums.getOrNull(3)
        val head = scene.headPose()
        val pose =
            if (x != null && y != null && z != null) {
                HeadPose(Vec3(x, y, z), Quat4.axisAngle(Vec3.UP, Math.toRadians((yaw ?: 0f).toDouble()).toFloat()))
            } else head
        if (pose == null) {
            TpLog.w("CMD seat: no head pose yet")
            return
        }
        val s = vm.state.value
        if (s.phase != Phase.SEAT && s.phase != Phase.RESEAT) vm.debugSet { it.copy(phase = Phase.SEAT, snack = null) }
        vm.sit(pose)
        TpLog.i("CMD seat at=${TpLog.pos(pose.position)} yaw=${yaw ?: "head"}")
    }

    private fun later(ms: Long, block: () -> Unit) = main.postDelayed(block, ms)

    /** A calm base: in layout with 5.1 at the recommended positions, nothing selected, no overlays. */
    private fun base(vm: PlannerViewModel, scene: PlannerScene, nums: List<Float>, floats: Map<String, Float>) {
        TpGraph.head.forceLost = false
        TpGraph.anchors.failNext = false
        TpGraph.planes.hideCeiling = false
        TpGraph.planes.hideAll = false
        vm.debugHoldScanning = false
        vm.longSnacks = true
        scene.debugDragCancel()
        vm.debugSet { it.copy(menuOpen = false, clearDialog = false, blocked = false, selected = null, dragging = null, coach = false, rearCoach = null, absorbersOn = false, page = Page.SPEAKERS, mode = ScreenMode.TV, inches = null, ratio = 1.2f, config = Config.C51, restore = RestoreState.NONE, snack = null, unsaved = false, holdStarting = false, trackingLost = false) }
        val s = vm.state.value
        if (s.seat == null || s.phase != Phase.LAYOUT || nums.size >= 3 || floats.containsKey("x")) {
            if (s.phase == Phase.STARTING) vm.debugLeaveStarting()
            seat(vm, scene, nums, floats)
        }
        vm.debugSet { it.copy(positions = emptyMap(), noWall = emptySet(), snack = null, coach = false) }
        vm.debugCancelCoach()
    }

    private fun state(vm: PlannerViewModel, scene: PlannerScene, id: String, nums: List<Float>, floats: Map<String, Float>) {
        when (id) {
            "S01" -> vm.debugSet { it.copy(holdStarting = true, phase = Phase.STARTING, seat = null, speakers = emptyList(), snack = null) }
            "S02" -> {
                TpGraph.head.forceLost = false
                vm.debugHoldScanning = true
                vm.debugSet { it.copy(holdStarting = false, seat = null, speakers = emptyList(), positions = emptyMap(), page = Page.SPEAKERS, menuOpen = false, clearDialog = false, blocked = false, trackingLost = false, coach = false, rearCoach = null) }
                vm.debugLeaveStarting()
                vm.debugSet { it.copy(phase = Phase.SCANNING) }
                vm.debugSnack(SnackKind.SCANNING)
            }
            "S03" -> {
                TpGraph.head.forceLost = false
                vm.debugHoldScanning = false
                if (vm.state.value.phase == Phase.STARTING) vm.debugLeaveStarting()
                vm.debugSet { it.copy(phase = Phase.SEAT, seat = null, speakers = emptyList(), positions = emptyMap(), page = Page.SPEAKERS, snack = null, menuOpen = false, clearDialog = false, blocked = false, trackingLost = false, restore = RestoreState.NONE, coach = false, rearCoach = null) }
            }
            "S04" -> base(vm, scene, nums, floats)
            "S05" -> {
                base(vm, scene, nums, floats)
                vm.onEvent(PlannerEvent.ConfigSelected(Config.C512))
            }
            "S06" -> {
                base(vm, scene, nums, floats)
                vm.onEvent(PlannerEvent.ConfigSelected(Config.C714))
            }
            "S07" -> {
                base(vm, scene, nums, floats)
                vm.onEvent(PlannerEvent.SpeakerTapped("L"))
            }
            "S08" -> {
                base(vm, scene, nums, floats)
                vm.onEvent(PlannerEvent.SpeakerTapped("L"))
                later(300) {
                    TpLog.i("CMD ${scene.debugMove("L", -35f, null, null)}")
                    vm.debugSet { it.copy(dragging = "L") }
                }
            }
            "S09" -> {
                base(vm, scene, nums, floats)
                later(300) { TpLog.i("CMD ${scene.debugMove("Ls", -131f, null, null)}") }
            }
            "S10" -> {
                base(vm, scene, nums, floats)
                vm.onEvent(PlannerEvent.RoomSourceSelected(RoomSource.SCAN))
                TpGraph.planes.hideCeiling = true
                later(200) { vm.onEvent(PlannerEvent.ConfigSelected(Config.C714)) }
            }
            "S11" -> {
                base(vm, scene, nums, floats)
                vm.onEvent(PlannerEvent.AbsorbersToggled(true))
            }
            "S12" -> {
                base(vm, scene, nums, floats)
                vm.onEvent(PlannerEvent.PageSelected(Page.SCREEN))
            }
            "S13" -> {
                base(vm, scene, nums, floats)
                vm.onEvent(PlannerEvent.PageSelected(Page.SCREEN))
                vm.onEvent(PlannerEvent.ModeSelected(ScreenMode.PROJECTOR))
            }
            "S14" -> {
                base(vm, scene, nums, floats)
                vm.onEvent(PlannerEvent.PageSelected(Page.SCREEN))
                vm.onEvent(PlannerEvent.ModeSelected(ScreenMode.PROJECTOR))
                vm.debugSet { it.copy(inches = it.advice?.max ?: 150, ratio = 2.5f) }
            }
            "S15" -> {
                base(vm, scene, nums, floats)
                later(300) { vm.onEvent(PlannerEvent.MoreClicked) }
            }
            "S16" -> {
                base(vm, scene, nums, floats)
                vm.onEvent(PlannerEvent.ConfigSelected(Config.C714))
                // wait for the anchor and the save, then restore from storage
                later(4000) { vm.debugRestore() }
            }
            "S17" -> {
                base(vm, scene, nums, floats)
                later(300) { vm.debugRestoreTimeout() }
            }
            "S18" -> {
                base(vm, scene, nums, floats)
                later(300) { TpGraph.head.forceLost = true }
            }
            "S19" -> {
                TpGraph.head.forceLost = false
                vm.debugSet { it.copy(blocked = true, phase = Phase.UNAVAILABLE, menuOpen = false, clearDialog = false, trackingLost = false, snack = null, selected = null, coach = false, rearCoach = null) }
            }
            "S20" -> {
                base(vm, scene, nums, floats)
                later(300) { vm.debugSet { it.copy(clearDialog = true) } }
            }
            "S21" -> {
                base(vm, scene, nums, floats)
                vm.debugCoachReset()
                later(300) { vm.debugSet { it.copy(coach = true) } }
            }
            "S22" -> {
                base(vm, scene, nums, floats)
                later(300) { vm.onEvent(PlannerEvent.ReseatClicked) }
            }
            "S23" -> {
                // the seat 0.35 m in front of the rear wall: the rear surrounds have no room
                base(vm, scene, nums, floats)
                val s = vm.state.value
                val seat = s.seat
                val rear = seat?.let { io.embodit.theaterplanner.domain.Theater.rearWallDistance(s.room, it) }
                if (seat != null && rear != null) {
                    val back = seat.ear - seat.f.at(0f) * (rear - 0.35f)
                    seat(vm, scene, listOf(back.x, back.y, back.z), emptyMap())
                    vm.debugSet { it.copy(positions = emptyMap(), noWall = emptySet()) }
                    vm.debugCancelCoach()
                }
                vm.onEvent(PlannerEvent.ConfigSelected(Config.C714))
                later(300) {
                    vm.onEvent(PlannerEvent.SpeakerTapped("Lrs"))
                    vm.debugSet { it.copy(rearCoach = null) }
                }
            }
            "S24" -> {
                base(vm, scene, nums, floats)
                vm.debugCoachReset()
                vm.onEvent(PlannerEvent.ConfigSelected(Config.C714))
                later(300) {
                    vm.onEvent(PlannerEvent.SpeakerTapped("Lrs"))
                    vm.debugSet { it.copy(coach = false, rearCoach = "Lrs") }
                }
            }
            "S25" -> {
                base(vm, scene, nums, floats)
                vm.onEvent(PlannerEvent.RoomSourceSelected(RoomSource.SCAN))
                vm.onEvent(PlannerEvent.ConfigSelected(Config.C714))
                later(300) {
                    val s = vm.state.value
                    val floating = s.speakers.firstOrNull { it.noWall }
                    if (floating != null) {
                        vm.onEvent(PlannerEvent.SpeakerTapped(floating.id))
                        return@later
                    }
                    // every recommended direction found a wall: slide the first listener-level speaker to the nearest azimuth without one
                    val seat = s.seat ?: return@later
                    var found = false
                    for (sp in s.speakers.filter { it.def.kind == io.embodit.theaterplanner.domain.SpeakerKind.LISTENER }) {
                        for (step in 1..36) for (sign in intArrayOf(-1, 1)) {
                            val az = sp.def.azimuth + sign * step * 5f
                            if (!found && io.embodit.theaterplanner.domain.Theater.land(s.room, seat, az).noWall) {
                                found = true
                                TpLog.i("CMD ${scene.debugMove(sp.id, az, null, null)}")
                            }
                        }
                        if (found) break
                    }
                    if (!found) TpLog.w("CMD state S25: every direction found a wall here; sit facing an opening")
                }
            }
            "S26" -> {
                base(vm, scene, nums, floats)
                vm.debugSnack(SnackKind.STANDING)
            }
            "S27" -> {
                base(vm, scene, nums, floats)
                // after the anchor and the 0.5 s save of the fresh seat have gone through, else they would clear the snackbar
                later(2500) { vm.debugSaveFail() }
            }
            "S28" -> {
                base(vm, scene, nums, floats)
                later(300) {
                    vm.onEvent(PlannerEvent.MoreClicked)
                    vm.onEvent(PlannerEvent.RoomPageShown(true))
                }
            }
            "S29" -> {
                // a headset that finds no room: nothing scanned, the virtual room after 5 s (taste profile D7)
                vm.longSnacks = true
                TpGraph.planes.hideAll = true
                later(200) { vm.debugRescan() }
            }
            else -> TpLog.w("CMD state unknown '$id'")
        }
        TpLog.i("CMD state $id requested")
        later(1200) { TpLog.i(vm.stateLine()) }
    }
}
