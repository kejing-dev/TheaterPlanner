package io.embodit.theaterplanner.ui.planner.scene

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import com.pico.spatial.core.ecs.Entity
import com.pico.spatial.core.ecs.SceneUpdateContext
import com.pico.spatial.core.ecs.System
import com.pico.spatial.core.ecs.TransformComponent
import com.pico.spatial.core.math.Quat
import com.pico.spatial.core.math.Vector3
import com.pico.spatial.ui.foundation.content.SpatialViewAttachments
import com.pico.spatial.core.container.SpatialViewContent
import com.pico.spatial.ui.geometry.Offset3D
import com.pico.spatial.ui.foundation.gesture.SpatialDragValue
import com.pico.spatial.ui.foundation.gesture.SpatialTapValue
import com.pico.spatial.core.ecs.ViewCoordinateSpace
import io.embodit.theaterplanner.domain.DragLine
import io.embodit.theaterplanner.domain.FacePanel
import io.embodit.theaterplanner.domain.HeadPose
import io.embodit.theaterplanner.domain.MoveHint
import io.embodit.theaterplanner.domain.P2
import io.embodit.theaterplanner.domain.PanelFacing
import io.embodit.theaterplanner.domain.Quat4
import io.embodit.theaterplanner.domain.RoomModel
import io.embodit.theaterplanner.domain.ScreenMode
import io.embodit.theaterplanner.domain.ScreenRules
import io.embodit.theaterplanner.domain.Seat
import io.embodit.theaterplanner.domain.SpeakerDef
import io.embodit.theaterplanner.domain.SpeakerKind
import io.embodit.theaterplanner.domain.SpeakerState
import io.embodit.theaterplanner.domain.Status
import io.embodit.theaterplanner.domain.Theater
import io.embodit.theaterplanner.domain.Vec3
import io.embodit.theaterplanner.domain.wrapDeg
import io.embodit.theaterplanner.platform.TpGraph
import io.embodit.theaterplanner.platform.TpLog
import io.embodit.theaterplanner.ui.planner.Page
import io.embodit.theaterplanner.ui.planner.Phase
import io.embodit.theaterplanner.ui.planner.PlannerEvent
import io.embodit.theaterplanner.ui.planner.PlannerUiState
import io.embodit.theaterplanner.ui.planner.RestoreState
import io.embodit.theaterplanner.ui.planner.components.PanelSizes
import io.embodit.theaterplanner.ui.planner.components.ReadoutUi
import java.util.Locale
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.tan
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.yield

/** The framework instantiates the system itself, so the running scene is reached through this holder. */
internal object ActiveScene {
    @Volatile var scene: PlannerScene? = null
}

/** The one per-frame ECS system of the app. */
class PlannerFrameSystem : System() {
    override fun update(context: SceneUpdateContext) {
        ActiveScene.scene?.onFrame(context)
    }
}

/** Ids of the attachment panels; the Compose side declares exactly these, once. */
object PanelIds {
    const val CONSOLE = "console"
    const val SEAT = "seatPanel"
    const val STRIP = "speakerStrip"
    const val READOUT = "readout"
    const val SIZE_TAG = "sizeTag"
    const val MENU = "menu"
    const val DIALOG = "dialog"
    const val SNACK = "snack"
    const val COACH = "coach"
    const val TAG_SLOTS = 2
    const val ABS_SLOTS = 9

    fun tag(i: Int) = "tag$i"

    fun abs(i: Int) = "abs$i"

    val pool: List<String> = buildList {
        add(CONSOLE); add(SEAT); add(STRIP); add(READOUT); add(SIZE_TAG); add(MENU); add(DIALOG); add(SNACK); add(COACH)
        repeat(TAG_SLOTS) { add(tag(it)) }
        repeat(ABS_SLOTS) { add(abs(it)) }
    }
}

/**
 * The 3D side of the app: everything in the room (speakers, bands, zones, screen, projector, absorber
 * frames, seat mark), the tap and drag targets and the placement of the pre-built panels.
 *
 * Rules (Spatial Ruler / Furniture Fit): one calibrated stage root; Compose state is rendered here
 * through [render] (idempotent); per-frame work happens in [onFrame] and never goes through Compose,
 * except [panelShown] and [readout] (written on change, at most 10 times a second); everything
 * started in [attach] is stopped in [detach]. Main thread only.
 */
class PlannerScene(private val sink: (PlannerEvent) -> Unit) {

    val panelShown = mutableStateMapOf<String, Boolean>()
    val readout = mutableStateOf<ReadoutUi?>(null)
    val bindTick = mutableIntStateOf(0)

    private val main = Handler(Looper.getMainLooper())
    private val planes = TpGraph.planes
    private val head = TpGraph.head
    private val stick = TpGraph.stick

    private var content: SpatialViewContent? = null
    private var contentProbe: Entity? = null
    private var stageRoot: Entity? = null
    private var rig: PanelRig? = null
    private var shared: SharedResources? = null
    private var materials: TpMaterials? = null
    private var meshes: TpMeshes? = null
    private var colliders: RoomColliders? = null
    private var scope: CoroutineScope? = null
    private var buildJob: Job? = null
    private var attached = false
    private var built = false
    private var paused = false
    private var ui = PlannerUiState()
    private var pose = HeadPose(Vec3.ZERO, Quat4.IDENTITY)
    private var hasHead = false
    private var frames = 0L
    private var lastFrameMs = -1L
    private var lastLogMs = 0L
    private var lastTrackingLost = false
    private var lastConsolePressMs = -1L
    private var lastDragEndMs = -1L
    private var readoutWriteMs = 0L

    // views
    private val speakerViews = HashMap<String, SpeakerView>()
    private val bandViews = HashMap<String, BandView>()
    private val zoneViews = HashMap<String, ZoneView>()
    private val standViews = HashMap<String, Strip>()
    private var screenView: ScreenView? = null
    private var projectorView: ProjectorView? = null
    private val absorberViews = ArrayList<AbsorberView>()
    private var seatMark: SeatMarkView? = null
    private var grid: GridView? = null
    private var arrow: ArrowView? = null
    private var soundLine: Strip? = null
    private var gazeFrame: Frame? = null
    private var snapFrame: Frame? = null

    // live placement (drag / thumbstick), never through Compose state
    private class Drag(val id: String, val press: Vec3) {
        var dir: Vec3? = null
        var equivalent = 0f
        var hand = Vec3.ZERO
        var prevHand = Vec3.ZERO
        var totalPx = Vec3.ZERO
        var begun = false
        var moves = 0
        var lastLogMs = 0L
    }

    private class Turn(val id: String) {
        var neutralSinceMs = -1L
        var frames = 0
    }

