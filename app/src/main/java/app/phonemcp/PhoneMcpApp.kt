package app.phonemcp

import android.app.Application
import android.content.Context
import app.phonemcp.data.AuditLog
import app.phonemcp.data.SecretStore
import app.phonemcp.data.SettingsRepository
import app.phonemcp.exec.Executor
import app.phonemcp.exec.ShizukuExecutor
import app.phonemcp.policy.ApprovalManager
import app.phonemcp.service.Notifs
import kotlin.concurrent.thread

/** Process-wide singletons. Small enough that a DI framework would only add risk. */
class PhoneMcpApp : Application() {
    lateinit var settings: SettingsRepository; private set
    lateinit var secrets: SecretStore; private set
    lateinit var audit: AuditLog; private set
    lateinit var state: ServerState; private set
    lateinit var approvals: ApprovalManager; private set
    lateinit var shizuku: ShizukuExecutor; private set

    /** The backend the running server uses; null while stopped. */
    @Volatile var activeExecutor: Executor? = null

    /** True while Phone MCP's own UI is on screen. Synthetic input is refused then, so the AI cannot change its own settings. */
    @Volatile var uiVisible = false

    override fun onCreate() {
        super.onCreate()
        settings = SettingsRepository(this)
        secrets = SecretStore(this)
        audit = AuditLog(this)
        state = ServerState()
        shizuku = ShizukuExecutor(this)
        approvals = ApprovalManager(
            onShown = { Notifs.showApproval(this, settings.value.language, it) },
            onCleared = { Notifs.cancelApproval(this) }
        )
        Notifs.createChannels(this, settings.value.language)
        thread(isDaemon = true) { audit.purgeOlderThan(settings.value.auditRetentionDays) }
    }
}

val Context.app: PhoneMcpApp get() = applicationContext as PhoneMcpApp
