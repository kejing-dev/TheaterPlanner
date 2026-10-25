package io.embodit.theaterplanner.ui.planner.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
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
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
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
import com.pico.spatial.ui.design.SegmentControl
import com.pico.spatial.ui.design.SegmentControlDefaults
import com.pico.spatial.ui.design.SegmentItem
import com.pico.spatial.ui.design.Switch
import com.pico.spatial.ui.design.Text
import com.pico.spatial.ui.foundation.material.backgroundMaterial
import com.pico.spatial.ui.platform.Material
import io.embodit.theaterplanner.R
import io.embodit.theaterplanner.domain.Config
import io.embodit.theaterplanner.domain.ProjectorRules
import io.embodit.theaterplanner.domain.ScreenMode
import io.embodit.theaterplanner.domain.ScreenRules
import io.embodit.theaterplanner.domain.Status
import io.embodit.theaterplanner.domain.Units
import io.embodit.theaterplanner.ui.planner.Page
import io.embodit.theaterplanner.ui.planner.Phase
import io.embodit.theaterplanner.ui.planner.PlannerEvent
import io.embodit.theaterplanner.ui.planner.PlannerUiState
import io.embodit.theaterplanner.ui.planner.StatusKind
import io.embodit.theaterplanner.ui.planner.StatusLine
import io.embodit.theaterplanner.ui.planner.debug.probe
import io.embodit.theaterplanner.ui.theme.TpColors
import io.embodit.theaterplanner.ui.theme.TpType
import java.util.Locale

private val RowH = 48.dp
private val KeyW = 56.dp

/**
 * The console (design `panels.console`, 400 x 400 dp at 0.75): the one resident panel. Row 1 is the
 * page segment, the reseat key and the More key; below it the speaker page or the screen page. The
 * whole panel is disabled (dimmed, controls off) while starting, scanning, sitting, reseating or
 * when tracking is lost.
 */
@Composable
internal fun ConsolePanel(state: PlannerUiState?, onEvent: (PlannerEvent) -> Unit) {
    Column(
        modifier =
            Modifier
                .size(PanelSizes.CONSOLE_W.dp, PanelSizes.CONSOLE_H.dp)
                .clip(RoundedCornerShape(28.dp))
                .backgroundMaterial(enable = true, style = Material.Regular)
                .then(glassRefresh())
                .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (state == null) return@Column
        val enabled = state.consoleEnabled
        val ink = PicoTheme.colorScheme.labelPrimary
        val grey = PicoTheme.colorScheme.labelSecondary
        // row 1: [speakers | screen] · reseat · more
        Row(modifier = Modifier.fillMaxWidth().height(RowH).alpha(if (enabled) 1f else PanelSizes.DISABLED_ALPHA), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Segments(
                items = Page.entries,
                selected = state.page,
                width = 232.dp,
                enabled = enabled,
                name = { "page_${it.name.lowercase(Locale.US)}" },
                onSelect = { onEvent(PlannerEvent.PageSelected(it)) },
            ) { page ->
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Icon(painter = painterResource(if (page == Page.SPEAKERS) R.drawable.ic_speaker else R.drawable.ic_screen), contentDescription = null, modifier = Modifier.size(20.dp), tint = ink)
                    Text(text = rs(if (page == Page.SPEAKERS) R.string.page_speakers else R.string.page_screen), color = ink, style = TpType.label, maxLines = 1, softWrap = false)
                }
            }
            Spacer(modifier = Modifier.weight(1f))
            Key(name = "reseat", icon = R.drawable.ic_seat, description = rs(R.string.action_reseat), enabled = enabled) { onEvent(PlannerEvent.ReseatClicked) }
            Key(name = "more", icon = R.drawable.ic_more, description = rs(R.string.action_more), enabled = enabled) { onEvent(PlannerEvent.MoreClicked) }
        }
        when {
            state.phase == Phase.STARTING -> Starting()
            state.page == Page.SPEAKERS || state.seat == null -> SpeakerPage(state, enabled, ink, grey, onEvent)
            else -> ScreenPage(state, enabled, ink, grey, onEvent)
        }
    }
}