    private var drag: Drag? = null
    private var turn: Turn? = null
    private var live: Pair<String, Vec3>? = null
    private var liveNoWall = false
    private var liveState: SpeakerState? = null
    private var snapped = false
    private var awaitCommit: Pair<String, Long>? = null

    // panel follow
    private val consoleFollow = HorizontalFollow("console", { h -> restAt(h, -CONSOLE_RIGHT_DEG, -CONSOLE_DOWN_DEG, CONSOLE_M) }, CONSOLE_WINDOW_DEG)
    private val seatFollow = HorizontalFollow("seat", { h -> restAt(h, 0f, -SEAT_DOWN_DEG, SEAT_M) }, 30f)
    private val snackFollow = HorizontalFollow("snack", { h -> restAt(h, 0f, gazePitchDeg(h) + SNACK_UP_DEG, SNACK_M) }, 25f)

    // =========================================================================================
    // lifecycle
    // =========================================================================================

    fun attach(content: SpatialViewContent, attachments: SpatialViewAttachments) {
        if (attached || stageRoot != null) detach()
        val t0 = SystemClock.elapsedRealtime()
        this.content = content
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        val root = Entity()
        runCatching { root.setName("stageRoot") }
        content.addEntity(root)
        val probe = Entity()
        runCatching { probe.setName("contentProbe") }
        content.addEntity(probe)
        contentProbe = probe
        // An entity added to the SpatialView is not at the Stage origin (0.256 m off in z, Spatial Ruler):
        // move the root once so that its local space equals Stage space.
        val offset = root.convertPositionFrom(Vector3.ZERO, null)
        val rotation = root.convertRotationFrom(Quat.identity(), null)
        root.components[TransformComponent::class.java]?.apply {
            setPosition(offset)
            setQuaternion(rotation)
        }
        stageRoot = root
        TpLog.i("STAGE root offset=${TpLog.pos(offset.domain())}")

        val rig = PanelRig(root) { id, shown -> panelShown[id] = shown }
        this.rig = rig
        rig.world(PanelIds.CONSOLE, PanelSizes.CONSOLE_W, PanelSizes.CONSOLE_H, CONSOLE_SCALE)
        rig.world(PanelIds.SEAT, PanelSizes.SEAT_W, PanelSizes.SEAT_H, SEAT_SCALE)
        rig.world(PanelIds.STRIP, PanelSizes.STRIP_W, PanelSizes.STRIP_H, 1f)
        rig.world(PanelIds.READOUT, PanelSizes.READOUT_W, PanelSizes.READOUT_H, 1f)
        rig.world(PanelIds.SIZE_TAG, PanelSizes.SIZE_TAG_W, PanelSizes.SIZE_TAG_H, 1f)
        rig.world(PanelIds.MENU, PanelSizes.MENU_W, PanelSizes.MENU_H, CONSOLE_SCALE)
        rig.world(PanelIds.DIALOG, PanelSizes.DIALOG_W, PanelSizes.DIALOG_H, DIALOG_SCALE)
        rig.world(PanelIds.SNACK, PanelSizes.SNACK_W, PanelSizes.SNACK_H, SNACK_SCALE)
        rig.world(PanelIds.COACH, PanelSizes.COACH_W, PanelSizes.COACH_H, COACH_SCALE)
        for (i in 0 until PanelIds.TAG_SLOTS) rig.world(PanelIds.tag(i), PanelSizes.TAG_W, PanelSizes.TAG_H, 1f)
        for (i in 0 until PanelIds.ABS_SLOTS) rig.world(PanelIds.abs(i), PanelSizes.ABS_TAG_W, PanelSizes.ABS_TAG_H, 1f)
        head.start()
        stick.start()
        TpGraph.anchors.start()
        ActiveScene.scene = this
        attached = true
        bindPanels(attachments)
        startSensing()
        buildJob =
            scope?.launch {
                try {
                    build(root, t0)
                } catch (t: Throwable) {
                    if (t !is CancellationException) TpLog.e("SCENE build failed: $t", t)
                    throw t
                }
            }
        TpLog.i("SCENE attached")
    }

    /** The meshes, one per yielded frame (a mesh costs about a frame at idle fps), then the fixed views. */
    private suspend fun build(root: Entity, t0: Long) {
        delay(300)
        val shared = SharedResources()
        this.shared = shared
        val m = TpMaterials(shared)
        materials = m
        yield()
        val meshes = TpMeshes(shared)
        this.meshes = meshes
        yield()
        val colliders = RoomColliders(root)
        colliders.buildBackstop()
        this.colliders = colliders
        colliders.sync(planes.all)
        yield()
        screenView = ScreenView(root, meshes, m)
        projectorView = ProjectorView(root, meshes, m)
        seatMark = SeatMarkView(root, meshes, m)
        arrow = ArrowView(root, meshes, m)
        soundLine = Strip(root, meshes.band, m.lineGreen, "soundLine")
        gazeFrame = Frame(root, meshes.dashes, m.frameDim, "gazeFrame")
        snapFrame = Frame(root, meshes.dashes, m.snapDash, "snapFrame")
        yield()
        repeat(PanelIds.ABS_SLOTS) { absorberViews += AbsorberView(root, meshes, m, it) }
        screenView?.hide()
        projectorView?.hide()
        seatMark?.hide()
        arrow?.hide()
        absorberViews.forEach { it.hide() }
        built = true
        TpLog.i("SCENE built ms=${SystemClock.elapsedRealtime() - t0}")
        render(ui)
        sink(PlannerEvent.SceneReady)
    }

    private fun startSensing() {
        planes.start {
            colliders?.sync(planes.all)
            sink(PlannerEvent.PlanesChanged(RoomModel(planes.all.toList())))
        }
    }

    /** The Stage went to the background: stop every tracker (design `container.lifecycle`). */
    fun pause() {
        if (paused || !attached) return
        paused = true
        planes.stop()
        head.stop()
        stick.stop()
        endDrag(cancelled = true)
        turn = null
        TpLog.i("SCENE paused")
    }

    fun resume() {
        if (!paused || !attached) return
        paused = false
        head.start()
        stick.start()
        startSensing()
        TpLog.i("SCENE resumed")
    }

    fun bindPanels(attachments: SpatialViewAttachments) {
        val rig = rig ?: return
        if (!rig.bind(attachments, PanelIds.pool, SystemClock.elapsedRealtime())) {
            main.removeCallbacks(rebind)
            main.postDelayed(rebind, 250L)
        }
    }

    private val rebind = Runnable { if (stageRoot != null) bindTick.intValue++ }

