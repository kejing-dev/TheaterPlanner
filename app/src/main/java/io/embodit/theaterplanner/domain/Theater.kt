package io.embodit.theaterplanner.domain

import kotlin.math.abs
import kotlin.math.atan
import kotlin.math.atan2
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sign
import kotlin.math.sqrt
import kotlin.math.tan

/**
 * The placement rules of the design package (`rules.placeListener`, `placeOverhead`, `dragListener`,
 * `dragOverhead`, `status`, `outOfRangeHint`, `rearWall`), pure Kotlin. Every position is Stage
 * metres; every angle degrees.
 */
object Theater {
    /** A listener-level speaker stands this far in front of its wall (along the wall normal). */
    const val WALL_RETREAT_M = 0.12f

    /** Nearer than this to the ear point the speaker is "too close" (orange). */
    const val MIN_DISTANCE_M = 0.5f

    /** Overhead speakers stay this far inside the ceiling polygon. */
    const val CEILING_INSET_M = 0.2f

    /** Half width of the ceiling zone around the recommended lateral offset. */
    const val LAT_BAND_M = 0.3f

    /** Overhead lateral offset keeps this far from the side walls. */
    const val SIDE_WALL_MIN_M = 0.3f

    /** A rear wall nearer than this makes 7.1.4 suggest 5.1. */
    const val REAR_WALL_MIN_M = 0.6f

    /** Snapping (taste profile D): within this of the recommended azimuth / ceiling point the speaker snaps. */
    const val SNAP_DEG = 3f
    const val SNAP_M = 0.15f

    /** Thumbstick: dead zone and top turn rate (Furniture Fit v0.1.1). */
    const val STICK_DEADZONE = 0.15f
    const val STICK_MAX_DEG_S = 60f

    /** Default distance of a floating speaker when neither L nor R found a wall. */
    const val FLOATING_M = 2.5f

    /** The centre speaker: its middle this far below the screen's lower edge, never lower than this above the floor. */
    const val CENTER_BELOW_SCREEN_M = 0.20f
    const val CENTER_MIN_ABOVE_FLOOR_M = 0.30f

    // ---- listener-level placement -----------------------------------------------------------

    /**
     * Where a listener-level speaker at azimuth [az] lands: the nearest WALL hit of the horizontal ray
     * from P (<= 8 m), retreated 0.12 m along that wall's normal towards the room; or, without a wall,
     * floating at the average distance of L / R (design `placeListener`, R2-3).
     */
    fun land(room: RoomModel, seat: Seat, az: Float): Landing {
        val d = seat.dir(az)
        val origin = seat.ear
        val hit = room.hitWall(origin, d.at(0f).normalized())
        if (hit != null) {
            var n = P2(hit.normal.x, hit.normal.z).normalized()
            if (n.dot(P2.of(origin) - P2.of(hit.point)) < 0f) n = -n
            if (n.length < 0.5f) n = -d
            return Landing(P2.of(hit.point) + n * WALL_RETREAT_M, false, n, hit.plane)
        }
        val dist = lrDistance(room, seat)
        return Landing(P2.of(origin) + d * dist, true, null, null)
    }

    /** Average distance of L and R at their recommended azimuths (walls only); 2.5 m when neither has a wall. */
    fun lrDistance(room: RoomModel, seat: Seat): Float {
        val p = P2.of(seat.ear)
        val ds = ArrayList<Float>(2)
        for (az in floatArrayOf(-26f, 26f)) {
            val hit = room.hitWall(seat.ear, seat.dir(az).at(0f).normalized()) ?: continue
            ds += max(0.2f, (P2.of(hit.point) - p).length - WALL_RETREAT_M)
        }
        return if (ds.isEmpty()) FLOATING_M else ds.average().toFloat()
    }

    /** Height of a listener-level speaker: C below the screen's lower edge, the rest at ear height. */
    fun listenerY(def: SpeakerDef, seat: Seat, screenBottom: Float): Float =
        if (def.id == "C") max(seat.floorY + CENTER_MIN_ABOVE_FLOOR_M, screenBottom - CENTER_BELOW_SCREEN_M) else seat.ear.y

    fun recommendListener(room: RoomModel, seat: Seat, def: SpeakerDef, screenBottom: Float): Vec3 {
        val l = land(room, seat, def.azimuth)
        return l.point.at(listenerY(def, seat, screenBottom))
    }

    // ---- overhead placement ----------------------------------------------------------------

