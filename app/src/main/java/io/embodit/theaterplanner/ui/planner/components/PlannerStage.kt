package io.embodit.theaterplanner.ui.planner.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import com.pico.spatial.ui.foundation.content.SpatialView
import com.pico.spatial.ui.foundation.gesture.TargetEntity
import com.pico.spatial.ui.foundation.gesture.detectSpatialDragGesture
import com.pico.spatial.ui.foundation.gesture.detectSpatialTapGesture
import io.embodit.theaterplanner.platform.TpLog
import io.embodit.theaterplanner.ui.planner.Phase
import io.embodit.theaterplanner.ui.planner.PlannerEvent
import io.embodit.theaterplanner.ui.planner.PlannerUiState
import io.embodit.theaterplanner.ui.planner.debug.LocalProbePanel
import io.embodit.theaterplanner.ui.planner.debug.Probe
import io.embodit.theaterplanner.ui.planner.scene.PanelIds
import io.embodit.theaterplanner.ui.planner.scene.PlannerScene

/**
 * The Stage content: one full-size SpatialView with the speakers, the room's tap targets and the panel
 * pool. Every attachment panel is declared once with a fixed size, shown or not ([PlannerScene] parents
 * and places them). Tap and drag recognizers live in separate pointerInput blocks (Spatial Ruler).
 *
 * Window model: single panel with popups. The console is the one resident panel; the speaker strip,
 * readout, tags and coachmark ride on 3D things; seat prompt, menu, dialog and snackbar float.
 */
@Composable
internal fun PlannerStage(state: PlannerUiState, scene: PlannerScene, onEvent: (PlannerEvent) -> Unit) {
    val context = LocalContext.current
    val bindTick = scene.bindTick.intValue
    SpatialView(
        modifier =
            Modifier
                .fillMaxSize()
                .pointerInput(Unit) {
                    detectSpatialTapGesture(context = context, targetedToEntity = TargetEntity.any()) { value -> scene.onSpatialTap(value) }
                }
                .pointerInput(Unit) {
                    detectSpatialDragGesture(
                        context = context,
                        targetedToEntity = TargetEntity.any(),
                        onDragStart = { start, value -> scene.onSpatialDrag("start", start, value) },
                        onDragEnd = { value -> scene.onSpatialDrag("end", null, value) },
                        onDragCancel = { value -> scene.onSpatialDrag("cancel", null, value) },
                    ) { value -> scene.onSpatialDrag("move", null, value) }
                },
        attachments = {
            AttachmentPanel(PanelIds.CONSOLE) {
                PoolPanel(PanelIds.CONSOLE, scene, pressed = { scene.onConsolePointer() }) { inView -> ConsolePanel(state = if (inView) state else null, onEvent = onEvent) }
            }
            AttachmentPanel(PanelIds.SEAT) {
                PoolPanel(PanelIds.SEAT, scene) { inView ->
                    SeatPanel(
                        state = if (inView && (state.phase == Phase.SEAT || state.phase == Phase.RESEAT)) state else null,
                        onSeatHere = { scene.seatHere() },
                        onEvent = onEvent,
                    )
                }
            }
            AttachmentPanel(PanelIds.STRIP) {
                PoolPanel(PanelIds.STRIP, scene) { inView -> SpeakerStrip(state = if (inView) state else null, onEvent = onEvent) }
            }
            AttachmentPanel(PanelIds.READOUT) {
                PoolPanel(PanelIds.READOUT, scene) { inView -> ReadoutPanel(readout = if (inView) scene.readout.value else null, unit = state.unit) }
            }
            for (i in 0 until PanelIds.TAG_SLOTS) {
                AttachmentPanel(PanelIds.tag(i)) {
                    PoolPanel(PanelIds.tag(i), scene) { inView -> SpeakerTag(sp = if (inView) scene.tagSpeaker(i, state) else null) }
                }
            }
            AttachmentPanel(PanelIds.SIZE_TAG) {
                PoolPanel(PanelIds.SIZE_TAG, scene) { inView -> SizeTag(inches = if (inView) state.screen?.inches else null) }
            }
            for (i in 0 until PanelIds.ABS_SLOTS) {
                AttachmentPanel(PanelIds.abs(i)) {
                    PoolPanel(PanelIds.abs(i), scene) { inView -> AbsorberTag(source = if (inView) state.absorbers.getOrNull(i)?.sourceId else null) }
                }
            }
            AttachmentPanel(PanelIds.MENU) {
                PoolPanel(PanelIds.MENU, scene, pressed = { scene.onConsolePointer() }) { inView -> MoreMenu(state = if (inView) state else null, head = { scene.headPose() }, onEvent = onEvent) }
            }
            AttachmentPanel(PanelIds.DIALOG) {
                PoolPanel(PanelIds.DIALOG, scene) { inView -> DialogPanel(clear = inView && state.clearDialog, blocked = inView && state.blocked, onEvent = onEvent) }
            }
            AttachmentPanel(PanelIds.SNACK) {
                PoolPanel(PanelIds.SNACK, scene) { inView -> SnackPanel(snack = if (inView) state.snack else null, onEvent = onEvent) }
            }
            AttachmentPanel(PanelIds.COACH) {
                PoolPanel(PanelIds.COACH, scene) { inView -> CoachPanel(visible = inView && (state.coach || state.rearCoach != null), rear = state.rearCoach != null, onEvent = onEvent) }
            }
        },
        update = { _, attachments ->
            // reading the tick makes this block run again while panel entities are still missing
            @Suppress("UNUSED_EXPRESSION") bindTick
            scene.bindPanels(attachments)
        },
        initial = { content, attachments -> scene.attach(content, attachments) },
    )
}

/**
 * Root of a pooled panel: [content] gets `inView` (the panel has been moved into view; a pooled panel
 * draws only then, Spatial Ruler finding 3). Debug builds log every press as `PTR panel=<id> ev=`.
 */
@Composable
private fun PoolPanel(id: String, scene: PlannerScene, pressed: (() -> Unit)? = null, content: @Composable (inView: Boolean) -> Unit) {
    val inView = scene.panelShown[id] == true
    val logged = Probe.enabled
    val watcher =
        if (logged || pressed != null) {
            Modifier.pointerInput(id, logged) {
                awaitPointerEventScope {
                    while (true) {
                        val event = awaitPointerEvent(PointerEventPass.Initial)
                        if (event.type == PointerEventType.Move) continue
                        if (event.type == PointerEventType.Press) pressed?.invoke()
                        if (logged) {
                            val p = event.changes.firstOrNull()?.position
                            TpLog.i(String.format(java.util.Locale.US, "PTR panel=%s ev=%s x=%.1f y=%.1f", id, event.type, p?.x ?: 0f, p?.y ?: 0f))
                        }
                    }
                }
            }
        } else Modifier
    CompositionLocalProvider(LocalProbePanel provides id) {
        Box(modifier = watcher) { content(inView) }
    }
}
