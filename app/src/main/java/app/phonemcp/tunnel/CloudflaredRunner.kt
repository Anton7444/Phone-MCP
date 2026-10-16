package app.phonemcp.tunnel

import android.content.Context
import android.util.Log
import app.phonemcp.TunnelError
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.URL
import kotlin.concurrent.thread

interface TunnelListener {
    fun onStarting()
    fun onUp(publicBase: String)
    fun onRestarting()
    fun onFailed(error: TunnelError, detail: String)
}

sealed interface TunnelPlan {
    data class Quick(val port: Int) : TunnelPlan
    data class Named(val port: Int, val token: String, val hostname: String) : TunnelPlan
}

/**
 * Runs the bundled cloudflared binary (shipped as libcloudflared.so so Android lets us execute it) and keeps it
 * alive. Android has no /etc/resolv.conf, which breaks Go's resolver, so we (1) talk to the quick-tunnel API from
 * Java and (2) hand cloudflared fixed edge IPs. See docs/decisions.md.
 *
 * Never logs the connector secret or full URL; only cloudflared's own output lines (which hold neither) are logged.
 */
class CloudflaredRunner(
    private val ctx: Context,
    private val scope: CoroutineScope,
    private val listener: TunnelListener
) {
    @Volatile private var process: Process? = null
    private var supervisor: Job? = null
    @Volatile private var stopping = false

    fun start(plan: TunnelPlan) {
        stop()
        stopping = false
        supervisor = scope.launch(Dispatchers.IO) { supervise(plan) }
    }

    /** Kills the process and cancels supervision. Safe to call repeatedly. */
    fun stop() {
        stopping = true
        supervisor?.cancel()
        supervisor = null
        killProcess()
    }

    /** Used on network change: drop the current process so the supervisor starts a fresh one. */
    fun restartNow() {
        killProcess()
    }

    private fun killProcess() {
        process?.let { p ->
            try { p.destroy() } catch (_: Exception) {}
            try { p.destroyForcibly() } catch (_: Exception) {}
        }
        process = null
    }

    private fun binary(): File? {
        val f = File(ctx.applicationInfo.nativeLibraryDir, "libcloudflared.so")
        return f.takeIf { it.exists() && it.canExecute() }
    }

    private suspend fun supervise(plan: TunnelPlan) {
        val bin = binary()
        if (bin == null) {
            listener.onFailed(TunnelError.NO_BINARY, "libcloudflared.so missing or not executable")
            return
        }
        var backoff = 2_000L
        var first = true
        while (scope.isActive && !stopping) {
            if (first) listener.onStarting() else listener.onRestarting()
            val started = System.currentTimeMillis()
            val outcome = try {
                runOnce(bin, plan)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "tunnel attempt failed: ${e.javaClass.simpleName}")
                RunOutcome(null, TunnelError.PROCESS_FAILED, e.message ?: "")
            }
            if (stopping) return
            if (outcome.base == null) {
                // Did not come up: report it, then keep retrying in the background.
                listener.onFailed(outcome.error ?: TunnelError.PROCESS_FAILED, outcome.detail)
            }
            first = false
            backoff = if (System.currentTimeMillis() - started > 60_000) 2_000L else (backoff * 2).coerceAtMost(30_000L)
            delay(backoff)
        }
    }

    private class RunOutcome(val base: String?, val error: TunnelError?, val detail: String)

    /** One process lifetime. Returns when the process has exited (or never came up). */
    private suspend fun runOnce(bin: File, plan: TunnelPlan): RunOutcome {
        return when (plan) {
            is TunnelPlan.Named -> {
                val p = launch(bin, listOf("tunnel", "--no-autoupdate", "--protocol", "http2") + edgeArgs() + listOf("run"), mapOf("TUNNEL_TOKEN" to plan.token))
                val ready = awaitReady(p, null)
                if (ready.registered) {
                    listener.onUp("https://${plan.hostname}")
                    p.waitForSuspending()
                    RunOutcome("https://${plan.hostname}", null, "")
                } else {
                    killProcess()
                    RunOutcome(null, ready.error, ready.detail)
                }
            }
            is TunnelPlan.Quick -> runQuick(bin, plan)
        }
    }

    private suspend fun runQuick(bin: File, plan: TunnelPlan.Quick): RunOutcome {
        // Preferred path: credentials fetched from Java (uses Android's DNS), fixed edge IPs for cloudflared.
        val creds = try { fetchQuickCredentials() } catch (e: Exception) { Log.w(TAG, "quick api failed: ${e.javaClass.simpleName}"); null }
        if (creds != null) {
            val dir = File(ctx.filesDir, "cf").apply { mkdirs() }
            val credFile = File(dir, "cred.json")
            val cfg = File(dir, "config.yml")
            credFile.writeText(
                JSONObject().put("AccountTag", creds.accountTag).put("TunnelSecret", creds.secret).put("TunnelID", creds.id).toString()
            )
            cfg.writeText(
                "tunnel: ${creds.id}\ncredentials-file: ${credFile.absolutePath}\ningress:\n  - service: http://127.0.0.1:${plan.port}\n"
            )
            val p = launch(
                bin,
                listOf("tunnel", "--config", cfg.absolutePath, "--no-autoupdate", "--protocol", "http2") + edgeArgs() + listOf("run", creds.id),
                emptyMap()
            )
            val ready = awaitReady(p, null, timeoutMs = 25_000)
            credFile.delete()
            if (ready.registered) {
                val base = "https://${creds.hostname}"
                listener.onUp(base)
                p.waitForSuspending()
                return RunOutcome(base, null, "")
            }
            killProcess()
        }
        // Fallback: let cloudflared do everything itself (works on devices where Go's DNS does work).
        val p = launch(
            bin,
            listOf("tunnel", "--url", "http://127.0.0.1:${plan.port}", "--no-autoupdate", "--protocol", "http2"),
            emptyMap()
        )
        val ready = awaitReady(p, URL_REGEX, timeoutMs = 40_000)
        return if (ready.registered && ready.url != null) {
            listener.onUp(ready.url)
            p.waitForSuspending()
            RunOutcome(ready.url, null, "")
        } else {
            killProcess()
            RunOutcome(null, ready.error ?: TunnelError.TIMEOUT, ready.detail)
        }
    }

    // ---- process plumbing ---------------------------------------------------------------------------

    private class Ready(val registered: Boolean, val url: String?, val error: TunnelError?, val detail: String)

    private val lines = java.util.concurrent.LinkedBlockingQueue<String>()

    private fun launch(bin: File, args: List<String>, extraEnv: Map<String, String>): Process {
        killProcess()
        lines.clear()
        val home = File(ctx.filesDir, "cf").apply { mkdirs() }
        val pb = ProcessBuilder(listOf(bin.absolutePath) + args)
        pb.redirectErrorStream(true)
        pb.environment().apply {
            put("HOME", home.absolutePath)
            put("TMPDIR", ctx.cacheDir.absolutePath)
            put("SSL_CERT_DIR", "/system/etc/security/cacerts")
            putAll(extraEnv)
        }
        val p = pb.start()
        process = p
        thread(isDaemon = true, name = "cloudflared-log") {
            try {
                p.inputStream.bufferedReader().forEachLine { line ->
                    lines.offer(line)
                    if (lines.size > 400) lines.poll()
                }
            } catch (_: Exception) {
            }
        }
        return p
    }

    private suspend fun awaitReady(p: Process, urlRegex: Regex?, timeoutMs: Long = 40_000): Ready {
        val result = CompletableDeferred<Ready>()
        val watcher = scope.launch(Dispatchers.IO) {
            var url: String? = null
            var lastErr = ""
            while (isActive && !result.isCompleted) {
                val line = lines.poll(200, java.util.concurrent.TimeUnit.MILLISECONDS)
                if (line != null) {
                    if (urlRegex != null && url == null) url = urlRegex.find(line)?.value
                    if (line.contains("Registered tunnel connection")) {
                        result.complete(Ready(true, url, null, ""))
                        return@launch
                    }
                    if (line.contains(" ERR ") || line.contains("failed", true) || line.contains("error", true)) {
                        lastErr = line.take(200)
                    }
                } else if (!p.isAlive) {
                    result.complete(Ready(false, null, TunnelError.PROCESS_FAILED, lastErr.ifEmpty { "cloudflared exited (${p.exitValue()})" }))
                    return@launch
                }
            }
        }
        val r = withTimeoutOrNull(timeoutMs) { result.await() }
        watcher.cancel()
        return r ?: Ready(false, null, TunnelError.TIMEOUT, "")
    }

    private suspend fun Process.waitForSuspending() = withContext(Dispatchers.IO) {
        try { waitFor() } catch (_: InterruptedException) {}
    }

    // ---- quick tunnel API + edge addresses ----------------------------------------------------------

    private class QuickCreds(val id: String, val accountTag: String, val secret: String, val hostname: String)

    private fun fetchQuickCredentials(): QuickCreds {
        val conn = URL("https://api.trycloudflare.com/tunnel").openConnection() as HttpURLConnection
        try {
            conn.requestMethod = "POST"
            conn.connectTimeout = 15_000
            conn.readTimeout = 15_000
            conn.setRequestProperty("User-Agent", "cloudflared/phone-mcp")
            conn.setRequestProperty("Content-Type", "application/json")
            conn.doOutput = true
            conn.outputStream.use { it.write(ByteArray(0)) }
            if (conn.responseCode != 200) throw java.io.IOException("api status ${conn.responseCode}")
            val j = JSONObject(conn.inputStream.bufferedReader().readText())
            val r = j.getJSONObject("result")
            return QuickCreds(r.getString("id"), r.getString("account_tag"), r.getString("secret"), r.getString("hostname"))
        } finally {
            conn.disconnect()
        }
    }

    /** Cloudflare's tunnel edge. Resolved with Android's DNS when possible, hardcoded otherwise. */
    private fun edgeArgs(): List<String> {
        val ips = LinkedHashSet<String>()
        for (host in listOf("region1.v2.argotunnel.com", "region2.v2.argotunnel.com")) {
            try {
                InetAddress.getAllByName(host).filter { it.address.size == 4 }.take(4).forEach { ips += it.hostAddress ?: "" }
            } catch (_: Exception) {
            }
        }
        if (ips.size < 4) ips += FALLBACK_EDGE
        return ips.filter { it.isNotEmpty() }.take(8).flatMap { listOf("--edge", "$it:7844") }
    }

    companion object {
        private const val TAG = "PhoneMcpTunnel"
        private val URL_REGEX = Regex("""https://[a-z0-9-]+\.trycloudflare\.com""")
        private val FALLBACK_EDGE = listOf(
            "198.41.192.167", "198.41.192.67", "198.41.192.57", "198.41.192.107",
            "198.41.200.13", "198.41.200.193", "198.41.200.33", "198.41.200.233"
        )
    }
}
