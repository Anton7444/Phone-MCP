package app.phonemcp.service

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.Network
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.ServiceCompat
import app.phonemcp.BuildConfig
import app.phonemcp.PrivilegeMode
import app.phonemcp.ProbeFailure
import app.phonemcp.ProbeResult
import app.phonemcp.R
import app.phonemcp.RunStatus
import app.phonemcp.ServerSnapshot
import app.phonemcp.StartError
import app.phonemcp.TunnelError
import app.phonemcp.TunnelStatus
import app.phonemcp.TunnelType
import app.phonemcp.app
import app.phonemcp.data.AppSettings
import app.phonemcp.exec.BasicExecutor
import app.phonemcp.exec.Executor
import app.phonemcp.exec.RootExecutor
import app.phonemcp.mcp.HttpServer
import app.phonemcp.mcp.McpRpc
import app.phonemcp.mcp.ToolRunner
import app.phonemcp.tunnel.CloudflaredRunner
import app.phonemcp.tunnel.LanTls
import app.phonemcp.tunnel.NetworkAddresses
import app.phonemcp.tunnel.TunnelListener
import app.phonemcp.tunnel.TunnelPlan
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
import java.io.IOException

/**
 * Owns everything that must stay alive with the screen off: the HTTP server, the tunnel, the wake and Wi-Fi locks.
 * The UI never starts the server directly; it sends intents here and watches [app.phonemcp.ServerState].
 * Modelled on how Termux's service handles its wake lock (re-implemented, not copied).
 */
