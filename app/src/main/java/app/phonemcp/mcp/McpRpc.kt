package app.phonemcp.mcp

import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

class ToolResult(val content: JSONArray, val isError: Boolean) {
    companion object {
        fun text(s: String, isError: Boolean = false) =
            ToolResult(JSONArray().put(JSONObject().put("type", "text").put("text", s)), isError)

        fun error(code: String, message: String) = text("$code: $message", true)
    }
}

interface ToolHost {
    fun listTools(): List<JSONObject>
    suspend fun callTool(name: String, args: JSONObject): ToolResult
}

/** Stateless MCP over plain JSON-RPC (Streamable HTTP, no SSE, no sessions). */
class McpRpc(private val host: ToolHost, private val serverVersion: String) {

    /** Returns the response body, or null when there is nothing to send back (notifications only). */
    suspend fun handle(body: String): String? {
        val parsed: Any = try {
            val t = body.trim()
            if (t.startsWith("[")) JSONArray(t) else JSONObject(t)
        } catch (_: JSONException) {
            return error(JSONObject.NULL, -32700, "Parse error").toString()
        }
        if (parsed is JSONArray) {
            if (parsed.length() == 0) return error(JSONObject.NULL, -32600, "Empty batch").toString()
            val out = JSONArray()
            for (i in 0 until parsed.length()) {
                val m = parsed.optJSONObject(i)
                val r = if (m == null) error(JSONObject.NULL, -32600, "Invalid Request") else handleOne(m)
                if (r != null) out.put(r)
            }
            return if (out.length() == 0) null else out.toString()
        }
        return handleOne(parsed as JSONObject)?.toString()
    }

    private suspend fun handleOne(msg: JSONObject): JSONObject? {
        val hasId = msg.has("id") && !msg.isNull("id")
        val id: Any = if (hasId) msg.get("id") else JSONObject.NULL
        val method = msg.optString("method", "")
        if (method.isEmpty()) {
            // A response from the client, or garbage. Responses need no reply.
            return if (msg.has("result") || msg.has("error")) null else error(id, -32600, "Invalid Request")
        }
        if (!hasId) return null // notification (e.g. notifications/initialized)
        val params = msg.optJSONObject("params") ?: JSONObject()
        return try {
            when (method) {
                "initialize" -> ok(id, initializeResult(params))
                "ping" -> ok(id, JSONObject())
                "tools/list" -> ok(id, JSONObject().put("tools", JSONArray(host.listTools())))
                "tools/call" -> {
                    val name = params.optString("name", "")
                    if (name.isEmpty()) return error(id, -32602, "Missing tool name")
                    val args = params.optJSONObject("arguments") ?: JSONObject()
                    val r = host.callTool(name, args)
                    ok(id, JSONObject().put("content", r.content).put("isError", r.isError))
                }
                else -> error(id, -32601, "Method not found: $method")
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            error(id, -32603, "Internal error")
        }
    }

    private fun initializeResult(params: JSONObject): JSONObject {
        val requested = params.optString("protocolVersion", "")
        val version = if (requested in SUPPORTED) requested else SUPPORTED[0]
        return JSONObject()
            .put("protocolVersion", version)
            .put("capabilities", JSONObject().put("tools", JSONObject().put("listChanged", false)))
            .put("serverInfo", JSONObject().put("name", "phone-mcp").put("title", "Phone MCP").put("version", serverVersion))
            .put(
                "instructions",
                "Phone MCP gives access to an Android phone. Dangerous actions pause until the owner approves them on the phone itself."
            )
    }

    private fun ok(id: Any, result: JSONObject) = JSONObject().put("jsonrpc", "2.0").put("id", id).put("result", result)

    private fun error(id: Any, code: Int, message: String) = JSONObject().put("jsonrpc", "2.0").put("id", id)
        .put("error", JSONObject().put("code", code).put("message", message))

    companion object {
        val SUPPORTED = listOf("2025-06-18", "2025-03-26", "2024-11-05")
    }
}
