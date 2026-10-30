package app.phonemcp.ui

import android.app.Application
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.PersistableBundle
import android.os.PowerManager
import android.provider.Settings
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import app.phonemcp.PhoneMcpApp
import app.phonemcp.PrivilegeMode
import app.phonemcp.ProbeResult
import app.phonemcp.R
import app.phonemcp.StartError
import app.phonemcp.TunnelType
import app.phonemcp.RunStatus
import app.phonemcp.data.AppSettings
import app.phonemcp.data.AuditEntry
import app.phonemcp.exec.BasicExecutor
import app.phonemcp.exec.RootCheck
import app.phonemcp.exec.RootExecutor
import app.phonemcp.exec.ShizukuCheck
import app.phonemcp.i18n.Locales
import app.phonemcp.service.McpService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

enum class Screen { WELCOME, MODE, CHECK, TUNNEL, DONE, MAIN }
enum class Tab { HOME, ACTIVITY, SETTINGS }

class AppViewModel(application: Application) : AndroidViewModel(application) {
    private val app = application as PhoneMcpApp

    val settings = app.settings.flow
    val server = app.state.flow
    val pendingApproval = app.approvals.pending

    var screen by mutableStateOf(if (app.settings.value.setupDone) Screen.MAIN else Screen.WELCOME)
    var tab by mutableStateOf(Tab.HOME)

    // wizard
    var wizardMode by mutableStateOf(app.settings.value.mode ?: PrivilegeMode.ROOT)
    var wizardTunnel by mutableStateOf(app.settings.value.tunnel)
    var wizardToken by mutableStateOf("")
    var wizardHost by mutableStateOf(app.settings.value.namedHostname)
    var wizardRelayToken by mutableStateOf("")
    var wizardRelayHost by mutableStateOf(app.settings.value.relayHost)
    /** True when the wizard was opened from Settings or the Home "fix" button instead of first-run setup. */
    private var wizardReturnsToMain = false
    private var wizardReturnTab = Tab.SETTINGS

    var rootCheck by mutableStateOf<RootCheck?>(null)
    var rootChecking by mutableStateOf(false)
    var shizukuCheck by mutableStateOf<ShizukuCheck?>(null)
    var shizukuBusy by mutableStateOf(false)

    var probe by mutableStateOf<ProbeResult?>(null)
    var urlRevealed by mutableStateOf(false)
    var batteryDialog by mutableStateOf(false)
    /** True between tapping Start and the service taking over (covers the biometric dialog). */
    var startPending by mutableStateOf(false)
    private var batteryPrompted = false

    /** Bumped from Activity.onResume so screens re-read system state (battery whitelist, notification and storage access). */
    var resumeTick by mutableIntStateOf(0)

    // ---- wizard navigation --------------------------------------------------------------------------

    fun startSetup() {
        wizardReturnsToMain = false
        wizardMode = if (RootExecutor.suDetected()) PrivilegeMode.ROOT else PrivilegeMode.SHIZUKU
        screen = Screen.MODE
    }

    fun openModeSwitch(returnTab: Tab) {
        wizardReturnsToMain = true
        wizardReturnTab = returnTab
        wizardMode = app.settings.value.mode ?: PrivilegeMode.ROOT
        screen = Screen.MODE
    }

    fun back() {
        when (screen) {
            Screen.MODE -> if (wizardReturnsToMain) leaveWizard() else screen = Screen.WELCOME
            Screen.CHECK -> screen = Screen.MODE
            Screen.TUNNEL -> screen = Screen.MODE
            Screen.DONE, Screen.WELCOME -> {}
            Screen.MAIN -> if (tab != Tab.HOME) tab = Tab.HOME
        }
    }

    private fun leaveWizard() {
        wizardReturnsToMain = false
        screen = Screen.MAIN
        tab = wizardReturnTab
    }

    fun continueFromMode() {
        rootCheck = null
        shizukuCheck = null
        screen = Screen.CHECK
        if (wizardMode == PrivilegeMode.ROOT) runRootCheck()
    }

    /** When switching mode from Settings the checks are advisory: Home shows a Fix banner if the new mode is not ready. */
    val canSwitchAnyway: Boolean get() = wizardReturnsToMain

    fun skipPrivileges() {
        wizardMode = PrivilegeMode.BASIC
        checksPassed()
    }

    fun checksPassed() {
        if (wizardReturnsToMain) {
            if (app.state.value.active) stopServer()
            app.settings.update { it.copy(mode = wizardMode) }
            probe = null
            leaveWizard()
        } else {
            screen = Screen.TUNNEL
        }
    }

