package io.embodit.theaterplanner.platform.tracking

import android.os.SystemClock
import com.pico.spatial.tracking.DataProvider
import com.pico.spatial.tracking.controller.ControllerTrackingProvider
import io.embodit.theaterplanner.domain.Quat4
import io.embodit.theaterplanner.domain.Vec3
import io.embodit.theaterplanner.platform.TpLog
import kotlin.math.abs

/**
 * The controllers' thumbstick X axis (v0.1.1, device feedback: the stick turns the selected piece),
 * from `ControllerTrackingProvider`'s action listener (SDK doc "Obtain controller input actions"; Full
 * Space only). The listener runs on the data source thread at the device rate: it only stores the
 * value; the ECS system reads it once per frame. Hand gestures are untouched (another input path).
 */
class ControllerStick {
    private var provider: ControllerTrackingProvider? = null

    @Volatile private var x = 0f
    @Volatile private var atMs = 0L
    @Volatile var actions = 0L
        private set

    /** The controller whose trigger was touched last aims (the right one at first, Spatial Ruler). */
    @Volatile private var useRight = true

    /** Debug: a stick value that wins over the controllers until [debugUntilMs] (emulator QA). */
    @Volatile private var debugX = 0f
    @Volatile private var debugUntilMs = 0L

    // the stick pushed further of the two controllers
    private val listener =
        ControllerTrackingProvider.ControllerActionListener { a ->
            val l = a.left.thumbstickValue.x
            val r = a.right.thumbstickValue.x
            x = if (abs(r) >= abs(l)) r else l
            if (a.left.triggerValue > TRIGGER_TOUCH && a.right.triggerValue < TRIGGER_TOUCH) useRight = false
            if (a.right.triggerValue > TRIGGER_TOUCH && a.left.triggerValue < TRIGGER_TOUCH) useRight = true
            atMs = SystemClock.elapsedRealtime()
            actions++
        }

    fun start() {
        if (provider != null) return
        val p = ControllerTrackingProvider()
        runCatching { p.addControllerActionListener(listener) }.onFailure { TpLog.w("STICK listener: $it") }
        val result = runCatching { p.start() }
        TpLog.i("STICK start=${result.getOrNull() ?: result.exceptionOrNull()} support=${runCatching { p.supportState }.getOrNull()}")
        provider = p
    }

    fun stop() {
        provider?.let { p ->
            runCatching { p.removeControllerActionListener(listener) }
            runCatching { p.stop() }
        }
        provider = null
        x = 0f
        actions = 0L
    }

    /** Thumbstick X in [-1, 1] (right +); 0 without a controller or when the last report is stale. */
    fun x(nowMs: Long): Float {
        if (nowMs < debugUntilMs) return debugX
        val p = provider ?: return 0f
        if (runCatching { p.supportState }.getOrNull() != DataProvider.SupportState.SUPPORTED) return 0f
        if (nowMs - atMs > STALE_MS) return 0f
        return x.coerceIn(-1f, 1f)
    }

    /**
     * The aiming controller's ray, Stage space: its pose position and -Z (taste profile D1: drags follow the
     * controller ray, Spatial Ruler's `ControllerPointerSource`). Null without a tracked controller (hands,
     * emulator eye mode): the caller falls back to the system pointer's line. Main thread.
     */
    fun ray(): Pair<Vec3, Vec3>? {
        val p = provider ?: return null
        if (runCatching { p.supportState }.getOrNull() != DataProvider.SupportState.SUPPORTED) return null
        val data = runCatching { p.latestData }.getOrNull() ?: return null
        val pose = (if (useRight) data.right ?: data.left else data.left ?: data.right) ?: return null
        val origin = Vec3(pose.position.x, pose.position.y, pose.position.z)
        val forward = Quat4(pose.rotation.x, pose.rotation.y, pose.rotation.z, pose.rotation.w).rotate(Vec3.AHEAD)
        if (!origin.isFinite() || !forward.isFinite() || forward.length < 0.5f) return null
        return origin to forward.normalized()
    }

    /** Debug `stick`: holds [value] for [ms]. */
    fun debugHold(value: Float, ms: Long) {
        debugX = value.coerceIn(-1f, 1f)
        debugUntilMs = SystemClock.elapsedRealtime() + ms
    }

    fun status(): String = "support=${runCatching { provider?.supportState }.getOrNull()} actions=$actions x=${"%.2f".format(x)}"

    private companion object {
        const val STALE_MS = 500L
        const val TRIGGER_TOUCH = 0.2f
    }
}