    /**
     * Recommended lateral offset of the overhead speakers: the average of L / R's lateral offsets at
     * their recommended positions, clamped to >= 0.3 m and to 0.3 m from the nearer side wall
     * (design `placeOverhead`, R2-6: refreshed only on seat / reseat / reset all).
     */
    fun overheadLat(room: RoomModel, seat: Seat): Float {
        val p = P2.of(seat.ear)
        val vals = floatArrayOf(-26f, 26f).map { az -> abs((land(room, seat, az).point - p).dot(seat.right)) }
        val left = room.hitWall(seat.ear, (-seat.right).at(0f).normalized())?.distance ?: 2f
        val right = room.hitWall(seat.ear, seat.right.at(0f).normalized())?.distance ?: 2f
        val limit = min(left, right) - SIDE_WALL_MIN_M
        return (vals.average().toFloat()).coerceIn(0.3f, max(0.3f, limit))
    }

    /** Recommended ceiling point of an overhead speaker (on the ceiling, 0.2 m inside its polygon). */
    fun recommendOverhead(room: RoomModel, seat: Seat, def: SpeakerDef, lat: Float): Vec3 {
        val ceilY = room.ceilingY()
        val forward = (ceilY - seat.ear.y) / tan(Math.toRadians(def.angle.toDouble())).toFloat()
        val xz = P2.of(seat.ear) + seat.right * (def.side * lat) + seat.f * forward
        return room.clampToCeiling(xz.at(ceilY), CEILING_INSET_M)
    }

    // ---- evaluation -------------------------------------------------------------------------

    /** The speaker at [position] judged against the seat (design `status`, `overheadStatus`). */
    fun evaluate(def: SpeakerDef, position: Vec3, seat: Seat, room: RoomModel, lat: Float, screenBottom: Float, noWall: Boolean, plane: PlaneSurface?, wallNormal: P2?): SpeakerState {
        val p = P2.of(seat.ear)
        val xz = P2.of(position)
        return if (def.kind == SpeakerKind.LISTENER) {
            val az = seat.azimuthOf(xz)
            val dist3 = position.distanceTo(seat.ear)
            val close = (xz - p).length < MIN_DISTANCE_M + 0.01f
            val ok = inRange(az, def.range)
            val status = if (!ok) Status.RED else if (close) Status.ORANGE else Status.GREEN
            val rec = recommendListener(room, seat, def, screenBottom)
            val n = wallNormal ?: (p - xz).normalized()
            SpeakerState(
                def = def,
                position = position,
                value = az,
                inAngleRange = ok,
                latOk = true,
                tooClose = close,
                noWall = noWall,
                status = status,
                distance = dist3,
                latNow = seat.lateralOf(xz),
                latRec = def.side * lat,
                atRecommended = (xz - P2.of(rec)).length < 0.02f,
                recommended = rec,
                plane = plane,
                faceNormal = n.at(0f),
            )
        } else {
            val dy = position.y - seat.ear.y
            val dz = seat.forwardOf(xz)
            val angle = Math.toDegrees(atan2(dy.toDouble(), dz.toDouble())).toFloat()
            val latNow = seat.lateralOf(xz)
            val latRec = def.side * lat
            val latOk = abs(latNow - latRec) <= LAT_BAND_M + 0.05f
            val angOk = inRange(angle, def.range)
            val rec = recommendOverhead(room, seat, def, lat)
            SpeakerState(
                def = def,
                position = position,
                value = angle,
                inAngleRange = angOk,
                latOk = latOk,
                tooClose = false,
                noWall = false,
                status = if (angOk && latOk) Status.GREEN else Status.RED,
                distance = position.distanceTo(seat.ear),
                latNow = latNow,
                latRec = latRec,
                atRecommended = position.distanceTo(rec) < 0.02f,
                recommended = rec,
                plane = room.ceiling,
                faceNormal = Vec3(0f, -1f, 0f),
            )
        }
    }

    fun inRange(v: Float, range: ClosedFloatingPointRange<Float>): Boolean = v >= range.start - SpeakerDef.TOLERANCE_DEG && v <= range.endInclusive + SpeakerDef.TOLERANCE_DEG