    fun detach() {
        if (!attached && stageRoot == null) return
        attached = false
        if (ActiveScene.scene === this) ActiveScene.scene = null
        main.removeCallbacksAndMessages(null)
        buildJob?.cancel()
        scope?.cancel()
        scope = null
        planes.stop()
        head.stop()
        stick.stop()
        TpGraph.anchors.stop()
        panelShown.clear()
        readout.value = null
        speakerViews.values.forEach { it.destroy() }
        speakerViews.clear()
        bandViews.values.forEach { it.destroy() }
        bandViews.clear()
        zoneViews.values.forEach { it.destroy() }
        zoneViews.clear()
        standViews.values.forEach { it.destroy() }
        standViews.clear()
        absorberViews.clear()
        runCatching { colliders?.clear() }
        colliders = null
        runCatching { rig?.release() }
        runCatching { stageRoot?.destroy(true) }
        runCatching { contentProbe?.destroy(true) }
        shared?.close()
        shared = null
        materials = null
        meshes = null
        screenView = null
        projectorView = null
        seatMark = null
        grid = null
        arrow = null
        soundLine = null
        gazeFrame = null
        snapFrame = null
        stageRoot = null
        contentProbe = null
        rig = null
        content = null
        built = false
        hasHead = false
        drag = null
        turn = null
        live = null
        liveState = null
        awaitCommit = null
        consoleFollow.reset()
        seatFollow.reset()
        snackFollow.reset()
        TpLog.i("SCENE detached")
    }

    // =========================================================================================
    // state -> entities
    // =========================================================================================

    fun render(state: PlannerUiState) {
        val before = ui
        ui = state
        val root = stageRoot ?: return
        val rig = rig ?: return
        if (!built) {
            rig[PanelIds.CONSOLE]?.show()
            return
        }
        val m = materials ?: return
        val meshes = meshes ?: return
        val seat = state.seat
        val showLayout = seat != null && (state.phase == Phase.LAYOUT || state.phase == Phase.RESEAT)
        // speakers: create / destroy views for the current configuration
        val ids = if (showLayout) state.speakers.map { it.id }.toSet() else emptySet()
        for (id in speakerViews.keys.filter { it !in ids }) {
            speakerViews.remove(id)?.destroy()
            bandViews.remove(id)?.destroy()
            zoneViews.remove(id)?.destroy()
            standViews.remove(id)?.destroy()
        }
        if (showLayout) {
            for (sp in state.speakers) {
                if (sp.id !in speakerViews) speakerViews[sp.id] = SpeakerView(root, meshes, m, sp.id)
                if (sp.def.kind == SpeakerKind.LISTENER) {
                    if (sp.id !in bandViews) bandViews[sp.id] = BandView(root, meshes, m, sp.id)
                } else if (sp.id !in zoneViews) zoneViews[sp.id] = ZoneView(root, meshes, m, sp.id)
            }
        }
        if (before.dragging != null && state.dragging == null) clearLive()
        awaitCommit?.let { (id, _) ->
            if (state.positions[id] != before.positions[id]) {
                awaitCommit = null
                if (turn == null && drag == null) clearLive()
            }
        }
        drawStatic(state)
        panels(state)
    }

    /** Everything that only changes with the state (not per frame). */
    private fun drawStatic(state: PlannerUiState) {
        val seat = state.seat
        val screenView = screenView ?: return
        val projectorView = projectorView ?: return
        if (seat == null || !(state.phase == Phase.LAYOUT || state.phase == Phase.RESEAT)) {
            screenView.hide()
            projectorView.hide()
            seatMark?.hide()
            grid?.hide()
            absorberViews.forEach { it.hide() }
            bandViews.values.forEach { it.hide() }
            zoneViews.values.forEach { it.hide() }
            return
        }
        val screenPage = state.page == Page.SCREEN
        state.screen?.let { screenView.draw(it, state.mode, bright = screenPage) }
        val p = state.projector
        if (screenPage && state.mode == ScreenMode.PROJECTOR && p != null && state.screen != null) projectorView.draw(p, state.screen, state.room.ceilingY()) else projectorView.hide()
        // bands and zones (hidden on the screen page; faint while the absorbers show)
        for (sp in state.speakers) {
            if (sp.def.kind == SpeakerKind.LISTENER) {
                val view = bandViews[sp.id] ?: continue
                if (screenPage) {
                    view.hide()
                    continue
                }
                val pts = ArrayList<Vec3>(4)
                val nrm = ArrayList<Vec3>(4)
                val lo = sp.def.range.start
                val hi = sp.def.range.endInclusive
                for (i in 0..3) {
                    val az = lo + (hi - lo) * i / 3f
                    val hit = state.room.hitWall(seat.ear, seat.dir(az).at(0f).normalized()) ?: continue
                    pts += Vec3(hit.point.x, sp.position.y, hit.point.z) + hit.normal * 0.005f
                    nrm += hit.normal
                }
                val look = if (state.absorbersOn) 2 else if (sp.id == state.selected) 1 else 0
                view.draw(pts, nrm, look)
            } else {
                val view = zoneViews[sp.id] ?: continue
                if (screenPage) {
                    view.hide()
                    continue
                }
                val ceilY = state.room.ceilingY()
                val fa = (ceilY - seat.ear.y) / tan(Math.toRadians(sp.def.range.start.toDouble())).toFloat()
                val fb = (ceilY - seat.ear.y) / tan(Math.toRadians(sp.def.range.endInclusive.toDouble())).toFloat()
                val center = (P2.of(seat.ear) + seat.right * sp.latRec + seat.f * ((fa + fb) / 2f)).at(ceilY)
                view.draw(center, seat.right.at(0f), seat.f.at(0f), Theater.LAT_BAND_M, abs(fa - fb) / 2f, ceilY)
            }
        }
        // absorbers
        for (i in absorberViews.indices) {
            val a = state.absorbers.getOrNull(i)
            if (a != null) absorberViews[i].draw(a) else absorberViews[i].hide()
        }
        // assumed ceiling grid
        if (state.overheadAssumed) {
            val g = grid ?: GridView(stageRoot!!, meshes!!, materials!!).also { grid = it }
            g.draw(Vec3(seat.ear.x, state.room.ceilingY(), seat.ear.z), seat.right.at(0f), seat.f.at(0f))
        } else grid?.hide()
    }

