package io.embodit.theaterplanner.domain

import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.atan
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

/**
 * Content placed from the head of this moment (taste profile D9): its position and level heading (yaw)
 * only, never pitch or roll, never the world origin or an older pose.
 */
object HeadFrame {
    /** The point [dist] metres from the head, [rightDeg] right of the level heading and [upDeg] above the horizon. */
    fun at(head: HeadPose, rightDeg: Float, upDeg: Float, dist: Float): Vec3 {
        val d = head.level().turned(rightDeg)
        val u = Math.toRadians(upDeg.coerceIn(-80f, 80f).toDouble()).toFloat()
        return head.position + d.at(0f) * (dist * cos(u)) + Vec3(0f, dist * sin(u), 0f)
    }

    /** How a panel of [halfW] x [halfH] metres at [at] is seen from the head: angles from the level heading, degrees. */
    fun view(head: HeadPose, at: Vec3, halfW: Float, halfH: Float): ViewBox {
        val v = at - head.position
        val f = head.level()
        val flat = P2(v.x, v.z)
        val yaw = Math.toDegrees(atan2(flat.dot(f.right).toDouble(), flat.dot(f).toDouble())).toFloat()
        val dist = v.length.coerceAtLeast(0.05f)
        val pitch = Math.toDegrees(asin((v.y / dist).coerceIn(-1f, 1f).toDouble())).toFloat()
        return ViewBox(yaw, pitch, halfAngle(halfW, dist), halfAngle(halfH, dist))
    }

    fun halfAngle(half: Float, dist: Float): Float = Math.toDegrees(atan((half / dist.coerceAtLeast(0.05f)).toDouble())).toFloat()
}

/** A panel as seen from the head: centre yaw (right +) and pitch (up +), half extents, degrees. */
data class ViewBox(val yaw: Float, val pitch: Float, val halfYaw: Float, val halfPitch: Float) {
    fun overlaps(o: ViewBox, margin: Float = 0f): Boolean =
        abs(wrapDeg(yaw - o.yaw)) < halfYaw + o.halfYaw + margin && abs(pitch - o.pitch) < halfPitch + o.halfPitch + margin
}

/**
 * Pop-up panels never hide behind resident ones (taste profile D8): the wanted box is moved the least
 * angle that clears every obstacle, past an obstacle's left or right edge or above it (below only as a
 * last resort), staying within a comfortable view.
 */
object PanelLayout {
    const val MARGIN_DEG = 3f
    const val MAX_YAW_DEG = 40f
    const val MIN_PITCH_DEG = -40f
    const val MAX_PITCH_DEG = 20f

    fun clear(want: ViewBox, obstacles: List<ViewBox>, margin: Float = MARGIN_DEG): ViewBox {
        if (obstacles.none { want.overlaps(it, margin) }) return want
        val candidates = ArrayList<ViewBox>()
        for (o in obstacles) {
            val dy = want.halfYaw + o.halfYaw + margin
            val dp = want.halfPitch + o.halfPitch + margin
            candidates += want.copy(yaw = o.yaw - dy)
            candidates += want.copy(yaw = o.yaw + dy)
            candidates += want.copy(pitch = o.pitch + dp)
            candidates += want.copy(pitch = o.pitch - dp)
        }
        fun cost(b: ViewBox): Float = abs(wrapDeg(b.yaw - want.yaw)) + abs(b.pitch - want.pitch) * (if (b.pitch < want.pitch) 1.5f else 1f)
        return candidates
            .filter { b -> abs(b.yaw) <= MAX_YAW_DEG && b.pitch in MIN_PITCH_DEG..MAX_PITCH_DEG && obstacles.none { b.overlaps(it, margin * 0.99f) } }
            .minByOrNull { cost(it) }
            ?: want.copy(pitch = (obstacles.maxOf { it.pitch + it.halfPitch } + want.halfPitch + margin).coerceAtMost(MAX_PITCH_DEG + 15f))
    }
}
