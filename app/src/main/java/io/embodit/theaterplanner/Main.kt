package io.embodit.theaterplanner

import com.pico.spatial.ui.foundation.dsl.DefaultStage
import com.pico.spatial.ui.foundation.dsl.SpatialAppScope
import io.embodit.theaterplanner.ui.planner.PlannerScreen
import io.embodit.theaterplanner.ui.theme.TheaterPlannerTheme

/**
 * App entry. One Mixed Stage (AndroidManifest: `pico.spatial.stage.style=1`, immersion 0/0/0): Full
 * Space with passthrough holds the whole product. There is no WindowContainer and no Shared Space
 * version (design `container`).
 */
fun mainApp(scope: SpatialAppScope) =
    with(scope) {
        DefaultStage {
            TheaterPlannerTheme {
                PlannerScreen()
            }
        }
    }