    private fun panels(state: PlannerUiState) {
        val rig = rig ?: return
        rig[PanelIds.CONSOLE]?.show()
        val seatOn = state.phase == Phase.SEAT || state.phase == Phase.RESEAT
        if (!seatOn) {
            rig[PanelIds.SEAT]?.hide()
            seatFollow.reset()
        }
        val stripOn = state.selected != null && state.dragging == null && state.inLayout && !state.trackingLost && !state.blocked
        if (!stripOn) rig[PanelIds.STRIP]?.hide()
        if (state.dragging == null && turn == null) rig[PanelIds.READOUT]?.hide()
        if (state.menuOpen) rig[PanelIds.MENU]?.show() else rig[PanelIds.MENU]?.hide()
        val dialogOn = state.clearDialog || state.blocked
        val dialog = rig[PanelIds.DIALOG]
        if (dialogOn && dialog?.shown != true && hasHead) {
            dialog?.show(restAt(pose, 0f, gazePitchDeg(pose), DIALOG_M) + Vec3(0f, -0.05f, 0f))
        } else if (!dialogOn) dialog?.hide()
        if (state.snack != null) rig[PanelIds.SNACK]?.show() else {
            rig[PanelIds.SNACK]?.hide()
            snackFollow.reset()
        }
        val coachOn = (state.coach || state.rearCoach != null) && !state.menuOpen && !state.clearDialog && !state.trackingLost && !state.blocked && state.inLayout
        if (!coachOn) rig[PanelIds.COACH]?.hide()
        if (!(state.inLayout && state.page == Page.SCREEN)) rig[PanelIds.SIZE_TAG]?.hide()
        for (i in 0 until PanelIds.ABS_SLOTS) if (state.absorbers.getOrNull(i) == null || !state.inLayout) rig[PanelIds.abs(i)]?.hide()
    }

    // =========================================================================================
    // per frame
    // =========================================================================================

    fun onFrame(context: SceneUpdateContext) {
        val root = stageRoot ?: return
        if (root.scene == null || context.scene != root.scene) return
        val now = SystemClock.elapsedRealtime()
        frames++
        head.pose()?.let {
            pose = it
            hasHead = it.position.length > 1e-3f
        }
        val rig = rig ?: return
        rig.onFrame(now)
        rig.stowBehind(pose)
        if (!attached || paused) return
        pollTracking(now)
        if (built) {
            stepStick(now)
            drawLive(now)
        }
        if (hasHead) placePanels(now)
        rig.faceViewer(pose.position)
        lastFrameMs = now
        if (now - lastLogMs > LOG_MS) {
            lastLogMs = now
            TpLog.i("FRAME n=$frames head=${TpLog.pos(pose.position)} fwd=${TpLog.pos(pose.forward)} speakers=${ui.speakers.size} planes=${planes.all.size} stick=${stick.status()}")
        }
    }

    private fun pollTracking(now: Long) {
        val lost = head.lost(now)
        if (lost != lastTrackingLost) {
            lastTrackingLost = lost
            sink(PlannerEvent.TrackingChanged(lost))
        }
    }

    /** The live position of a speaker: the dragged / turned one, else the state's. */
    private fun positionOf(sp: SpeakerState): Vec3 = live?.takeIf { it.first == sp.id }?.second ?: sp.position

    private fun stateOf(sp: SpeakerState): SpeakerState = liveState?.takeIf { it.id == sp.id } ?: sp

    /** Speakers, sound line, seat mark, arrow, gaze preview: the things that follow the head or a drag every frame. */
    private fun drawLive(now: Long) {
        val state = ui
        val seat = state.seat
        val stale = state.trackingLost
        val screenPage = state.page == Page.SCREEN
        if (seat != null && (state.phase == Phase.LAYOUT || state.phase == Phase.RESEAT)) {
            val ear = seat.ear
            for (sp in state.speakers) {
                val v = speakerViews[sp.id] ?: continue
                val s = stateOf(sp)
                val pos = positionOf(sp)
                if (sp.def.kind == SpeakerKind.LISTENER) {
                    v.placeListener(pos, (ear - pos).rejectFrom(Vec3.UP), s.status, dim = screenPage, stale = stale)
                    // a floating speaker stands on a dashed support (design S25)
                    if (s.noWall) {
                        val stand = standViews.getOrPut(sp.id) { Strip(stageRoot!!, meshes!!.dashes, materials!!.stand, "stand:${sp.id}") }
                        stand.draw(Vec3(pos.x, seat.floorY + 0.005f, pos.z), pos - Vec3(0f, SpeakerDims.H / 2f, 0f), 0.008f, (pose.position - pos).rejectFrom(Vec3.UP))
                    } else standViews[sp.id]?.hide()
                } else {
                    v.placeOverhead(pos, s.status, dim = screenPage, stale = stale)
                }
            }
            // seat mark: pole and ball only when the head is away from the seat
            val away = (P2.of(pose.position) - P2.of(ear)).length > 0.5f
            seatMark?.draw(ear, seat.floorY, showPole = away, headPos = pose.position)
            // the sound line, arrow and snap target of the selected speaker
            val selId = state.dragging ?: state.selected
            val sel = selId?.let { id -> state.speakers.firstOrNull { it.id == id } }
            if (sel != null && !screenPage) {
                val s = stateOf(sel)
                val pos = positionOf(sel)
                val from = ear + (pos - ear).normalized() * 0.6f
                soundLine?.material(materials!!.line(s.status))
                soundLine?.draw(from, pos, 0.006f, (pose.position - pos))
                val hint = Theater.moveHint(s, seat, state.room)
                if (hint != null) arrow?.draw(pos, hint.target, s.faceNormal) else arrow?.hide()
                drawSnapTarget(s, pos)
            } else {
                soundLine?.hide()
                arrow?.hide()
                snapFrame?.hide()
            }
            gazeFrame?.hide()
        } else {
            soundLine?.hide()
            arrow?.hide()
            snapFrame?.hide()
            seatMark?.hide()
            standViews.values.forEach { it.hide() }
            drawGazePreview(state)
        }
    }

    /** The dashed target frame at the recommended spot of the selected speaker (taste profile D: snap target). */
    private fun drawSnapTarget(s: SpeakerState, pos: Vec3) {
        val f = snapFrame ?: return
        if (s.atRecommended || pos.distanceTo(s.recommended) < 0.02f) {
            f.hide()
            return
        }
        val rec = s.recommended
        if (s.def.kind == SpeakerKind.LISTENER) {
            val n = s.faceNormal
            val landing = ui.seat?.let { Theater.land(ui.room, it, s.def.azimuth) }
            val nn = landing?.wallNormal?.at(0f) ?: n
            val right = Vec3.UP.cross(nn).normalized()
            f.draw(rec - nn * (SpeakerDims.D / 2f - 0.01f), right, Vec3.UP, SpeakerDims.W / 2f + 0.02f, SpeakerDims.H / 2f + 0.02f, 0.008f, nn)
        } else {
            val seat = ui.seat ?: return
            f.draw(rec - Vec3(0f, 0.004f, 0f), seat.right.at(0f), seat.f.at(0f), SpeakerDims.PUCK / 2f + 0.03f, SpeakerDims.PUCK / 2f + 0.03f, 0.008f, Vec3(0f, -1f, 0f))
        }
    }

