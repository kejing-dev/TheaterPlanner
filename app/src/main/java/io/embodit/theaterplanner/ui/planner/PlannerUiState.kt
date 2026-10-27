package io.embodit.theaterplanner.ui.planner

import io.embodit.theaterplanner.domain.Absorber
import io.embodit.theaterplanner.domain.Config
import io.embodit.theaterplanner.domain.HeadPose
import io.embodit.theaterplanner.domain.LanguagePref
import io.embodit.theaterplanner.domain.LengthUnit
import io.embodit.theaterplanner.domain.ProjectorGeom
import io.embodit.theaterplanner.domain.RoomModel
import io.embodit.theaterplanner.domain.RoomSource
import io.embodit.theaterplanner.domain.ScreenGeom
import io.embodit.theaterplanner.domain.ScreenMode
import io.embodit.theaterplanner.domain.Seat
import io.embodit.theaterplanner.domain.Settings
import io.embodit.theaterplanner.domain.SizeAdvice
import io.embodit.theaterplanner.domain.SpeakerKind
import io.embodit.theaterplanner.domain.SpeakerState
import io.embodit.theaterplanner.domain.Status
import io.embodit.theaterplanner.domain.Vec3
import io.embodit.theaterplanner.domain.VirtualRoom

enum class Phase { STARTING, SCANNING, SEAT, LAYOUT, RESEAT, UNAVAILABLE }

enum class Page { SPEAKERS, SCREEN }

enum class RestoreState { NONE, LOOKING, RESTORED, ELSEWHERE }

/** Snackbar kinds (design `copy.snackbar`); sticky ones stay until their condition ends. */
enum class SnackKind(val sticky: Boolean) {
    SCANNING(true),
    STANDING(false),
    TOO_LOW(false),
    RESTORED(false),
    ELSEWHERE(true),
    LOST(true),
    CLEARED(false),
    UNSAVED(true),
    /** Nothing scanned in time: the virtual room is in use (taste profile D7). */
    VIRTUAL_ROOM(false),
}

data class SnackUi(val kind: SnackKind, val id: Int)

/** The lines of the console's status row (design `rules.status`): the first is the icon line, the second is grey. */
data class StatusLine(val kind: StatusKind, val n: Int = 0, val deg: Int = 0, val lack: Float = 0f) {
    val status: Status
        get() =
            when (kind) {
                StatusKind.SOME_OUT, StatusKind.TOO_FAR -> Status.RED
                StatusKind.TOO_CLOSE, StatusKind.NO_REAR, StatusKind.ASSUMED_CEILING, StatusKind.VIEW_SMALL, StatusKind.VIEW_LARGE -> Status.ORANGE
                StatusKind.ALL_IN, StatusKind.VIEW_OK -> Status.GREEN
            }
}

enum class StatusKind { SOME_OUT, TOO_CLOSE, NO_REAR, ASSUMED_CEILING, ALL_IN, TOO_FAR, VIEW_SMALL, VIEW_LARGE, VIEW_OK }

/**
 * The whole UI state (design `states`, `panels`). Pure data: panels and the scene read it; only the
 * ViewModel writes it.
 */
