package io.embodit.theaterplanner.ui.planner.scene

import com.pico.spatial.core.ecs.Entity
import com.pico.spatial.core.ecs.InteractableComponent
import io.embodit.theaterplanner.domain.Absorber
import io.embodit.theaterplanner.domain.ProjectorGeom
import io.embodit.theaterplanner.domain.Quat4
import io.embodit.theaterplanner.domain.ScreenGeom
import io.embodit.theaterplanner.domain.ScreenMode
import io.embodit.theaterplanner.domain.Status
import io.embodit.theaterplanner.domain.Vec3
import kotlin.math.atan2

/** Dimensions of the basic-shape speakers (design `entities3d`). */
internal object SpeakerDims {
    const val W = 0.18f
    const val H = 0.30f
    const val D = 0.20f
    const val FACE = 0.12f
    const val PUCK = 0.20f
    const val PUCK_T = 0.02f
}

/**
 * A listener-level speaker (box + glowing front disc) or a ceiling speaker (puck + disc). The hit box
 * is a separate unscaled entity so that its collider keeps the speaker's real size.
 */
internal class SpeakerView(root: Entity, meshes: TpMeshes, private val m: TpMaterials, val id: String) {
    private val body = Part(root, meshes.cube, m.speakerBody, "speaker:$id")
    private val face = Part(root, meshes.disc, m.faceGreen, "speakerFace:$id")
    private val hit = Entity()
    private var overhead = false
    private var hitSize: Vec3? = null

    init {
        runCatching { hit.setName("speakerHit:$id") }
        hit.components.set(InteractableComponent())
        root.addChild(hit)
        body.entity.setOpacity(1f)
    }

    fun owns(entity: Entity): Boolean = entity.id == hit.id || entity.id == body.entity.id || entity.id == face.entity.id

    /** Listener-level: the box stands at [center] facing [toward] (horizontal unit vector towards the ear point). */
    fun placeListener(center: Vec3, toward: Vec3, status: Status, dim: Boolean, stale: Boolean) {
        overhead = false
        val z = Vec3(toward.x, 0f, toward.z).normalized().let { if (it.length < 0.5f) Vec3.AHEAD else it }
        val x = Vec3.UP.cross(z).normalized()
        val rot = Quat4.fromBasis(x, Vec3.UP, z)
        body.place(center, Vec3(SpeakerDims.W, SpeakerDims.H, SpeakerDims.D), rot)
        face.place(center + z * (SpeakerDims.D / 2f + 0.003f), Vec3(SpeakerDims.FACE, SpeakerDims.FACE, 1f), rot)
        look(status, dim, stale)
        collider(center, Vec3(SpeakerDims.W, SpeakerDims.H, SpeakerDims.D), rot)
    }

    /** Ceiling speaker: a puck under the ceiling at [center] (its underside), disc facing down. */
    fun placeOverhead(center: Vec3, status: Status, dim: Boolean, stale: Boolean) {
        overhead = true
        body.place(center + Vec3(0f, SpeakerDims.PUCK_T / 2f, 0f), Vec3(SpeakerDims.PUCK, SpeakerDims.PUCK_T, SpeakerDims.PUCK), Quat4.IDENTITY)
        face.placeFacing(center - Vec3(0f, 0.003f, 0f), Vec3(SpeakerDims.FACE, SpeakerDims.FACE, 1f), Vec3(0f, -1f, 0f))
        look(status, dim, stale)
        collider(center, Vec3(SpeakerDims.PUCK + 0.04f, 0.08f, SpeakerDims.PUCK + 0.04f), Quat4.IDENTITY)
    }

    private fun look(status: Status, dim: Boolean, stale: Boolean) {
        body.material(if (dim) m.speakerBodyDim else m.speakerBody)
        face.material(if (dim) m.faceDim else m.face(status, stale))
        val o = if (dim) 0.5f else 1f
        body.entity.setOpacity(o)
        face.entity.setOpacity(o)
    }

    private fun collider(center: Vec3, size: Vec3, rot: Quat4) {
        if (hitSize == null || hitSize!!.distanceTo(size) > 0.001f) {
            hit.components.set(boxCollision(size))
            hitSize = size
        }
        hit.setLocal(position = center, rotation = rot)
    }

    fun destroy() {
        body.destroy()
        face.destroy()
        runCatching { hit.destroy(true) }
    }
}

/** The recommended band on the wall for one listener-level speaker: up to 3 strips between 4 sampled points, and two end lines. */
internal class BandView(root: Entity, meshes: TpMeshes, private val m: TpMaterials, id: String) {
    private val strips = List(3) { Strip(root, meshes.band, m.bandGreen, "band:$id:$it") }
    private val ends = List(2) { Strip(root, meshes.band, m.bandEdge, "bandEnd:$id:$it") }