    /** S03: a dashed 16:9 frame where the level gaze meets a wall (design `rules.seatPanel`). */
    private fun drawGazePreview(state: PlannerUiState) {
        val f = gazeFrame ?: return
        if (!(state.phase == Phase.SEAT || state.phase == Phase.RESEAT) || !hasHead) {
            f.hide()
            return
        }
        val g = pose.level()
        val hit = state.room.hitWall(pose.position, g.at(0f).normalized())
        if (hit == null) {
            f.hide()
            return
        }
        val d = hit.distance
        val w = ScreenRules.diagonalFor(ScreenRules.VIEW_BEST_DEG, d) * ScreenRules.INCH_W
        val h = w * 9f / 16f
        val n = hit.normal
        val right = Vec3.UP.cross(n).normalized()
        f.draw(Vec3(hit.point.x, pose.position.y + 0.05f, hit.point.z) + n * 0.01f, right, Vec3.UP, w / 2f, h / 2f, 0.01f, n)
    }

    // =========================================================================================
    // thumbstick: turn the selected speaker about the seat's vertical axis, snap at the recommended spot
    // =========================================================================================

    private fun turnTarget(): String? {
        val s = ui
        if (!s.dragEnabled || s.menuOpen || s.page == Page.SCREEN) return null
        return s.dragging ?: s.selected
    }

    private fun stepStick(now: Long) {
        val dt = if (lastFrameMs < 0) 0f else ((now - lastFrameMs) / 1000f).coerceIn(0f, 0.1f)
        val rate = Theater.stickRateDeg(stick.x(now))
        val target = turnTarget()
        turn?.let { t -> if (t.id != target) finishTurn("target") }
        awaitCommit?.let { (_, at) -> if (now - at > COMMIT_WAIT_MS) { awaitCommit = null; if (turn == null && drag == null) clearLive() } }
        val seat = ui.seat ?: return
        if (rate != 0f && target != null) {
            val sp = ui.speakers.firstOrNull { it.id == target } ?: return
            val t = turn ?: Turn(target).also {
                turn = it
                TpLog.i(String.format(Locale.US, "STICK turn start id=%s x=%.2f", target, stick.x(now)))
            }
            t.neutralSinceMs = -1L
            t.frames++
            val current = positionOf(sp)
            val turned = Theater.turnAboutSeat(seat, P2.of(current), rate * dt)
            if (sp.def.kind == SpeakerKind.LISTENER) setLiveListener(sp, seat.azimuthOf(turned), now)
            else setLiveOverhead(sp, ui.room.clampToCeiling(turned.at(current.y), Theater.CEILING_INSET_M), now)
        } else {
            val t = turn ?: return
            if (t.neutralSinceMs < 0) t.neutralSinceMs = now else if (now - t.neutralSinceMs >= STICK_RELEASE_MS) finishTurn("release")
        }
    }

    private fun finishTurn(why: String) {
        val t = turn ?: return
        turn = null
        val l = live
        TpLog.i("STICK turn end id=${t.id} frames=${t.frames} why=$why at=${TpLog.pos(l?.second)}")
        if (drag?.id == t.id) return // still dragged: the drag writes it on release
        if (l == null || l.first != t.id) {
            clearLive()
            return
        }
        awaitCommit = t.id to SystemClock.elapsedRealtime()
        sink(PlannerEvent.SpeakerTurned(t.id, l.second, liveNoWall))
    }

    /** Debug `stick`: the stick reads [x] for [ms]. */
    fun debugStick(x: Float, ms: Long): String {
        stick.debugHold(x, ms)
        return String.format(Locale.US, "stick x=%.2f ms=%d target=%s", x, ms, turnTarget() ?: "-")
    }

    // =========================================================================================
    // live placement (shared by drag and stick)
    // =========================================================================================

    /** A listener-level speaker at azimuth [az]: lands on the wall; snaps to the recommended azimuth within 3 degrees. */
    private fun setLiveListener(sp: SpeakerState, azIn: Float, now: Long) {
        val seat = ui.seat ?: return
        var az = azIn
        val snap = abs(wrapDeg(az - sp.def.azimuth)) <= Theater.SNAP_DEG
        if (snap) az = sp.def.azimuth
        val landing = Theater.land(ui.room, seat, az)
        val pos = landing.point.at(sp.position.y)
        setLive(sp, pos, landing.noWall, landing.plane, landing.wallNormal, snap, now)
    }

    /** An overhead speaker at a ceiling point: snaps to the recommended point within 15 cm. */
    private fun setLiveOverhead(sp: SpeakerState, pointIn: Vec3, now: Long) {
        var point = pointIn
        val snap = point.distanceTo(sp.recommended) <= Theater.SNAP_M
        if (snap) point = sp.recommended
        setLive(sp, point, false, ui.room.ceiling, null, snap, now)
    }

    private fun setLive(sp: SpeakerState, pos: Vec3, noWall: Boolean, plane: io.embodit.theaterplanner.domain.PlaneSurface?, wallNormal: P2?, snap: Boolean, now: Long) {
        val seat = ui.seat ?: return
        if (snap && !snapped) TpLog.i("SNAP id=${sp.id} at=${TpLog.pos(pos)}")
        snapped = snap
        live = sp.id to pos
        liveNoWall = noWall
        val screenBottom = ui.screen?.bottom ?: (seat.ear.y - 0.3f)
        val s = Theater.evaluate(sp.def, pos, seat, ui.room, ui.overheadLat, screenBottom, noWall, plane, wallNormal)
        liveState = s
        if (now - readoutWriteMs >= READOUT_MS) {
            readoutWriteMs = now
            val hint = Theater.moveHint(s, seat, ui.room)
            readout.value = ReadoutUi(s.value, s.status, hint)
        }
    }

    private fun clearLive() {
        live = null
        liveState = null
        snapped = false
        readout.value = null
    }

    // =========================================================================================
    // panels
    // =========================================================================================

    /** The console's rest pose (design `panels.console.placement`): 30 degrees right, 22 down, 0.55 m, from the level heading. */
    private fun restAt(h: HeadPose, leftDeg: Float, upDeg: Float, dist: Float): Vec3 {
        val f = h.level()
        val l = Math.toRadians(leftDeg.toDouble()).toFloat()
        val u = Math.toRadians(upDeg.coerceIn(-80f, 80f).toDouble()).toFloat()
        val left = P2(f.z, -f.x)
        val d = (f * cos(l) + left * sin(l)).normalized()
        return h.position + d.at(0f) * (dist * cos(u)) + Vec3(0f, dist * sin(u), 0f)
    }

    private fun gazePitchDeg(h: HeadPose): Float = Math.toDegrees(asin(h.forward.y.coerceIn(-1f, 1f).toDouble())).toFloat().coerceIn(-GAZE_PITCH_MAX_DEG, GAZE_PITCH_MAX_DEG)