class McpService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val app get() = applicationContext.app
    private val settings get() = app.settings
    private val state get() = app.state

    private var server: HttpServer? = null
    private var runner: CloudflaredRunner? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null
    private var lockTimeoutMs = -1L
    private var boundTailscaleIp: String? = null
    private var boundLanIp: String? = null
    private var startJob: Job? = null
    private var autoStopJob: Job? = null
    private var lockExpiryJob: Job? = null
    private var settingsJob: Job? = null
    private var netJob: Job? = null
    private var netCallback: ConnectivityManager.NetworkCallback? = null
    @Volatile private var tearingDown = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                goForeground()
                if (startJob?.isActive != true && !state.value.active) {
                    startJob = scope.launch { startAll() }
                }
            }
            ACTION_RESTART -> {
                goForeground()
                restart()
            }
            ACTION_TOGGLE_LOCK -> {
                settings.update { it.copy(wakeLock = !state.value.wakeLockHeld) }
                applyLocks()
            }
            ACTION_RETRY_TUNNEL -> {
                if (settings.value.tunnel == TunnelType.TAILSCALE) restart() // the listener is bound to the Tailscale address
                else scope.launch { if (state.value.status == RunStatus.RUNNING) startTunnel(settings.value) }
            }
            ACTION_STOP -> stopFully()
            else -> {
                // Restarted by the system without an intent: do not come back on our own (biometric gate).
                stopSelf()
            }
        }
        return START_NOT_STICKY
    }

    private fun restart() {
        startJob?.cancel()
        startJob = scope.launch {
            teardown()
            delay(300)
            startAll()
        }
    }

    override fun onDestroy() {
        teardown()
        scope.cancel()
        super.onDestroy()
    }

    // ---- start-up -----------------------------------------------------------------------------------

    private fun goForeground() {
        val n = Notifs.service(this, settings.value.language, state.value.copy(status = if (state.value.active) state.value.status else RunStatus.STARTING))
        try {
            if (Build.VERSION.SDK_INT >= 34) {
                ServiceCompat.startForeground(this, Notifs.ID_SERVICE, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
            } else {
                startForeground(Notifs.ID_SERVICE, n)
            }
        } catch (e: Exception) {
            Log.e(TAG, "startForeground failed: ${e.javaClass.simpleName}")
            state.update { it.copy(status = RunStatus.ERROR, startError = StartError.INTERNAL, errorDetail = e.message.orEmpty()) }
            stopSelf()
        }
    }

    private suspend fun startAll() {
        tearingDown = false
        val s = settings.value
        val mode = s.mode
        if (mode == null) {
            fail(StartError.NO_MODE, null, "")
            return
        }
        state.update {
            ServerSnapshot(
                status = RunStatus.STARTING, mode = mode, port = s.port, tunnelType = s.tunnel,
                tunnelStatus = TunnelStatus.OFF, startedAt = System.currentTimeMillis()
            )
        }
        refreshNotification()

        val executor: Executor = when (mode) {
            PrivilegeMode.ROOT -> RootExecutor()
            PrivilegeMode.SHIZUKU -> app.shizuku
            PrivilegeMode.BASIC -> BasicExecutor()
        }
        val probe = try {
            executor.probe()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            ProbeResult(false, ProbeFailure.TEST_FAILED, e.message.orEmpty())
        }
        // Never start the server with broken privileges.
        if (!probe.ok) {
            fail(StartError.PROBE_FAILED, probe.failure, probe.detail)
            return
        }
        app.activeExecutor = executor

        val tools = ToolRunner(applicationContext, settings, app.secrets, app.audit, app.approvals, state) { app.activeExecutor }
        // Tailscale mode listens only on the Tailscale address, not on Wi-Fi.
        boundTailscaleIp = if (s.tunnel == TunnelType.TAILSCALE) NetworkAddresses.tailscale() else null
        boundLanIp = if (s.tunnel == TunnelType.LAN) NetworkAddresses.lan() else null
        val bindHost = when (s.tunnel) {
            // Only the Wi-Fi/LAN address, never the wildcard: cellular and other interfaces stay closed.
            TunnelType.LAN -> boundLanIp ?: "127.0.0.1"
            TunnelType.TAILSCALE -> boundTailscaleIp ?: "127.0.0.1"
            else -> "127.0.0.1"
        }
        val srv = HttpServer(s.port, bindHost, { app.secrets.mcpSecret() }, McpRpc(tools, BuildConfig.VERSION_NAME), scope,
            tls = if (s.tunnel == TunnelType.LAN) try { LanTls.serverSocketFactory() } catch (_: Exception) { fail(StartError.INTERNAL, null, "TLS setup failed"); return } else null)
        try {
            srv.start()
        } catch (e: IOException) {
            app.activeExecutor = null
            fail(StartError.PORT_IN_USE, null, e.message.orEmpty())
            return
        }
        server = srv

        state.update { it.copy(status = RunStatus.RUNNING) }
        applyLocks()
        watchSettings()
        watchNetwork()
        scheduleAutoStop(s)
        startTunnel(s)
        refreshNotification()
    }

    private fun fail(error: StartError, probe: ProbeFailure?, detail: String) {
        teardown()
        state.update {
            it.copy(status = RunStatus.ERROR, startError = error, probeFailure = probe, errorDetail = detail, tunnelStatus = TunnelStatus.OFF)
        }
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    // ---- tunnel -------------------------------------------------------------------------------------

    private val tunnelListener = object : TunnelListener {
        override fun onStarting() {
            state.update { it.copy(tunnelStatus = TunnelStatus.STARTING, tunnelError = null, tunnelDetail = "") }
        }

        override fun onUp(publicBase: String) {
            val previous = state.value.publicBase
            val changed = previous != null && previous != publicBase
            state.update {
                it.copy(tunnelStatus = TunnelStatus.UP, tunnelError = null, tunnelDetail = "", publicBase = publicBase, urlChanged = changed || it.urlChanged)
            }
            if (changed) Notifs.alert(applicationContext, settings.value.language, R.string.alert_url_changed_title, R.string.alert_url_changed_text)
            refreshNotification()
        }

        override fun onRestarting() {
            state.update { it.copy(tunnelStatus = TunnelStatus.RESTARTING) }
        }

        override fun onFailed(error: TunnelError, detail: String) {
            state.update { it.copy(tunnelStatus = TunnelStatus.ERROR, tunnelError = error, tunnelDetail = detail) }
        }
    }

    private fun startTunnel(s: AppSettings) {
        state.update { it.copy(tlsFingerprint = null) }
        runner?.stop()
        runner = null
        when (s.tunnel) {
            TunnelType.QUICK -> {
                runner = CloudflaredRunner(applicationContext, scope, tunnelListener).also { it.start(TunnelPlan.Quick(s.port)) }
            }
            TunnelType.NAMED -> {
                val token = app.secrets.namedToken()
                val host = s.namedHostname.trim().removePrefix("https://").removePrefix("http://").trimEnd('/')
                when {
                    token == null -> tunnelListener.onFailed(TunnelError.NO_TOKEN, "")
                    host.isEmpty() -> tunnelListener.onFailed(TunnelError.NO_HOSTNAME, "")
                    else -> runner = CloudflaredRunner(applicationContext, scope, tunnelListener)
                        .also { it.start(TunnelPlan.Named(s.port, token, host)) }
                }
            }
            TunnelType.TAILSCALE -> directUp(NetworkAddresses.tailscale(), s.port)
            TunnelType.LAN -> directUp(NetworkAddresses.lan(), s.port)
        }
    }

    private fun directUp(ip: String?, port: Int) {
        if (ip == null) tunnelListener.onFailed(TunnelError.NO_NETWORK, "")
        else if (settings.value.tunnel == TunnelType.LAN) {
            state.update { it.copy(tlsFingerprint = try { LanTls.fingerprint() } catch (_: Exception) { null }) }
            tunnelListener.onUp("https://$ip:$port")
        } else tunnelListener.onUp("http://$ip:$port")
    }

    private fun watchNetwork() {
        val cm = getSystemService(ConnectivityManager::class.java)
        var first = true
        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                if (first) { first = false; return }
                netJob?.cancel()
                netJob = scope.launch {
                    delay(3000) // debounce flapping
                    val s = settings.value
                    when (s.tunnel) {
                        TunnelType.QUICK, TunnelType.NAMED -> runner?.restartNow()
                        TunnelType.TAILSCALE -> {
                            if (NetworkAddresses.tailscale() != boundTailscaleIp) restart()
                            else directUp(boundTailscaleIp, s.port)
                        }
                        TunnelType.LAN -> {
                            if (NetworkAddresses.lan() != boundLanIp) restart()
                            else directUp(boundLanIp, s.port)
                        }
                    }
                }
            }
        }
        try {
            cm.registerDefaultNetworkCallback(cb)
            netCallback = cb
        } catch (e: Exception) {
            Log.w(TAG, "network callback unavailable: ${e.javaClass.simpleName}")
        }
    }

    // ---- locks --------------------------------------------------------------------------------------

    /** Acquire/release are idempotent and always happen together; the Wi-Fi lock also needs its own setting. */
    @Suppress("DEPRECATION")
    private fun applyLocks() {
        val s = settings.value
        val running = state.value.status == RunStatus.RUNNING && !tearingDown
        val want = running && s.wakeLock
        val timeout = s.effectiveWakeLockMinutes * 60_000L
        if (!want) {
            releaseLocks()
            return
        }
        if (wakeLock?.isHeld == true && lockTimeoutMs != timeout) releaseLocks() // timeout changed: re-acquire
        if (wakeLock?.isHeld != true) {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            val wl = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "phonemcp:service-wakelock").apply { setReferenceCounted(false) }
            if (timeout > 0) wl.acquire(timeout) else wl.acquire()
            wakeLock = wl
            lockTimeoutMs = timeout
            lockExpiryJob?.cancel()
            if (timeout > 0) {
                lockExpiryJob = scope.launch {
                    delay(timeout + 500)
                    syncLockState()
                }
            }
        }
        if (s.wifiLock && wifiLock?.isHeld != true) {
            val wm = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            val wf = wm.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "phonemcp:wifilock").apply { setReferenceCounted(false) }
            wf.acquire()
            wifiLock = wf
        } else if (!s.wifiLock && wifiLock?.isHeld == true) {
            wifiLock?.release()
            wifiLock = null
        }
        syncLockState()
    }

    private fun releaseLocks() {
        lockExpiryJob?.cancel()
        try { if (wakeLock?.isHeld == true) wakeLock?.release() } catch (_: Exception) {}
        try { if (wifiLock?.isHeld == true) wifiLock?.release() } catch (_: Exception) {}
        wakeLock = null
        wifiLock = null
        lockTimeoutMs = -1
        syncLockState()
    }

    private fun syncLockState() {
        val w = wakeLock?.isHeld == true
        val f = wifiLock?.isHeld == true
        if (state.value.wakeLockHeld != w || state.value.wifiLockHeld != f) {
            state.update { it.copy(wakeLockHeld = w, wifiLockHeld = f) }
            refreshNotification()
        }
    }

    private fun watchSettings() {
        settingsJob?.cancel()
        settingsJob = scope.launch { settings.flow.drop(1).collect { applyLocks() } }
    }

    private fun scheduleAutoStop(s: AppSettings) {
        autoStopJob?.cancel()
        if (s.autoStopMinutes <= 0) return
        autoStopJob = scope.launch {
            delay(s.autoStopMinutes * 60_000L)
            Notifs.alert(applicationContext, settings.value.language, R.string.alert_autostop_title, R.string.alert_autostop_text)
            stopFully()
        }
    }

    // ---- shutdown -----------------------------------------------------------------------------------

    /** Stops everything this service started. Safe to call more than once. */
    private fun teardown() {
        tearingDown = true
        autoStopJob?.cancel()
        settingsJob?.cancel()
        netJob?.cancel()
        netCallback?.let { cb -> try { getSystemService(ConnectivityManager::class.java).unregisterNetworkCallback(cb) } catch (_: Exception) {} }
        netCallback = null
        runner?.stop()
        runner = null
        server?.stop()
        server = null
        releaseLocks()
        app.activeExecutor?.let { try { it.close() } catch (_: Exception) {} }
        app.activeExecutor = null
        Notifs.cancelApproval(applicationContext)
        val cur = state.value
        if (cur.status != RunStatus.ERROR) {
            state.update {
                it.copy(
                    status = RunStatus.STOPPED, tunnelStatus = TunnelStatus.OFF, tunnelError = null, publicBase = null,
                    wakeLockHeld = false, wifiLockHeld = false, urlChanged = false
                )
            }
        }
    }

    private fun stopFully() {
        startJob?.cancel()
        teardown()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun refreshNotification() {
        Notifs.notify(this, Notifs.ID_SERVICE, Notifs.service(this, settings.value.language, state.value))
    }

    companion object {
        private const val TAG = "PhoneMcpService"
        const val ACTION_START = "app.phonemcp.START"
        const val ACTION_STOP = "app.phonemcp.STOP"
        const val ACTION_RESTART = "app.phonemcp.RESTART"
        const val ACTION_TOGGLE_LOCK = "app.phonemcp.TOGGLE_LOCK"
        const val ACTION_RETRY_TUNNEL = "app.phonemcp.RETRY_TUNNEL"

        fun intent(ctx: Context, action: String) = Intent(ctx, McpService::class.java).setAction(action)
    }
}
