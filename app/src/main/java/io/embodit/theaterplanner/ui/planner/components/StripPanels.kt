package io.embodit.theaterplanner.ui.planner.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.pico.spatial.ui.design.Icon
import com.pico.spatial.ui.design.IconButton
import com.pico.spatial.ui.design.IconButtonDefaults
import com.pico.spatial.ui.design.PicoTheme
import com.pico.spatial.ui.design.Text
import com.pico.spatial.ui.foundation.material.backgroundMaterial
import com.pico.spatial.ui.platform.Material
import io.embodit.theaterplanner.R
import io.embodit.theaterplanner.domain.LengthUnit
import io.embodit.theaterplanner.domain.MoveHint
import io.embodit.theaterplanner.domain.SpeakerKind
import io.embodit.theaterplanner.domain.SpeakerState
import io.embodit.theaterplanner.domain.Status
import io.embodit.theaterplanner.domain.Theater
import io.embodit.theaterplanner.domain.Units
import io.embodit.theaterplanner.ui.planner.PlannerEvent
import io.embodit.theaterplanner.ui.planner.PlannerUiState
import io.embodit.theaterplanner.ui.planner.debug.probe
import io.embodit.theaterplanner.ui.theme.TpColors
import io.embodit.theaterplanner.ui.theme.TpType
import kotlin.math.abs
import kotlin.math.roundToInt

/** A dark card on glass (Spatial Ruler's tag card): the plates that ride on 3D things. */
@Composable
private fun TagCard(w: Float, h: Float, radius: Dp = 20.dp, padding: Dp = 12.dp, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Box(
        modifier =
            modifier
                .size(w.dp, h.dp)
                .clip(RoundedCornerShape(radius))
                .backgroundMaterial(enable = true, style = Material.Thick)
                .then(glassRefresh())
                .padding(4.dp)
                .clip(RoundedCornerShape(radius - 4.dp))
                .background(TpColors.PlateInk)
                .padding(padding),
    ) { content() }
}

/** The 40 dp round number badge in the speaker's status colour. */
@Composable
private fun Badge(label: String, status: Status, size: Dp = 40.dp) {
    Box(modifier = Modifier.size(size).clip(CircleShape).background(TpColors.of(status)), contentAlignment = Alignment.Center) {
        Text(text = label, color = TpColors.TapeInk, style = TpType.label, maxLines = 1, softWrap = false)
    }
}

/**
 * The speaker strip (design `panels.speakerStrip`, 320 x 112): badge · name / "112° · best 110–120°" /
 * one third line by priority · distance + reset. Taste profile D adds the stick hint as the third line
 * when nothing else claims it.
 */
@Composable
internal fun SpeakerStrip(state: PlannerUiState?, onEvent: (PlannerEvent) -> Unit) {
    TagCard(PanelSizes.STRIP_W, PanelSizes.STRIP_H) {
        val sp = state?.selectedSpeaker ?: return@TagCard
        val seat = state.seat ?: return@TagCard
        val hint = Theater.moveHint(sp, seat, state.room)
        Row(modifier = Modifier.fillMaxSize(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Badge(sp.def.label, sp.status)
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(text = speakerName(sp.def), color = TpColors.White, style = TpType.title, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.probe("strip_name"))
                Text(text = rangeText(sp), color = TpColors.OnInkSecondary, style = TpType.label, maxLines = 1, softWrap = false, modifier = Modifier.probe("strip_range"))
                val (third, color) = thirdLine(sp, hint, state.unit, assumed = !state.room.hasCeiling && sp.def.kind == SpeakerKind.OVERHEAD)
                Text(text = third, color = color, style = TpType.label, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.probe("strip_third"))
            }
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(text = Units.distance(sp.distance, state.unit), color = TpColors.White, style = TpType.label, maxLines = 1, softWrap = false, modifier = Modifier.probe("strip_distance"))
                val can = !sp.atRecommended && state.dragEnabled
                val ink = if (can) TpColors.White else TpColors.OnInkSecondary.copy(alpha = 0.4f)
                IconButton(
                    onClick = { onEvent(PlannerEvent.ResetOne(sp.id)) },
                    modifier = Modifier.probe("strip_reset"),
                    enabled = can,
                    colors = IconButtonDefaults.iconButtonColors(containerColor = TpColors.OnInkFill, contentColor = ink),
                    size = IconButtonDefaults.iconButtonSize(width = 48.dp, height = 40.dp),
                ) { Icon(painter = painterResource(R.drawable.ic_reset), contentDescription = rs(R.string.action_reset), modifier = Modifier.size(22.dp), tint = ink) }
            }
        }
    }
}

