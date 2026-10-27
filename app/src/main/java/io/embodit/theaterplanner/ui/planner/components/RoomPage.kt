package io.embodit.theaterplanner.ui.planner.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.pico.spatial.ui.design.Button
import com.pico.spatial.ui.design.ButtonDefaults
import com.pico.spatial.ui.design.Icon
import com.pico.spatial.ui.design.IconButton
import com.pico.spatial.ui.design.IconButtonDefaults
import com.pico.spatial.ui.design.PicoTheme
import com.pico.spatial.ui.design.Text
import com.pico.spatial.ui.foundation.material.backgroundMaterial
import com.pico.spatial.ui.platform.Material
import io.embodit.theaterplanner.R
import io.embodit.theaterplanner.domain.HeadPose
import io.embodit.theaterplanner.domain.RoomSource
import io.embodit.theaterplanner.domain.Units
import io.embodit.theaterplanner.domain.VirtualRoom
import io.embodit.theaterplanner.ui.planner.PlannerEvent
import io.embodit.theaterplanner.ui.planner.PlannerUiState
import io.embodit.theaterplanner.ui.planner.debug.probe
import io.embodit.theaterplanner.ui.theme.TpType

private val RoomRowH = 48.dp
private val RoomKeyW = 52.dp

/**
 * The menu's room page (S28, taste profile D7): virtual / scanned room, the virtual room's width, length
 * and height in 10 cm steps, and nudges to line it up with the real walls without walking (10 cm moves
 * along the current view, 2 degree turns, "fit to my seat"). A scanned floor / ceiling snaps by itself.
 */
@Composable
internal fun RoomPage(state: PlannerUiState?, head: () -> HeadPose?, onEvent: (PlannerEvent) -> Unit) {
    Column(
        modifier =
            Modifier
                .size(PanelSizes.MENU_PANEL_W.dp, PanelSizes.ROOM_PAGE_H.dp)
                .clip(RoundedCornerShape(24.dp))
                .backgroundMaterial(enable = true, style = Material.Thick)
                .then(glassRefresh())
                .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (state == null) return@Column
        val ink = PicoTheme.colorScheme.labelPrimary
        val grey = PicoTheme.colorScheme.labelSecondary
        val virtual = state.roomSource == RoomSource.VIRTUAL
        val v = state.vroom
        Row(modifier = Modifier.fillMaxWidth().height(RoomRowH), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            RoomKey("room_back", R.drawable.ic_arrow, rs(R.string.room_back), enabled = true, rotation = 180f, width = 48.dp) { onEvent(PlannerEvent.RoomPageShown(false)) }
            Text(text = rs(R.string.menu_room), color = ink, style = TpType.title, maxLines = 1, modifier = Modifier.weight(1f))
            Segments(
                items = listOf(RoomSource.VIRTUAL, RoomSource.SCAN),
                selected = if (virtual) RoomSource.VIRTUAL else RoomSource.SCAN,
                width = 196.dp,
                enabled = true,
                name = { if (it == RoomSource.VIRTUAL) "room_virtual" else "room_scan" },
                onSelect = { if (it == RoomSource.VIRTUAL || state.scanUsable) onEvent(PlannerEvent.RoomSourceSelected(it)) },
            ) { item ->
                val on = item == RoomSource.VIRTUAL || state.scanUsable
                Text(text = rs(if (item == RoomSource.VIRTUAL) R.string.room_src_virtual else R.string.room_src_scan), color = if (on) ink else PicoTheme.colorScheme.labelQuaternary, style = TpType.label, maxLines = 1, softWrap = false)
            }
        }
        val step = VirtualRoom.SIZE_STEP_M
        SizeRow("room_w", rs(R.string.room_width), v?.width, VirtualRoom.MIN_W, VirtualRoom.MAX_W, virtual, state) { d -> onEvent(PlannerEvent.RoomResized(d * step, 0f, 0f)) }
        SizeRow("room_l", rs(R.string.room_length), v?.length, VirtualRoom.MIN_L, VirtualRoom.MAX_L, virtual, state) { d -> onEvent(PlannerEvent.RoomResized(0f, d * step, 0f)) }
        SizeRow("room_h", rs(R.string.room_height), v?.height, VirtualRoom.MIN_H, VirtualRoom.MAX_H, virtual, state) { d -> onEvent(PlannerEvent.RoomResized(0f, 0f, d * step)) }
        val m = VirtualRoom.MOVE_STEP_M
        Row(modifier = Modifier.fillMaxWidth().height(RoomRowH), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(text = rs(R.string.room_move), color = if (virtual) ink else grey, style = TpType.label, maxLines = 1, modifier = Modifier.width(56.dp))
            RoomKey("room_left", R.drawable.ic_arrow, rs(R.string.dir_left), virtual, rotation = 180f) { onEvent(PlannerEvent.RoomMoved(-m, 0f, 0f, head())) }
            RoomKey("room_right", R.drawable.ic_arrow, rs(R.string.dir_right), virtual, rotation = 0f) { onEvent(PlannerEvent.RoomMoved(m, 0f, 0f, head())) }
            RoomKey("room_ahead", R.drawable.ic_arrow, rs(R.string.dir_forward), virtual, rotation = -90f) { onEvent(PlannerEvent.RoomMoved(0f, m, 0f, head())) }
            RoomKey("room_backward", R.drawable.ic_arrow, rs(R.string.dir_back), virtual, rotation = 90f) { onEvent(PlannerEvent.RoomMoved(0f, -m, 0f, head())) }
        }
        Row(modifier = Modifier.fillMaxWidth().height(RoomRowH), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(text = rs(R.string.room_turn), color = if (virtual) ink else grey, style = TpType.label, maxLines = 1, modifier = Modifier.width(56.dp))
            TextKey("room_turn_left", rs(R.string.room_turn_left), virtual) { onEvent(PlannerEvent.RoomMoved(0f, 0f, -VirtualRoom.TURN_STEP_DEG, head())) }
            TextKey("room_turn_right", rs(R.string.room_turn_right), virtual) { onEvent(PlannerEvent.RoomMoved(0f, 0f, VirtualRoom.TURN_STEP_DEG, head())) }
            Button(
                onClick = { onEvent(PlannerEvent.RoomRecentered(head())) },
                modifier = Modifier.weight(1f).probe("room_recenter"),
                size = ButtonDefaults.Regular.copy(height = RoomRowH),
                shape = CircleShape,
                enabled = virtual,
                colors = ButtonDefaults.buttonColors(containerColor = PicoTheme.colorScheme.fillTertiary, contentColor = ink),
            ) { Text(text = rs(R.string.room_recenter), color = if (virtual) ink else PicoTheme.colorScheme.labelQuaternary, style = TpType.label, maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis) }
        }
        Text(
            text = rs(if (virtual) R.string.room_hint_virtual else R.string.room_hint_scan),
            color = grey,
            style = TpType.label,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.fillMaxWidth().probe("room_hint"),
        )
    }
}