    // ---- checks -------------------------------------------------------------------------------------

    fun runRootCheck() {
        if (rootChecking) return
        viewModelScope.launch {
            rootChecking = true
            rootCheck = try { RootExecutor().detailedCheck() } finally { rootChecking = false }
        }
    }

    /** Polls Shizuku state while the check screen is visible; stops once everything passes. */
    suspend fun pollShizuku() {
        while (currentCoroutineContext().isActive && shizukuCheck?.ok != true) {
            if (!shizukuBusy) shizukuCheck = app.shizuku.detailedCheck(askPermission = false)
            delay(2000)
        }
    }

    fun authorizeShizuku() {
        viewModelScope.launch {
            shizukuBusy = true
            try { shizukuCheck = app.shizuku.detailedCheck(askPermission = true) } finally { shizukuBusy = false }
        }
    }

    fun refreshProbe(force: Boolean = false) {
        val mode = app.settings.value.mode ?: return
        if (app.state.value.active) {
            probe = ProbeResult(true)
            return
        }
        viewModelScope.launch {
            probe = when (mode) {
                PrivilegeMode.ROOT -> if (force || probe == null) RootExecutor().probe() else probe
                PrivilegeMode.SHIZUKU -> app.shizuku.lightProbe()
                PrivilegeMode.BASIC -> BasicExecutor().probe()
            }
        }
    }

    fun fixProbe() {
        val mode = app.settings.value.mode ?: PrivilegeMode.ROOT
        wizardReturnsToMain = true
        wizardReturnTab = Tab.HOME
        wizardMode = mode
        if (mode == PrivilegeMode.BASIC) {
            // Only missing piece is "All files access"; Settings has the shortcut.
            wizardReturnsToMain = false
            tab = Tab.SETTINGS
            return
        }
        continueFromMode()
    }

    // ---- setup finish / server control --------------------------------------------------------------

    val canFinishSetup: Boolean
        get() = when (wizardTunnel) {
            TunnelType.NAMED -> wizardToken.isNotBlank() && wizardHost.isNotBlank()
            TunnelType.RELAY -> wizardRelayHost.isNotBlank() && (wizardRelayToken.isNotBlank() || hasRelayToken())
            else -> true
        }

    fun finishSetup() {
        if (!canFinishSetup) return
        if (wizardTunnel == TunnelType.NAMED) app.secrets.setNamedToken(wizardToken)
        if (wizardTunnel == TunnelType.RELAY && wizardRelayToken.isNotBlank()) app.secrets.setRelayToken(wizardRelayToken)
        app.settings.update {
            it.copy(mode = wizardMode, tunnel = wizardTunnel, namedHostname = wizardHost.trim(), relayHost = wizardRelayHost.trim(), setupDone = true)
        }
        wizardReturnsToMain = false
        urlRevealed = false
        screen = Screen.DONE
    }

    fun requestStart(activity: FragmentActivity) {
        val s = app.settings.value
        startPending = true
        if (s.biometricOnStart && Biometrics.available(app)) {
            Biometrics.prompt(
                activity,
                activity.getString(R.string.bio_start_title),
                activity.getString(R.string.bio_start_sub)
            ) { ok -> if (ok) launchService() else startPending = false }
        } else {
            launchService()
        }
    }

    private fun launchService() {
        try {
            ContextCompat.startForegroundService(app, McpService.intent(app, McpService.ACTION_START))
        } catch (e: Exception) {
            startPending = false
            app.state.update { it.copy(status = RunStatus.ERROR, startError = StartError.INTERNAL, errorDetail = e.message.orEmpty()) }
            return
        }
        startPending = false
        if (!batteryPrompted && !isBatteryWhitelisted()) {
            batteryPrompted = true
            batteryDialog = true
        }
    }

    fun stopServer() {
        if (!app.state.value.active) return
        try { app.startService(McpService.intent(app, McpService.ACTION_STOP)) } catch (_: Exception) {}
    }

    fun retryTunnel() {
        try { app.startService(McpService.intent(app, McpService.ACTION_RETRY_TUNNEL)) } catch (_: Exception) {}
    }

    fun rotateSecret() {
        app.secrets.rotate()
        urlRevealed = false
        if (app.state.value.active) {
            try { ContextCompat.startForegroundService(app, McpService.intent(app, McpService.ACTION_RESTART)) } catch (_: Exception) {}
        }
    }