    /** [points] on the wall (4, or fewer when the wall is missing), [normals] towards the room. */
    fun draw(points: List<Vec3>, normals: List<Vec3>, look: Int) {
        val fill = when (look) { 1 -> m.bandSelected; 2 -> m.bandFaint; else -> m.bandGreen }
        for (i in strips.indices) {
            if (i + 1 < points.size) {
                strips[i].material(fill)
                strips[i].draw(points[i], points[i + 1], BAND_W, normals[i])
            } else strips[i].hide()
        }
        if (points.size >= 2) {
            ends[0].draw(points.first() - Vec3(0f, BAND_W / 2f, 0f), points.first() + Vec3(0f, BAND_W / 2f, 0f), 0.004f, normals.first())
            ends[1].draw(points.last() - Vec3(0f, BAND_W / 2f, 0f), points.last() + Vec3(0f, BAND_W / 2f, 0f), 0.004f, normals.last())
        } else ends.forEach { it.hide() }
    }

    fun hide() {
        strips.forEach { it.hide() }
        ends.forEach { it.hide() }
    }

    fun destroy() {
        strips.forEach { it.destroy() }
        ends.forEach { it.destroy() }
    }

    companion object {
        const val BAND_W = 0.20f
    }
}

/** The recommended zone on the ceiling for one overhead speaker: a green patch and a dashed outline. */
internal class ZoneView(root: Entity, meshes: TpMeshes, m: TpMaterials, id: String) {
    private val patch = Part(root, meshes.square, m.zoneGreen, "zone:$id")
    private val frame = Frame(root, meshes.dashes, m.zoneEdge, "zoneEdge:$id")

    /** Rectangle on the ceiling at height [y]: [center], [right] and [forward] unit vectors, half sizes. */
    fun draw(center: Vec3, right: Vec3, forward: Vec3, halfLat: Float, halfFwd: Float, y: Float) {
        val c = Vec3(center.x, y - 0.006f, center.z)
        val yaw = atan2(forward.x, forward.z)
        patch.place(c, Vec3(halfLat * 2f, 1f, halfFwd * 2f), Quat4.axisAngle(Vec3.UP, yaw))
        frame.draw(Vec3(center.x, y - 0.004f, center.z), right, forward, halfLat, halfFwd, 0.008f, Vec3(0f, -1f, 0f))
    }

    fun hide() {
        patch.show(false)
        frame.hide()
    }

    fun destroy() {
        patch.destroy()
        frame.destroy()
    }
}

/** The 16:9 screen on the front wall (design `entities3d.screenRect`). */
internal class ScreenView(root: Entity, meshes: TpMeshes, private val m: TpMaterials) {
    private val face = Part(root, meshes.cube, m.screenBlack, "screen")
    private val frame = Frame(root, meshes.band, m.frameLight, "screenFrame")

    fun draw(s: ScreenGeom, mode: ScreenMode, bright: Boolean) {
        val n = s.normal.at(0f)
        val right = s.right.at(0f)
        val rot = Quat4.fromBasis(right, Vec3.UP, n)
        face.place(s.center, Vec3(s.width, s.height, 0.004f), rot)
        face.material(if (mode == ScreenMode.TV) (if (bright) m.screenBlack else m.screenBlackDim) else (if (bright) m.screenWhite else m.screenWhiteDim))
        frame.material(if (bright) m.frameLight else m.frameDim)
        val w = if (mode == ScreenMode.TV) 0.02f else 0.008f
        frame.draw(s.center + n * 0.003f, right, Vec3.UP, s.width / 2f + w / 2f, s.height / 2f + w / 2f, w, n)
    }

    fun hide() {
        face.show(false)
        frame.hide()
    }
}

/** The ceiling-mounted projector: box, lens, hanger and the four light-cone lines to the screen corners. */
internal class ProjectorView(root: Entity, meshes: TpMeshes, private val m: TpMaterials) {
    private val box = Part(root, meshes.cube, m.projector, "projector")
    private val lens = Part(root, meshes.disc, m.lens, "projectorLens")
    private val hanger = Strip(root, meshes.band, m.hanger, "projectorHanger")
    private val cone = List(4) { Strip(root, meshes.band, m.amberSheer, "cone:$it") }

    fun draw(p: ProjectorGeom, s: ScreenGeom, ceilingY: Float) {
        val n = s.normal.at(0f) // towards the seat = towards the projector
        val right = s.right.at(0f)
        val rot = Quat4.fromBasis(right, Vec3.UP, n)
        box.place(p.center, Vec3(0.35f, 0.12f, 0.30f), rot)
        box.material(if (p.tooFar) m.projectorRed else m.projector)
        lens.place(p.lens - n * 0.002f, Vec3(0.06f, 0.06f, 1f), rot)
        hanger.draw(p.center + Vec3(0f, 0.06f, 0f), Vec3(p.center.x, ceilingY, p.center.z), 0.02f, right)
        val corners =
            listOf(
                s.center + right * (s.width / 2f) + Vec3(0f, s.height / 2f, 0f),
                s.center - right * (s.width / 2f) + Vec3(0f, s.height / 2f, 0f),
                s.center + right * (s.width / 2f) - Vec3(0f, s.height / 2f, 0f),
                s.center - right * (s.width / 2f) - Vec3(0f, s.height / 2f, 0f),
            )
        for (i in cone.indices) {
            cone[i].material(if (p.tooFar) m.coneRed else m.amberSheer)
            cone[i].draw(p.lens, corners[i], 0.002f, Vec3.UP)
        }
    }