data class PlannerUiState(
    val phase: Phase = Phase.STARTING,
    /** The room every rule works on: the scanned planes ([RoomSource.SCAN]) or the virtual box ([RoomSource.VIRTUAL]). */
    val room: RoomModel = RoomModel.EMPTY,
    /** What plane detection has found so far (whatever the source). */
    val scanned: RoomModel = RoomModel.EMPTY,
    val roomSource: RoomSource = RoomSource.NONE,
    /** The virtual room (kept while the scanned room is in use, so that switching back finds it again). */
    val vroom: VirtualRoom? = null,
    /** The user has resized or moved the virtual room (it no longer re-centres on the seat). */
    val vroomTouched: Boolean = false,
    /** The menu shows its room page (S28). */
    val roomPage: Boolean = false,
    val seat: Seat? = null,
    val config: Config = Config.C51,
    val page: Page = Page.SPEAKERS,
    /** Every speaker that has ever appeared: its position (design `configSwitch`). */
    val positions: Map<String, Vec3> = emptyMap(),
    /** Speakers whose direction found no wall (floating, design S25). */
    val noWall: Set<String> = emptySet(),
    val overheadLat: Float = 0.6f,
    /** The speakers of the current configuration, evaluated. */
    val speakers: List<SpeakerState> = emptyList(),
    val selected: String? = null,
    val dragging: String? = null,
    val absorbersOn: Boolean = false,
    val absorbers: List<Absorber> = emptyList(),
    /** Null: the recommended ("use best") size. */
    val inches: Int? = null,
    val mode: ScreenMode = ScreenMode.TV,
    val ratio: Float = 1.20f,
    val screen: ScreenGeom? = null,
    val advice: SizeAdvice? = null,
    val projector: ProjectorGeom? = null,
    val rearWallDistance: Float? = null,
    val menuOpen: Boolean = false,
    val clearDialog: Boolean = false,
    val blocked: Boolean = false,
    val snack: SnackUi? = null,
    val coach: Boolean = false,
    /** The rear speaker whose one-time coachmark shows (design S24). */
    val rearCoach: String? = null,
    val trackingLost: Boolean = false,
    val restore: RestoreState = RestoreState.NONE,
    val unsaved: Boolean = false,
    val settings: Settings = Settings(),
    /** Debug: stay in S01. */
    val holdStarting: Boolean = false,
) {
    val unit: LengthUnit
        get() = settings.unit

    val inLayout: Boolean
        get() = phase == Phase.LAYOUT && seat != null

    /** The console takes input (design `panels.console.states`). */
    val consoleEnabled: Boolean
        get() = inLayout && !trackingLost && !blocked

    val dragEnabled: Boolean
        get() = inLayout && !trackingLost && !blocked && !clearDialog

    val selectedSpeaker: SpeakerState?
        get() = selected?.let { id -> speakers.firstOrNull { it.id == id } }

    /** The scanned room can carry the layout (a floor and a wall). */
    val scanUsable: Boolean
        get() = scanned.hasFloor && scanned.hasWall

    val overheadAssumed: Boolean
        get() = !room.hasCeiling && speakers.any { it.def.kind == SpeakerKind.OVERHEAD }

    fun speaker(id: String): SpeakerState? = speakers.firstOrNull { it.id == id }

    /** The status lines of the speaker page in the design's fixed order (at most two). */
    fun speakerStatusLines(): List<StatusLine> {
        val out = ArrayList<StatusLine>(2)
        val outOfRange = speakers.count { it.status == Status.RED }
        val tooClose = speakers.count { it.status == Status.ORANGE }
        if (outOfRange > 0) out += StatusLine(StatusKind.SOME_OUT, outOfRange)
        if (tooClose > 0) out += StatusLine(StatusKind.TOO_CLOSE, tooClose)
        val rear = rearWallDistance
        if (config == Config.C714 && rear != null && rear < io.embodit.theaterplanner.domain.Theater.REAR_WALL_MIN_M) out += StatusLine(StatusKind.NO_REAR)
        if (overheadAssumed) out += StatusLine(StatusKind.ASSUMED_CEILING)
        if (out.isEmpty() || out.size < 2 && outOfRange == 0 && tooClose == 0) out += StatusLine(StatusKind.ALL_IN, speakers.size)
        return out.take(2)
    }

    /** The status lines of the screen page in the design's fixed order (at most two). */
    fun screenStatusLines(): List<StatusLine> {
        val out = ArrayList<StatusLine>(2)
        val s = screen ?: return out
        val p = projector
        if (mode == ScreenMode.PROJECTOR && p != null && p.tooFar) out += StatusLine(StatusKind.TOO_FAR, lack = p.lack)
        val deg = Math.round(s.viewDeg)
        out +=
            when {
                s.viewDeg < io.embodit.theaterplanner.domain.ScreenRules.VIEW_SMALL_DEG - 0.5f -> StatusLine(StatusKind.VIEW_SMALL, deg = deg)
                s.viewDeg > io.embodit.theaterplanner.domain.ScreenRules.VIEW_LARGE_DEG + 0.5f -> StatusLine(StatusKind.VIEW_LARGE, deg = deg)
                else -> StatusLine(StatusKind.VIEW_OK, deg = deg)
            }
        return out.take(2)
    }

    /** The design state the app is in now (design `states`), for the log and the QA station. */
    val designState: String
        get() =
            when {
                blocked || phase == Phase.UNAVAILABLE -> "S19"
                phase == Phase.STARTING -> "S01"
                phase == Phase.SCANNING -> "S02"
                restore == RestoreState.ELSEWHERE -> "S17"
                phase == Phase.SEAT && snack?.kind == SnackKind.VIRTUAL_ROOM -> "S29"
                phase == Phase.SEAT -> "S03"
                clearDialog -> "S20"
                trackingLost -> "S18"
                phase == Phase.RESEAT -> "S22"
                snack?.kind == SnackKind.UNSAVED -> "S27"
                snack?.kind == SnackKind.STANDING || snack?.kind == SnackKind.TOO_LOW -> "S26"
                restore == RestoreState.RESTORED && snack?.kind == SnackKind.RESTORED -> "S16"
                menuOpen && roomPage -> "S28"
                menuOpen -> "S15"
                rearCoach != null -> "S24"
                coach -> "S21"
                page == Page.SCREEN && mode == ScreenMode.PROJECTOR && projector?.tooFar == true -> "S14"
                page == Page.SCREEN && mode == ScreenMode.PROJECTOR -> "S13"
                page == Page.SCREEN -> "S12"
                dragging != null -> "S08"
                selectedSpeaker?.noWall == true -> "S25"
                selectedSpeaker?.status == Status.RED && speakers.any { it.tooClose } -> "S23"
                selectedSpeaker?.status == Status.RED -> "S09"
                selected != null -> "S07"
                absorbersOn -> "S11"
                overheadAssumed -> "S10"
                speakers.any { it.tooClose } -> "S23"
                config == Config.C512 -> "S05"
                config == Config.C714 -> "S06"
                else -> "S04"
            }
}