@Composable
private fun Starting() {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        CircularProgressIndicator(modifier = Modifier.size(40.dp), color = PicoTheme.colorScheme.labelPrimary, progressSize = CircularProgressDefaults.circleProgressSize(40.dp))
    }
}

@Composable
private fun SpeakerPage(state: PlannerUiState, enabled: Boolean, ink: Color, grey: Color, onEvent: (PlannerEvent) -> Unit) {
    Column(modifier = Modifier.fillMaxWidth().alpha(if (enabled) 1f else PanelSizes.DISABLED_ALPHA), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Segments(
            items = Config.entries,
            selected = state.config,
            width = (PanelSizes.CONSOLE_W - 32f).dp,
            enabled = enabled,
            name = { "cfg_${it.tag.replace('.', '_')}" },
            onSelect = { onEvent(PlannerEvent.ConfigSelected(it)) },
        ) { cfg -> Text(text = cfg.tag, color = ink, style = TpType.title, maxLines = 1, softWrap = false) }
        Text(
            text = rs(when (state.config) { Config.C51 -> R.string.count_51; Config.C512 -> R.string.count_512; Config.C714 -> R.string.count_714 }),
            color = grey,
            style = TpType.label,
            maxLines = 1,
            modifier = Modifier.padding(start = 4.dp).probe("cfg_count"),
        )
        StatusRow(lines = if (state.inLayout) state.speakerStatusLines() else emptyList(), unit = state.unit)
        // absorbers switch
        Row(modifier = Modifier.fillMaxWidth().height(RowH), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Icon(painter = painterResource(R.drawable.ic_absorber), contentDescription = null, modifier = Modifier.size(24.dp), tint = ink)
            Text(text = rs(R.string.absorbers), color = ink, style = TpType.title, maxLines = 1)
            Spacer(modifier = Modifier.weight(1f))
            if (state.absorbersOn) Text(text = rs(R.string.rough), color = grey, style = TpType.label, maxLines = 1, modifier = Modifier.probe("rough"))
            Switch(checked = state.absorbersOn, onCheckedChange = { onEvent(PlannerEvent.AbsorbersToggled(it)) }, modifier = Modifier.probe("absorbers"), enabled = enabled)
        }
        val canReset = state.speakers.any { !it.atRecommended }
        val resetInk = if (canReset && enabled) ink else PicoTheme.colorScheme.labelQuaternary
        Button(
            onClick = { onEvent(PlannerEvent.ResetAll) },
            modifier = Modifier.fillMaxWidth().probe("reset_all"),
            size = ButtonDefaults.Regular.copy(height = RowH),
            shape = CircleShape,
            enabled = canReset && enabled,
            colors = ButtonDefaults.buttonColors(containerColor = PicoTheme.colorScheme.fillTertiary, contentColor = resetInk),
        ) {
            Icon(painter = painterResource(R.drawable.ic_reset), contentDescription = null, modifier = Modifier.size(20.dp), tint = resetInk)
            Spacer(modifier = Modifier.width(8.dp))
            Text(text = rs(R.string.reset_all), color = resetInk, maxLines = 1)
        }
        Text(text = rs(R.string.sub_hint), color = grey, style = TpType.label, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(start = 4.dp))
    }
}