    private fun placePanels(now: Long) {
        val rig = rig ?: return
        val state = ui
        val console = rig[PanelIds.CONSOLE] ?: return
        val hold = state.menuOpen || state.clearDialog || state.blocked || now - lastConsolePressMs < KEY_HOLD_MS
        consoleFollow.step(pose, now, hold)?.let { console.moveTo(it) }
        val facing = FacePanel.facing(console.position, pose.position, null)
        val cw = PanelSizes.CONSOLE_W * DP_M * CONSOLE_SCALE
        val ch = PanelSizes.CONSOLE_H * DP_M * CONSOLE_SCALE
        if (state.menuOpen) {
            val mw = PanelSizes.MENU_W * DP_M * CONSOLE_SCALE
            val mh = PanelSizes.MENU_H * DP_M * CONSOLE_SCALE
            rig[PanelIds.MENU]?.moveTo(console.position + facing.up * (ch / 2f + mh / 2f + 0.008f) + facing.right * (cw / 2f - mw / 2f) + facing.normal * 0.01f)
        }
        if (state.phase == Phase.SEAT || state.phase == Phase.RESEAT) seatFollow.step(pose, now, hold = false)?.let { rig[PanelIds.SEAT]?.show(it) }
        if (state.snack != null) snackFollow.step(pose, now, hold = false)?.let { rig[PanelIds.SNACK]?.show(it) }
        placeSpeakerPanels(state, facing)
    }

    private fun placeSpeakerPanels(state: PlannerUiState, consoleFacing: PanelFacing) {
        val rig = rig ?: return
        val seat = state.seat
        if (seat == null || !state.inLayout) {
            rig[PanelIds.STRIP]?.hide()
            rig[PanelIds.READOUT]?.hide()
            rig[PanelIds.tag(0)]?.hide()
            rig[PanelIds.tag(1)]?.hide()
            rig[PanelIds.COACH]?.hide()
            return
        }
        val eye = pose.position
        fun k(at: Vec3) = (STRIP_K * at.distanceTo(eye)).coerceIn(0.6f, 2.4f)
        // strip above the selected speaker (below it for a ceiling speaker), readout beside a dragged one
        val sel = (state.dragging ?: state.selected)?.let { id -> state.speakers.firstOrNull { it.id == id } }
        if (sel != null) {
            val pos = positionOf(sel)
            val toward = (eye - pos).rejectFrom(Vec3.UP).normalized()
            val overhead = sel.def.kind == SpeakerKind.OVERHEAD
            val kk = k(pos)
            val stripH = PanelSizes.STRIP_H * DP_M * kk
            val stripAt = if (overhead) pos + toward * 0.18f - Vec3(0f, 0.18f + stripH / 2f, 0f) else pos + Vec3(0f, SpeakerDims.H / 2f + 0.18f + stripH / 2f, 0f)
            val stripOn = state.dragging == null && turn == null && !state.trackingLost && !state.blocked
            if (stripOn) rig[PanelIds.STRIP]?.show(stripAt, kk, kk) else rig[PanelIds.STRIP]?.hide()
            if (state.dragging != null || turn != null) rig[PanelIds.READOUT]?.show(pos + toward * 0.10f + Vec3(0f, if (overhead) -0.12f else 0.05f, 0f), kk, kk) else rig[PanelIds.READOUT]?.hide()
            rig[PanelIds.tag(0)]?.show(if (overhead) pos + toward * 0.05f - Vec3(0f, 0.08f, 0f) else pos + Vec3(0f, SpeakerDims.H / 2f + 0.06f, 0f), kk, kk)
        } else {
            rig[PanelIds.STRIP]?.hide()
            rig[PanelIds.READOUT]?.hide()
            rig[PanelIds.tag(0)]?.hide()
        }
        // the plate of the speaker the gaze rests on (within 8 degrees), not the selected one
        val gazed = gazedSpeaker(state, sel?.id)
        if (gazed != null) {
            val pos = positionOf(gazed)
            val toward = (eye - pos).rejectFrom(Vec3.UP).normalized()
            val kk = k(pos)
            rig[PanelIds.tag(1)]?.show(if (gazed.def.kind == SpeakerKind.OVERHEAD) pos + toward * 0.05f - Vec3(0f, 0.08f, 0f) else pos + Vec3(0f, SpeakerDims.H / 2f + 0.06f, 0f), kk, kk)
        } else rig[PanelIds.tag(1)]?.hide()
        // size plate at the screen's lower left corner (screen page)
        val screen = state.screen
        if (state.page == Page.SCREEN && screen != null) {
            val right = screen.right.at(0f)
            val n = screen.normal.at(0f)
            rig[PanelIds.SIZE_TAG]?.show(screen.center - right * (screen.width / 2f - 0.30f) - Vec3(0f, screen.height / 2f - 0.12f, 0f) + n * 0.02f, 1f, 1f)
        } else rig[PanelIds.SIZE_TAG]?.hide()
        // absorber plates at the lower edge of each frame
        for (i in 0 until PanelIds.ABS_SLOTS) {
            val a = state.absorbers.getOrNull(i)
            if (a == null) {
                rig[PanelIds.abs(i)]?.hide()
                continue
            }
            val at = if (a.onCeiling) a.point + (P2.of(seat.ear) - P2.of(a.point)).normalized().at(0f) * 0.36f - Vec3(0f, 0.02f, 0f) else a.point - Vec3(0f, 0.36f, 0f) + a.normal * 0.02f
            rig[PanelIds.abs(i)]?.show(at, k(at) * 0.9f, k(at) * 0.9f)
        }
        // coachmarks: S21 beside L (or the nearest front speaker), S24 beside the rear speaker
        val coachOn = (state.coach || state.rearCoach != null) && !state.menuOpen && !state.clearDialog && !state.trackingLost && !state.blocked
        if (coachOn) {
            val anchorSp = state.rearCoach?.let { id -> state.speakers.firstOrNull { it.id == id } } ?: state.speakers.firstOrNull { it.id == "L" } ?: state.speakers.firstOrNull()
            if (anchorSp != null) {
                val pos = positionOf(anchorSp)
                val toward = (eye - pos).rejectFrom(Vec3.UP).normalized()
                val side = Vec3.UP.cross(toward).normalized()
                val kc = k(pos) * COACH_SCALE
                val cw = PanelSizes.COACH_W * DP_M * kc
                val at = if (anchorSp.def.kind == SpeakerKind.OVERHEAD) pos + toward * 0.25f - Vec3(0f, 0.3f, 0f) else pos - side * (SpeakerDims.W / 2f + cw / 2f + 0.06f) + Vec3(0f, 0.10f, 0f) + toward * 0.05f
                rig[PanelIds.COACH]?.show(at, kc, kc)
            }
        } else rig[PanelIds.COACH]?.hide()
    }