@Composable
private fun rangeText(sp: SpeakerState): String {
    val lo = abs(sp.def.range.start).roundToInt()
    val hi = abs(sp.def.range.endInclusive).roundToInt()
    return rs(R.string.range, abs(sp.value).roundToInt(), minOf(lo, hi), maxOf(lo, hi))
}

/** Third line by priority: move hint (red) > too close (orange) > no wall (grey) > assumed ceiling (grey) > stick hint. */
@Composable
private fun thirdLine(sp: SpeakerState, hint: MoveHint?, unit: LengthUnit, assumed: Boolean): Pair<String, Color> =
    when {
        hint != null -> rs(R.string.move, moveDirWord(hint.dir), Units.cm(hint.cm, unit)) to PicoTheme.colorScheme.error
        sp.tooClose -> rs(R.string.too_close_one) to PicoTheme.colorScheme.alert
        sp.noWall -> rs(R.string.no_wall) to TpColors.OnInkSecondary
        assumed -> rs(R.string.assumed_one) to TpColors.OnInkSecondary
        else -> rs(R.string.hint_stick) to TpColors.OnInkSecondary
    }

/** What the readout pill shows while a speaker is dragged (written by the scene at most 10 times a second). */
data class ReadoutUi(val value: Float, val status: Status, val hint: MoveHint?)

/** The readout pill (design `panels.readout`): "112°" at 32 sp; red with "往右 32 cm" when out of range. */
@Composable
internal fun ReadoutPanel(readout: ReadoutUi?, unit: LengthUnit) {
    Box(modifier = Modifier.size(PanelSizes.READOUT_W.dp, PanelSizes.READOUT_H.dp), contentAlignment = Alignment.Center) {
        if (readout == null) return@Box
        val bad = readout.status == Status.RED
        val bg = if (bad) PicoTheme.colorScheme.error else TpColors.PlateInk
        val ink = if (bad) TpColors.TapeInk else TpColors.White
        Row(
            modifier = Modifier.clip(RoundedCornerShape(24.dp)).background(bg).padding(horizontal = 14.dp, vertical = 2.dp).probe("readout"),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(text = "${abs(readout.value).roundToInt()}°", color = ink, style = TpType.readout, maxLines = 1, softWrap = false)
            val hint = readout.hint
            if (hint != null) Text(text = rs(R.string.move, moveDirWord(hint.dir), Units.cm(hint.cm, unit)), color = ink, style = TpType.label, maxLines = 1, softWrap = false)
        }
    }
}

/** The speaker's number plate (design `entities3d.speaker`: "L", "Ls" ... on the selected and the gazed-at speaker). */
@Composable
internal fun SpeakerTag(sp: SpeakerState?) {
    Box(modifier = Modifier.size(PanelSizes.TAG_W.dp, PanelSizes.TAG_H.dp), contentAlignment = Alignment.Center) {
        if (sp == null) return@Box
        Box(modifier = Modifier.clip(RoundedCornerShape(18.dp)).background(TpColors.PlateInk).padding(horizontal = 12.dp, vertical = 6.dp), contentAlignment = Alignment.Center) {
            Text(text = "${sp.def.label} ${abs(sp.value).roundToInt()}°", color = TpColors.of(sp.status), style = TpType.label, maxLines = 1, softWrap = false)
        }
    }
}

/** The floating size plate at the screen's lower left corner ("85″"). */
@Composable
internal fun SizeTag(inches: Int?) {
    Box(modifier = Modifier.size(PanelSizes.SIZE_TAG_W.dp, PanelSizes.SIZE_TAG_H.dp), contentAlignment = Alignment.Center) {
        if (inches == null) return@Box
        Box(modifier = Modifier.clip(RoundedCornerShape(20.dp)).background(TpColors.PlateInk).padding(horizontal = 14.dp, vertical = 6.dp), contentAlignment = Alignment.Center) {
            Text(text = "$inches″", color = TpColors.White, style = TpType.title, maxLines = 1, softWrap = false)
        }
    }
}

/** The small "L" / "C" / "R" plate under an absorber frame: whose reflection it is. */
@Composable
internal fun AbsorberTag(source: String?) {
    Box(modifier = Modifier.size(PanelSizes.ABS_TAG_W.dp, PanelSizes.ABS_TAG_H.dp), contentAlignment = Alignment.Center) {
        if (source == null) return@Box
        Box(modifier = Modifier.clip(RoundedCornerShape(16.dp)).background(TpColors.PlateInk).padding(horizontal = 10.dp, vertical = 4.dp), contentAlignment = Alignment.Center) {
            Text(text = source, color = TpColors.ReflectBlue, style = TpType.label, maxLines = 1, softWrap = false)
        }
    }
}
