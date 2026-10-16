package app.phonemcp.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import app.phonemcp.R
import app.phonemcp.app

/** "Deny" button on the approval notification. Allowing is only possible in the app, behind biometrics. */
class ApprovalActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == ACTION_DENY) {
            context.app.approvals.resolve(intent.getLongExtra("id", -1), false)
            Notifs.cancelApproval(context)
        }
    }

    companion object {
        const val ACTION_DENY = "app.phonemcp.DENY"
    }
}

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        val s = context.app.settings.value
        if (!s.startOnBoot || !s.setupDone) return
        if (s.biometricOnStart) {
            // The biometric gate cannot run without the user; ask them to open the app instead.
            Notifs.alert(context, s.language, R.string.alert_boot_title, R.string.alert_boot_text)
            return
        }
        try {
            ContextCompat.startForegroundService(context, McpService.intent(context, McpService.ACTION_START))
        } catch (_: Exception) {
            Notifs.alert(context, s.language, R.string.alert_boot_title, R.string.alert_boot_text)
        }
    }
}
