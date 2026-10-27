package io.embodit.theaterplanner.ui.planner.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextOverflow
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
import io.embodit.theaterplanner.domain.ScreenMode
import io.embodit.theaterplanner.ui.planner.Objects
import io.embodit.theaterplanner.ui.planner.PlannerEvent
import io.embodit.theaterplanner.ui.planner.PlannerUiState
import io.embodit.theaterplanner.ui.planner.debug.probe
import io.embodit.theaterplanner.ui.theme.TpType

private val ObjRowH = 48.dp

/**
 * The menu's object list (S30, taste profile D14): every kind of thing the app puts in the room, each with
 * hide / show, the screen with "move in front of me" (onto the wall ahead of the head of this moment), the
 * absorbers with "off", the speakers with "reset all". The screen is also dragged along its wall on the
 * screen page; the projector and the absorber frames sit where the rules put them (throw distance,
 * reflection points), so they can be hidden or switched off, not dragged.
 */
@Composable
internal fun ObjectsPage(state: PlannerUiState?, head: () -> HeadPose?, onEvent: (PlannerEvent) -> Unit) {
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
        Row(modifier = Modifier.fillMaxWidth().height(ObjRowH), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            IconButton(
                onClick = { onEvent(PlannerEvent.ObjectsPageShown(false)) },
                modifier = Modifier.probe("objects_back"),
                colors = IconButtonDefaults.iconButtonColors(containerColor = PicoTheme.colorScheme.fillTertiary, contentColor = ink),
                size = IconButtonDefaults.iconButtonSize(width = 48.dp, height = ObjRowH),
            ) { Icon(painter = painterResource(R.drawable.ic_arrow), contentDescription = rs(R.string.room_back), modifier = Modifier.size(22.dp).rotate(180f), tint = ink) }
            Text(text = rs(R.string.menu_objects), color = ink, style = TpType.title, maxLines = 1, modifier = Modifier.weight(1f))
        }
        val screenName = rs(if (state.mode == ScreenMode.TV) R.string.obj_tv else R.string.obj_projection_screen)
        ObjectRow(screenName, state, Objects.SCREEN, enabled = state.screen != null, onEvent = onEvent) {
            SmallButton("obj_screen_front", rs(R.string.obj_to_front), enabled = state.screen != null) { onEvent(PlannerEvent.ScreenToFront(head())) }
        }
        ObjectRow(rs(R.string.obj_projector), state, Objects.PROJECTOR, enabled = state.mode == ScreenMode.PROJECTOR, onEvent = onEvent)
        ObjectRow(rs(R.string.obj_absorbers, state.absorbers.size), state, Objects.ABSORBERS, enabled = state.absorbersOn, onEvent = onEvent) {
            SmallButton("obj_absorbers_off", rs(R.string.obj_off), enabled = state.absorbersOn) { onEvent(PlannerEvent.AbsorbersToggled(false)) }
        }
        ObjectRow(rs(R.string.obj_speakers, state.speakers.size), state, Objects.SPEAKERS, enabled = state.speakers.isNotEmpty(), onEvent = onEvent) {
            SmallButton("obj_speakers_reset", rs(R.string.obj_reset), enabled = state.speakers.isNotEmpty()) { onEvent(PlannerEvent.ResetAll) }
        }
        Text(text = rs(R.string.obj_hint), color = PicoTheme.colorScheme.labelSecondary, style = TpType.label, maxLines = 3, overflow = TextOverflow.Ellipsis, modifier = Modifier.fillMaxWidth().probe("objects_hint"))
    }
}

@Composable
private fun ObjectRow(name: String, state: PlannerUiState, key: String, enabled: Boolean, onEvent: (PlannerEvent) -> Unit, extra: @Composable () -> Unit = {}) {
    val ink = if (enabled) PicoTheme.colorScheme.labelPrimary else PicoTheme.colorScheme.labelSecondary
    val hidden = key in state.hidden
    Row(modifier = Modifier.fillMaxWidth().height(ObjRowH), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(text = name, color = ink, style = TpType.label, maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        extra()
        SmallButton("obj_${key}_hide", rs(if (hidden) R.string.obj_show else R.string.obj_hide), enabled = enabled) { onEvent(PlannerEvent.ObjectHidden(key, !hidden)) }
    }
}

@Composable
private fun SmallButton(name: String, text: String, enabled: Boolean, onClick: () -> Unit) {
    val ink = if (enabled) PicoTheme.colorScheme.labelPrimary else PicoTheme.colorScheme.labelQuaternary
    Button(
        onClick = onClick,
        modifier = Modifier.probe(name),
        size = ButtonDefaults.Regular.copy(height = ObjRowH, minWidth = 72.dp),
        shape = CircleShape,
        enabled = enabled,
        colors = ButtonDefaults.buttonColors(containerColor = PicoTheme.colorScheme.fillTertiary, contentColor = ink),
    ) { Text(text = text, color = ink, style = TpType.label, maxLines = 1, softWrap = false) }
}
