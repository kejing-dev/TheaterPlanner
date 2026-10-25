package io.embodit.theaterplanner.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.pico.spatial.ui.design.PicoTheme
import io.embodit.theaterplanner.domain.Status

/**
 * The design's exact colours (design-package `palette`). This is the only file with colour literals;
 * the four semantic roles are injected into `PicoTheme` by [TheaterPlannerTheme], the brand tokens are
 * used directly (3D materials, the solid dark faces of plates and tags).
 */
object TpColors {
    // ---- ColorScheme roles with a fixed design value (the other twelve inherit SpatialUI Vibrant)
    val Error = Color(0xFFFF5F52) // design-style: fixed-figma-color error: out of range, rear wall too close
    val Alert = Color(0xFFFF9F2E) // design-style: fixed-figma-color alert: too close, view small / large, assumed ceiling
    val Passable = Color(0xFF5BD68E) // design-style: fixed-figma-color passable: in range, bands, zones
    val Interaction = Color(0xFFFFB547) // design-style: fixed-figma-color interaction: theater amber (selected, ear point, light cone)

    // ---- brand tokens outside ColorScheme
    val Velvet = Color(0xFF6E1F33) // design-style: fixed-figma-color velvet: app icon background
    val SpeakerBody = Color(0xFF2A2C31) // design-style: fixed-figma-color speakerBody
    val ReflectBlue = Color(0xFF7FB2FF) // design-style: fixed-figma-color reflectBlue: absorber frames
    val ScreenBlack = Color(0xFF0B0B0D) // design-style: fixed-figma-color screenBlack (85 %)
    val PlateInk = Color(0xF016181C) // design-style: fixed-figma-color plateInk: speaker number plates, readout
    val White = Color(0xFFFFFFFF) // design-style: fixed-figma-color labelPrimaryLight
    val OnInkFill = Color(0x24FFFFFF) // design-style: fixed-figma-color onInkFill white 14 %
    val OnInkSecondary = Color(0xA8FFFFFF) // design-style: fixed-figma-color onInkSecondary white 66 %
    val TapeInk = Color(0xFF16181C) // design-style: fixed-figma-color tapeInk: content on the semantic fills
    val StaleGrey = Color(0xFF9A9A96) // design-style: fixed-figma-color staleGrey: tracking lost
    val ProjectorGrey = Color(0xFFD7D9DE) // design-style: fixed-figma-color projector housing
    val Hanger = Color(0xFF8A9099) // design-style: fixed-figma-color projector hanger

    @Composable
    fun of(status: Status): Color =
        when (status) {
            Status.GREEN -> PicoTheme.colorScheme.passable
            Status.ORANGE -> PicoTheme.colorScheme.alert
            Status.RED -> PicoTheme.colorScheme.error
        }
}

/** Type roles of the design (`typography.roles`): tabular figures everywhere a number is shown. */
object TpType {
    val headline: TextStyle
        @Composable get() = PicoTheme.typography.headlineSmall.copy(fontSize = 24.sp, lineHeight = 30.sp, fontWeight = FontWeight.SemiBold, fontFeatureSettings = "tnum, lnum")

    val title: TextStyle
        @Composable get() = PicoTheme.typography.titleMedium.copy(fontSize = 16.sp, lineHeight = 20.sp, fontWeight = FontWeight.SemiBold, fontFeatureSettings = "tnum, lnum")

    val label: TextStyle
        @Composable get() = PicoTheme.typography.labelLarge.copy(fontSize = 14.sp, lineHeight = 18.sp, fontWeight = FontWeight.SemiBold, fontFeatureSettings = "tnum, lnum")

    val body: TextStyle
        @Composable get() = PicoTheme.typography.bodyLargeMultiline.copy(fontSize = 16.sp, lineHeight = 24.sp, fontWeight = FontWeight.Medium)

    val readout: TextStyle
        @Composable get() = PicoTheme.typography.headlineLarge.copy(fontSize = 32.sp, lineHeight = 38.sp, fontWeight = FontWeight.SemiBold, fontFeatureSettings = "tnum, lnum")

    val dialogTitle: TextStyle
        @Composable get() = PicoTheme.typography.headlineLarge.copy(fontSize = 24.sp, lineHeight = 30.sp, fontWeight = FontWeight.SemiBold)

    val seatTitle: TextStyle
        @Composable get() = PicoTheme.typography.titleMedium.copy(fontSize = 18.sp, lineHeight = 24.sp, fontWeight = FontWeight.SemiBold)
}
