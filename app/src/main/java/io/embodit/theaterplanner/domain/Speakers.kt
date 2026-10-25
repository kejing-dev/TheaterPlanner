package io.embodit.theaterplanner.domain

/** Speaker configurations (design `port.keep`). */
enum class Config(val tag: String, val count: Int, val overhead: Int) {
    C51("5.1", 5, 0),
    C512("5.1.2", 7, 2),
    C714("7.1.4", 11, 4);

    companion object {
        fun byTag(tag: String): Config? = entries.firstOrNull { it.tag == tag }
    }
}

enum class SpeakerKind { LISTENER, OVERHEAD }

/**
 * One speaker of the angle table (design `rules.angleTable`, Dolby Atmos Home Theater Installation
 * Guidelines 2018-12). Listener-level speakers have an [azimuth] (right +) and a [range]; overhead
 * speakers an elevation [angle] measured from straight ahead in the side view, and a [side] (-1 left).
 */
data class SpeakerDef(
    val id: String,
    /** What the plates show (7.1.4's side surrounds are `Lss` / `Rss` inside, "Ls" / "Rs" on screen). */
    val label: String,
    val zh: String,
    val en: String,
    val kind: SpeakerKind,
    val azimuth: Float,
    val angle: Float,
    val range: ClosedFloatingPointRange<Float>,
    val side: Int,
    val configs: Set<Config>,
) {
    val isRear: Boolean
        get() = if (kind == SpeakerKind.LISTENER) kotlin.math.abs(azimuth) >= 125f else angle >= 125f

    companion object {
        private fun ll(id: String, zh: String, en: String, az: Float, lo: Float, hi: Float, vararg c: Config, label: String = id) =
            SpeakerDef(id, label, zh, en, SpeakerKind.LISTENER, az, 0f, lo..hi, if (az < 0f) -1 else if (az > 0f) 1 else 0, c.toSet())

        private fun oh(id: String, zh: String, en: String, side: Int, angle: Float, lo: Float, hi: Float, vararg c: Config) =
            SpeakerDef(id, id, zh, en, SpeakerKind.OVERHEAD, 0f, angle, lo..hi, side, c.toSet())

        val ALL: List<SpeakerDef> =
            listOf(
                ll("C", "中置", "Center", 0f, -5f, 5f, Config.C51, Config.C512, Config.C714),
                ll("L", "左", "Left", -26f, -30f, -22f, Config.C51, Config.C512, Config.C714),
                ll("R", "右", "Right", 26f, 22f, 30f, Config.C51, Config.C512, Config.C714),
                ll("Ls", "左环绕", "Surround L", -115f, -120f, -110f, Config.C51, Config.C512),
                ll("Rs", "右环绕", "Surround R", 115f, 110f, 120f, Config.C51, Config.C512),
                ll("Lss", "左侧环绕", "Side L", -100f, -110f, -90f, Config.C714, label = "Ls"),
                ll("Rss", "右侧环绕", "Side R", 100f, 90f, 110f, Config.C714, label = "Rs"),
                ll("Lrs", "左后环绕", "Rear L", -142f, -150f, -135f, Config.C714),
                ll("Rrs", "右后环绕", "Rear R", 142f, 135f, 150f, Config.C714),
                oh("Ltm", "左顶中", "Top Middle L", -1, 80f, 65f, 100f, Config.C512),
                oh("Rtm", "右顶中", "Top Middle R", 1, 80f, 65f, 100f, Config.C512),
                oh("Ltf", "左顶前", "Top Front L", -1, 45f, 30f, 55f, Config.C714),
                oh("Rtf", "右顶前", "Top Front R", 1, 45f, 30f, 55f, Config.C714),
                oh("Ltr", "左顶后", "Top Rear L", -1, 135f, 125f, 150f, Config.C714),
                oh("Rtr", "右顶后", "Top Rear R", 1, 135f, 125f, 150f, Config.C714),
            )

        fun byId(id: String): SpeakerDef? = ALL.firstOrNull { it.id == id }

        fun of(config: Config): List<SpeakerDef> = ALL.filter { config in it.configs }

        /** Judgement margin: half a degree (display rounds; a freshly placed speaker must not turn red). */
        const val TOLERANCE_DEG = 0.5f
    }
}

/** Traffic-light status of a speaker (design `rules.status`). */
enum class Status { GREEN, ORANGE, RED }

/** Where a recommended or dragged listener-level speaker lands: on a wall (retreated 0.12 m) or floating. */
data class Landing(val point: P2, val noWall: Boolean, val wallNormal: P2?, val plane: PlaneSurface?)

/** One speaker as evaluated against the seat (design `rules.placeListener` / `placeOverhead` / `status`). */
data class SpeakerState(
    val def: SpeakerDef,
    /** Centre of the speaker, Stage metres. */
    val position: Vec3,
    /** Azimuth (listener-level, right +) or elevation angle (overhead), degrees. */
    val value: Float,
    val inAngleRange: Boolean,
    /** Overhead only: the lateral offset is within the recommended band. */
    val latOk: Boolean,
    val tooClose: Boolean,
    val noWall: Boolean,
    val status: Status,
    /** Distance from the ear point to the speaker centre. */
    val distance: Float,
    /** Current lateral offset (overhead), metres, right +. */
    val latNow: Float,
    /** Recommended lateral offset (overhead), metres, right +. */
    val latRec: Float,
    /** Within 2 cm of the recommended position. */
    val atRecommended: Boolean,
    /** The recommended position for this speaker now. */
    val recommended: Vec3,
    /** The wall the speaker stands in front of (null when floating or overhead). */
    val plane: PlaneSurface?,
    /** Normal of that wall towards the room (listener-level); for overhead the ceiling's down normal. */
    val faceNormal: Vec3,
) {
    val id: String
        get() = def.id

    val isRange: Boolean
        get() = status == Status.GREEN
}

/** A move hint for a speaker that is out of range (design `rules.outOfRangeHint`). */
data class MoveHint(val dir: MoveDir, val cm: Int, val target: Vec3)

enum class MoveDir { LEFT, RIGHT, FORWARD, BACK, CENTER, OUTWARD }
