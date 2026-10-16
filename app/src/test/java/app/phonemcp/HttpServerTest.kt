package app.phonemcp

import app.phonemcp.mcp.HttpServer
import app.phonemcp.mcp.McpRpc
import app.phonemcp.mcp.ToolHost
import app.phonemcp.mcp.ToolResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.net.ServerSocket
import java.net.Socket

/** Real sockets against the real server, so the hand-written HTTP layer is exercised end to end. */
class HttpServerTest {
    private val secret = "s3cret-abcdefghijklmnopqrstuvwxyz0123456789ABC"
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var port = 0
    private lateinit var server: HttpServer

    private val host = object : ToolHost {
        override fun listTools() = listOf(JSONObject().put("name", "echo"))
        override suspend fun callTool(name: String, args: JSONObject) = ToolResult.text("pong")
    }

    @Before fun start() {
        port = ServerSocket(0).use { it.localPort }
        server = HttpServer(port, "127.0.0.1", { secret }, McpRpc(host, "t"), scope)
        server.start()
    }

    @After fun stop() {
        server.stop()
        scope.cancel()
    }

    private fun raw(request: String): String =
        Socket("127.0.0.1", port).use { s ->
            s.soTimeout = 5000
            s.getOutputStream().write(request.toByteArray())
            s.getOutputStream().flush()
            s.getInputStream().readBytes().toString(Charsets.UTF_8)
        }

    private fun post(path: String, body: String, extra: String = "") =
        raw("POST $path HTTP/1.1\r\nHost: x.trycloudflare.com\r\nContent-Type: application/json\r\nContent-Length: ${body.toByteArray().size}\r\n$extra\r\n$body")

    private val list = """{"jsonrpc":"2.0","id":1,"method":"tools/list"}"""

    @Test fun answersToolsListOnSecretPath() {
        val r = post("/mcp/$secret", list)
        assertTrue(r.startsWith("HTTP/1.1 200"))
        assertTrue(r.contains("application/json"))
        assertTrue(r.contains("\"echo\""))
    }

    @Test fun wrongOrMissingSecretIs404WithNoHints() {
        for (p in listOf("/mcp/wrong", "/mcp", "/", "/mcp/$secret/extra", "/.well-known/oauth-protected-resource")) {
            val r = post(p, list)
            assertTrue("path $p -> ${r.lineSequence().first()}", r.startsWith("HTTP/1.1 404"))
            assertFalse("404 must not mention mcp", r.contains("mcp"))
            assertFalse(r.contains(secret))
        }
    }

    @Test fun unauthorisedBodyIsNotReadAtAll() {
        // Declares a 1 MB body but never sends it: must still get an immediate 404, not hang waiting for the body.
        val r = raw("POST /nope HTTP/1.1\r\nHost: x\r\nContent-Length: 1000000\r\n\r\n")
        assertTrue(r.startsWith("HTTP/1.1 404"))
    }

    @Test fun getIs405() {
        assertTrue(raw("GET /mcp/$secret HTTP/1.1\r\nHost: x\r\n\r\n").startsWith("HTTP/1.1 405"))
    }

    @Test fun notificationGets202() {
        val r = post("/mcp/$secret", """{"jsonrpc":"2.0","method":"notifications/initialized"}""")
        assertTrue(r.startsWith("HTTP/1.1 202"))
    }

    @Test fun chunkedBodiesAreDecoded() {
        val half = list.length / 2
        val a = list.substring(0, half)
        val b = list.substring(half)
        val req = "POST /mcp/$secret HTTP/1.1\r\nHost: x\r\nTransfer-Encoding: chunked\r\n\r\n" +
            "${a.length.toString(16)}\r\n$a\r\n${b.length.toString(16)}\r\n$b\r\n0\r\n\r\n"
        assertTrue(raw(req).contains("\"echo\""))
    }

    @Test fun hugeBodyIsRejected() {
        val r = raw("POST /mcp/$secret HTTP/1.1\r\nHost: x\r\nContent-Length: 99999999\r\n\r\n")
        assertTrue(r.startsWith("HTTP/1.1 413"))
    }

    @Test fun garbageDoesNotKillTheServer() {
        raw("NOT HTTP AT ALL\r\n\r\n")
        assertTrue(post("/mcp/$secret", list).startsWith("HTTP/1.1 200"))
    }

    @Test fun repeatedBadPathsGetRateLimitedButRealEndpointStillWorks() {
        var limited = false
        repeat(40) { if (post("/nope", "{}").startsWith("HTTP/1.1 429")) limited = true }
        assertTrue(limited)
        assertEquals(true, post("/mcp/$secret", list).startsWith("HTTP/1.1 200"))
    }
}
