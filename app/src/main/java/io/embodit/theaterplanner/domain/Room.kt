package io.embodit.theaterplanner.domain

/**
 * What the app knows of the room from plane detection (design `container.mrCapabilities`): the floor
 * height, the walls (WALL quads only are "walls" for placement; every plane counts for dragging) and
 * the ceiling (the largest CEILING plane, or none: the assumed 2.60 m ceiling then).
 */
data class RoomModel(val planes: List<PlaneSurface>) {
    val floors: List<PlaneSurface> = planes.filter { it.semantic == "FLOOR" && it.isHorizontal }
    val walls: List<PlaneSurface> = planes.filter { it.semantic == "WALL" && it.isVertical }
    val ceiling: PlaneSurface? = planes.filter { it.semantic == "CEILING" && it.isHorizontal }.maxByOrNull { it.area }

    /** Height of the floor, Stage metres: the lowest floor plane; null before the floor is known. */
    val floorY: Float? = floors.minOfOrNull { it.centroid.y }

    val hasFloor: Boolean
        get() = floorY != null

    val hasWall: Boolean
        get() = walls.isNotEmpty()

    val hasCeiling: Boolean
        get() = ceiling != null

    /** Ceiling height in Stage metres: the real ceiling, else floor + 2.60 (design `assumedCeiling`). */
    fun ceilingY(): Float = ceiling?.centroid?.y ?: ((floorY ?: 0f) + ASSUMED_CEILING_M)

    /** Nearest WALL hit of a ray (used for placement and the front wall), within [maxDistance]. */
    fun hitWall(origin: Vec3, direction: Vec3, maxDistance: Float = WALL_REACH_M): SurfaceHit? =
        walls.mapNotNull { it.hit(origin, direction, maxDistance) }.minByOrNull { it.distance }

    /** Nearest hit of a ray on any detected plane (dragging: "指向线打任何东西"). */
    fun hitAny(origin: Vec3, direction: Vec3, maxDistance: Float = 20f): SurfaceHit? =
        planes.mapNotNull { it.hit(origin, direction, maxDistance) }.minByOrNull { it.distance }

    /** Hit of a ray on the real ceiling (polygon), or on the assumed ceiling plane when there is none. */
    fun hitCeiling(origin: Vec3, direction: Vec3): Vec3? {
        val c = ceiling
        if (c != null) return c.hit(origin, direction)?.point
        if (direction.y <= 1e-4f) return null
        val t = (ceilingY() - origin.y) / direction.y
        if (t <= 0f || t > 20f) return null
        return origin + direction * t
    }

    /** A point on the ceiling kept [inset] inside the ceiling polygon (real ceiling only). */
    fun clampToCeiling(point: Vec3, inset: Float): Vec3 {
        val c = ceiling ?: return Vec3(point.x, ceilingY(), point.z)
        return c.clampInside(point, inset)
    }

    companion object {
        val EMPTY = RoomModel(emptyList())
        const val ASSUMED_CEILING_M = 2.60f
        const val WALL_REACH_M = 8f
    }
}