@Composable
private fun SizeRow(name: String, label: String, value: Float?, min: Float, max: Float, enabled: Boolean, state: PlannerUiState, onStep: (Int) -> Unit) {
    val ink = if (enabled) PicoTheme.colorScheme.labelPrimary else PicoTheme.colorScheme.labelSecondary
    Row(modifier = Modifier.fillMaxWidth().height(RoomRowH), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(text = label, color = ink, style = TpType.label, maxLines = 1, modifier = Modifier.width(56.dp))
        RoomKey("${name}_minus", R.drawable.ic_minus, rs(R.string.action_minus), enabled && value != null && value > min + 0.001f) { onStep(-1) }
        Text(
            text = value?.let { Units.distance(it, state.unit) } ?: "-",
            color = ink,
            style = TpType.title,
            maxLines = 1,
            softWrap = false,
            textAlign = TextAlign.Center,
            modifier = Modifier.weight(1f).probe("${name}_value"),
        )
        RoomKey("${name}_plus", R.drawable.ic_plus, rs(R.string.action_plus), enabled && value != null && value < max - 0.001f) { onStep(1) }
    }
}

@Composable
private fun RoomKey(name: String, icon: Int, description: String, enabled: Boolean, rotation: Float = 0f, width: Dp = RoomKeyW, onClick: () -> Unit) {
    val ink = if (enabled) PicoTheme.colorScheme.labelPrimary else PicoTheme.colorScheme.labelQuaternary
    IconButton(
        onClick = onClick,
        modifier = Modifier.probe(name),
        enabled = enabled,
        colors = IconButtonDefaults.iconButtonColors(containerColor = PicoTheme.colorScheme.fillTertiary, contentColor = ink),
        size = IconButtonDefaults.iconButtonSize(width = width, height = RoomRowH),
    ) { Icon(painter = painterResource(icon), contentDescription = description, modifier = Modifier.size(22.dp).rotate(rotation), tint = ink) }
}

@Composable
private fun TextKey(name: String, text: String, enabled: Boolean, onClick: () -> Unit) {
    val ink = if (enabled) PicoTheme.colorScheme.labelPrimary else PicoTheme.colorScheme.labelQuaternary
    Button(
        onClick = onClick,
        modifier = Modifier.probe(name),
        size = ButtonDefaults.Regular.copy(height = RoomRowH, minWidth = 72.dp),
        shape = CircleShape,
        enabled = enabled,
        colors = ButtonDefaults.buttonColors(containerColor = PicoTheme.colorScheme.fillTertiary, contentColor = ink),
    ) { Text(text = text, color = ink, style = TpType.label, maxLines = 1, softWrap = false) }
}
