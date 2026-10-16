package app.phonemcp.mcp

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicInteger

/**
 * Minimal HTTP/1.1 server for one endpoint: POST /mcp/<secret>.
 * Everything else is a bare 404 that reveals nothing. Hand-written on purpose: one fewer fast-moving
 * dependency, and the request surface is tiny (see docs/decisions.md).
 */
class HttpServer(
    private val port: Int,
    private val bindHost: String,
    private val secret: () -> String,
    private val rpc: McpRpc,
    private val scope: CoroutineScope,
    /** When set, the listener speaks TLS (LAN mode). */
    private val tls: javax.net.ssl.SSLServerSocketFactory? = null
) {
    private var server: ServerSocket? = null
    private val badPathTimes = ArrayDeque<Long>()
    private val active = AtomicInteger()
    private val open = java.util.concurrent.ConcurrentHashMap.newKeySet<Socket>()

    /** Throws [IOException] (e.g. BindException) if the port cannot be bound. */
    fun start() {
        val s = tls?.createServerSocket() ?: ServerSocket()
        s.reuseAddress = true
        s.bind(InetSocketAddress(InetAddress.getByName(bindHost), port))
        server = s
        Thread({
            while (!s.isClosed) {
                val c = try { s.accept() } catch (_: IOException) { break }
                if (!scope.isActive) {
                    try { c.close() } catch (_: IOException) {}
                    break
                }
                if (active.incrementAndGet() > MAX_CONNECTIONS) {
                    active.decrementAndGet()
                    try { c.close() } catch (_: IOException) {}
                    continue
                }
                scope.launch(Dispatchers.IO) {
                    try { serve(c) } finally { active.decrementAndGet() }
                }
            }
        }, "mcp-accept").apply { isDaemon = true }.start()
    }

    fun stop() {
        try { server?.close() } catch (_: IOException) {}
        server = null
        // Sockets accepted earlier must not outlive a restart (e.g. a secret rotation).
        for (c in open.toList()) try { c.close() } catch (_: IOException) {}
        open.clear()
    }

    private class Request(val method: String, val path: String, val headers: Map<String, String>, val body: ByteArray)

    private class HttpError(val status: Int) : Exception()

    private suspend fun serve(sock: Socket) {
        open.add(sock)
        sock.use {
            try {
                sock.soTimeout = 30_000
                val req = readRequest(BufferedInputStream(sock.getInputStream()), sock)
                val (status, headers, body) = route(req)
                write(sock, status, headers, body)
            } catch (e: HttpError) {
                tryWrite(sock, e.status)
            } catch (_: IOException) {
                // client went away
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (_: Exception) {
                tryWrite(sock, 500)
            } catch (_: StackOverflowError) {
                tryWrite(sock, 500) // one pathological request must not take the process down
            } finally {
                open.remove(sock)
            }
        }
    }

    private suspend fun route(req: Request): Triple<Int, List<Pair<String, String>>, ByteArray> {
        // The path was already checked in readRequest, before any body was read.
        if (req.method != "POST") {
            return Triple(405, listOf("Allow" to "POST"), ByteArray(0))
        }
        val reply = rpc.handle(String(req.body, Charsets.UTF_8))
        return if (reply == null) Triple(202, emptyList(), ByteArray(0))
        else Triple(200, listOf("Content-Type" to "application/json"), reply.toByteArray(Charsets.UTF_8))
    }

    private fun isAuthorised(rawPath: String): Boolean {
        val path = rawPath.substringBefore('?').trimEnd('/')
        val prefix = "/mcp/"
        val given = if (path.startsWith(prefix)) path.removePrefix(prefix) else return false
        return MessageDigest.isEqual(given.toByteArray(), secret().toByteArray())
    }

    @Synchronized
    private fun tooManyBadRequests(): Boolean {
        val now = System.currentTimeMillis()
        while (badPathTimes.isNotEmpty() && now - badPathTimes.first() > 60_000) badPathTimes.removeFirst()
        badPathTimes.addLast(now)
        return badPathTimes.size > 30
    }

    // ---- HTTP parsing -------------------------------------------------------------------------------

    private fun readLine(input: InputStream, limit: Int = 8192): String? {
        val sb = StringBuilder()
        while (true) {
            val b = input.read()
            if (b < 0) return if (sb.isEmpty()) null else sb.toString()
            if (b == '\n'.code) return sb.toString().trimEnd('\r')
            sb.append(b.toChar())
            if (sb.length > limit) throw HttpError(431)
        }
    }

    private fun readRequest(input: InputStream, sock: Socket): Request {
        val first = readLine(input) ?: throw IOException("empty")
        val parts = first.split(' ')
        if (parts.size < 3) throw HttpError(400)
        val headers = HashMap<String, String>()
        var total = 0
        while (true) {
            val line = readLine(input) ?: throw IOException("eof")
            if (line.isEmpty()) break
            total += line.length
            if (total > 32_768) throw HttpError(431)
            val i = line.indexOf(':')
            if (i > 0) headers[line.substring(0, i).trim().lowercase()] = line.substring(i + 1).trim()
        }
        if (!isAuthorised(parts[1])) {
            // Reject before reading the body: unauthenticated callers get no free memory or time from us.
            val len = headers["content-length"]?.toLongOrNull() ?: 0L
            if (len in 1..8192) { try { ByteArray(len.toInt()).also { readFully(input, it) } } catch (_: IOException) {} }
            throw HttpError(if (tooManyBadRequests()) 429 else 404)
        }
        if (headers["expect"]?.equals("100-continue", true) == true) {
            sock.getOutputStream().write("HTTP/1.1 100 Continue\r\n\r\n".toByteArray())
            sock.getOutputStream().flush()
        }
        val body = when {
            headers["transfer-encoding"]?.contains("chunked", true) == true -> readChunked(input)
            headers.containsKey("content-length") -> {
                val len = headers["content-length"]?.toLongOrNull() ?: throw HttpError(400)
                if (len < 0) throw HttpError(400)
                if (len > MAX_BODY) throw HttpError(413)
                ByteArray(len.toInt()).also { readFully(input, it) }
            }
            else -> ByteArray(0)
        }
        return Request(parts[0].uppercase(), parts[1], headers, body)
    }

    private fun readFully(input: InputStream, buf: ByteArray) {
        var off = 0
        while (off < buf.size) {
            val n = input.read(buf, off, buf.size - off)
            if (n < 0) throw IOException("eof")
            off += n
        }
    }

    private fun readChunked(input: InputStream): ByteArray {
        val out = ByteArrayOutputStream()
        while (true) {
            val line = readLine(input) ?: throw IOException("eof")
            val size = line.substringBefore(';').trim().toIntOrNull(16) ?: throw HttpError(400)
            if (size == 0) {
                while (true) { if (readLine(input).isNullOrEmpty()) break } // trailers
                break
            }
            if (out.size() + size > MAX_BODY) throw HttpError(413)
            val chunk = ByteArray(size)
            readFully(input, chunk)
            out.write(chunk)
            readLine(input) // CRLF after chunk
        }
        return out.toByteArray()
    }

    // ---- responses ----------------------------------------------------------------------------------

    private fun write(sock: Socket, status: Int, headers: List<Pair<String, String>>, body: ByteArray) {
        val sb = StringBuilder("HTTP/1.1 $status ${reason(status)}\r\n")
        headers.forEach { (k, v) -> sb.append(k).append(": ").append(v).append("\r\n") }
        sb.append("Content-Length: ").append(body.size).append("\r\nConnection: close\r\n\r\n")
        val os = sock.getOutputStream()
        os.write(sb.toString().toByteArray(Charsets.ISO_8859_1))
        os.write(body)
        os.flush()
    }

    private fun tryWrite(sock: Socket, status: Int) {
        try { write(sock, status, emptyList(), ByteArray(0)) } catch (_: Exception) {}
    }

    private fun reason(status: Int) = when (status) {
        200 -> "OK"; 202 -> "Accepted"; 400 -> "Bad Request"; 404 -> "Not Found"; 405 -> "Method Not Allowed"
        413 -> "Payload Too Large"; 429 -> "Too Many Requests"; 431 -> "Request Header Fields Too Large"
        else -> "Error"
    }

    companion object {
        private const val MAX_BODY = 1_048_576L
        private const val MAX_CONNECTIONS = 64
    }
}