    fun dismissUrlChanged() = app.state.update { it.copy(urlChanged = false) }

    fun update(f: (AppSettings) -> AppSettings) = app.settings.update(f)

    fun setLanguage(lang: String) {
        app.settings.update { it.copy(language = lang) }
        Locales.apply(lang)
    }

    fun saveNamedToken(token: String) = app.secrets.setNamedToken(token)
    fun hasNamedToken(): Boolean = app.secrets.namedToken() != null
    fun saveRelayToken(token: String) = app.secrets.setRelayToken(token)
    fun hasRelayToken(): Boolean = app.secrets.relayToken() != null

    // ---- helpers for the UI -------------------------------------------------------------------------

    fun fullUrl(): String? = app.state.value.publicBase?.let { it + "/mcp/" + app.secrets.mcpSecret() }

    fun maskedUrl(): String? = app.state.value.publicBase?.let { "$it/mcp/••••••••" }

    /** Standard `mcpServers` JSON for any MCP client; the secret is masked unless [masked] is false. */
    fun mcpConfig(masked: Boolean = false): String? {
        val full = fullUrl() ?: return null
        val url = if (masked) full.substringBefore("/mcp/") + "/mcp/••••••••" else full
        return """
            {
              "mcpServers": {
                "phone": { "type": "http", "url": "$url" }
              }
            }
        """.trimIndent()
    }

    fun copyConfig(ctx: Context): Boolean = mcpConfig()?.let { copyText(ctx, it) } ?: false

    fun copyUrl(ctx: Context): Boolean = fullUrl()?.let { copyText(ctx, it) } ?: false

    private fun copyText(ctx: Context, url: String): Boolean {
        val cm = ctx.getSystemService(ClipboardManager::class.java)
        val clip = ClipData.newPlainText("Phone MCP", url)
        clip.description.extras = PersistableBundle().apply { putBoolean("android.content.extra.IS_SENSITIVE", true) }
        cm.setPrimaryClip(clip)
        // The URL is a credential: wipe it from the clipboard after a minute (only if it is still ours).
        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
            try {
                if (cm.primaryClip?.getItemAt(0)?.text?.toString() == url) cm.clearPrimaryClip()
            } catch (_: Exception) {}
        }, 60_000)
        return true
    }

    fun shareUrl(ctx: Context) {
        val url = fullUrl() ?: return
        val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, url)
        ctx.startActivity(Intent.createChooser(send, null).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    fun isBatteryWhitelisted(): Boolean =
        app.getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(app.packageName)

    fun batteryIntent(): Intent =
        Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${app.packageName}"))

    fun recentActivity(limit: Int): List<AuditEntry> = app.audit.recent(limit)
    val auditChanges = app.audit.changes

    suspend fun loadActivity(limit: Int): List<AuditEntry> = withContext(Dispatchers.IO) { app.audit.recent(limit) }

    fun clearActivity() = viewModelScope.launch(Dispatchers.IO) { app.audit.clear() }

    fun exportActivity(ctx: Context) {
        viewModelScope.launch {
            val uri = withContext(Dispatchers.IO) {
                val dir = File(ctx.cacheDir, "export").apply { mkdirs() }
                val f = File(dir, "phonemcp-activity.csv")
                f.writeText(app.audit.exportCsv())
                FileProvider.getUriForFile(ctx, "${ctx.packageName}.files", f)
            }
            val send = Intent(Intent.ACTION_SEND).setType("text/csv").putExtra(Intent.EXTRA_STREAM, uri)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            ctx.startActivity(Intent.createChooser(send, null).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }

    fun resolveApproval(id: Long, allow: Boolean) = app.approvals.resolve(id, allow)

    fun vendorAutoStartIntents(): List<Intent> = listOf(
        "com.coloros.safecenter/com.coloros.safecenter.startupapp.StartupAppListActivity",
        "com.oplus.battery/com.oplus.powermanager.fuelgaue.PowerUsageModelActivity",
        "com.oplus.battery/com.oplus.powermanager.fuelgaue.PowerConsumptionActivity",
        "com.miui.securitycenter/com.miui.permcenter.autostart.AutoStartManagementActivity",
        "com.huawei.systemmanager/com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity",
        "com.vivo.permissionmanager/com.vivo.permissionmanager.activity.BgStartUpManagerActivity",
        "com.samsung.android.lool/com.samsung.android.sm.battery.ui.BatteryActivity"
    ).map { Intent().setClassName(it.substringBefore('/'), it.substringAfter('/')).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) } +
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${app.packageName}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
}