    /** The speaker the view direction points at within 8 degrees (design `densityCap`), other than [exclude]. */
    private fun gazedSpeaker(state: PlannerUiState, exclude: String?): SpeakerState? {
        var best: SpeakerState? = null
        var bestDeg = 8f
        val f = pose.forward
        for (sp in state.speakers) {
            if (sp.id == exclude) continue
            val to = positionOf(sp) - pose.position
            val len = to.length
            if (len < 0.2f) continue
            val deg = Math.toDegrees(acos((to / len).dot(f).coerceIn(-1f, 1f).toDouble())).toFloat()
            if (deg < bestDeg) {
                bestDeg = deg
                best = sp
            }
        }
        return best
    }

    /** The plate of tag slot [i] for the stage: 0 = the selected / dragged speaker, 1 = the gazed-at one. */
    fun tagSpeaker(i: Int, state: PlannerUiState): SpeakerState? {
        if (!state.inLayout) return null
        val sel = (state.dragging ?: state.selected)?.let { id -> state.speakers.firstOrNull { it.id == id } }
        return if (i == 0) sel?.let { stateOf(it) } else gazedSpeaker(state, sel?.id)?.let { stateOf(it) }
    }

    /** A key of the console was pressed: it holds still for a moment (lazy follow). */
    fun onConsolePointer() {
        lastConsolePressMs = SystemClock.elapsedRealtime()
    }

    /** "就坐这儿": sit at the head pose of this moment (the button catches its own pinch). */
    fun seatHere() {
        if (!hasHead) return
        sink(PlannerEvent.SeatHere(pose))
    }

    fun headPose(): HeadPose? = if (hasHead) pose else null

    // =========================================================================================
    // input: system taps and drags
    // =========================================================================================

    private fun speakerOf(entity: Entity?): String? {
        if (entity == null) return null
        for ((id, v) in speakerViews) if (v.owns(entity)) return id
        return null
    }

    fun onSpatialTap(value: SpatialTapValue) {
        val now = SystemClock.elapsedRealtime()
        val target = value.targetEntity
        val name = runCatching { target?.getName() }.getOrNull() ?: "none"
        val stage = toStage(value.position.x, value.position.y, value.position.z)
        if (lastDragEndMs > 0 && now - lastDragEndMs < TAP_AFTER_DRAG_MS) {
            TpLog.i("TAP ignored reason=after-drag target=$name")
            return
        }
        val sitting = ui.phase == Phase.SEAT || ui.phase == Phase.RESEAT
        val id = speakerOf(target)
        when {
            sitting && hasHead -> {
                TpLog.i("TAP sit target=$name stage=${TpLog.pos(stage)} head=${TpLog.pos(pose.position)}")
                sink(PlannerEvent.Pinched(pose))
            }
            id != null -> {
                TpLog.i("TAP speaker id=$id stage=${TpLog.pos(stage)}")
                sink(PlannerEvent.SpeakerTapped(id))
            }
            target != null && colliders?.isRoom(target) == true -> {
                TpLog.i("TAP room target=$name stage=${TpLog.pos(stage)}")
                sink(PlannerEvent.EmptyTapped)
            }
            else -> TpLog.i("TAP other target=$name stage=${TpLog.pos(stage)}")
        }
    }

    fun onSpatialDrag(phase: String, start: Offset3D?, value: SpatialDragValue) {
        val now = SystemClock.elapsedRealtime()
        when (phase) {
            "start" -> {
                drag = null
                val target = value.targetEntity
                val name = runCatching { target?.getName() }.getOrNull() ?: "none"
                val press = if (start != null) toStage(start.x, start.y, start.z) else null
                val id = speakerOf(target)
                TpLog.i("DRAG start target=$name speaker=${id ?: "-"} stage=${TpLog.pos(press)}")
                if (id == null || press == null) return
                if (!ui.dragEnabled || ui.menuOpen || ui.page == Page.SCREEN) {
                    TpLog.i("DRAG refused reason=${if (ui.menuOpen) "menu" else if (ui.page == Page.SCREEN) "screen-page" else "disabled"} state=${ui.designState}")
                    return
                }
                drag = Drag(id, press)
            }
            "move" -> {
                val d = drag ?: return
                val a = value.dragAmount
                d.moves++
                d.totalPx = Vec3(d.totalPx.x + a.x, d.totalPx.y + a.y, d.totalPx.z + a.z)
                d.hand = handDelta(d.totalPx) ?: d.hand
                if (!d.begun && d.hand.length >= DRAG_START_M) beginDrag(d)
                if (d.begun) {
                    val dir = d.dir ?: return
                    val turned = DragLine.turn(dir, d.hand - d.prevHand, d.equivalent, pose.right, pose.up, pose.forward)
                    d.prevHand = d.hand
                    d.dir = turned
                    val sp = ui.speakers.firstOrNull { it.id == d.id } ?: return
                    val seat = ui.seat ?: return
                    if (sp.def.kind == SpeakerKind.LISTENER) {
                        val current = positionOf(sp)
                        val az = Theater.dragAzimuth(seat, ui.room, pose.position, turned, (P2.of(current) - P2.of(seat.ear)).length) ?: return
                        setLiveListener(sp, az, now)
                    } else {
                        val p = Theater.dragCeiling(ui.room, pose.position, turned) ?: return
                        setLiveOverhead(sp, p, now)
                    }
                    if (d.moves <= 3 || now - d.lastLogMs > 500) {
                        d.lastLogMs = now
                        TpLog.i("DRAG move id=${d.id} live=${TpLog.pos(live?.second)} value=${liveState?.value?.let { String.format(Locale.US, "%.1f", it) }} n=${d.moves}")
                    }
                }
            }
            "end", "cancel" -> {
                val d = drag ?: return
                drag = null
                if (!d.begun) {
                    TpLog.i("DRAG $phase not-begun n=${d.moves}")
                    return
                }
                lastDragEndMs = now
                TpLog.i("DRAG $phase id=${d.id} n=${d.moves}")
                endDrag(cancelled = phase == "cancel", id = d.id)
            }
        }
    }

    private fun beginDrag(d: Drag) {
        d.begun = true
        val to = d.press - pose.position
        if (to.length < 1e-3f) return
        d.dir = to.normalized()
        val pitch = Math.toDegrees(asin(pose.forward.y.coerceIn(-1f, 1f).toDouble())).toFloat()
        d.equivalent = DragLine.equivalentDepth(to.length, pitch)
        d.prevHand = d.hand
        sink(PlannerEvent.DragStarted(d.id))
        TpLog.i("DRAG begin id=${d.id} depth=${String.format(Locale.US, "%.2f", d.equivalent)}")
    }

    private fun endDrag(cancelled: Boolean, id: String? = null) {
        val dragged = id ?: drag?.id ?: ui.dragging ?: return
        drag = null
        val l = live?.takeIf { it.first == dragged }
        sink(PlannerEvent.DragEnded(dragged, if (cancelled) null else l?.second, liveNoWall))
    }