/** Events from the panels, the scene and the platform. */
sealed interface PlannerEvent {
    data object SceneReady : PlannerEvent

    data class PlanesChanged(val room: RoomModel) : PlannerEvent

    data class TrackingChanged(val lost: Boolean) : PlannerEvent

    /** A pinch anywhere in the room (surface, backstop, speaker) while sitting is asked for. */
    data class Pinched(val head: HeadPose) : PlannerEvent

    data class SeatHere(val head: HeadPose) : PlannerEvent

    data object ReseatClicked : PlannerEvent

    data object ReseatCancel : PlannerEvent

    data class ConfigSelected(val config: Config) : PlannerEvent

    data class PageSelected(val page: Page) : PlannerEvent

    data class SpeakerTapped(val id: String) : PlannerEvent

    data object EmptyTapped : PlannerEvent

    data class DragStarted(val id: String) : PlannerEvent

    /** [position] null: cancelled. */
    data class DragEnded(val id: String, val position: Vec3?, val noWall: Boolean) : PlannerEvent

    /** The thumbstick turned a speaker about the seat's vertical axis (taste profile D). */
    data class SpeakerTurned(val id: String, val position: Vec3, val noWall: Boolean) : PlannerEvent

    data class ResetOne(val id: String) : PlannerEvent

    data object ResetAll : PlannerEvent

    data class AbsorbersToggled(val on: Boolean) : PlannerEvent

    data class ModeSelected(val mode: ScreenMode) : PlannerEvent

    data class SizeStepped(val delta: Int) : PlannerEvent

    data object UseRecommended : PlannerEvent

    data class RatioStepped(val delta: Int) : PlannerEvent

    data object MoreClicked : PlannerEvent

    data class LanguageSelected(val language: LanguagePref) : PlannerEvent

    data class UnitSelected(val unit: LengthUnit) : PlannerEvent

    data object ClearAllClicked : PlannerEvent

    data object ClearConfirmed : PlannerEvent

    data object DialogDismissed : PlannerEvent

    data object ExitClicked : PlannerEvent

    data object BlockedExit : PlannerEvent

    data object SnackActionClicked : PlannerEvent

    data object SnackClosed : PlannerEvent

    data object CoachDismissed : PlannerEvent

    /** Menu: open / close the room page. */
    data class RoomPageShown(val shown: Boolean) : PlannerEvent

    data class RoomSourceSelected(val source: RoomSource) : PlannerEvent

    /** Virtual room size steps, metres (taste profile D7). */
    data class RoomResized(val dw: Float, val dl: Float, val dh: Float) : PlannerEvent

    /** Virtual room nudges: [right] / [ahead] metres along the viewer's level heading, [turnDeg] about the room centre. */
    data class RoomMoved(val right: Float, val ahead: Float, val turnDeg: Float, val head: HeadPose?) : PlannerEvent

    /** Virtual room: put it back around the seat (or the head) at the current size. */
    data class RoomRecentered(val head: HeadPose?) : PlannerEvent

    data object RearCoachDismissed : PlannerEvent
}

sealed interface PlannerEffect {
    data object Exit : PlannerEffect

    data class Feedback(val kind: String) : PlannerEffect
}