    /** Direction and centimetres to the nearest end of the range (design `outOfRangeHint`); null when in range. */
    fun moveHint(s: SpeakerState, seat: Seat, room: RoomModel): MoveHint? {
        if (s.status != Status.RED) return null
        if (s.def.kind == SpeakerKind.LISTENER) {
            val d0 = wrapDeg(s.def.range.start - s.value)
            val d1 = wrapDeg(s.def.range.endInclusive - s.value)
            val dd = if (abs(d0) < abs(d1)) d0 else d1
            val target = land(room, seat, s.value + dd).point.at(s.position.y)
            val cm = (hypot(target.x - s.position.x, target.z - s.position.z) * 100f).roundToInt()
            return MoveHint(if (dd > 0f) MoveDir.RIGHT else MoveDir.LEFT, cm, target)
        }
        val xz = P2.of(s.position)
        if (!s.inAngleRange) {
            val targetAngle = if (s.value < s.def.range.start) s.def.range.start else s.def.range.endInclusive
            val forward = (s.position.y - seat.ear.y) / tan(Math.toRadians(targetAngle.toDouble())).toFloat()
            val delta = forward - seat.forwardOf(xz)
            val target = (xz + seat.f * delta).at(s.position.y)
            return MoveHint(if (delta > 0f) MoveDir.FORWARD else MoveDir.BACK, (abs(delta) * 100f).roundToInt(), target)
        }
        val delta = s.latRec - s.latNow
        val inward = abs(s.latNow) > abs(s.latRec)
        val mv = sign(delta) * max(0f, abs(delta) - LAT_BAND_M)
        val target = (xz + seat.right * mv).at(s.position.y)
        return MoveHint(if (inward) MoveDir.CENTER else MoveDir.OUTWARD, (abs(mv) * 100f).roundToInt(), target)
    }

    /** Distance from P to the rear wall (along -f), or null when the room is open behind. */
    fun rearWallDistance(room: RoomModel, seat: Seat): Float? = room.hitWall(seat.ear, (-seat.f).at(0f).normalized())?.distance

    // ---- dragging and the thumbstick --------------------------------------------------------

    /**
     * Listener-level drag (design `dragListener`): the pointing line's hit on anything gives the
     * azimuth; nothing hit -> the line meets the vertical cylinder around P at the current distance.
     * Null keeps the previous frame.
     */
    fun dragAzimuth(seat: Seat, room: RoomModel, origin: Vec3, direction: Vec3, currentDistance: Float): Float? {
        val hit = room.hitAny(origin, direction)
        if (hit != null) return seat.azimuthOf(P2.of(hit.point))
        // |o + t d - p|_xz = r
        val o = P2.of(origin) - P2.of(seat.ear)
        val d = P2(direction.x, direction.z)
        val a = d.dot(d)
        if (a < 1e-6f) return null
        val b = 2f * o.dot(d)
        val c = o.dot(o) - currentDistance * currentDistance
        val disc = b * b - 4f * a * c
        if (disc < 0f) return null
        val t = (-b + sqrt(disc)) / (2f * a)
        if (t <= 0f) return null
        return seat.azimuthOf(P2.of(seat.ear) + o + d * t)
    }

    /** Overhead drag (design `dragOverhead`): the hit on the (real or assumed) ceiling, kept 0.2 m inside; null keeps the previous frame. */
    fun dragCeiling(room: RoomModel, origin: Vec3, direction: Vec3): Vec3? {
        val hit = room.hitCeiling(origin, direction) ?: return null
        return room.clampToCeiling(hit, CEILING_INSET_M)
    }

    /**
     * Thumbstick X (-1..1, right +) -> turn rate in degrees per second about the seat's vertical axis:
     * nothing inside the dead zone, then up to [STICK_MAX_DEG_S] on a square curve.
     */
    fun stickRateDeg(x: Float): Float {
        val a = abs(x)
        if (!a.isFinite() || a <= STICK_DEADZONE) return 0f
        val t = ((a - STICK_DEADZONE) / (1f - STICK_DEADZONE)).coerceIn(0f, 1f)
        return sign(x) * STICK_MAX_DEG_S * t * t
    }

    /** The point [xz] turned by [deg] (right +) about the seat's vertical axis. */
    fun turnAboutSeat(seat: Seat, xz: P2, deg: Float): P2 {
        val v = xz - P2.of(seat.ear)
        return P2.of(seat.ear) + v.turned(deg)
    }
}

/** Screen rules (design `rules.screen`). */
data class ScreenGeom(
    val inches: Int,
    val width: Float,
    val height: Float,
    /** Centre of the screen face, 1 cm off the wall. */
    val center: Vec3,
    /** Unit normal of the screen towards the seat. */
    val normal: P2,
    /** Horizontal unit vector along the screen, to the right as seen from the seat. */
    val right: P2,
    val bottom: Float,
    val top: Float,
    /** Seat to screen plane distance. */
    val distance: Float,
    /** Horizontal viewing angle, degrees. */
    val viewDeg: Float,
)

