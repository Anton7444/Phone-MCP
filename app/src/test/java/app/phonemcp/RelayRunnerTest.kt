package app.phonemcp

import app.phonemcp.mcp.HttpServer
import app.phonemcp.mcp.McpRpc
import app.phonemcp.mcp.ToolHost
import app.phonemcp.mcp.ToolResult
import app.phonemcp.tunnel.RelayPlan
import app.phonemcp.tunnel.RelayRunner
import com.sun.net.httpserver.HttpExchange
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.util.Base64
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * The relay client against a fake relay (JDK HttpServer) and the real local MCP server, over real sockets.
 * The fake relay speaks the protocol documented in relay/relay.py.
 */
class RelayRunnerTest {
    private val secret = "s3cret-abcdefghijklmnopqrstuvwxyz0123456789ABC"
    private val token = "relay-token-0123456789abcdefghijklmnopqrstuvwxyz"
    private val jobId = "0123456789abcdef0123456789abcdef"
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private lateinit var local: HttpServer
    private var localPort = 0
    private var fake: com.sun.net.httpserver.HttpServer? = null
    private var runner: RelayRunner? = null

    private val host = object : ToolHost {
        override fun listTools() = listOf(JSONObject().put("name", "echo"))
        override suspend fun callTool(name: String, args: JSONObject) = ToolResult.text("pong")
    }

    private class Events : app.phonemcp.tunnel.TunnelListener {
        val up = CompletableFuture<String>()
        val failed = CompletableFuture<TunnelError>()
        override fun onStarting() {}
        override fun onUp(publicBase: String) { up.complete(publicBase) }
        override fun onRestarting() {}
        override fun onFailed(error: TunnelError, detail: String) { failed.complete(error) }
    }

    @Before fun startLocal() {
        localPort = ServerSocket(0).use { it.localPort }
        local = HttpServer(localPort, "127.0.0.1", { secret }, McpRpc(host, "t"), scope)
        local.start()
    }

    @After fun stopAll() {
        runner?.stop()
        fake?.stop(0)
        local.stop()
        scope.cancel()
    }

    /** Fake relay: serves [jobs] to successive polls (then 204), captures replies by job id. [pollStatus] overrides poll answers. */
    private fun startFakeRelay(jobs: List<JSONObject>, replies: CopyOnWriteArrayList<JSONObject>, got: CompletableFuture<Unit>, pollStatus: Int? = null, seenHash: CompletableFuture<String>? = null): String {
        val next = AtomicInteger()
        val srv = com.sun.net.httpserver.HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        srv.createContext("/relay/$token/poll") { ex: HttpExchange ->
            seenHash?.complete(JSONObject(ex.requestBody.readBytes().toString(Charsets.UTF_8)).getString("h"))
            ex.requestBody.close()
            val i = next.getAndIncrement()
            if (pollStatus != null) {
                ex.sendResponseHeaders(pollStatus, -1)
            } else if (i < jobs.size) {
                val b = jobs[i].toString().toByteArray()
                ex.responseHeaders.add("Content-Type", "application/json")
                ex.sendResponseHeaders(200, b.size.toLong())
                ex.responseBody.use { it.write(b) }
            } else {
                Thread.sleep(150)
                ex.sendResponseHeaders(204, -1)
            }
            ex.close()
        }
        srv.createContext("/relay/$token/reply/") { ex: HttpExchange ->
            replies.add(JSONObject(ex.requestBody.readBytes().toString(Charsets.UTF_8)).put("id", ex.requestURI.path.substringAfterLast('/')))
            ex.sendResponseHeaders(200, -1)
            ex.close()
            if (replies.size >= jobs.size) got.complete(Unit)
        }
        srv.start()
        fake = srv
        return "http://127.0.0.1:${srv.address.port}"
    }

    private fun job(path: String, body: String, method: String = "POST") = JSONObject()
        .put("id", jobId).put("method", method).put("path", path)
        .put("headers", JSONObject().put("Content-Type", "application/json").put("Accept", "application/json"))
        .put("body_b64", Base64.getEncoder().encodeToString(body.toByteArray()))