@Composable
private fun ScreenPage(state: PlannerUiState, enabled: Boolean, ink: Color, grey: Color, onEvent: (PlannerEvent) -> Unit) {
    val screen = state.screen
    val advice = state.advice
    Column(modifier = Modifier.fillMaxWidth().alpha(if (enabled) 1f else PanelSizes.DISABLED_ALPHA), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Segments(
            items = ScreenMode.entries,
            selected = state.mode,
            width = (PanelSizes.CONSOLE_W - 32f).dp,
            enabled = enabled,
            name = { "mode_${it.tag}" },
            onSelect = { onEvent(PlannerEvent.ModeSelected(it)) },
        ) { mode ->
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Icon(painter = painterResource(if (mode == ScreenMode.TV) R.drawable.ic_tv else R.drawable.ic_projector), contentDescription = null, modifier = Modifier.size(20.dp), tint = ink)
                Text(text = rs(if (mode == ScreenMode.TV) R.string.mode_tv else R.string.mode_projector), color = ink, style = TpType.label, maxLines = 1, softWrap = false)
            }
        }
        // size row: − · 85" + width · +
        val inches = screen?.inches ?: 0
        val atMax = advice != null && inches >= advice.max
        val atMin = advice != null && inches <= advice.min
        Row(modifier = Modifier.fillMaxWidth().height(RowH), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Key(name = "size_minus", icon = R.drawable.ic_minus, description = rs(R.string.action_minus), enabled = enabled && !atMin) { onEvent(PlannerEvent.SizeStepped(-1)) }
            Column(modifier = Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(text = rs(R.string.inches, inches), color = ink, style = TpType.headline, maxLines = 1, softWrap = false, modifier = Modifier.probe("size_value"))
                val width = screen?.let { Units.shortLength(it.width, state.unit) } ?: ""
                Text(text = (if (atMax) rs(R.string.max_size) + " · " else "") + rs(R.string.width, width), color = grey, style = TpType.label, maxLines = 1, softWrap = false)
            }
            Key(name = "size_plus", icon = R.drawable.ic_plus, description = rs(R.string.action_plus), enabled = enabled && !atMax) { onEvent(PlannerEvent.SizeStepped(1)) }
        }
        StatusRow(lines = if (state.inLayout) state.screenStatusLines() else emptyList(), unit = state.unit)
        // recommendation row
        Row(modifier = Modifier.fillMaxWidth().height(RowH), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            val text =
                when {
                    advice == null -> ""
                    advice.allOver && state.mode == ScreenMode.TV -> rs(R.string.tv_capped)
                    advice.allOver -> rs(R.string.max_size)
                    else -> rs(R.string.recommend, advice.lo, advice.hi)
                }
            Text(text = text, color = ink, style = TpType.label, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f).probe("recommend"))
            val canUse = advice != null && inches != advice.use
            val useInk = if (canUse && enabled) TpColors.TapeInk else PicoTheme.colorScheme.labelQuaternary
            Button(
                onClick = { onEvent(PlannerEvent.UseRecommended) },
                modifier = Modifier.probe("use_recommended"),
                size = ButtonDefaults.Regular.copy(height = 40.dp, minWidth = 96.dp),
                shape = CircleShape,
                enabled = canUse && enabled,
                colors = ButtonDefaults.buttonColors(containerColor = if (canUse && enabled) PicoTheme.colorScheme.interaction else PicoTheme.colorScheme.fillTertiary, contentColor = useInk),
            ) { Text(text = rs(R.string.use_recommended), color = useInk, maxLines = 1) }
        }
        if (state.mode == ScreenMode.PROJECTOR) {
            val p = state.projector
            Row(modifier = Modifier.fillMaxWidth().height(RowH), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(text = rs(R.string.ratio), color = ink, style = TpType.label, maxLines = 1)
                Key(name = "ratio_minus", icon = R.drawable.ic_minus, description = rs(R.string.action_minus), enabled = enabled && state.ratio > ProjectorRules.RATIO_MIN + 0.001f, width = 44.dp) { onEvent(PlannerEvent.RatioStepped(-1)) }
                Text(text = String.format(Locale.US, "%.2f", state.ratio), color = ink, style = TpType.title, maxLines = 1, softWrap = false, modifier = Modifier.probe("ratio_value"))
                Key(name = "ratio_plus", icon = R.drawable.ic_plus, description = rs(R.string.action_plus), enabled = enabled && state.ratio < ProjectorRules.RATIO_MAX - 0.001f, width = 44.dp) { onEvent(PlannerEvent.RatioStepped(1)) }
                Spacer(modifier = Modifier.weight(1f))
                val tail =
                    when {
                        p == null -> ""
                        p.overhead -> rs(R.string.overhead)
                        p.noRearWall -> rs(R.string.no_rear_wall)
                        else -> rs(R.string.throw_distance, Units.distance(p.throwM, state.unit))
                    }
                Text(text = tail, color = grey, style = TpType.label, maxLines = 1, softWrap = false, modifier = Modifier.probe("throw"))
            }
            Text(text = rs(R.string.ratio_hint), color = grey, style = TpType.label, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(start = 4.dp))
        }
    }
}

