package ai.dui.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import ai.dui.data.receiver.TermuxOpenReceiver as CoreOpenReceiver

/**
 * Direct ai.dui.app.TermuxOpenReceiver implementation for shell scripts
 * (e.g., xdg-open, termux-open) targeting explicit component ai.dui.app.TermuxOpenReceiver.
 */
class TermuxOpenReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent == null) return
        CoreOpenReceiver.handleOpen(context, intent)
    }
}