enum class ScreenMode(val tag: String) {
    TV("tv"),
    PROJECTOR("projector");

    companion object {
        fun byTag(tag: String?): ScreenMode = entries.firstOrNull { it.tag == tag } ?: TV
    }
}

/** The recommended size range and the "use best" size (design `rules.screen`). */
data class SizeAdvice(val lo: Int, val hi: Int, val use: Int, val allOver: Boolean, val min: Int, val max: Int)

object ScreenRules {
    /** Width in metres per diagonal inch of a 16:9 screen. */
    const val INCH_W = (0.0254 * 16.0 / 18.357559750685819).toFloat()
    const val TV_MIN = 32
    const val TV_MAX = 115
    const val PROJECTOR_MIN = 60
    const val PROJECTOR_MAX = 200
    const val VIEW_SMALL_DEG = 30f
    const val VIEW_LARGE_DEG = 40f
    const val VIEW_BEST_DEG = 36f
    const val OFF_WALL_M = 0.01f
    const val BOTTOM_MIN_M = 0.5f
    const val TOP_CLEAR_M = 0.05f
    const val ABOVE_EAR_M = 0.05f

    fun widthOf(inches: Int): Float = inches * INCH_W

    fun viewDeg(width: Float, distance: Float): Float = Math.toDegrees(2.0 * atan((width / 2f / max(0.05f, distance)).toDouble())).toFloat()

    fun diagonalFor(viewDeg: Float, distance: Float): Float = 2f * distance * tan(Math.toRadians(viewDeg / 2.0)).toFloat() / INCH_W

    fun geom(seat: Seat, room: RoomModel, inches: Int): ScreenGeom {
        val w = widthOf(inches)
        val h = w * 9f / 16f
        val ceilY = room.ceilingY()
        var cy = seat.ear.y + ABOVE_EAR_M
        if (cy + h / 2f > ceilY - TOP_CLEAR_M) cy = ceilY - TOP_CLEAR_M - h / 2f
        if (cy - h / 2f < seat.floorY + BOTTOM_MIN_M) cy = seat.floorY + BOTTOM_MIN_M + h / 2f
        val n = seat.screenNormal
        val center = (seat.screenCenter + n * OFF_WALL_M).at(cy)
        // right as seen from the seat: the normal points at the seat, so right = normal turned 90 degrees to its left
        val right = P2(n.z, -n.x)
        val d = seat.screenDistance
        return ScreenGeom(inches, w, h, center, n, right, cy - h / 2f, cy + h / 2f, d, viewDeg(w, d))
    }

    /** Hard limits of the diagonal: the mode's own, the wall's width and the room's height (design R2-4). */
    fun limits(seat: Seat, room: RoomModel, mode: ScreenMode): IntRange {
        val n = seat.screenNormal
        val right = P2(n.z, -n.x)
        val from = (seat.screenCenter + n * 0.02f).at(seat.ear.y)
        val a = room.hitWall(from, right.at(0f).normalized(), 6f)?.distance ?: 3f
        val b = room.hitWall(from, (-right).at(0f).normalized(), 6f)?.distance ?: 3f
        val maxW = 2f * min(a, b) - 0.2f
        val maxH = room.ceilingY() - TOP_CLEAR_M - seat.floorY - BOTTOM_MIN_M
        val byW = floor5(maxW / INCH_W)
        val byH = floor5(maxH * 16f / 9f / INCH_W)
        val hardMin = if (mode == ScreenMode.TV) TV_MIN else PROJECTOR_MIN
        val hardMax = if (mode == ScreenMode.TV) TV_MAX else PROJECTOR_MAX
        return hardMin..max(hardMin, min(hardMax, min(byW, byH)))
    }

    fun advice(seat: Seat, room: RoomModel, mode: ScreenMode): SizeAdvice {
        val d = seat.screenDistance
        val lim = limits(seat, room, mode)
        val lo0 = ceil5(diagonalFor(VIEW_SMALL_DEG, d))
        val hi0 = floor5(diagonalFor(VIEW_LARGE_DEG, d))
        val best = round5(diagonalFor(VIEW_BEST_DEG, d))
        return SizeAdvice(lo0.coerceIn(lim), hi0.coerceIn(lim), best.coerceIn(lim), lo0 > lim.last, lim.first, lim.last)
    }

