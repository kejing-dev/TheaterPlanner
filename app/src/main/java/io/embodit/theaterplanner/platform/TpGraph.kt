package io.embodit.theaterplanner.platform

import android.content.Context
import io.embodit.theaterplanner.data.PrefsLayoutStore
import io.embodit.theaterplanner.platform.anchor.WorldAnchorGateway
import io.embodit.theaterplanner.platform.sense.PlaneTracker
import io.embodit.theaterplanner.platform.tracking.ControllerStick
import io.embodit.theaterplanner.platform.tracking.HeadTracker

/**
 * Process-wide platform services. The scene starts and stops the sensing services (every start has a
 * stop: `PlannerScene.attach` / `detach`); the ViewModel only sees interfaces and lambdas.
 */
object TpGraph {
    val anchors = WorldAnchorGateway()
    val planes = PlaneTracker()
    val head = HeadTracker()
    val stick = ControllerStick()

    @Volatile private var store: PrefsLayoutStore? = null

    fun store(context: Context): PrefsLayoutStore = store ?: synchronized(this) { store ?: PrefsLayoutStore(context).also { store = it } }
}