    private val list = """{"jsonrpc":"2.0","id":1,"method":"tools/list"}"""

    private fun plan(base: String) = RelayPlan(localPort, base, token, RelayRunner.sha256Hex(secret))

    @Test fun replaysAJobAgainstTheLocalServerAndPostsTheAnswerBack() {
        val replies = CopyOnWriteArrayList<JSONObject>()
        val got = CompletableFuture<Unit>()
        val hash = CompletableFuture<String>()
        val base = startFakeRelay(listOf(job("/mcp/$secret", list)), replies, got, seenHash = hash)
        val ev = Events()
        runner = RelayRunner(scope, ev).also { it.start(plan(base)) }

        got.get(10, TimeUnit.SECONDS)
        assertEquals(base, ev.up.get(5, TimeUnit.SECONDS))
        assertEquals(RelayRunner.sha256Hex(secret), hash.get(5, TimeUnit.SECONDS))
        val r = replies.single()
        assertEquals(200, r.getInt("status"))
        assertEquals("application/json", r.getJSONObject("headers").getString("Content-Type"))
        val body = String(Base64.getDecoder().decode(r.getString("body_b64")))
        assertTrue(body, body.contains("echo"))
        assertEquals(jobId, r.getString("id"))
    }

    @Test fun anAnswerForAGetIsTheLocalServers405() {
        val replies = CopyOnWriteArrayList<JSONObject>()
        val got = CompletableFuture<Unit>()
        val base = startFakeRelay(listOf(job("/mcp/$secret", "", method = "GET")), replies, got)
        runner = RelayRunner(scope, Events()).also { it.start(plan(base)) }
        got.get(10, TimeUnit.SECONDS)
        assertEquals(405, replies.single().getInt("status"))
        assertEquals("POST", replies.single().getJSONObject("headers").getString("Allow"))
    }

    @Test fun aWrongSecretIsRefusedByTheLocalServerNotByTheRelay() {
        val replies = CopyOnWriteArrayList<JSONObject>()
        val got = CompletableFuture<Unit>()
        val base = startFakeRelay(listOf(job("/mcp/not-the-secret", list)), replies, got)
        runner = RelayRunner(scope, Events()).also { it.start(plan(base)) }
        got.get(10, TimeUnit.SECONDS)
        assertEquals(404, replies.single().getInt("status"))
    }

    @Test fun neverReplaysPathsOutsideMcp() {
        val replies = CopyOnWriteArrayList<JSONObject>()
        val got = CompletableFuture<Unit>()
        val base = startFakeRelay(listOf(job("/etc/passwd", list), job("//127.0.0.1/mcp/$secret", list)), replies, got)
        runner = RelayRunner(scope, Events()).also { it.start(plan(base)) }
        got.get(10, TimeUnit.SECONDS)
        assertEquals(2, replies.size)
        assertTrue(replies.all { it.getInt("status") == 404 })
    }

    @Test fun reportsARejectedToken() {
        val ev = Events()
        val base = startFakeRelay(emptyList(), CopyOnWriteArrayList(), CompletableFuture(), pollStatus = 404)
        runner = RelayRunner(scope, ev).also { it.start(plan(base)) }
        assertEquals(TunnelError.RELAY_REJECTED, ev.failed.get(10, TimeUnit.SECONDS))
    }

    @Test fun baseUrlAddsHttpsOnlyWhenNoSchemeIsGiven() {
        assertEquals("https://74.222.12.182", RelayRunner.baseUrl(" 74.222.12.182/ "))
        assertEquals("https://example.org:8443", RelayRunner.baseUrl("example.org:8443"))
        assertEquals("http://127.0.0.1:9", RelayRunner.baseUrl("http://127.0.0.1:9"))
        assertNotNull(RelayRunner.sha256Hex("x"))
        assertEquals(64, RelayRunner.sha256Hex("x").length)
    }
}
