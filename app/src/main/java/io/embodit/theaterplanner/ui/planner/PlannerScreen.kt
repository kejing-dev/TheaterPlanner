package io.embodit.theaterplanner.ui.planner

import android.content.pm.ApplicationInfo
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.pico.spatial.ui.foundation.dsl.registerSystem
import com.pico.spatial.ui.foundation.dsl.unregisterSystem
import com.pico.spatial.ui.platform.LocalAudioEffectPlayer
import com.pico.spatial.ui.platform.SpatialSoundEffect
import com.pico.spatial.ui.platform.containers.LocalSpatialNavigator
import io.embodit.theaterplanner.platform.TpLog
import io.embodit.theaterplanner.ui.planner.components.LocalTpResources
import io.embodit.theaterplanner.ui.planner.components.PlannerStage
import io.embodit.theaterplanner.ui.planner.components.localizedResources
import io.embodit.theaterplanner.ui.planner.debug.Probe
import io.embodit.theaterplanner.ui.planner.scene.PlannerFrameSystem
import io.embodit.theaterplanner.ui.planner.scene.PlannerScene

/**
 * The one screen of the app, in a Mixed Stage (Full Space). It wires the ViewModel (state), the
 * [PlannerScene] (entities, per-frame work, taps and drags) and [PlannerStage] (the panels), and owns nothing.
 */
@Composable
fun PlannerScreen() {
    val context = LocalContext.current
    val viewModel: PlannerViewModel = viewModel(factory = PlannerViewModel.Factory(context))
    val state by viewModel.state.collectAsStateWithLifecycle()
    val navigator = LocalSpatialNavigator.current
    remember(context) {
        Probe.enabled = (context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0
        true
    }
    val scene = remember(viewModel) { PlannerScene(viewModel::onEvent) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle

    DisposableEffect(scene, lifecycle) {
        // the one per-frame system; every start has its stop in onDispose
        registerSystem<PlannerFrameSystem>()
        Probe.host =
            object : Probe.Host {
                override fun viewModel(): PlannerViewModel = viewModel

                override fun scene(): PlannerScene = scene
            }
        // in the background every tracker stops (design `container.lifecycle`)
        val observer =
            LifecycleEventObserver { _, event ->
                when (event) {
                    Lifecycle.Event.ON_STOP -> scene.pause()
                    Lifecycle.Event.ON_START -> scene.resume()
                    else -> Unit
                }
            }
        lifecycle.addObserver(observer)
        TpLog.i("STAGE enter")
        onDispose {
            lifecycle.removeObserver(observer)
            Probe.host = null
            unregisterSystem<PlannerFrameSystem>()
            scene.detach()
            TpLog.i("STAGE disposed")
        }
    }

    LaunchedEffect(scene, state) { scene.render(state) }

    val audio = LocalAudioEffectPlayer.current
    LaunchedEffect(viewModel) {
        viewModel.effects.collect { effect ->
            when (effect) {
                PlannerEffect.Exit -> {
                    TpLog.i("STAGE close requested")
                    runCatching { navigator.closeStage() }.onFailure { TpLog.e("STAGE close failed: $it", it) }
                }
                is PlannerEffect.Feedback ->
                    runCatching {
                        audio.playSystem(if (effect.kind == "snap") SpatialSoundEffect.StateSelected else SpatialSoundEffect.OpClick)
                    }.onFailure { TpLog.w("FEEDBACK failed: $it") }
            }
        }
    }

    // the in-app language switches at run time: panels read their copy from this local
    val resources = remember(state.settings.language, context) { localizedResources(context, state.settings.language) }
    CompositionLocalProvider(LocalTpResources provides resources) {
        PlannerStage(state = state, scene = scene, onEvent = viewModel::onEvent)
    }
}
