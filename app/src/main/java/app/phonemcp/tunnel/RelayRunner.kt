package app.phonemcp.tunnel

import app.phonemcp.TunnelError
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.Base64

/** Where the relay lives and how to prove who we are. [secretHash] is sha256-hex of the connector secret. */
data class RelayPlan(val localPort: Int, val host: String, val token: String, val secretHash: String)

/**
 * Client for the reverse relay in `relay/relay.py` (a small service you run on your own server).
 *
 * The phone connects OUT over HTTPS and long-polls for requests; the server hands it whatever Claude POSTs to
 * `https://<server>/mcp/<secret>`. Each request is replayed against this app's own local server (127.0.0.1), and the
 * answer is posted back. The local server still checks the secret, so the relay adds a hop, not a bypass.
 *
 * Only paths under /mcp/ are replayed. TLS ends on the relay server: use one you control.
 * Never logs the token or the secret; error details have the token scrubbed.
 */
class RelayRunner(
    private val scope: CoroutineScope,
    private val listener: TunnelListener
) {
    @Volatile private var supervisor: Job? = null
    @Volatile private var plan: RelayPlan? = null

    fun start(plan: RelayPlan) {
        stop()
        this.plan = plan
        supervisor = scope.launch(Dispatchers.IO) { supervise(plan) }
    }

    fun stop() {
        supervisor?.cancel()
        supervisor = null
    }

    /** Network changed: drop the poll in flight and start over. */
    fun restartNow() {
        plan?.let { start(it) }
    }

    private class RelayJob(val id: String, val method: String, val path: String, val headers: Map<String, String>, val body: ByteArray)

    private class Rejected : IOException("relay refused the token")

    private suspend fun supervise(plan: RelayPlan) {
        val base = baseUrl(plan.host)
        val gate = Semaphore(MAX_PARALLEL)
        listener.onStarting()
        var backoff = MIN_BACKOFF_MS
        var up = false
        while (currentCoroutineContext().isActive) {
            try {
                val job = poll(base, plan)
                if (!up) {
                    up = true
                    listener.onUp(base)
                }
                backoff = MIN_BACKOFF_MS
                if (job != null) {
                    scope.launch(Dispatchers.IO) { gate.withPermit { handle(base, plan, job) } }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Rejected) {
                up = false
                listener.onFailed(TunnelError.RELAY_REJECTED, "")
                delay(REJECTED_WAIT_MS)
            } catch (e: Exception) {
                if (up) listener.onRestarting()
                else listener.onFailed(TunnelError.PROCESS_FAILED, scrub(e, plan.token))
                up = false
                delay(backoff)
                backoff = (backoff * 2).coerceAtMost(MAX_BACKOFF_MS)
            }
        }
    }

    // ---- relay protocol -----------------------------------------------------------------------------

    private fun open(url: String, readTimeoutMs: Int): HttpURLConnection {
        val c = URL(url).openConnection() as HttpURLConnection
        c.requestMethod = "POST"
        c.connectTimeout = 10_000
        c.readTimeout = readTimeoutMs
        c.useCaches = false
        c.instanceFollowRedirects = false
        c.doOutput = true
        c.setRequestProperty("Content-Type", "application/json")
        return c
    }

    /** Long-poll. Returns a job, or null when nothing arrived (the server answers 204 after ~25 s). */
    private fun poll(base: String, plan: RelayPlan): RelayJob? {
        val c = open("$base/relay/${plan.token}/poll", POLL_READ_TIMEOUT_MS)
        try {
            c.outputStream.use { it.write("""{"h":"${plan.secretHash}","v":1}""".toByteArray()) }
            return when (val code = c.responseCode) {
                200 -> parseJob(c.inputStream.readBytes().toString(Charsets.UTF_8))
                204 -> null
                404 -> throw Rejected()
                else -> throw IOException("relay answered HTTP $code")
            }
        } finally {
            c.disconnect()
        }
    }

    private fun parseJob(json: String): RelayJob {
        val o = JSONObject(json)
        val h = o.optJSONObject("headers")
        val headers = HashMap<String, String>()
        if (h != null) for (k in h.keys()) headers[k] = h.getString(k)
        return RelayJob(
            id = o.getString("id"),
            method = o.getString("method").uppercase(),
            path = o.getString("path"),
            headers = headers,
            body = Base64.getDecoder().decode(o.optString("body_b64", ""))
        )
    }

    private fun handle(base: String, plan: RelayPlan, job: RelayJob) {
        if (!ID.matches(job.id)) return
        val reply = try {
            forward(plan.localPort, job)
        } catch (_: Exception) {
            Reply(502, emptyMap(), ByteArray(0))
        }
        val payload = JSONObject()
            .put("status", reply.status)
            .put("headers", JSONObject(reply.headers as Map<*, *>))
            .put("body_b64", Base64.getEncoder().encodeToString(reply.body))
            .toString().toByteArray()
        repeat(2) {
            try {
                val c = open("$base/relay/${plan.token}/reply/${job.id}", 30_000)
                try {
                    c.outputStream.use { it.write(payload) }
                    c.responseCode // 404 = the caller gave up already; nothing more to do either way
                    return
                } finally {
                    c.disconnect()
                }
            } catch (_: IOException) {
                // retry once
            }
        }
    }

    private class Reply(val status: Int, val headers: Map<String, String>, val body: ByteArray)

    /** Replays one request against our own server. Anything outside /mcp/ is refused without touching it. */
    private fun forward(localPort: Int, job: RelayJob): Reply {
        if (!job.path.startsWith("/mcp/") || job.method !in ALLOWED_METHODS) return Reply(404, emptyMap(), ByteArray(0))
        val c = URL("http://127.0.0.1:$localPort${job.path}").openConnection() as HttpURLConnection
        try {
            c.requestMethod = job.method
            c.connectTimeout = 5_000
            c.readTimeout = LOCAL_READ_TIMEOUT_MS // approvals wait for a tap on the phone
            c.useCaches = false
            c.instanceFollowRedirects = false
            for ((k, v) in job.headers) c.setRequestProperty(k, v)
            if (job.method == "POST" || job.method == "PUT") {
                c.doOutput = true
                c.outputStream.use { it.write(job.body) }
            }
            val code = c.responseCode
            val stream = if (code >= 400) c.errorStream else c.inputStream
            val body = stream?.use { it.readBytes() } ?: ByteArray(0)
            val headers = HashMap<String, String>()
            c.getHeaderField("Content-Type")?.let { headers["Content-Type"] = it }
            c.getHeaderField("Allow")?.let { headers["Allow"] = it }
            return Reply(code, headers, body)
        } finally {
            c.disconnect()
        }
    }

    companion object {
        private const val MAX_PARALLEL = 8
        private const val MIN_BACKOFF_MS = 2_000L
        private const val MAX_BACKOFF_MS = 30_000L
        private const val REJECTED_WAIT_MS = 30_000L
        private const val POLL_READ_TIMEOUT_MS = 40_000
        private const val LOCAL_READ_TIMEOUT_MS = 165_000
        private val ID = Regex("[0-9a-f]{32}")
        private val ALLOWED_METHODS = setOf("POST", "GET", "DELETE", "PUT", "OPTIONS")

        /** "74.222.12.182" or "https://my.host" -> a base URL without a trailing slash. Plain http only if typed. */
        fun baseUrl(host: String): String {
            val h = host.trim().trimEnd('/')
            return if (h.startsWith("http://") || h.startsWith("https://")) h else "https://$h"
        }

        fun sha256Hex(s: String): String =
            MessageDigest.getInstance("SHA-256").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }

        private fun scrub(e: Exception, token: String): String =
            (e.javaClass.simpleName + ": " + (e.message ?: "")).replace(token, "***").take(160)
    }
}
