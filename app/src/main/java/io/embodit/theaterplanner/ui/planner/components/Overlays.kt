package io.embodit.theaterplanner.ui.planner.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.pico.spatial.ui.design.Button
import com.pico.spatial.ui.design.ButtonDefaults
import com.pico.spatial.ui.design.CircularProgressDefaults
import com.pico.spatial.ui.design.CircularProgressIndicator
import com.pico.spatial.ui.design.Icon
import com.pico.spatial.ui.design.IconButton
import com.pico.spatial.ui.design.IconButtonDefaults
import com.pico.spatial.ui.design.PicoTheme
import com.pico.spatial.ui.design.Text
import com.pico.spatial.ui.foundation.material.backgroundMaterial
import com.pico.spatial.ui.platform.Material
import io.embodit.theaterplanner.R
import io.embodit.theaterplanner.domain.HeadPose
import io.embodit.theaterplanner.domain.LanguagePref
import io.embodit.theaterplanner.domain.LengthUnit
import io.embodit.theaterplanner.domain.RoomSource
import io.embodit.theaterplanner.ui.planner.Phase
import io.embodit.theaterplanner.ui.planner.PlannerEvent
import io.embodit.theaterplanner.ui.planner.PlannerUiState
import io.embodit.theaterplanner.ui.planner.SnackKind
import io.embodit.theaterplanner.ui.planner.SnackUi
import io.embodit.theaterplanner.ui.planner.debug.probe
import io.embodit.theaterplanner.ui.theme.TpColors
import io.embodit.theaterplanner.ui.theme.TpType

private val RowH = 48.dp

/**
 * The seat prompt (design `panels.seatCoach`, 360 x 184): seat glyph, "坐到看电影的位置", one sentence,
 * and the "就坐这儿" button (which catches its own pinch, design `rules.seatPanel`); while reseating the
 * title changes and a cancel key appears.
 */