/** The status row (design `panels.console.speakerPage`): a 40 dp round icon in the status colour and up to two lines. */
@Composable
private fun StatusRow(lines: List<StatusLine>, unit: io.embodit.theaterplanner.domain.LengthUnit) {
    val first = lines.firstOrNull()
    Row(modifier = Modifier.fillMaxWidth().height(56.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        val color = if (first == null) PicoTheme.colorScheme.fillTertiary else TpColors.of(first.status)
        Box(modifier = Modifier.size(40.dp).clip(CircleShape).background(color), contentAlignment = Alignment.Center) {
            if (first != null) {
                val icon = when (first.status) { Status.GREEN -> R.drawable.ic_check; Status.ORANGE -> R.drawable.ic_warning; Status.RED -> R.drawable.ic_cross }
                Icon(painter = painterResource(icon), contentDescription = null, modifier = Modifier.size(22.dp), tint = TpColors.TapeInk)
            }
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(text = first?.let { statusText(it, unit) } ?: "", color = PicoTheme.colorScheme.labelPrimary, style = TpType.title, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.probe("status_1"))
            val second = lines.getOrNull(1)
            if (second != null) Text(text = statusText(second, unit), color = PicoTheme.colorScheme.labelSecondary, style = TpType.label, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.probe("status_2"))
        }
    }
}

@Composable
internal fun statusText(line: StatusLine, unit: io.embodit.theaterplanner.domain.LengthUnit): String =
    when (line.kind) {
        StatusKind.SOME_OUT -> rs(R.string.status_some_out, line.n)
        StatusKind.TOO_CLOSE -> rs(R.string.status_too_close, line.n)
        StatusKind.NO_REAR -> rs(R.string.status_no_rear)
        StatusKind.ASSUMED_CEILING -> rs(R.string.status_assumed)
        StatusKind.ALL_IN -> rs(R.string.status_all_in, line.n)
        StatusKind.TOO_FAR -> rs(R.string.status_too_far, Units.distance(line.lack, unit))
        StatusKind.VIEW_SMALL -> rs(R.string.status_view_small, line.deg)
        StatusKind.VIEW_LARGE -> rs(R.string.status_view_large, line.deg)
        StatusKind.VIEW_OK -> rs(R.string.status_view_ok, line.deg)
    }

@Composable
private fun Key(name: String, icon: Int, description: String, enabled: Boolean, width: androidx.compose.ui.unit.Dp = KeyW, onClick: () -> Unit) {
    val ink = if (enabled) PicoTheme.colorScheme.labelPrimary else PicoTheme.colorScheme.labelQuaternary
    IconButton(
        onClick = onClick,
        modifier = Modifier.probe(name),
        enabled = enabled,
        colors = IconButtonDefaults.iconButtonColors(containerColor = PicoTheme.colorScheme.fillTertiary, contentColor = ink),
        size = IconButtonDefaults.iconButtonSize(width = width, height = RowH),
    ) { Icon(painter = painterResource(icon), contentDescription = description, modifier = Modifier.size(24.dp), tint = ink) }
}

@Composable
internal fun <T> Segments(items: List<T>, selected: T, width: androidx.compose.ui.unit.Dp, enabled: Boolean, name: (T) -> String, onSelect: (T) -> Unit, title: @Composable (T) -> Unit) {
    // the control keeps about 4 dp of its own padding on each side (Furniture Fit's menu measurement)
    val itemW = (width - 8.dp) / items.size
    SegmentControl(modifier = Modifier.width(width).height(RowH), backgroundColor = PicoTheme.colorScheme.fillTertiary) {
        items.forEach { item ->
            SegmentItem(
                selected = item == selected,
                onClick = { if (enabled) onSelect(item) },
                modifier = Modifier.width(itemW).fillMaxHeight().probe(name(item)),
                textStyle = PicoTheme.typography.labelLarge,
                colors =
                    SegmentControlDefaults.colors(
                        itemBackgroundColor = Color.Transparent,
                        itemContentColor = PicoTheme.colorScheme.labelPrimary,
                        selectedItemBackgroundColor = PicoTheme.colorScheme.fillSecondary,
                        selectedItemContentColor = PicoTheme.colorScheme.labelPrimary,
                    ),
                title = { title(item) },
            )
        }
    }
}
