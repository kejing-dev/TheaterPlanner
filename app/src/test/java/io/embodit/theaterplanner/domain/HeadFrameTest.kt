package io.embodit.theaterplanner.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Taste profile D8 / D9: content comes from the head of this moment (position + level heading), and a
 * pop-up never hides behind the console. Yaw is about +Y (0 = looking along -Z, positive = turned left,
 * as the debug `seat` command and the SDK quaternions have it).
 */
class HeadFrameTest {
    private fun rad(deg: Float) = Math.toRadians(deg.toDouble()).toFloat()

    private fun head(x: Float, y: Float, z: Float, yawDeg: Float, pitchDeg: Float = 0f, rollDeg: Float = 0f) =
        HeadPose(Vec3(x, y, z), Quat4.axisAngle(Vec3.UP, rad(yawDeg)) * Quat4.axisAngle(Vec3.RIGHT, rad(pitchDeg)) * Quat4.axisAngle(Vec3(0f, 0f, -1f), rad(rollDeg)))

    private fun close(expected: Float, actual: Float, eps: Float = 1e-3f) = assertEquals(expected, actual, eps)

    @Test
    fun levelHeadingIgnoresPitchAndRoll() {
        for (yaw in listOf(0f, 37f, 90f, 180f, -120f)) {
            val flat = head(0f, 1.2f, 0f, yaw).level()
            val tilted = head(0f, 1.2f, 0f, yaw, pitchDeg = -45f, rollDeg = 25f).level()
            close(flat.x, tilted.x)
            close(flat.z, tilted.z)
            close(1f, tilted.length)
        }
    }

    @Test
    fun levelHeadingFollowsYaw() {
        head(0f, 0f, 0f, 0f).level().let { close(0f, it.x); close(-1f, it.z) }
        head(0f, 0f, 0f, 90f).level().let { close(-1f, it.x); close(0f, it.z) } // turned left
        head(0f, 0f, 0f, -90f).level().let { close(1f, it.x); close(0f, it.z) } // turned right
        head(0f, 0f, 0f, 180f).level().let { close(0f, it.x); close(1f, it.z) } // turned round
    }

    @Test
    fun straightAheadIsInFrontOfTheHeadAfterTurningRound() {
        val h = head(1f, 1.2f, 2f, 180f, pitchDeg = -30f)
        val p = HeadFrame.at(h, 0f, 0f, 1f)
        close(1f, p.x)
        close(1.2f, p.y)
        close(3f, p.z)
    }

    @Test
    fun rightIsToTheViewersRight() {
        val p = HeadFrame.at(head(0f, 1f, 0f, 0f), 30f, 0f, 1f)
        assertTrue(p.x > 0.49f)
        val q = HeadFrame.at(head(0f, 1f, 0f, 180f), 30f, 0f, 1f)
        assertTrue(q.x < -0.49f)
    }

    @Test
    fun viewRoundTripsAt() {
        for (yaw in listOf(0f, 90f, 180f, -135f)) {
            val h = head(0.3f, 1.1f, -0.7f, yaw, pitchDeg = 20f)
            for ((r, u) in listOf(0f to 0f, 30f to -22f, -25f to 10f)) {
                val v = HeadFrame.view(h, HeadFrame.at(h, r, u, 0.8f), 0.1f, 0.05f)
                close(r, v.yaw, 0.05f)
                close(u, v.pitch, 0.05f)
            }
        }
    }

    /** The user's report: reseat after turning round put the content behind. Sitting again must use the new heading. */
    @Test
    fun reseatAfterTurningRoundPutsTheScreenAhead() {
        val first = head(0f, 1.1f, 0f, 0f)
        val room = VirtualRoom.around(P2.of(first.position), first.level()).model
        val seat1 = Seat.first(first.position, first.level(), room)
        assertTrue((seat1.screenCenter - P2.of(seat1.ear)).dot(first.level()) > 1f)

        for (yaw in listOf(180f, 90f, -90f, 135f)) {
            val turned = head(0.2f, 1.1f, 0.1f, yaw, pitchDeg = -15f)
            val seat2 = Seat.first(turned.position, turned.level(), room)
            val ahead = (seat2.screenCenter - P2.of(seat2.ear)).normalized()
            assertTrue("yaw $yaw: screen not ahead", ahead.dot(turned.level()) > 0.9f)
            assertTrue("yaw $yaw: front not the heading", seat2.f.dot(turned.level()) > 0.9f)
        }
    }

    @Test
    fun virtualRoomFrontWallIsAhead() {
        for (yaw in listOf(0f, 60f, 180f, -100f)) {
            val h = head(1f, 1.1f, -2f, yaw)
            val v = VirtualRoom.around(P2.of(h.position), h.level())
            val seat = Seat.first(h.position, h.level(), v.model)
            assertFalse(seat.screenFloating)
            close(VirtualRoom.DEFAULT_L * VirtualRoom.SEAT_FROM_FRONT, seat.screenDistance, 0.01f)
            assertTrue(seat.f.dot(h.level()) > 0.999f)
        }
    }

    @Test
    fun seatPromptClearsTheConsole() {
        // looking at the console (which then sits straight ahead, 22 degrees down, about 12 degrees half size)
        val console = ViewBox(0f, -22f, 12.3f, 12.3f)
        val want = ViewBox(0f, -18f, 9.2f, 4.7f)
        val got = PanelLayout.clear(want, listOf(console))
        assertFalse(got.overlaps(console))
        assertTrue(got.pitch in PanelLayout.MIN_PITCH_DEG..PanelLayout.MAX_PITCH_DEG)
        assertTrue(kotlin.math.abs(got.yaw) <= PanelLayout.MAX_YAW_DEG)
    }

    @Test
    fun seatPromptStaysPutWhenNothingIsInTheWay() {
        val console = ViewBox(30f, -22f, 12.3f, 12.3f)
        val want = ViewBox(0f, -18f, 9.2f, 4.7f)
        assertEquals(want, PanelLayout.clear(want, listOf(console)))
    }

    @Test
    fun seatPromptClearsConsoleAndSnackbar() {
        val console = ViewBox(0f, -22f, 12.3f, 12.3f)
        val snack = ViewBox(0f, -4f, 17f, 1.5f)
        val want = ViewBox(0f, -18f, 9.2f, 4.7f)
        val got = PanelLayout.clear(want, listOf(console, snack))
        assertFalse(got.overlaps(console))
        assertFalse(got.overlaps(snack))
    }

    @Test
    fun noObstaclesNoMove() {
        val want = ViewBox(0f, -18f, 9.2f, 4.7f)
        assertEquals(want, PanelLayout.clear(want, emptyList()))
    }
}
