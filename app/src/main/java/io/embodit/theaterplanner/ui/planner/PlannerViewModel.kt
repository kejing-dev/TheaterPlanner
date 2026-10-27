package io.embodit.theaterplanner.ui.planner

import android.content.Context
import android.os.SystemClock
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import io.embodit.theaterplanner.data.AnchorGateway
import io.embodit.theaterplanner.data.AnchorOutcome
import io.embodit.theaterplanner.data.AnchorRecord
import io.embodit.theaterplanner.data.LayoutStore
import io.embodit.theaterplanner.domain.Config
import io.embodit.theaterplanner.domain.HeadPose
import io.embodit.theaterplanner.domain.P2
import io.embodit.theaterplanner.domain.ProjectorRules
import io.embodit.theaterplanner.domain.ReflectionRules
import io.embodit.theaterplanner.domain.RoomModel
import io.embodit.theaterplanner.domain.RoomSource
import io.embodit.theaterplanner.domain.SavedLayout
import io.embodit.theaterplanner.domain.SavedRoom
import io.embodit.theaterplanner.domain.ScreenMode
import io.embodit.theaterplanner.domain.ScreenRules
import io.embodit.theaterplanner.domain.Seat
import io.embodit.theaterplanner.domain.SpeakerDef
import io.embodit.theaterplanner.domain.SpeakerKind
import io.embodit.theaterplanner.domain.SpeakerState
import io.embodit.theaterplanner.domain.Theater
import io.embodit.theaterplanner.domain.Vec3
import io.embodit.theaterplanner.domain.VirtualRoom
import io.embodit.theaterplanner.platform.TpGraph
import io.embodit.theaterplanner.platform.TpLog
import java.util.Locale
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlin.math.abs

/**
 * The whole behaviour of the app (design-package `rules`, `states`, `interactions`): events from the
 * panels and the scene come in through [onEvent], the state goes out through [state]. Pure Kotlin
 * apart from the injected platform services.
 */
