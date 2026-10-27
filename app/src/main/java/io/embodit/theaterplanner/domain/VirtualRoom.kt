package io.embodit.theaterplanner.domain

import java.util.Locale

/** Where the room geometry comes from (taste profile D7): plane detection, or the adjustable virtual box. */
enum class RoomSource { NONE, SCAN, VIRTUAL }

/**
 * The fallback room (taste profile D7): a box of [width] x [length] x [height] metres standing on the
 * floor at [floorY], its floor centre at [center], its front wall ahead along [forward]. It is turned
 * into the same planes plane detection would give (FLOOR, four WALLs, CEILING), so every rule of the app
 * works on it unchanged. The Stage's y = 0 is the physical ground (Stage coordinate space), hence the
 * default floor at 0.
 */
data class VirtualRoom(
    val center: P2,
    val yawDeg: Float,
    val width: Float = DEFAULT_W,
    val length: Float = DEFAULT_L,
    val height: Float = DEFAULT_H,
    val floorY: Float = 0f,
) {
    /** Unit direction towards the front wall (yaw 0 = Stage -Z). */
    val forward: P2
        get() = P2(0f, -1f).turned(yawDeg)

    val right: P2
        get() = forward.right

    val ceilingY: Float
        get() = floorY + height

    val planes: List<PlaneSurface> by lazy {
        val f = forward * (length / 2f)
        val r = right * (width / 2f)
        val fl = center + f - r
        val fr = center + f + r
        val br = center - f + r
        val bl = center - f - r
        fun wall(id: String, a: P2, b: P2) = PlaneSurface(id, "WALL", listOf(a.at(floorY), b.at(floorY), b.at(ceilingY), a.at(ceilingY)))
        listOf(
            PlaneSurface(ID_FLOOR, "FLOOR", listOf(fl.at(floorY), fr.at(floorY), br.at(floorY), bl.at(floorY))),
            PlaneSurface(ID_CEILING, "CEILING", listOf(fl.at(ceilingY), fr.at(ceilingY), br.at(ceilingY), bl.at(ceilingY))),
            wall(ID_FRONT, fl, fr),
            wall(ID_RIGHT, fr, br),
            wall(ID_BACK, br, bl),
            wall(ID_LEFT, bl, fl),
        )
    }

    val model: RoomModel by lazy { RoomModel(planes) }

    /** The floor corners in order front-left, front-right, back-right, back-left. */
    fun corners(): List<P2> {
        val f = forward * (length / 2f)
        val r = right * (width / 2f)
        return listOf(center + f - r, center + f + r, center - f + r, center - f - r)
    }

    /** Width grows to both sides, length grows backwards (the front wall with the screen stays), height upwards. */
    fun resized(dw: Float = 0f, dl: Float = 0f, dh: Float = 0f): VirtualRoom {
        val l = (length + dl).coerceIn(MIN_L, MAX_L).round2()
        return copy(
            center = center - forward * ((l - length) / 2f),
            width = (width + dw).coerceIn(MIN_W, MAX_W).round2(),
            length = l,
            height = (height + dh).coerceIn(MIN_H, MAX_H).round2(),
        )
    }

    /** Moved by [right] / [ahead] metres along [frame] (the viewer's level heading) and turned by [turnDeg] about its centre. */
    fun moved(frame: P2, right: Float, ahead: Float, turnDeg: Float = 0f): VirtualRoom = copy(center = center + frame.right * right + frame * ahead, yawDeg = wrapDeg(yawDeg + turnDeg))

    val label: String
        get() = String.format(Locale.US, "%.1f x %.1f x %.2f", width, length, height)

    private fun Float.round2(): Float = Math.round(this * 100f) / 100f

    companion object {
        const val DEFAULT_W = 4.0f
        const val DEFAULT_L = 5.0f
        const val DEFAULT_H = 2.7f
        const val MIN_W = 2.0f
        const val MAX_W = 12.0f
        const val MIN_L = 2.0f
        const val MAX_L = 15.0f
        const val MIN_H = 2.0f
        const val MAX_H = 6.0f
        const val SIZE_STEP_M = 0.1f
        const val MOVE_STEP_M = 0.1f
        const val TURN_STEP_DEG = 2f

        /** The seat sits this share of the length behind the front wall (a living-room sofa). */
        const val SEAT_FROM_FRONT = 0.6f
        const val MIN_BEHIND_SEAT_M = 0.6f

        const val ID_FLOOR = "vr-floor"
        const val ID_CEILING = "vr-ceiling"
        const val ID_FRONT = "vr-front"
        const val ID_RIGHT = "vr-right"
        const val ID_BACK = "vr-back"
        const val ID_LEFT = "vr-left"

        /** The box around a viewer at [at] looking along [facing]: centred across, front wall ahead, sofa 60 % back. */
        fun around(at: P2, facing: P2, width: Float = DEFAULT_W, length: Float = DEFAULT_L, height: Float = DEFAULT_H, floorY: Float = 0f): VirtualRoom {
            val f = facing.normalized().let { if (it.length < 0.5f) P2(0f, -1f) else it }
            val ahead = (length * SEAT_FROM_FRONT).coerceAtMost(length - MIN_BEHIND_SEAT_M)
            val center = at + f * (ahead - length / 2f)
            val yaw = Math.toDegrees(kotlin.math.atan2(f.x.toDouble(), -f.z.toDouble())).toFloat()
            return VirtualRoom(center, yaw, width, length, height, floorY)
        }
    }
}
