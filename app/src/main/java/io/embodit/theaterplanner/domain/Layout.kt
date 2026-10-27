package io.embodit.theaterplanner.domain

import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

enum class LanguagePref(val tag: String) {
    SYSTEM("system"),
    ZH("zh"),
    EN("en");
}

enum class LengthUnit(val tag: String) {
    M("m"),
    FT("ft");

    companion object {
        fun defaultFor(country: String): LengthUnit = if (country.uppercase(Locale.US) == "US") FT else M
    }
}

/** Preferences kept apart from the layout (design `persistence.what`). */
data class Settings(
    val language: LanguagePref = LanguagePref.SYSTEM,
    val unit: LengthUnit = LengthUnit.M,
    val coachSeen: Boolean = false,
    val rearCoachSeen: Boolean = false,
)

/** A world anchor's pose as the app sees it. */
data class AnchorPose(val position: Vec3, val rotation: Quat4) {
    fun toLocal(world: Vec3): Vec3 = rotation.conjugate().rotate(world - position)

    fun toWorld(local: Vec3): Vec3 = rotation.rotate(local) + position

    fun toLocalDir(d: P2): P2 {
        val v = rotation.conjugate().rotate(d.at(0f))
        return P2(v.x, v.z).normalized()
    }

    fun toWorldDir(d: P2): P2 {
        val v = rotation.rotate(d.at(0f))
        return P2(v.x, v.z).normalized()
    }
}

/**
 * Everything saved relative to the room anchor (design `persistence`): the seat (ear point, floor
 * height), the front and the screen centre, the configuration and page, every speaker that has ever
 * appeared (its position), the screen settings and the absorber switch.
 */
data class SavedLayout(
    val anchorId: String,
    val ear: Vec3,
    val floorY: Float,
    val f: P2,
    val screenCenter: P2,
    val screenNormal: P2,
    val screenFloating: Boolean,
    val config: Config,
    val screenPage: Boolean,
    val speakers: Map<String, Vec3>,
    val inches: Int?,
    val mode: ScreenMode,
    val ratio: Float,
    val absorbers: Boolean,
    /** The virtual room the layout was made in (taste profile D7); null when the scanned room was used. */
    val room: SavedRoom? = null,
)

/** A virtual room relative to the anchor: floor centre, forward direction, size, floor height. */
data class SavedRoom(val center: P2, val forward: P2, val width: Float, val length: Float, val height: Float, val floorY: Float)

object LayoutCodec {
    fun encode(l: SavedLayout): String {
        val o = JSONObject()
        o.put("anchor", l.anchorId)
        o.put("ear", vec(l.ear))
        o.put("floorY", l.floorY.toDouble())
        o.put("f", p2(l.f))
        o.put("sc", p2(l.screenCenter))
        o.put("sn", p2(l.screenNormal))
        o.put("floating", l.screenFloating)
        o.put("cfg", l.config.tag)
        o.put("screenPage", l.screenPage)
        val sp = JSONObject()
        for ((id, v) in l.speakers) sp.put(id, vec(v))
        o.put("speakers", sp)
        if (l.inches != null) o.put("inches", l.inches)
        o.put("mode", l.mode.tag)
        o.put("ratio", l.ratio.toDouble())
        o.put("absorbers", l.absorbers)
        l.room?.let { r ->
            val ro = JSONObject()
            ro.put("c", p2(r.center))
            ro.put("f", p2(r.forward))
            ro.put("w", r.width.toDouble())
            ro.put("l", r.length.toDouble())
            ro.put("h", r.height.toDouble())
            ro.put("floorY", r.floorY.toDouble())
            o.put("room", ro)
        }
        return o.toString()
    }

    fun decode(text: String?): SavedLayout? {
        if (text.isNullOrBlank()) return null
        return runCatching {
                val o = JSONObject(text)
                val sp = o.getJSONObject("speakers")
                val speakers = LinkedHashMap<String, Vec3>()
                for (k in sp.keys()) speakers[k] = vec(sp.getJSONArray(k))
                SavedLayout(
                    anchorId = o.getString("anchor"),
                    ear = vec(o.getJSONArray("ear")),
                    floorY = o.getDouble("floorY").toFloat(),
                    f = p2(o.getJSONArray("f")),
                    screenCenter = p2(o.getJSONArray("sc")),
                    screenNormal = p2(o.getJSONArray("sn")),
                    screenFloating = o.optBoolean("floating", false),
                    config = Config.byTag(o.optString("cfg", "5.1")) ?: Config.C51,
                    screenPage = o.optBoolean("screenPage", false),
                    speakers = speakers,
                    inches = if (o.has("inches")) o.getInt("inches") else null,
                    mode = ScreenMode.byTag(o.optString("mode", "tv")),
                    ratio = o.optDouble("ratio", ProjectorRules.RATIO_DEFAULT.toDouble()).toFloat(),
                    absorbers = o.optBoolean("absorbers", false),
                    room =
                        o.optJSONObject("room")?.let { ro ->
                            SavedRoom(p2(ro.getJSONArray("c")), p2(ro.getJSONArray("f")), ro.getDouble("w").toFloat(), ro.getDouble("l").toFloat(), ro.getDouble("h").toFloat(), ro.getDouble("floorY").toFloat())
                        },
                )
            }
            .getOrNull()
    }

    private fun vec(v: Vec3) = JSONArray().put(v.x.toDouble()).put(v.y.toDouble()).put(v.z.toDouble())

    private fun vec(a: JSONArray) = Vec3(a.getDouble(0).toFloat(), a.getDouble(1).toFloat(), a.getDouble(2).toFloat())

    private fun p2(v: P2) = JSONArray().put(v.x.toDouble()).put(v.z.toDouble())

    private fun p2(a: JSONArray) = P2(a.getDouble(0).toFloat(), a.getDouble(1).toFloat())
}

/** Units for the panels (design `rules.units`): m / ft for distances; inches for screens; cm / in for moves. */
object Units {
    fun distance(m: Float, unit: LengthUnit): String =
        if (unit == LengthUnit.M) String.format(Locale.US, "%.2f m", m) else String.format(Locale.US, "%.1f ft", m / 0.3048f)

    fun shortLength(m: Float, unit: LengthUnit): String =
        if (unit == LengthUnit.M) String.format(Locale.US, "%d cm", Math.round(m * 100f)) else String.format(Locale.US, "%d in", Math.round(m / 0.0254f))

    fun cm(cm: Int, unit: LengthUnit): String =
        if (unit == LengthUnit.M) "$cm cm" else "${Math.round(cm / 2.54f)} in"
}