class PlannerViewModel(
    private val anchors: AnchorGateway,
    private val store: LayoutStore,
    private val blockReason: () -> String?,
    /** The head pose of this moment (the virtual room is put around it), null before the first sample. */
    private val headPose: () -> HeadPose? = { null },
    scopeOverride: CoroutineScope? = null,
) : ViewModel() {

    private val scope: CoroutineScope = scopeOverride ?: viewModelScope
    private val _state = MutableStateFlow(PlannerUiState(settings = store.loadSettings()))
    val state: StateFlow<PlannerUiState> = _state

    private val _effects = MutableSharedFlow<PlannerEffect>(extraBufferCapacity = 16)
    val effects: SharedFlow<PlannerEffect> = _effects

    /** Timed snackbars stay 60 s (debug `snack long`). */
    @Volatile var longSnacks = false

    private var anchor: AnchorRecord? = null
    private var saved: SavedLayout? = null
    private var snackSeq = 0
    private var timedSnackJob: Job? = null
    private var saveJob: Job? = null
    private var restoreJob: Job? = null
    private var blockJob: Job? = null
    private var coachJob: Job? = null
    private var anchorJob: Job? = null
    private var fallbackJob: Job? = null
    private var snappedFloor: Float? = null
    private var snappedCeiling: Float? = null
    private var lastLogged: String? = null
    private var lastLoggedLine: String? = null

    init {
        saved = store.loadLayout()
        if (saved != null) {
            update { it.copy(restore = RestoreState.LOOKING) }
        }
    }

    // =========================================================================================
    // events
    // =========================================================================================

    fun onEvent(e: PlannerEvent) {
        val s = _state.value
        when (e) {
            PlannerEvent.SceneReady -> onSceneReady()
            is PlannerEvent.PlanesChanged -> onPlanes(e.room)
            is PlannerEvent.TrackingChanged -> onTracking(e.lost)
            is PlannerEvent.Pinched -> if (s.phase == Phase.SEAT || s.phase == Phase.RESEAT) sit(e.head)
            is PlannerEvent.SeatHere -> if (s.phase == Phase.SEAT || s.phase == Phase.RESEAT) sit(e.head)
            PlannerEvent.ReseatClicked -> if (s.consoleEnabled) update { it.copy(phase = Phase.RESEAT, selected = null, menuOpen = false) }
            PlannerEvent.ReseatCancel -> if (s.phase == Phase.RESEAT) update { it.copy(phase = Phase.LAYOUT) }
            is PlannerEvent.ConfigSelected -> if (s.consoleEnabled) changeThenSave { it.copy(config = e.config, selected = null) }
            is PlannerEvent.PageSelected -> if (s.consoleEnabled) changeThenSave { it.copy(page = e.page, selected = null) }
            is PlannerEvent.SpeakerTapped -> onSpeakerTapped(e.id)
            PlannerEvent.EmptyTapped -> onEmptyTapped()
            is PlannerEvent.DragStarted -> if (s.dragEnabled && s.menuOpen.not()) update { it.copy(dragging = e.id, selected = e.id, coach = false) }
            is PlannerEvent.DragEnded -> onDragEnded(e)
            is PlannerEvent.SpeakerTurned -> if (s.dragEnabled) place(e.id, e.position, e.noWall, "stick")
            is PlannerEvent.ResetOne -> if (s.dragEnabled) resetOne(e.id)
            PlannerEvent.ResetAll -> if (s.consoleEnabled) resetAll()
            is PlannerEvent.AbsorbersToggled -> if (s.consoleEnabled) changeThenSave { it.copy(absorbersOn = e.on) }
            is PlannerEvent.ModeSelected -> if (s.consoleEnabled) changeThenSave { it.copy(mode = e.mode, inches = it.inches?.coerceIn(ScreenRules.limits(it.seat!!, it.room, e.mode))) }
            is PlannerEvent.SizeStepped -> if (s.consoleEnabled) stepSize(e.delta)
            PlannerEvent.UseRecommended -> if (s.consoleEnabled) changeThenSave { it.copy(inches = it.advice?.use) }
            is PlannerEvent.RatioStepped -> if (s.consoleEnabled) changeThenSave { it.copy(ratio = (it.ratio + e.delta * ProjectorRules.RATIO_STEP).coerceIn(ProjectorRules.RATIO_MIN, ProjectorRules.RATIO_MAX)) }
            PlannerEvent.MoreClicked -> if (s.consoleEnabled) update { it.copy(menuOpen = !it.menuOpen, roomPage = false, objectsPage = false) }
            is PlannerEvent.RoomPageShown -> if (s.menuOpen || !e.shown) update { it.copy(roomPage = e.shown, objectsPage = false) }
            is PlannerEvent.ObjectsPageShown -> if (s.menuOpen || !e.shown) update { it.copy(objectsPage = e.shown, roomPage = false) }
            is PlannerEvent.ObjectHidden -> {
                update { it.copy(hidden = if (e.hidden) it.hidden + e.key else it.hidden - e.key) }
                TpLog.i("OBJECT ${e.key} hidden=${e.hidden}")
            }
            is PlannerEvent.ScreenToFront -> if (s.consoleEnabled && e.head != null) screenToFront(e.head)
            is PlannerEvent.ScreenMoved -> if (s.dragEnabled) screenMoved(e.center)
            is PlannerEvent.RoomSourceSelected -> if (s.consoleEnabled) selectSource(e.source, "menu")
            is PlannerEvent.RoomResized -> if (s.consoleEnabled) editRoom("resize") { it.resized(e.dw, e.dl, e.dh) }
            is PlannerEvent.RoomMoved ->
                if (s.consoleEnabled) {
                    val frame = e.head?.level() ?: s.seat?.f ?: s.vroom?.forward ?: P2(0f, -1f)
                    editRoom("move") { it.moved(frame, e.right, e.ahead, e.turnDeg) }
                }
            is PlannerEvent.RoomRecentered -> if (s.consoleEnabled) editRoom("recenter") { v -> virtualAround(v, e.head) }
            is PlannerEvent.LanguageSelected -> settings { it.copy(language = e.language) }
            is PlannerEvent.UnitSelected -> settings { it.copy(unit = e.unit) }
            PlannerEvent.ClearAllClicked -> update { it.copy(menuOpen = false, clearDialog = true) }
            PlannerEvent.ClearConfirmed -> clearAll()
            PlannerEvent.DialogDismissed -> update { it.copy(clearDialog = false) }
            PlannerEvent.ExitClicked -> {
                update { it.copy(menuOpen = false) }
                _effects.tryEmit(PlannerEffect.Exit)
            }
            PlannerEvent.BlockedExit -> _effects.tryEmit(PlannerEffect.Exit)
            PlannerEvent.SnackActionClicked -> onSnackAction()
            PlannerEvent.SnackClosed -> update { it.copy(snack = if (it.snack?.kind?.sticky == true) it.snack else null) }
            PlannerEvent.CoachDismissed -> {
                settings { it.copy(coachSeen = true) }
                update { it.copy(coach = false) }
            }
            PlannerEvent.RearCoachDismissed -> {
                settings { it.copy(rearCoachSeen = true) }
                update { it.copy(rearCoach = null) }
            }
        }
    }

    private fun onSceneReady() {
        val s = _state.value
        if (s.phase != Phase.STARTING) return
        // the head tracker's support state may still be NOT_IN_FULL_SPACE for a moment: give it two seconds
        blockJob?.cancel()
        blockJob =
            scope.launch {
                var reason = blockReason()
                val t0 = SystemClock.elapsedRealtime()
                while (reason != null && SystemClock.elapsedRealtime() - t0 < BLOCK_GRACE_MS) {
                    delay(250)
                    reason = blockReason()
                }
                if (reason != null) {
                    TpLog.i("BLOCKED reason=$reason")
                    update { it.copy(blocked = true, phase = Phase.UNAVAILABLE) }
                    return@launch
                }
                if (_state.value.holdStarting) return@launch
                enterScanning()
            }
    }

    private fun enterScanning() {
        update { it.copy(phase = Phase.SCANNING, snack = SnackUi(SnackKind.SCANNING, ++snackSeq)) }
        if (saved != null) startRestore()
        // never stuck scanning (taste profile D7): no usable scan in 5 s -> the virtual room
        fallbackJob?.cancel()
        fallbackJob =
            scope.launch {
                delay(SCAN_TIMEOUT_MS)
                if (_state.value.roomSource == RoomSource.NONE && !debugHoldScanning) useVirtual("timeout")
            }
        advanceFromScanning()
    }

    /** Debug: leave S01 now. */
    fun debugLeaveStarting() {
        if (_state.value.phase == Phase.STARTING) {
            update { it.copy(holdStarting = false) }
            enterScanning()
        }
    }

    /** Debug: stay in S02 whatever the planes say. */
    @Volatile var debugHoldScanning = false

    private fun advanceFromScanning() {
        if (_state.value.phase != Phase.SCANNING || debugHoldScanning) return
        if (_state.value.roomSource == RoomSource.NONE) {
            if (!_state.value.scanUsable) return
            fallbackJob?.cancel()
            update { it.copy(roomSource = RoomSource.SCAN) }
            applyRoom("scanned")
        }
        if (_state.value.restore == RestoreState.LOOKING) return // the restore decides
        update { it.copy(phase = Phase.SEAT, snack = if (it.snack?.kind == SnackKind.SCANNING) null else it.snack) }
    }

    private fun onPlanes(scanned: RoomModel) {
        update { it.copy(scanned = scanned) }
        when (_state.value.roomSource) {
            RoomSource.NONE -> advanceFromScanning()
            RoomSource.VIRTUAL -> snapToScan()
            RoomSource.SCAN -> onScannedPlanes(scanned)
        }
    }

    /** The scanned room in use changed (v0.1.1 behaviour): the room follows, speakers stay, a late ceiling re-seats the overhead ones. */
    private fun onScannedPlanes(room: RoomModel) {
        val s = _state.value
        val hadCeiling = s.room.hasCeiling
        update { it.copy(room = room) }
        if (_state.value.inLayout || _state.value.phase == Phase.RESEAT) {
            // a ceiling that arrives after the overhead speakers were placed on the assumed one re-seats them (design S10)
            if (!hadCeiling && room.hasCeiling) {
                val seat = _state.value.seat
                if (seat != null) {
                    update { st ->
                        val pos = st.positions.toMutableMap()
                        for (def in SpeakerDef.ALL.filter { it.kind == SpeakerKind.OVERHEAD }) if (def.id in pos) pos[def.id] = Theater.recommendOverhead(room, seat, def, st.overheadLat)
                        st.copy(positions = pos)
                    }
                }
            }
            update { evaluate(it) }
        }
    }

    // ---- objects (taste profile D14) ---------------------------------------------------------

    /** "Move in front of me": the front becomes the wall ahead of the head of this moment; the ear point stays, the speakers turn along. */
    private fun screenToFront(head: HeadPose) {
        val s = _state.value
        val old = s.seat ?: return
        val seat = Seat.first(old.ear, head.level(), s.room).copy(floorY = old.floorY)
        val (pos, nw) = transplant(old, s.room, seat, s.room, s.positions, s.noWall)
        changeThenSave { it.copy(seat = seat, positions = pos, noWall = nw, overheadLat = Theater.overheadLat(it.room, seat), hidden = it.hidden - Objects.SCREEN) }
        TpLog.i(String.format(Locale.US, "OBJECT screen to-front screen=(%.3f, %.3f) wall=%s", seat.screenCenter.x, seat.screenCenter.z, seat.screenWall?.label ?: "-"))
    }

    /** The screen dragged along its wall: f = P -> the new centre; the speakers stay where they are and are judged again. */
    private fun screenMoved(center: P2) {
        val seat = _state.value.seat ?: return
        val f = (center - P2.of(seat.ear)).normalized()
        if (f.length < 0.5f) return
        changeThenSave { it.copy(seat = seat.copy(screenCenter = center, f = f)) }
        TpLog.i(String.format(Locale.US, "OBJECT screen moved to=(%.3f, %.3f)", center.x, center.z))
    }

    // ---- the room: scanned or virtual (taste profile D7) ------------------------------------

    /** The virtual room around the seat (or the head, or the Stage origin) at [size]'s dimensions; the scanned floor when known. */
    private fun virtualAround(size: VirtualRoom?, head: HeadPose?): VirtualRoom {
        val s = _state.value
        val seat = s.seat
        val at = seat?.let { P2.of(it.ear) } ?: head?.let { P2.of(it.position) } ?: P2.ZERO
        val facing = seat?.f ?: head?.level() ?: P2(0f, -1f)
        val floorY = s.scanned.floorY ?: size?.floorY ?: 0f
        return VirtualRoom.around(at, facing, size?.width ?: VirtualRoom.DEFAULT_W, size?.length ?: VirtualRoom.DEFAULT_L, size?.height ?: VirtualRoom.DEFAULT_H, floorY)
    }

    /** Nothing usable scanned in time (or the restore needs a room now): the virtual room, [given] or a default one around the head. */
    private fun useVirtual(reason: String, given: VirtualRoom? = null) {
        fallbackJob?.cancel()
        val s = _state.value
        val v = given ?: s.vroom ?: virtualAround(null, headPose())
        val snack = if (s.snack == null || s.snack.kind == SnackKind.SCANNING) SnackUi(SnackKind.VIRTUAL_ROOM, ++snackSeq) else null
        update { it.copy(vroom = v, roomSource = RoomSource.VIRTUAL, vroomTouched = it.vroomTouched || given != null, snack = snack ?: it.snack) }
        if (snack != null) timedSnack(snack, VIRTUAL_SNACK_MS)
        TpLog.i(String.format(Locale.US, "ROOM virtual reason=%s size=%s center=(%.2f, %.2f) yaw=%.1f floor=%.2f scanned=%d", reason, v.label, v.center.x, v.center.z, v.yawDeg, v.floorY, s.scanned.planes.size))
        snapToScan(relayout = false)
        applyRoom("virtual")
        advanceFromScanning()
    }

    /** Menu: the scanned room (needs a floor and a wall) or the virtual one. */
    private fun selectSource(source: RoomSource, reason: String) {
        val s = _state.value
        if (source == s.roomSource || source == RoomSource.NONE) return
        if (source == RoomSource.SCAN && !s.scanUsable) return
        update { it.copy(roomSource = source, vroom = if (source == RoomSource.VIRTUAL) it.vroom ?: virtualAround(null, headPose()) else it.vroom) }
        TpLog.i("ROOM source=$source via=$reason")
        applyRoom(reason)
        scheduleSave()
    }

    /** A size / position change of the virtual room by the user. */
    private fun editRoom(reason: String, f: (VirtualRoom) -> VirtualRoom) {
        val s = _state.value
        if (s.roomSource != RoomSource.VIRTUAL) return
        val v = s.vroom ?: return
        val next = f(v)
        if (next == v) return
        update { it.copy(vroom = next, vroomTouched = true) }
        TpLog.i(String.format(Locale.US, "ROOM %s size=%s center=(%.2f, %.2f) yaw=%.1f", reason, next.label, next.center.x, next.center.z, next.yawDeg))
        applyRoom(reason)
        scheduleSave()
    }

    /** A scanned floor or ceiling snaps the virtual room's floor height / height, once per new value. */
    private fun snapToScan(relayout: Boolean = true) {
        val s = _state.value
        val v = s.vroom ?: return
        var next = v
        val floor = s.scanned.floorY
        val lastFloor = snappedFloor
        if (floor != null && (lastFloor == null || abs(floor - lastFloor) > SNAP_EPS_M) && abs(floor - v.floorY) > SNAP_EPS_M) {
            snappedFloor = floor
            next = next.copy(floorY = floor)
        }
        val ceiling = s.scanned.ceiling?.centroid?.y
        val lastCeiling = snappedCeiling
        if (ceiling != null && (lastCeiling == null || abs(ceiling - lastCeiling) > SNAP_EPS_M)) {
            val h = ceiling - next.floorY
            if (h in VirtualRoom.MIN_H..VirtualRoom.MAX_H && abs(h - next.height) > SNAP_EPS_M) {
                snappedCeiling = ceiling
                next = next.copy(height = Math.round(h * 100f) / 100f)
            }
        }
        if (next == v) return
        TpLog.i(String.format(Locale.US, "ROOM snapped floor=%.2f height=%.2f (scan)", next.floorY, next.height))
        update { it.copy(vroom = next) }
        if (relayout && _state.value.roomSource == RoomSource.VIRTUAL) {
            applyRoom("snap")
            scheduleSave()
        }
    }

    /** The room every rule works on, from the source; a seated layout is fitted into it. */
    private fun applyRoom(reason: String, refit: Boolean = true) {
        val s = _state.value
        val room = if (s.roomSource == RoomSource.VIRTUAL) s.vroom?.model ?: s.scanned else s.scanned
        if (room == s.room) return
        update { it.copy(room = room) }
        TpLog.i("ROOM use reason=$reason source=${s.roomSource} planes=${room.planes.size} ceiling=${room.hasCeiling}")
        if (refit) refit(s.room)
    }

    /**
     * The walls moved: the seat keeps its ear point and front direction (the screen goes onto the new front
     * wall), every listener-level speaker re-lands on the walls at its azimuth, every overhead one keeps its
     * offset from the seat on the new ceiling.
     */
    private fun refit(oldRoom: RoomModel) {
        val s = _state.value
        val old = s.seat ?: return
        if (!(s.phase == Phase.LAYOUT || s.phase == Phase.RESEAT)) return
        val room = s.room
        val seat = Seat.first(old.ear, old.f, room)
        val (positions, noWall) = transplant(old, oldRoom, seat, room, s.positions, s.noWall)
        update { evaluate(it.copy(seat = seat, positions = positions, noWall = noWall, overheadLat = Theater.overheadLat(room, seat))) }
    }

    /**
     * Speaker positions carried from [old] in [oldRoom] to [seat] in [room]: a listener-level speaker re-lands on
     * the walls at its azimuth (taken at the wall point it stands 12 cm off, else every move creeps outwards), an
     * overhead one keeps its lateral / forward offset on the new ceiling.
     */
    private fun transplant(old: Seat, oldRoom: RoomModel, seat: Seat, room: RoomModel, from: Map<String, Vec3>, fromNoWall: Set<String>): Pair<Map<String, Vec3>, Set<String>> {
        val positions = LinkedHashMap<String, Vec3>()
        val noWall = fromNoWall.toMutableSet()
        for ((id, pos) in from) {
            val def = SpeakerDef.ALL.firstOrNull { it.id == id }
            if (def == null) {
                positions[id] = pos
                continue
            }
            val xz = P2.of(pos)
            if (def.kind == SpeakerKind.LISTENER) {
                val n = Theater.land(oldRoom, old, old.azimuthOf(xz)).wallNormal
                val az = old.azimuthOf(if (id in fromNoWall || n == null) xz else xz - n * Theater.WALL_RETREAT_M)
                val landing = Theater.land(room, seat, az)
                positions[id] = landing.point.at(pos.y - old.ear.y + seat.ear.y)
                if (landing.noWall) noWall += id else noWall -= id
            } else {
                val at = P2.of(seat.ear) + seat.right * old.lateralOf(xz) + seat.f * old.forwardOf(xz)
                positions[id] = room.clampToCeiling(at.at(room.ceilingY()), Theater.CEILING_INSET_M)
            }
        }
        return positions to noWall
    }

    private fun onTracking(lost: Boolean) {
        val s = _state.value
        if (lost == s.trackingLost) return
        if (lost) {
            TpLog.i("TRACK lost")
            update { it.copy(trackingLost = true, dragging = null, menuOpen = false, snack = SnackUi(SnackKind.LOST, ++snackSeq)) }
        } else {
            TpLog.i("TRACK recovered")
            update { it.copy(trackingLost = false, snack = if (it.snack?.kind == SnackKind.LOST) null else it.snack) }
        }
    }

    // ---- sitting ------------------------------------------------------------------------

    /** Sit (design T1 / T11): P and f from the head at the pinch; the speakers land at their recommended positions. */
    fun sit(head: HeadPose) {
        val st = _state.value
        val reseat = st.phase == Phase.RESEAT && st.seat != null
        val oldSeat = st.seat
        val oldRoom = st.room
        if (st.roomSource == RoomSource.VIRTUAL && !st.vroomTouched) {
            // the untouched virtual room is put around the place the user sits down in, front wall ahead
            val v = st.vroom
            val around = VirtualRoom.around(P2.of(head.position), head.level(), v?.width ?: VirtualRoom.DEFAULT_W, v?.length ?: VirtualRoom.DEFAULT_L, v?.height ?: VirtualRoom.DEFAULT_H, v?.floorY ?: 0f)
            update { it.copy(vroom = around) }
            applyRoom(if (reseat) "reseat" else "sit", refit = false)
        }
        val s = _state.value
        val room = s.room
        // sit and reseat alike come from the head of this moment, position and level heading (taste profile D9):
        // the screen goes onto the wall ahead, never back onto the old one behind a user who turned round
        val seat = Seat.first(head.position, head.level(), room)
        val lat = Theater.overheadLat(room, seat)
        TpLog.i(
            String.format(
                Locale.US,
                "SEAT %s P=%s ear_h=%.2f f=(%.3f, %.3f) screen=(%.3f, %.3f) floating=%s wall=%s",
                if (reseat) "reseat" else "sit",
                TpLog.pos(seat.ear),
                seat.earHeight,
                seat.f.x,
                seat.f.z,
                seat.screenCenter.x,
                seat.screenCenter.z,
                seat.screenFloating,
                seat.screenWall?.label ?: "-",
            )
        )
        val h = seat.earHeight
        val snack =
            when {
                h > Seat.STANDING_ABOVE_M -> SnackUi(SnackKind.STANDING, ++snackSeq)
                h < Seat.TOO_LOW_BELOW_M -> SnackUi(SnackKind.TOO_LOW, ++snackSeq)
                else -> null
            }
        update {
            val base = it.copy(seat = seat, overheadLat = lat, phase = Phase.LAYOUT, selected = null, dragging = null, restore = RestoreState.NONE, snack = snack ?: if (it.snack?.kind == SnackKind.ELSEWHERE || it.snack?.kind == SnackKind.SCANNING || it.snack?.kind == SnackKind.VIRTUAL_ROOM) null else it.snack)
            if (reseat && oldSeat != null) {
                // the speakers turn with the user: same angles and offsets from the new seat
                val (pos, nw) = transplant(oldSeat, oldRoom, seat, room, it.positions, it.noWall)
                evaluate(base.copy(positions = pos, noWall = nw))
            } else evaluate(base.copy(positions = emptyMap(), noWall = emptySet(), inches = null))
        }
        if (snack != null) timedSnack(snack)
        if (!reseat) {
            createAnchor(seat)
            if (!s.settings.coachSeen) {
                coachJob?.cancel()
                coachJob =
                    scope.launch {
                        delay(COACH_DELAY_MS)
                        if (_state.value.inLayout && !_state.value.settings.coachSeen) update { it.copy(coach = true) }
                    }
            }
        }
        scheduleSave()
    }

    private fun createAnchor(seat: Seat) {
        anchorJob?.cancel()
        anchorJob =
            scope.launch {
                val old = anchor
                anchor = null
                if (old != null) anchors.remove(old.id)
                val at = Vec3(seat.ear.x, seat.floorY, seat.ear.z)
                when (val r = anchors.create(at, "theater-seat")) {
                    is AnchorOutcome.Ok -> {
                        anchor = r.value
                        TpLog.i("ANCHOR created id=${r.value.id.take(8)} at=${TpLog.pos(r.value.pose.position)}")
                        if (_state.value.unsaved) update { it.copy(unsaved = false, snack = if (it.snack?.kind == SnackKind.UNSAVED) null else it.snack) }
                        scheduleSave()
                    }
                    is AnchorOutcome.Failed -> {
                        TpLog.w("ANCHOR create failed code=${r.code} msg=${r.message}")
                        update { it.copy(unsaved = true, snack = SnackUi(SnackKind.UNSAVED, ++snackSeq)) }
                    }
                }
            }
    }

    // ---- speakers --------------------------------------------------------------------------

    private fun onSpeakerTapped(id: String) {
        val s = _state.value
        if (!s.inLayout || s.trackingLost || s.blocked) return
        if (s.menuOpen) {
            update { it.copy(menuOpen = false) }
            return
        }
        if (s.clearDialog) return
        val sp = s.speaker(id) ?: return
        val rear = sp.def.isRear && !s.settings.rearCoachSeen
        update { it.copy(selected = id, page = Page.SPEAKERS, rearCoach = if (rear) id else null) }
        TpLog.i("SELECT id=$id az=${fmt(sp.value)} status=${sp.status}")
    }

    private fun onEmptyTapped() {
        val s = _state.value
        if (s.menuOpen) {
            update { it.copy(menuOpen = false) }
            return
        }
        if (s.selected != null) update { it.copy(selected = null, rearCoach = null) }
    }

    private fun onDragEnded(e: PlannerEvent.DragEnded) {
        val s = _state.value
        if (s.dragging != e.id) {
            update { it.copy(dragging = null) }
            return
        }
        update { it.copy(dragging = null) }
        if (e.position != null) place(e.id, e.position, e.noWall, "drag")
    }

    private fun place(id: String, position: Vec3, noWall: Boolean, via: String) {
        changeThenSave {
            val pos = it.positions.toMutableMap()
            pos[id] = position
            val nw = it.noWall.toMutableSet()
            if (noWall) nw += id else nw -= id
            it.copy(positions = pos, noWall = nw, selected = id)
        }
        val sp = _state.value.speaker(id)
        TpLog.i("PLACE id=$id via=$via at=${TpLog.pos(position)} value=${fmt(sp?.value ?: 0f)} status=${sp?.status}")
    }

    private fun resetOne(id: String) {
        changeThenSave {
            val pos = it.positions.toMutableMap()
            pos.remove(id)
            it.copy(positions = pos, noWall = it.noWall - id)
        }
        TpLog.i("RESET id=$id")
    }

    /** All speakers back to their recommended positions; the overhead lateral offset is refreshed (design R2-6). */
    private fun resetAll() {
        changeThenSave { it.copy(positions = emptyMap(), noWall = emptySet(), overheadLat = it.seat?.let { seat -> Theater.overheadLat(it.room, seat) } ?: it.overheadLat) }
        TpLog.i("RESET all")
    }

    private fun stepSize(delta: Int) {
        changeThenSave {
            val seat = it.seat ?: return@changeThenSave it
            val lim = ScreenRules.limits(seat, it.room, it.mode)
            val current = it.inches ?: it.advice?.use ?: lim.first
            it.copy(inches = (current + delta * 5).coerceIn(lim.first, lim.last))
        }
    }

    // ---- evaluation ------------------------------------------------------------------------

    /** Positions for missing speakers, then every speaker judged, the screen, the projector and the absorbers. */
    private fun evaluate(s: PlannerUiState): PlannerUiState {
        val seat = s.seat ?: return s.copy(speakers = emptyList(), screen = null, advice = null, projector = null, absorbers = emptyList())
        val room = s.room
        val advice = ScreenRules.advice(seat, room, s.mode)
        val inches = (s.inches ?: advice.use).coerceIn(advice.min, advice.max)
        val screen = ScreenRules.geom(seat, room, inches)
        val defs = SpeakerDef.of(s.config)
        val positions = s.positions.toMutableMap()
        val noWall = s.noWall.toMutableSet()
        val out = ArrayList<SpeakerState>(defs.size)
        for (def in defs) {
            var pos = positions[def.id]
            var landing: io.embodit.theaterplanner.domain.Landing? = null
            if (pos == null) {
                if (def.kind == SpeakerKind.LISTENER) {
                    landing = Theater.land(room, seat, def.azimuth)
                    pos = landing.point.at(Theater.listenerY(def, seat, screen.bottom))
                    if (landing.noWall) noWall += def.id else noWall -= def.id
                } else {
                    pos = Theater.recommendOverhead(room, seat, def, s.overheadLat)
                }
                positions[def.id] = pos
            } else if (def.kind == SpeakerKind.LISTENER) {
                // the height follows the rule live (C under the screen, the rest at ear height)
                pos = Vec3(pos.x, Theater.listenerY(def, seat, screen.bottom), pos.z)
                positions[def.id] = pos
                landing = Theater.land(room, seat, seat.azimuthOf(P2.of(pos)))
            } else {
                pos = Vec3(pos.x, room.ceilingY() - 0.01f, pos.z)
                positions[def.id] = pos
            }
            val floating = def.id in noWall
            out += Theater.evaluate(def, pos, seat, room, s.overheadLat, screen.bottom, floating, if (floating) null else landing?.plane, if (floating) null else landing?.wallNormal)
        }
        val projector = if (s.mode == ScreenMode.PROJECTOR) ProjectorRules.geom(seat, room, screen, s.ratio) else null
        val absorbers = if (s.absorbersOn) ReflectionRules.absorbers(seat, room, out, s.config) else emptyList()
        val rear = Theater.rearWallDistance(room, seat)
        return s.copy(positions = positions, noWall = noWall, speakers = out, screen = screen, advice = advice, projector = projector, absorbers = absorbers, rearWallDistance = rear)
    }

    // ---- settings, menu, clear ---------------------------------------------------------------

    private fun settings(f: (io.embodit.theaterplanner.domain.Settings) -> io.embodit.theaterplanner.domain.Settings) {
        val next = f(_state.value.settings)
        store.saveSettings(next)
        update { it.copy(settings = next) }
    }

    private fun clearAll() {
        TpLog.i("CLEAR all")
        saveJob?.cancel()
        val old = anchor
        anchor = null
        saved = null
        runCatching { store.saveLayout(null) }
        if (old != null) scope.launch { anchors.remove(old.id) }
        update {
            PlannerUiState(
                phase = Phase.SEAT,
                room = it.room,
                scanned = it.scanned,
                roomSource = it.roomSource,
                vroom = it.vroom,
                vroomTouched = it.vroomTouched,
                settings = it.settings,
                snack = SnackUi(SnackKind.CLEARED, ++snackSeq),
            )
        }
        _state.value.snack?.let { timedSnack(it) }
    }

    private fun onSnackAction() {
        val s = _state.value
        when (s.snack?.kind) {
            SnackKind.STANDING, SnackKind.TOO_LOW -> update { it.copy(phase = Phase.SEAT, seat = null, speakers = emptyList(), positions = emptyMap(), snack = null, coach = false) }
            SnackKind.ELSEWHERE -> update { it.copy(restore = RestoreState.NONE, phase = Phase.SEAT, snack = null) }
            SnackKind.UNSAVED -> {
                update { it.copy(snack = null) }
                val seat = s.seat
                if (anchor == null && seat != null) createAnchor(seat) else scheduleSave(now = true)
            }
            else -> update { it.copy(snack = null) }
        }
    }

    private fun timedSnack(snack: SnackUi, ms: Long = SNACK_MS) {
        timedSnackJob?.cancel()
        timedSnackJob =
            scope.launch {
                delay(if (longSnacks) 60_000L else ms)
                update { if (it.snack?.id == snack.id) it.copy(snack = null) else it }
            }
    }

    // ---- persistence ---------------------------------------------------------------------

    private fun changeThenSave(f: (PlannerUiState) -> PlannerUiState) {
        update { evaluate(f(it)) }
        scheduleSave()
    }

    private fun scheduleSave(now: Boolean = false) {
        saveJob?.cancel()
        saveJob =
            scope.launch {
                if (!now) delay(SAVE_DELAY_MS)
                saveNow()
            }
    }

    private fun saveNow() {
        val s = _state.value
        val seat = s.seat ?: return
        val a = anchor ?: return
        val layout =
            SavedLayout(
                anchorId = a.id,
                ear = a.pose.toLocal(seat.ear),
                floorY = seat.floorY - a.pose.position.y,
                f = a.pose.toLocalDir(seat.f),
                screenCenter = P2.of(a.pose.toLocal(seat.screenCenter.at(seat.ear.y))),
                screenNormal = a.pose.toLocalDir(seat.screenNormal),
                screenFloating = seat.screenFloating,
                config = s.config,
                screenPage = s.page == Page.SCREEN,
                speakers = s.positions.mapValues { (_, v) -> a.pose.toLocal(v) },
                inches = s.inches,
                mode = s.mode,
                ratio = s.ratio,
                absorbers = s.absorbersOn,
                room = s.vroom?.takeIf { s.roomSource == RoomSource.VIRTUAL }?.let { v -> SavedRoom(P2.of(a.pose.toLocal(v.center.at(v.floorY))), a.pose.toLocalDir(v.forward), v.width, v.length, v.height, v.floorY - a.pose.position.y) },
            )
        val ok = runCatching { store.saveLayout(layout) }.isSuccess
        if (ok) {
            saved = layout
            if (s.unsaved) update { it.copy(unsaved = false, snack = if (it.snack?.kind == SnackKind.UNSAVED) null else it.snack) }
            TpLog.i("SAVE ok speakers=${layout.speakers.size}")
        } else {
            TpLog.w("SAVE failed")
            update { it.copy(unsaved = true, snack = SnackUi(SnackKind.UNSAVED, ++snackSeq)) }
        }
    }

    /** Restore (design S16 / S17): the saved anchor must be found within 10 s. */
    private fun startRestore() {
        val layout = saved ?: return
        restoreJob?.cancel()
        restoreJob =
            scope.launch {
                val t0 = SystemClock.elapsedRealtime()
                var found: AnchorRecord? = null
                while (found == null && SystemClock.elapsedRealtime() - t0 < RESTORE_TIMEOUT_MS) {
                    val r = anchors.loadAll()
                    if (r is AnchorOutcome.Ok) found = r.value.firstOrNull { it.id == layout.anchorId }
                    if (found == null) delay(1000)
                }
                if (found == null) {
                    TpLog.i("RESTORE elsewhere id=${layout.anchorId.take(8)}")
                    update { it.copy(restore = RestoreState.ELSEWHERE, phase = Phase.SEAT, snack = SnackUi(SnackKind.ELSEWHERE, ++snackSeq)) }
                    return@launch
                }
                restoreFrom(layout, found)
            }
    }

    private fun restoreFrom(layout: SavedLayout, found: AnchorRecord) {
        anchor = found
        val a = found.pose
        val ear = a.toWorld(layout.ear)
        val r = layout.room
        if (r != null && _state.value.roomSource != RoomSource.SCAN) {
            // the virtual room the layout was made in, back in place
            val fwd = a.toWorldDir(r.forward)
            val yaw = Math.toDegrees(kotlin.math.atan2(fwd.x.toDouble(), -fwd.z.toDouble())).toFloat()
            useVirtual("restore", VirtualRoom(P2.of(a.toWorld(r.center.at(0f))), yaw, r.width, r.length, r.height, a.position.y + r.floorY))
        } else if (_state.value.roomSource == RoomSource.NONE) {
            if (_state.value.scanUsable) {
                fallbackJob?.cancel()
                update { it.copy(roomSource = RoomSource.SCAN) }
                applyRoom("restore")
            } else useVirtual("restore", VirtualRoom.around(P2.of(ear), a.toWorldDir(layout.f), floorY = a.position.y + layout.floorY))
        }
        val room = _state.value.room
        val seat =
            Seat(
                ear = ear,
                floorY = room.floorY ?: (a.position.y + layout.floorY),
                f = a.toWorldDir(layout.f),
                screenCenter = P2.of(a.toWorld(layout.screenCenter.at(0f))),
                screenNormal = a.toWorldDir(layout.screenNormal),
                screenWall = null,
                screenFloating = layout.screenFloating,
            )
        val positions = layout.speakers.mapValues { (_, v) -> a.toWorld(v) }
        update {
            evaluate(
                it.copy(
                    seat = seat,
                    overheadLat = Theater.overheadLat(room, seat),
                    positions = positions,
                    config = layout.config,
                    page = if (layout.screenPage) Page.SCREEN else Page.SPEAKERS,
                    inches = layout.inches,
                    mode = layout.mode,
                    ratio = layout.ratio,
                    absorbersOn = layout.absorbers,
                    phase = Phase.LAYOUT,
                    restore = RestoreState.RESTORED,
                    snack = SnackUi(SnackKind.RESTORED, ++snackSeq),
                )
            )
        }
        _state.value.snack?.let { timedSnack(it) }
        TpLog.i("RESTORE ok id=${found.id.take(8)} speakers=${positions.size} cfg=${layout.config.tag}")
    }

    // ---- debug seams (driven by the debug receiver only) --------------------------------------

    fun debugSet(f: (PlannerUiState) -> PlannerUiState) = update { evaluate(f(it)) }

    fun debugRestoreTimeout() {
        restoreJob?.cancel()
        update { it.copy(restore = RestoreState.ELSEWHERE, phase = Phase.SEAT, seat = null, speakers = emptyList(), positions = emptyMap(), snack = SnackUi(SnackKind.ELSEWHERE, ++snackSeq)) }
    }

    fun debugRestore() {
        saved = store.loadLayout()
        if (saved == null) {
            TpLog.w("CMD restore: nothing saved")
            return
        }
        update { it.copy(restore = RestoreState.LOOKING) }
        startRestore()
    }

    /** Debug: forget the room and scan again from S02 (the 5 s fallback runs again). */
    fun debugRescan() {
        fallbackJob?.cancel()
        restoreJob?.cancel()
        saved = null
        debugHoldScanning = false
        snappedFloor = null
        snappedCeiling = null
        update { it.copy(phase = Phase.STARTING, seat = null, speakers = emptyList(), positions = emptyMap(), noWall = emptySet(), room = RoomModel.EMPTY, roomSource = RoomSource.NONE, vroom = null, vroomTouched = false, menuOpen = false, roomPage = false, restore = RestoreState.NONE, holdStarting = false, coach = false, rearCoach = null, selected = null) }
        enterScanning()
    }

    /** Debug: the virtual room at this size (and touched, so sitting does not re-centre it). */
    fun debugRoomSize(w: Float?, l: Float?, h: Float?) {
        val v = _state.value.vroom ?: return
        onEvent(PlannerEvent.RoomResized((w ?: v.width) - v.width, (l ?: v.length) - v.length, (h ?: v.height) - v.height))
    }

    /** Debug: no first-run coachmark for this layout (the QA base state). */
    fun debugCancelCoach() {
        coachJob?.cancel()
        update { it.copy(coach = false, rearCoach = null) }
    }

    fun debugCoachReset() {
        settings { it.copy(coachSeen = false, rearCoachSeen = false) }
    }

    fun debugSaveFail() {
        saveJob?.cancel()
        anchorJob?.cancel()
        update { it.copy(unsaved = true, snack = SnackUi(SnackKind.UNSAVED, ++snackSeq)) }
    }

    fun debugSnack(kind: SnackKind) {
        val snack = SnackUi(kind, ++snackSeq)
        update { it.copy(snack = snack) }
        if (!kind.sticky) timedSnack(snack)
    }

    /** `STATE` line for the QA station: the design state and every speaker. */
    fun stateLine(): String {
        val s = _state.value
        val sb = StringBuilder()
        sb.append("STATE ").append(s.designState).append(" phase=").append(s.phase).append(" cfg=").append(s.config.tag).append(" page=").append(s.page)
        s.seat?.let { sb.append(String.format(Locale.US, " P=%s ear_h=%.2f", TpLog.pos(it.ear), it.earHeight)) }
        for (sp in s.speakers) {
            sb.append(String.format(Locale.US, " | %s %s=%.1f lat=%.2f dist=%.2f %s%s%s", sp.id, if (sp.def.kind == SpeakerKind.LISTENER) "az" else "el", sp.value, sp.latNow, sp.distance, sp.status, if (sp.noWall) " noWall" else "", if (sp.id == s.selected) " *" else ""))
        }
        s.screen?.let { sb.append(String.format(Locale.US, " | screen %d\" w=%.2f view=%.1f d=%.2f mode=%s", it.inches, it.width, it.viewDeg, it.distance, s.mode.tag)) }
        s.projector?.let { sb.append(String.format(Locale.US, " | projector t=%.2f tooFar=%s lack=%.2f noRear=%s", it.throwM, it.tooFar, it.lack, it.noRearWall)) }
        return sb.toString()
    }

    // =========================================================================================

    private inline fun update(f: (PlannerUiState) -> PlannerUiState) {
        val next = f(_state.value)
        _state.value = next
        val ds = next.designState
        if (ds != lastLogged) {
            lastLogged = ds
            val line = stateLine()
            if (line != lastLoggedLine) {
                lastLoggedLine = line
                TpLog.i(line)
            }
        }
    }

    private fun fmt(v: Float) = String.format(Locale.US, "%.1f", v)

    class Factory(private val context: Context) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            val app = context.applicationContext
            return PlannerViewModel(TpGraph.anchors, TpGraph.store(app), { TpGraph.head.blockReason() }, { TpGraph.head.pose() }) as T
        }
    }

    companion object {
        const val BLOCK_GRACE_MS = 2000L
        const val SAVE_DELAY_MS = 500L
        const val RESTORE_TIMEOUT_MS = 10_000L
        const val SNACK_MS = 5000L
        const val COACH_DELAY_MS = 1000L
        const val SCAN_TIMEOUT_MS = 5000L
        const val VIRTUAL_SNACK_MS = 8000L
        const val SNAP_EPS_M = 0.02f
    }
}
