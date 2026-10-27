package io.embodit.theaterplanner.ui.planner.components

import android.content.Context
import android.content.res.Configuration
import android.content.res.Resources
import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import io.embodit.theaterplanner.R
import io.embodit.theaterplanner.domain.LanguagePref
import io.embodit.theaterplanner.domain.MoveDir
import io.embodit.theaterplanner.domain.SpeakerDef
import java.util.Locale

/**
 * Copy for the language chosen in the app (follow system / 中文 / EN), switched at run time without
 * restarting the Stage: the screen provides a Resources object for that locale and every panel reads
 * its strings from it (Spatial Ruler). `LocalContext` is not overridden.
 */
val LocalTpResources = staticCompositionLocalOf<Resources> { error("LocalTpResources not provided") }

fun localizedResources(context: Context, language: LanguagePref): Resources {
    val tag =
        when (language) {
            LanguagePref.SYSTEM -> return context.resources
            LanguagePref.ZH -> "zh-CN"
            LanguagePref.EN -> "en"
        }
    val configuration = Configuration(context.resources.configuration)
    configuration.setLocale(Locale.forLanguageTag(tag))
    return context.createConfigurationContext(configuration).resources
}

@Composable
@ReadOnlyComposable
fun rs(@StringRes id: Int): String = LocalTpResources.current.getString(id)

@Composable
@ReadOnlyComposable
fun rs(@StringRes id: Int, vararg args: Any): String = LocalTpResources.current.getString(id, *args)

@Composable
@ReadOnlyComposable
fun isChinese(): Boolean = LocalTpResources.current.getString(R.string.lang_code) == "zh"

/** A speaker's name in the current language. */
@Composable
@ReadOnlyComposable
fun speakerName(def: SpeakerDef): String = if (isChinese()) def.zh else def.en

@Composable
@ReadOnlyComposable
fun moveDirWord(dir: MoveDir): String =
    rs(
        when (dir) {
            MoveDir.LEFT -> R.string.dir_left
            MoveDir.RIGHT -> R.string.dir_right
            MoveDir.FORWARD -> R.string.dir_forward
            MoveDir.BACK -> R.string.dir_back
            MoveDir.CENTER -> R.string.dir_center
            MoveDir.OUTWARD -> R.string.dir_outward
        }
    )

/** Panel sizes (design-package `panels[].sizeDp`), in dp. */
object PanelSizes {
    const val CONSOLE_W = 400f
    const val CONSOLE_H = 400f
    const val STRIP_W = 320f
    const val STRIP_H = 112f
    const val READOUT_W = 200f
    const val READOUT_H = 48f
    const val SEAT_W = 360f
    const val SEAT_H = 184f
    const val MENU_W = 300f
    const val MENU_H = 232f

    /** The menu's pool panel: big enough for the room page (S28); the main page sits in its lower right corner. */
    const val MENU_PANEL_W = 400f
    const val MENU_PANEL_H = 420f
    const val ROOM_PAGE_H = 420f
    const val DIALOG_W = 480f
    const val DIALOG_H = 240f
    const val SNACK_W = 560f
    const val SNACK_H = 72f
    const val COACH_W = 360f
    const val COACH_H = 80f
    const val TAG_W = 72f
    const val TAG_H = 36f
    const val SIZE_TAG_W = 96f
    const val SIZE_TAG_H = 40f
    const val ABS_TAG_W = 48f
    const val ABS_TAG_H = 32f

    /** Disabled content (SpatialUI's own disabled alpha). */
    const val DISABLED_ALPHA = 0.38f
}
