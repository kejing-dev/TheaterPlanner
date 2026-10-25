package io.embodit.theaterplanner.domain

import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.atan2

/**
 * The listening position and the front (design `rules.seat` / `rules.front`): the ear point P (the
 * head at the pinch), the forward unit direction f = P -> screen centre (horizontal), the screen's
 * centre on the front wall (or floating 2.5 m ahead) and that wall's normal towards P.
 */
data class Seat(
    /** Ear point: the head position at the pinch. */
    val ear: Vec3,
    /** Floor height under the seat. */
    val floorY: Float,
    /** Horizontal forward unit vector (P -> screen centre). */
    val f: P2,
    /** Screen centre on the wall (horizontal position; the height follows the screen rules). */
    val screenCenter: P2,
    /** Unit normal of the screen wall towards P (for a floating screen: -f). */
    val screenNormal: P2,
    val screenWall: PlaneSurface?,
    /** No wall ahead: the screen floats 2.5 m in front of P, facing P. */
    val screenFloating: Boolean,
) {
    val earHeight: Float
        get() = ear.y - floorY

    val right: P2
        get() = f.right

    /** Horizontal unit direction at [azimuthDeg] (right +) from the front. */
    fun dir(azimuthDeg: Float): P2 = f.turned(azimuthDeg)

    /** Signed horizontal angle of [point] as seen from the seat, right +, degrees. */
    fun azimuthOf(point: P2): Float {
        val v = point - P2.of(ear)
        return Math.toDegrees(atan2(v.dot(right).toDouble(), v.dot(f).toDouble())).toFloat()
    }

    /** Forward offset (along f) and lateral offset (along right) of a point. */
    fun forwardOf(point: P2): Float = (point - P2.of(ear)).dot(f)

    fun lateralOf(point: P2): Float = (point - P2.of(ear)).dot(right)

    /** Distance from P to the screen plane along the screen normal. */
    val screenDistance: Float
        get() = abs((P2.of(ear) - screenCenter).dot(screenNormal))

    companion object {
        /** Seat accepted at any height; above this the "standing?" snackbar shows (design S26). */
        const val STANDING_ABOVE_M = 1.45f
        const val TOO_LOW_BELOW_M = 0.8f
        const val FLOATING_SCREEN_M = 2.5f
        const val FRONT_WALL_MAX_DEG = 25f

        /**
         * First seating: pinch -> head position P, horizontal gaze g. A WALL hit along g whose normal
         * (flipped towards P) is within 25 degrees of -g puts the screen at the foot of the perpendicular
         * from P; a more oblique wall takes the gaze hit point; no wall floats the screen 2.5 m ahead.
         */
        fun first(head: Vec3, gaze: P2, room: RoomModel): Seat {
            val floorY = room.floorY ?: (head.y - 1.2f)
            val origin = Vec3(head.x, head.y, head.z)
            val hit = room.hitWall(origin, gaze.at(0f).normalized())
            val p = P2.of(head)
            if (hit == null) {
                val center = p + gaze * FLOATING_SCREEN_M
                return Seat(head, floorY, gaze, center, -gaze, null, true)
            }
            var n = P2(hit.normal.x, hit.normal.z).normalized()
            // flipped towards P
            if ((p - P2.of(hit.point)).dot(n) < 0f) n = -n
            val angle = Math.toDegrees(acos((n.dot(-gaze)).coerceIn(-1f, 1f).toDouble())).toFloat()
            val center =
                if (angle <= FRONT_WALL_MAX_DEG) {
                    // foot of the perpendicular from P onto the wall plane
                    val d = (p - P2.of(hit.point)).dot(n)
                    p - n * d
                } else P2.of(hit.point)
            val f = (center - p).normalized().let { if (it.length < 0.5f) gaze else it }
            return Seat(head, floorY, f, center, n, hit.plane, false)
        }

        /** Reseat (design `rules.reseat`): same screen centre, new P; f = new P -> screen centre. */
        fun reseat(previous: Seat, head: Vec3, room: RoomModel): Seat {
            val p = P2.of(head)
            val f = (previous.screenCenter - p).normalized().let { if (it.length < 0.5f) previous.f else it }
            return previous.copy(ear = head, floorY = room.floorY ?: previous.floorY, f = f)
        }
    }
}
