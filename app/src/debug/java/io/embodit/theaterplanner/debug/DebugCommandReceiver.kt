package io.embodit.theaterplanner.debug

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import io.embodit.theaterplanner.platform.TpLog

/**
 * Debug builds only. Forwards adb broadcasts to the running Stage (action
 * `io.embodit.theaterplanner.DEBUG`); see [DebugCommands] for the command list.
 *
 *   adb -s <serial> shell "am broadcast -a io.embodit.theaterplanner.DEBUG -p io.embodit.theaterplanner --es cmd state --es s S04"
 *
 * Extras: `cmd` = command name, `s` = text argument, `a` = comma-separated numbers, `id` = speaker id,
 * and the float extras `az`, `x`, `y`, `z`, `yaw` (design `qaHooks`).
 */
class DebugCommandReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val cmd = intent.getStringExtra("cmd") ?: return
        val s = intent.getStringExtra("s")
        val a = intent.getStringExtra("a")
        val id = intent.getStringExtra("id")
        val floats = HashMap<String, Float>()
        for (k in listOf("az", "x", "y", "z", "yaw")) if (intent.hasExtra(k)) floats[k] = intent.getFloatExtra(k, 0f)
        TpLog.i("CMD recv '$cmd' s='$s' a='$a' id='$id' f=$floats")
        // broadcasts arrive on the main thread, which is where the ViewModel and the scene live
        DebugCommands.run(context.applicationContext, cmd, s, a, id, floats)
    }
}