    fun floor5(x: Float): Int = (floor(x / 5f) * 5f).toInt()

    fun ceil5(x: Float): Int = (ceil(x / 5f) * 5f).toInt()

    fun round5(x: Float): Int = ((x / 5f).roundToInt() * 5)

    private fun Int.coerceIn(r: IntRange): Int = coerceIn(r.first, r.last)
}

/** The ceiling-mounted projector (design `rules.projector`). */
data class ProjectorGeom(
    val center: Vec3,
    /** Throw distance screen face -> lens, metres. */
    val throwM: Float,
    val tooFar: Boolean,
    /** How much the rear wall lacks, metres (when too far). */
    val lack: Float,
    val noRearWall: Boolean,
    /** Horizontally within 0.4 m of the seat. */
    val overhead: Boolean,
    /** The lens point (front face of the box). */
    val lens: Vec3,
)

object ProjectorRules {
    const val RATIO_MIN = 0.80f
    const val RATIO_MAX = 2.50f
    const val RATIO_STEP = 0.05f
    const val RATIO_DEFAULT = 1.20f
    const val BELOW_CEILING_M = 0.15f
    const val BOX_W = 0.35f
    const val BOX_H = 0.12f
    const val BOX_D = 0.30f
    const val REAR_CLEARANCE_M = 0.25f
    const val OVERHEAD_M = 0.4f

    fun geom(seat: Seat, room: RoomModel, screen: ScreenGeom, ratio: Float): ProjectorGeom {
        val n = screen.normal
        val t = ratio * screen.width
        val from = (seat.screenCenter + n * 0.02f).at(seat.ear.y)
        val back = room.hitWall(from, n.at(0f).normalized())
        val maxT = if (back != null) back.distance - REAR_CLEARANCE_M else Float.POSITIVE_INFINITY
        val tooFar = t > maxT
        val tUse = if (tooFar && back != null) back.distance - 0.2f else t
        val y = room.ceilingY() - BELOW_CEILING_M - BOX_H / 2f
        val xz = seat.screenCenter + n * tUse
        val center = xz.at(y)
        val lens = (xz - n * (BOX_D / 2f)).at(y)
        val over = (xz - P2.of(seat.ear)).length < OVERHEAD_M
        return ProjectorGeom(center, t, tooFar, if (tooFar) t - maxT else 0f, back == null, over, lens)
    }
}

/** A first-reflection point (design `rules.reflections`), the 60 x 60 cm absorber frame goes there. */
data class Absorber(val sourceId: String, val plane: PlaneSurface, val point: Vec3, val normal: Vec3, val onCeiling: Boolean)

object ReflectionRules {
    const val FRAME_M = 0.60f

    /**
     * Mirror method: for the sources L / C / R and the planes left wall, right wall (and the real
     * ceiling in 5.1), the point where the segment from the source to the mirrored ear point crosses
     * the plane, kept only inside the plane's polygon.
     */
    fun absorbers(seat: Seat, room: RoomModel, speakers: List<SpeakerState>, config: Config): List<Absorber> {
        val sources = speakers.filter { it.id == "L" || it.id == "C" || it.id == "R" }
        if (sources.isEmpty()) return emptyList()
        val planes = ArrayList<Pair<PlaneSurface, Boolean>>()
        room.hitWall(seat.ear, (-seat.right).at(0f).normalized())?.let { planes += it.plane to false }
        room.hitWall(seat.ear, seat.right.at(0f).normalized())?.let { if (planes.none { p -> p.first.id == it.plane.id }) planes += it.plane to false }
        if (config == Config.C51) room.ceiling?.let { planes += it to true }
        val out = ArrayList<Absorber>()
        val p = seat.ear
        for (s in sources) for ((plane, ceilingPlane) in planes) {
            val q = plane.vertices.firstOrNull() ?: continue
            var n = plane.normal
            if (n.dot(p - q) < 0f) n = -n // towards the room
            val mirrored = p - n * (2f * (p - q).dot(n))
            val denom = (mirrored - s.position).dot(n)
            if (abs(denom) < 1e-6f) continue
            val t = (q - s.position).dot(n) / denom
            if (t <= 0f || t >= 1f) continue
            val x = s.position + (mirrored - s.position) * t
            if (!plane.contains(x, 0.05f)) continue
            out += Absorber(s.id, plane, x, n, ceilingPlane)
        }
        return out
    }
}