@Composable
internal fun SeatPanel(state: PlannerUiState?, onSeatHere: () -> Unit, onEvent: (PlannerEvent) -> Unit) {
    Row(
        modifier =
            Modifier
                .size(PanelSizes.SEAT_W.dp, PanelSizes.SEAT_H.dp)
                .clip(RoundedCornerShape(28.dp))
                .backgroundMaterial(enable = true, style = Material.Thick)
                .then(glassRefresh())
                .padding(horizontal = 20.dp, vertical = 18.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        if (state == null) return@Row
        val reseat = state.phase == Phase.RESEAT
        val ink = PicoTheme.colorScheme.labelPrimary
        Icon(painter = painterResource(R.drawable.ic_seat), contentDescription = null, modifier = Modifier.size(48.dp), tint = ink)
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(text = rs(if (reseat) R.string.reseat_title else R.string.seat_title), color = ink, style = TpType.seatTitle, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.probe("seat_title"))
            if (!reseat) Text(text = rs(R.string.seat_body), color = PicoTheme.colorScheme.labelSecondary, style = TpType.body, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Row(modifier = Modifier.fillMaxWidth().padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Button(
                    onClick = onSeatHere,
                    modifier = Modifier.probe("seat_here"),
                    size = ButtonDefaults.Regular.copy(height = RowH, minWidth = 120.dp),
                    shape = CircleShape,
                    colors = ButtonDefaults.buttonColors(containerColor = PicoTheme.colorScheme.interaction, contentColor = TpColors.TapeInk),
                ) { Text(text = rs(R.string.seat_here), color = TpColors.TapeInk, maxLines = 1) }
                if (reseat) {
                    IconButton(
                        onClick = { onEvent(PlannerEvent.ReseatCancel) },
                        modifier = Modifier.probe("seat_cancel"),
                        colors = IconButtonDefaults.iconButtonColors(containerColor = PicoTheme.colorScheme.fillTertiary, contentColor = ink),
                        size = IconButtonDefaults.iconButtonSize(width = 56.dp, height = RowH),
                    ) { Icon(painter = painterResource(R.drawable.ic_cross), contentDescription = rs(R.string.action_cancel), modifier = Modifier.size(22.dp), tint = ink) }
                }
            }
        }
    }
}

/** The More menu (design `panels.menu`): its main page, or the room page (S28). */
@Composable
internal fun MoreMenu(state: PlannerUiState?, head: () -> HeadPose?, onEvent: (PlannerEvent) -> Unit) {
    Box(modifier = Modifier.size(PanelSizes.MENU_PANEL_W.dp, PanelSizes.MENU_PANEL_H.dp), contentAlignment = Alignment.BottomEnd) {
        when {
            state?.roomPage == true -> RoomPage(state = state, head = head, onEvent = onEvent)
            state?.objectsPage == true -> ObjectsPage(state = state, head = head, onEvent = onEvent)
            else -> MenuPage(state = state, onEvent = onEvent)
        }
    }
}

/** The menu's main page: language and unit segments that act in place, the room, Clear all, Exit. */
@Composable
private fun MenuPage(state: PlannerUiState?, onEvent: (PlannerEvent) -> Unit) {
    Column(
        modifier =
            Modifier
                .size(PanelSizes.MENU_W.dp, PanelSizes.MENU_H.dp)
                .clip(RoundedCornerShape(24.dp))
                .backgroundMaterial(enable = true, style = Material.Thick)
                .then(glassRefresh())
                .padding(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (state == null) return@Column
        val ink = PicoTheme.colorScheme.labelPrimary
        Segments(
            items = LanguagePref.entries,
            selected = state.settings.language,
            width = (PanelSizes.MENU_W - 16f).dp,
            enabled = true,
            name = { "lang_${it.tag}" },
            onSelect = { onEvent(PlannerEvent.LanguageSelected(it)) },
        ) { item ->
            when (item) {
                LanguagePref.SYSTEM -> Icon(painter = painterResource(R.drawable.ic_globe), contentDescription = rs(R.string.lang_system), modifier = Modifier.size(20.dp), tint = ink)
                LanguagePref.ZH -> Text(text = rs(R.string.lang_zh), color = ink, style = TpType.label, maxLines = 1, softWrap = false)
                LanguagePref.EN -> {
                    val english = rs(R.string.lang_en)
                    Text(text = rs(R.string.lang_en_short), color = ink, style = TpType.label, maxLines = 1, softWrap = false, modifier = Modifier.semantics { contentDescription = english })
                }
            }
        }
        Segments(
            items = LengthUnit.entries,
            selected = state.unit,
            width = (PanelSizes.MENU_W - 16f).dp,
            enabled = true,
            name = { "unit_${it.tag}" },
            onSelect = { onEvent(PlannerEvent.UnitSelected(it)) },
        ) { item -> Text(text = rs(if (item == LengthUnit.M) R.string.unit_m else R.string.unit_ft), color = ink, style = TpType.label, maxLines = 1) }
        // the room (taste profile D7): which one is in use and its size, opens the room page
        val v = state.vroom
        val roomText =
            if (state.roomSource == RoomSource.SCAN || v == null) rs(R.string.menu_room) + " · " + rs(R.string.room_src_scan)
            else rs(R.string.menu_room) + " · " + String.format(java.util.Locale.US, "%.1f × %.1f × %.1f", v.width, v.length, v.height) + (if (state.unit == LengthUnit.M) " m" else "")
        // the things in the room (taste profile D14): hide, move in front of me, off, reset
        Button(
            onClick = { onEvent(PlannerEvent.ObjectsPageShown(true)) },
            modifier = Modifier.fillMaxWidth().probe("menu_objects"),
            size = ButtonDefaults.Regular.copy(height = RowH),
            shape = CircleShape,
            enabled = state.seat != null,
            colors = ButtonDefaults.buttonColors(containerColor = PicoTheme.colorScheme.fillTertiary, contentColor = ink),
        ) {
            Text(text = rs(R.string.menu_objects), color = ink, maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            Icon(painter = painterResource(R.drawable.ic_arrow), contentDescription = null, modifier = Modifier.size(18.dp), tint = ink)
        }
        Button(
            onClick = { onEvent(PlannerEvent.RoomPageShown(true)) },
            modifier = Modifier.fillMaxWidth().probe("menu_room"),
            size = ButtonDefaults.Regular.copy(height = RowH),
            shape = CircleShape,
            colors = ButtonDefaults.buttonColors(containerColor = PicoTheme.colorScheme.fillTertiary, contentColor = ink),
        ) {
            Text(text = roomText, color = ink, maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            Icon(painter = painterResource(R.drawable.ic_arrow), contentDescription = null, modifier = Modifier.size(18.dp), tint = ink)
        }
        Row(modifier = Modifier.fillMaxWidth().height(RowH), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            val canClear = state.seat != null
            val clearInk = if (canClear) ink else PicoTheme.colorScheme.labelQuaternary
            Button(
                onClick = { onEvent(PlannerEvent.ClearAllClicked) },
                modifier = Modifier.weight(1f).probe("menu_clear"),
                size = ButtonDefaults.Regular.copy(height = RowH),
                shape = CircleShape,
                enabled = canClear,
                colors = ButtonDefaults.buttonColors(containerColor = PicoTheme.colorScheme.fillTertiary, contentColor = clearInk),
            ) {
                Icon(painter = painterResource(R.drawable.ic_trash), contentDescription = null, modifier = Modifier.size(20.dp), tint = clearInk)
                Spacer(modifier = Modifier.width(6.dp))
                Text(text = rs(R.string.menu_clear), color = clearInk, maxLines = 1)
            }
            IconButton(
                onClick = { onEvent(PlannerEvent.ExitClicked) },
                modifier = Modifier.probe("menu_exit"),
                colors = IconButtonDefaults.iconButtonColors(containerColor = PicoTheme.colorScheme.fillTertiary, contentColor = ink),
                size = IconButtonDefaults.iconButtonSize(width = 56.dp, height = RowH),
            ) { Icon(painter = painterResource(R.drawable.ic_exit), contentDescription = rs(R.string.menu_exit), modifier = Modifier.size(24.dp), tint = ink) }
        }
    }
}

/** Dialog buttons: 48 dp, capsule, icon + one word (Spatial Ruler). */
@Composable
private fun DialogButton(name: String, iconRes: Int, text: String, container: Color, content: Color, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        modifier = Modifier.probe(name),
        size = ButtonDefaults.Regular.copy(height = 48.dp, minWidth = 120.dp),
        shape = CircleShape,
        colors = ButtonDefaults.buttonColors(containerColor = container, contentColor = content),
    ) {
        Icon(painter = painterResource(iconRes), contentDescription = null, modifier = Modifier.size(20.dp), tint = content)
        Spacer(modifier = Modifier.width(8.dp))
        Text(text = text, color = content, maxLines = 1)
    }
}

/**
 * The two dialogs (design `panels.dialog`, 480 wide): the clear confirmation and "this device can't
 * find the room" (AlertDialog composes nothing in a Stage, Spatial Ruler). [blocked] wins.
 */
@Composable
internal fun DialogPanel(clear: Boolean, blocked: Boolean, onEvent: (PlannerEvent) -> Unit) {
    Column(
        modifier =
            Modifier
                .size(PanelSizes.DIALOG_W.dp, PanelSizes.DIALOG_H.dp)
                .clip(RoundedCornerShape(32.dp))
                .backgroundMaterial(enable = true, style = Material.Thick)
                .then(glassRefresh())
                .padding(28.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        if (!clear && !blocked) return@Column
        val ink = PicoTheme.colorScheme.labelPrimary
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            Icon(painter = painterResource(if (blocked) R.drawable.ic_unavailable else R.drawable.ic_trash), contentDescription = null, modifier = Modifier.size(40.dp), tint = ink)
            Text(text = rs(if (blocked) R.string.dialog_unavailable else R.string.dialog_clear), color = ink, style = TpType.dialogTitle, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f).probe("dialog_text"))
        }
        Spacer(modifier = Modifier.weight(1f))
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)) {
            if (blocked) {
                DialogButton("blocked_exit", R.drawable.ic_exit, rs(R.string.action_exit), PicoTheme.colorScheme.fillTertiary, ink) { onEvent(PlannerEvent.BlockedExit) }
            } else {
                DialogButton("dialog_cancel", R.drawable.ic_cross, rs(R.string.action_cancel), PicoTheme.colorScheme.fillTertiary, ink) { onEvent(PlannerEvent.DialogDismissed) }
                DialogButton("dialog_confirm", R.drawable.ic_trash, rs(R.string.action_clear), PicoTheme.colorScheme.error, TpColors.TapeInk) { onEvent(PlannerEvent.ClearConfirmed) }
            }
        }
    }
}

