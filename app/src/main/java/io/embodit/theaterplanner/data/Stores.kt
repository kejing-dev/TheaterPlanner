package io.embodit.theaterplanner.data

import android.content.Context
import io.embodit.theaterplanner.domain.AnchorPose
import io.embodit.theaterplanner.domain.LanguagePref
import io.embodit.theaterplanner.domain.LayoutCodec
import io.embodit.theaterplanner.domain.LengthUnit
import io.embodit.theaterplanner.domain.SavedLayout
import io.embodit.theaterplanner.domain.Settings
import io.embodit.theaterplanner.domain.Vec3
import java.util.Locale

/** A world anchor as the app sees it. [id] is the platform's UUID as a string. */
data class AnchorRecord(val id: String, val pose: AnchorPose)

sealed interface AnchorOutcome<out T> {
    data class Ok<T>(val value: T) : AnchorOutcome<T>

    data class Failed(val code: Int, val message: String) : AnchorOutcome<Nothing>
}

/** The platform's persistent anchors (`platform.anchor.WorldAnchorGateway`; a fake in tests). */
interface AnchorGateway {
    suspend fun create(position: Vec3, name: String): AnchorOutcome<AnchorRecord>

    /** All anchors of this app in this space; an empty store is `Ok(emptyList())`. */
    suspend fun loadAll(): AnchorOutcome<List<AnchorRecord>>

    suspend fun remove(anchorId: String): AnchorOutcome<Unit>
}

/** Layout and settings on the device (no network, no account). */
interface LayoutStore {
    fun loadLayout(): SavedLayout?

    fun saveLayout(layout: SavedLayout?)

    fun loadSettings(): Settings

    fun saveSettings(settings: Settings)
}

class PrefsLayoutStore(context: Context) : LayoutStore {
    private val prefs = context.getSharedPreferences("theater_planner", Context.MODE_PRIVATE)

    /** Debug: the next write fails (design S27). */
    @Volatile var failNextSave = false

    override fun loadLayout(): SavedLayout? = LayoutCodec.decode(prefs.getString(KEY_LAYOUT, null))

    override fun saveLayout(layout: SavedLayout?) {
        if (failNextSave) {
            failNextSave = false
            throw IllegalStateException("debug save_fail")
        }
        prefs.edit().apply { if (layout == null) remove(KEY_LAYOUT) else putString(KEY_LAYOUT, LayoutCodec.encode(layout)) }.commit()
    }

    override fun loadSettings(): Settings =
        Settings(
            language = LanguagePref.entries.firstOrNull { it.tag == prefs.getString("lang", null) } ?: LanguagePref.SYSTEM,
            unit = LengthUnit.entries.firstOrNull { it.tag == prefs.getString("unit", null) } ?: LengthUnit.defaultFor(Locale.getDefault().country),
            coachSeen = prefs.getBoolean("coach", false),
            rearCoachSeen = prefs.getBoolean("rearCoach", false),
        )

    override fun saveSettings(settings: Settings) {
        prefs.edit()
            .putString("lang", settings.language.tag)
            .putString("unit", settings.unit.tag)
            .putBoolean("coach", settings.coachSeen)
            .putBoolean("rearCoach", settings.rearCoachSeen)
            .apply()
    }

    private companion object {
        const val KEY_LAYOUT = "layout"
    }
}