    fun hide() {
        box.show(false)
        lens.show(false)
        hanger.hide()
        cone.forEach { it.hide() }
    }
}

/** One 60 x 60 cm absorber frame with its centre dot (design `entities3d.absorber`). */
internal class AbsorberView(root: Entity, meshes: TpMeshes, m: TpMaterials, index: Int) {
    private val frame = Frame(root, meshes.band, m.reflectBlue, "absorber:$index")
    private val dot = Part(root, meshes.disc, m.reflectBlue, "absorberDot:$index")

    fun draw(a: Absorber) {
        val n = a.normal
        val up = if (a.onCeiling) Vec3.AHEAD.rejectFrom(n).normalized() else Vec3.UP.rejectFrom(n).normalized()
        val right = up.cross(n).normalized()
        val c = a.point + n * 0.01f
        frame.draw(c, right, up, 0.30f, 0.30f, 0.02f, n)
        dot.placeFacing(c, Vec3(0.04f, 0.04f, 1f), n)
    }

    fun hide() {
        frame.hide()
        dot.show(false)
    }
}

/** The seat mark: a 0.5 m ring on the floor, a dashed pole to ear height and a 3 cm amber ball. */
internal class SeatMarkView(root: Entity, meshes: TpMeshes, m: TpMaterials) {
    private val ring = Part(root, meshes.ring, m.seatRing, "seatRing")
    private val pole = Strip(root, meshes.dashes, m.poleDash, "seatPole")
    private val ball = Part(root, meshes.sphere, m.amber, "seatBall")

    fun draw(ear: Vec3, floorY: Float, showPole: Boolean, headPos: Vec3) {
        ring.place(Vec3(ear.x, floorY + 0.005f, ear.z), Vec3(0.5f, 1f, 0.5f))
        if (showPole) {
            val toHead = (headPos - ear).rejectFrom(Vec3.UP).normalized().let { if (it.length < 0.5f) Vec3.AHEAD else it }
            pole.draw(Vec3(ear.x, floorY + 0.01f, ear.z), ear - Vec3(0f, 0.02f, 0f), 0.006f, toHead)
            ball.place(ear, Vec3(0.03f, 0.03f, 0.03f))
        } else {
            pole.hide()
            ball.show(false)
        }
    }

    fun hide() {
        ring.show(false)
        pole.hide()
        ball.show(false)
    }
}

/** The assumed ceiling: a 3 x 3 m dashed grid at floor + 2.60 m around the seat (design `entities3d.assumedCeiling`). */
internal class GridView(root: Entity, meshes: TpMeshes, m: TpMaterials) {
    private val lines = List(26) { Strip(root, meshes.dashes, m.gridWhite, "grid:$it") }

    fun draw(center: Vec3, right: Vec3, forward: Vec3) {
        for (i in 0..12) {
            val o = (i - 6) * 0.25f
            lines[i].draw(center + right * o - forward * 1.5f, center + right * o + forward * 1.5f, 0.006f, Vec3(0f, -1f, 0f))
            lines[13 + i].draw(center + forward * o - right * 1.5f, center + forward * o + right * 1.5f, 0.006f, Vec3(0f, -1f, 0f))
        }
    }

    fun hide() = lines.forEach { it.hide() }
}

/** The red arrow on the wall or ceiling from an out-of-range speaker towards the nearer end of its band. */
internal class ArrowView(root: Entity, meshes: TpMeshes, m: TpMaterials) {
    private val shaft = Strip(root, meshes.band, m.arrowRed, "arrow")
    private val headA = Strip(root, meshes.band, m.arrowRed, "arrowHeadA")
    private val headB = Strip(root, meshes.band, m.arrowRed, "arrowHeadB")

    /** From [from] towards [to] (at most 0.5 m long), lying on the surface with [normal]. */
    fun draw(from: Vec3, to: Vec3, normal: Vec3) {
        val full = to - from
        val len = full.length
        if (len < 0.05f) {
            hide()
            return
        }
        val dir = full / len
        val l = minOf(0.5f, len)
        val start = from + normal * 0.012f
        val tip = start + dir * l
        val side = normal.cross(dir).normalized()
        shaft.draw(start, tip - dir * 0.03f, 0.03f, normal)
        headA.draw(tip, tip - dir * 0.08f + side * 0.06f, 0.03f, normal)
        headB.draw(tip, tip - dir * 0.08f - side * 0.06f, 0.03f, normal)
    }

    fun hide() {
        shaft.hide()
        headA.hide()
        headB.hide()
    }
}