    /** Debug: a drag-like move of [id] to azimuth [az] (listener) or ceiling point [x], [z] (overhead), written at once. */
    fun debugMove(id: String, az: Float?, x: Float?, z: Float?): String {
        val sp = ui.speakers.firstOrNull { it.id == id } ?: return "no speaker $id"
        val seat = ui.seat ?: return "no seat"
        val now = SystemClock.elapsedRealtime()
        if (sp.def.kind == SpeakerKind.LISTENER) setLiveListener(sp, az ?: sp.value, now)
        else setLiveOverhead(sp, ui.room.clampToCeiling(Vec3(x ?: sp.position.x, sp.position.y, z ?: sp.position.z), Theater.CEILING_INSET_M), now)
        val l = live ?: return "no live"
        awaitCommit = id to now
        sink(PlannerEvent.SpeakerTurned(id, l.second, liveNoWall))
        return "moved $id to ${TpLog.pos(l.second)} value=${liveState?.value}"
    }

    fun debugDragCancel() {
        if (drag != null || live != null) endDrag(cancelled = true)
        turn = null
        clearLive()
    }

    private fun handDelta(px: Vec3): Vec3? {
        val moved = toStage(px.x, px.y, px.z) ?: return null
        val origin = toStage(0f, 0f, 0f) ?: return null
        return moved - origin
    }

    /** View-local pixels of a spatial gesture -> Stage metres (Spatial Ruler). */
    private fun toStage(x: Float, y: Float, z: Float): Vec3? {
        val c = content ?: return null
        val probe = contentProbe ?: return null
        return runCatching {
                val local = c.convertPosition(Vector3(x, y, z), ViewCoordinateSpace.Local, c.localSpatialCoordinateSpace)
                probe.convertPositionTo(local, null).domain()
            }
            .getOrNull()
    }

    // =========================================================================================
    // debug seams
    // =========================================================================================

    /** Debug `hidemode`: how hidden pooled panels are taken out of sight (auto = holder off, park = tiny behind the viewer). */
    fun debugHideMode(mode: String): String {
        val rig = rig ?: return "no rig"
        rig.hideMode = if (mode == "park") PanelRig.HideMode.PARK else PanelRig.HideMode.AUTO
        return "hidemode=${rig.hideMode}"
    }

    fun poseLine(): String = "POSE hmd pos=${TpLog.pos(pose.position)} ${TpLog.quat(pose.rotation)} fwd=${TpLog.pos(pose.forward)} valid=$hasHead"

    fun panelLines(): List<String> = rig?.dump() ?: emptyList()

    fun planeLines(): List<String> = planes.all.map { "PLANE ${it.label} center=${TpLog.pos(it.centroid)} normal=${TpLog.pos(it.normal)} area=${String.format(Locale.US, "%.2f", it.area)} verts=${it.vertices.size}" }

    companion object {
        const val CONSOLE_SCALE = 0.75f
        const val SEAT_SCALE = 0.9f
        const val DIALOG_SCALE = 1.0f
        const val SNACK_SCALE = 0.9f
        const val COACH_SCALE = 0.8f
        const val CONSOLE_RIGHT_DEG = 30f
        const val CONSOLE_DOWN_DEG = 22f
        const val CONSOLE_M = 0.55f
        const val CONSOLE_WINDOW_DEG = 55f
        const val SEAT_DOWN_DEG = 18f
        const val SEAT_M = 0.8f
        const val SNACK_UP_DEG = 18f
        const val SNACK_M = 0.9f
        const val DIALOG_M = 0.9f
        const val GAZE_PITCH_MAX_DEG = 60f
        const val STRIP_K = 0.5f
        const val KEY_HOLD_MS = 600L
        const val DRAG_START_M = 0.01f
        const val TAP_AFTER_DRAG_MS = 400L
        const val STICK_RELEASE_MS = 150L
        const val COMMIT_WAIT_MS = 1500L
        const val READOUT_MS = 100L
        const val LOG_MS = 5000L
    }
}

/**
 * Lazy follow by heading only (design `panels.console.placement`): the panel rests where it is; once
 * the head's level heading has turned more than [windowDeg] away from the panel for 0.5 s, it slides
 * (0.35 s, ease-in-out cubic) to the rest pose of that moment. Looking up or down never moves it.
 */
internal class HorizontalFollow(private val name: String, private val rest: (HeadPose) -> Vec3, private val windowDeg: Float) {
    private var at: Vec3? = null
    private var from = Vec3.ZERO
    private var to = Vec3.ZERO
    private var slideStartMs = NEVER
    private var outSinceMs = NEVER
    private var lastWritten: Vec3? = null

    fun reset() {
        at = null
        slideStartMs = NEVER
        outSinceMs = NEVER
        lastWritten = null
    }

    val position: Vec3?
        get() = if (slideStartMs != NEVER) to else at

    fun step(head: HeadPose, nowMs: Long, hold: Boolean): Vec3? {
        val current = at
        if (current == null) {
            val p = rest(head)
            at = p
            TpLog.i("FOLLOW $name park pos=${TpLog.pos(p)}")
            return write(p)
        }
        if (slideStartMs != NEVER) {
            val q = ((nowMs - slideStartMs) / SLIDE_MS).coerceIn(0f, 1f)
            val e = if (q < 0.5f) 4f * q * q * q else 1f - (-2f * q + 2f).let { it * it * it } / 2f
            val p = from.lerp(to, e)
            if (q >= 1f) {
                slideStartMs = NEVER
                at = to
            }
            return write(p)
        }
        val toPanel = P2.of(current) - P2.of(head.position)
        val dist = toPanel.length
        val heading = head.level()
        val away = if (dist < 1e-3f) 0f else Math.toDegrees(acos((toPanel.normalized().dot(heading)).coerceIn(-1f, 1f).toDouble())).toFloat()
        val out = away > windowDeg || dist < 0.25f || dist > 1.5f
        if (!out || hold) {
            outSinceMs = NEVER
        } else if (outSinceMs == NEVER) {
            outSinceMs = nowMs
        } else if (nowMs - outSinceMs >= OUT_MS) {
            from = current
            to = rest(head)
            slideStartMs = nowMs
            outSinceMs = NEVER
            TpLog.i(String.format(Locale.US, "FOLLOW %s slide angle=%.1f dist=%.2f to=%s", name, away, dist, TpLog.pos(to)))
        }
        return write(current)
    }

    private fun write(p: Vec3): Vec3? {
        if (p == lastWritten) return null
        lastWritten = p
        return p
    }

    companion object {
        private const val NEVER = -1L
        private const val OUT_MS = 500L
        private const val SLIDE_MS = 350f
    }
}