/** The snackbar (design `panels.snackbar`): Thick glass capsule, icon + <= 8 characters + optional action. */
@Composable
internal fun SnackPanel(snack: SnackUi?, onEvent: (PlannerEvent) -> Unit) {
    Box(modifier = Modifier.size(PanelSizes.SNACK_W.dp, PanelSizes.SNACK_H.dp), contentAlignment = Alignment.Center) {
        if (snack == null) return@Box
        val ink = PicoTheme.colorScheme.labelPrimary
        val (icon, text, action) =
            when (snack.kind) {
                SnackKind.SCANNING -> Triple(0, rs(R.string.snack_scanning), null)
                SnackKind.STANDING -> Triple(R.drawable.ic_warning, rs(R.string.snack_standing), R.string.snack_redo to R.drawable.ic_seat)
                SnackKind.TOO_LOW -> Triple(R.drawable.ic_warning, rs(R.string.snack_too_low), R.string.snack_redo to R.drawable.ic_seat)
                SnackKind.RESTORED -> Triple(R.drawable.ic_check, rs(R.string.snack_restored), null)
                SnackKind.ELSEWHERE -> Triple(R.drawable.ic_warning, rs(R.string.snack_elsewhere), R.string.snack_sit_again to R.drawable.ic_seat)
                SnackKind.LOST -> Triple(R.drawable.ic_unavailable, rs(R.string.snack_lost), null)
                SnackKind.CLEARED -> Triple(R.drawable.ic_check, rs(R.string.snack_cleared), null)
                SnackKind.UNSAVED -> Triple(R.drawable.ic_warning, rs(R.string.snack_unsaved), R.string.action_retry to R.drawable.ic_reset)
                SnackKind.VIRTUAL_ROOM -> Triple(R.drawable.ic_check, rs(R.string.snack_virtual_room), null)
            }
        val tint = if (snack.kind == SnackKind.STANDING || snack.kind == SnackKind.TOO_LOW) PicoTheme.colorScheme.alert else ink
        Row(
            modifier =
                Modifier
                    .widthIn(min = 240.dp, max = PanelSizes.SNACK_W.dp)
                    .height(56.dp)
                    .clip(RoundedCornerShape(28.dp))
                    .backgroundMaterial(enable = true, style = Material.Thick)
                    .then(glassRefresh())
                    .probe("snackbar")
                    .padding(start = 16.dp, end = if (action != null) 8.dp else 20.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (icon == 0) {
                CircularProgressIndicator(modifier = Modifier.size(20.dp), color = ink, progressSize = CircularProgressDefaults.circleProgressSize(20.dp))
            } else {
                Icon(painter = painterResource(icon), contentDescription = null, modifier = Modifier.size(24.dp), tint = tint)
            }
            Text(text = text, color = ink, style = TpType.title, maxLines = 1, softWrap = false)
            if (action != null) {
                Button(
                    onClick = { onEvent(PlannerEvent.SnackActionClicked) },
                    modifier = Modifier.probe("snackbar_action"),
                    size = ButtonDefaults.Regular.copy(height = 40.dp),
                    shape = CircleShape,
                    colors = ButtonDefaults.buttonColors(containerColor = PicoTheme.colorScheme.interaction, contentColor = TpColors.TapeInk),
                ) {
                    Icon(painter = painterResource(action.second), contentDescription = null, modifier = Modifier.size(18.dp), tint = TpColors.TapeInk)
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(text = rs(action.first), color = TpColors.TapeInk, maxLines = 1)
                }
            }
        }
    }
}

/** The one-step coachmarks (design `panels.coach`): pictogram, one sentence, tick. [rear] shows the rear-speaker sentence (S24). */
@Composable
internal fun CoachPanel(visible: Boolean, rear: Boolean, onEvent: (PlannerEvent) -> Unit) {
    Row(
        modifier =
            Modifier
                .size(PanelSizes.COACH_W.dp, PanelSizes.COACH_H.dp)
                .clip(RoundedCornerShape(24.dp))
                .backgroundMaterial(enable = true, style = Material.Thick)
                .then(glassRefresh())
                .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (!visible) return@Row
        val ink = PicoTheme.colorScheme.labelPrimary
        Icon(painter = painterResource(R.drawable.ic_hand_drag), contentDescription = null, modifier = Modifier.size(36.dp), tint = ink)
        Text(text = rs(if (rear) R.string.rear_coach else R.string.coach), color = ink, style = TpType.body, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f).probe("coach_text"))
        IconButton(
            onClick = { onEvent(if (rear) PlannerEvent.RearCoachDismissed else PlannerEvent.CoachDismissed) },
            modifier = Modifier.probe("coach_ok"),
            colors = IconButtonDefaults.iconButtonColors(containerColor = PicoTheme.colorScheme.interaction, contentColor = TpColors.TapeInk),
            size = IconButtonDefaults.iconButtonSize(width = 48.dp, height = 48.dp),
        ) { Icon(painter = painterResource(R.drawable.ic_check), contentDescription = rs(R.string.coach_ok), modifier = Modifier.size(24.dp), tint = TpColors.TapeInk) }
    }
}
