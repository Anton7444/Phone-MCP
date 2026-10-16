package app.phonemcp.exec

import app.phonemcp.Capability
import app.phonemcp.ExecResult
import app.phonemcp.PrivilegeMode
import app.phonemcp.ProbeResult
import app.phonemcp.ToolException

/** The seam between MCP tools and whatever privilege the phone gives us. */
interface Executor {
    val mode: PrivilegeMode
    val capabilities: Set<Capability>

    suspend fun probe(): ProbeResult
    suspend fun exec(cmd: String, timeoutMs: Long): ExecResult
    suspend fun readFile(path: String, offset: Long, length: Long): ByteArray
    suspend fun writeFile(path: String, data: ByteArray, append: Boolean)
    suspend fun listDir(path: String): String
    suspend fun fileInfo(path: String): String
    /** Resolves symlinks. Falls back to the input when the path cannot be resolved. */
    suspend fun canonicalize(path: String): String
    fun close()
}

const val MAX_READ_BYTES = 2L * 1024 * 1024

/** Reads a whole file in pieces (used for screenshots). */
suspend fun Executor.readFully(path: String, maxTotal: Long): ByteArray {
    val out = java.io.ByteArrayOutputStream()
    var offset = 0L
    val piece = 1024L * 1024
    while (offset < maxTotal) {
        val chunk = readFile(path, offset, minOf(piece, maxTotal - offset))
        if (chunk.isEmpty()) break
        out.write(chunk)
        offset += chunk.size
        if (chunk.size < piece) break
    }
    return out.toByteArray()
}

fun ProcessRunner.Raw.toResult() = ExecResult(
    exit = exit,
    stdout = String(out, Charsets.UTF_8),
    stderr = String(err, Charsets.UTF_8),
    timedOut = timedOut || ((exit == 124 || exit == 137) && err.isEmpty()),
    truncated = truncated
)

/** Shared implementation for Root and Shizuku: both reach the system through a shell. */
abstract class ShellBackedExecutor : Executor {
    protected abstract suspend fun execRaw(cmd: String, timeoutMs: Long, stdin: ByteArray?, maxBytes: Int): ProcessRunner.Raw

    override suspend fun exec(cmd: String, timeoutMs: Long): ExecResult =
        execRaw(cmd, timeoutMs, null, 400_000).toResult()

    override suspend fun readFile(path: String, offset: Long, length: Long): ByteArray {
        val q = ShellCmd.quote(path)
        val cmd = if (offset <= 0) "head -c $length $q" else "tail -c +${offset + 1} $q | head -c $length"
        val raw = execRaw(cmd, 30_000, null, (length + 16).coerceAtMost(Int.MAX_VALUE.toLong()).toInt())
        if (raw.out.isEmpty() && raw.err.isNotEmpty()) throw ToolException("IO_ERROR", String(raw.err).trim())
        return raw.out
    }

    override suspend fun writeFile(path: String, data: ByteArray, append: Boolean) {
        val redirect = if (append) ">>" else ">"
        val raw = execRaw("cat $redirect ${ShellCmd.quote(path)}", 30_000, data, 4096)
        if (raw.exit != 0) throw ToolException("IO_ERROR", String(raw.err).trim().ifEmpty { "write failed (exit ${raw.exit})" })
    }

    override suspend fun listDir(path: String): String {
        val r = exec("ls -la ${ShellCmd.quote(path)}", 15_000)
        if (r.exit != 0) throw ToolException("IO_ERROR", r.stderr.trim().ifEmpty { "ls failed (exit ${r.exit})" })
        return r.stdout
    }

    override suspend fun fileInfo(path: String): String {
        val r = exec("stat -c '%F|%s|%A|%a|%U|%G|%y' ${ShellCmd.quote(path)}", 15_000)
        if (r.exit != 0) throw ToolException("IO_ERROR", r.stderr.trim().ifEmpty { "stat failed (exit ${r.exit})" })
        val p = r.stdout.trim().split('|')
        if (p.size < 7) return r.stdout.trim()
        return "path: $path\ntype: ${p[0]}\nsize: ${p[1]} bytes\npermissions: ${p[2]} (${p[3]})\n" +
            "owner: ${p[4]}:${p[5]}\nmodified: ${p[6]}"
    }

    override suspend fun canonicalize(path: String): String {
        val r = try {
            exec("readlink -f ${ShellCmd.quote(path)}", 10_000)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            return path
        }
        val line = r.stdout.trim().lineSequence().firstOrNull().orEmpty()
        return if (r.exit == 0 && line.startsWith("/")) line else path
    }
}
