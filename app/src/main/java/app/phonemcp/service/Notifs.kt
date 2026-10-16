package app.phonemcp.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import app.phonemcp.MainActivity
import app.phonemcp.R
import app.phonemcp.ServerSnapshot
import app.phonemcp.RunStatus
import app.phonemcp.i18n.Locales
import app.phonemcp.policy.ApprovalRequest

object Notifs {
    private const val CH_SERVICE = "service"
    private const val CH_SERVICE_HELD = "service_held"
    private const val CH_APPROVAL = "approval"
    private const val CH_ALERT = "alert"
    const val ID_SERVICE = 1001
    const val ID_APPROVAL = 1002
    const val ID_ALERT = 1003

    fun createChannels(ctx: Context, lang: String) {
        val l = Locales.localized(ctx, lang)
        val nm = ctx.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannels(
            listOf(
                NotificationChannel(CH_SERVICE, l.getString(R.string.ch_service), NotificationManager.IMPORTANCE_LOW).apply { setShowBadge(false) },
                NotificationChannel(CH_SERVICE_HELD, l.getString(R.string.ch_service_held), NotificationManager.IMPORTANCE_DEFAULT).apply {
                    setShowBadge(false); setSound(null, null); enableVibration(false)
                },
                NotificationChannel(CH_APPROVAL, l.getString(R.string.ch_approval), NotificationManager.IMPORTANCE_HIGH),
                NotificationChannel(CH_ALERT, l.getString(R.string.ch_alert), NotificationManager.IMPORTANCE_DEFAULT)
            )
        )
    }

    private fun openApp(ctx: Context): PendingIntent = PendingIntent.getActivity(
        ctx, 0,
        Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
    )

    private fun serviceAction(ctx: Context, action: String, code: Int): PendingIntent = PendingIntent.getService(
        ctx, code, Intent(ctx, McpService::class.java).setAction(action),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
    )

    fun service(ctx: Context, lang: String, s: ServerSnapshot): Notification {
        val l = Locales.localized(ctx, lang)
        val held = s.wakeLockHeld
        val text = when (s.status) {
            RunStatus.STARTING -> l.getString(R.string.notif_starting)
            RunStatus.RUNNING -> l.getString(if (held) R.string.notif_running_held else R.string.notif_running)
            else -> l.getString(R.string.notif_starting)
        }
        return NotificationCompat.Builder(ctx, if (held) CH_SERVICE_HELD else CH_SERVICE)
            .setSmallIcon(R.drawable.ic_stat)
            .setContentTitle(l.getString(R.string.app_name))
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setPriority(if (held) NotificationCompat.PRIORITY_DEFAULT else NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setContentIntent(openApp(ctx))
            .addAction(
                0,
                l.getString(if (held) R.string.notif_release_lock else R.string.notif_acquire_lock),
                serviceAction(ctx, McpService.ACTION_TOGGLE_LOCK, 1)
            )
            .addAction(0, l.getString(R.string.notif_stop), serviceAction(ctx, McpService.ACTION_STOP, 2))
            .build()
    }

    fun showApproval(ctx: Context, lang: String, req: ApprovalRequest) {
        val l = Locales.localized(ctx, lang)
        val deny = PendingIntent.getBroadcast(
            ctx, 3,
            Intent(ctx, ApprovalActionReceiver::class.java).setAction(ApprovalActionReceiver.ACTION_DENY).putExtra("id", req.id),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val n = NotificationCompat.Builder(ctx, CH_APPROVAL)
            .setSmallIcon(R.drawable.ic_stat)
            .setContentTitle(l.getString(R.string.approval_title))
            .setContentText(app.phonemcp.policy.ApprovalText.display(req.command).take(120))
            .setStyle(NotificationCompat.BigTextStyle().bigText(app.phonemcp.policy.ApprovalText.display(req.command).take(400)))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setAutoCancel(true)
            .setTimeoutAfter(req.timeoutMs)
            .setContentIntent(openApp(ctx))
            .addAction(0, l.getString(R.string.approval_deny), deny)
            .build()
        notify(ctx, ID_APPROVAL, n)
    }

    fun cancelApproval(ctx: Context) = NotificationManagerCompat.from(ctx).cancel(ID_APPROVAL)

    fun alert(ctx: Context, lang: String, titleRes: Int, textRes: Int) {
        val l = Locales.localized(ctx, lang)
        val n = NotificationCompat.Builder(ctx, CH_ALERT)
            .setSmallIcon(R.drawable.ic_stat)
            .setContentTitle(l.getString(titleRes))
            .setContentText(l.getString(textRes))
            .setStyle(NotificationCompat.BigTextStyle().bigText(l.getString(textRes)))
            .setAutoCancel(true)
            .setContentIntent(openApp(ctx))
            .build()
        notify(ctx, ID_ALERT, n)
    }

    fun notify(ctx: Context, id: Int, n: Notification) {
        try {
            val nm = NotificationManagerCompat.from(ctx)
            if (nm.areNotificationsEnabled()) nm.notify(id, n)
        } catch (_: SecurityException) {
        }
    }
}
