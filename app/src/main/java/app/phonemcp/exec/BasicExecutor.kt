package app.phonemcp.exec

import android.os.Environment
import app.phonemcp.Capability
import app.phonemcp.ExecResult
import app.phonemcp.PrivilegeMode
import app.phonemcp.ProbeResult
import app.phonemcp.ToolException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.RandomAccessFile
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * No privilege. File tools use plain java.io.File and work only when the user grants
 * "All files access"; otherwise only device_info is offered.
 */
class BasicExecutor : Executor {
    override val mode = PrivilegeMode.BASIC

    override val capabilities: Set<Capability>
        get() = if (hasStorageAccess()) setOf(Capability.DEVICE_INFO, Capability.FILES) else setOf(Capability.DEVICE_INFO)

    override suspend fun probe(): ProbeResult = ProbeResult(true, null, if (hasStorageAccess()) "files" else "device-info-only")

    override suspend fun exec(cmd: String, timeoutMs: Long): ExecResult =
        throw ToolException("MODE_NOT_SUPPORTED", "Shell access needs Root or Shizuku mode")

    private fun fileOf(path: String): File {
        if (!hasStorageAccess()) throw ToolException("MODE_NOT_SUPPORTED", "Grant 'All files access' to Phone MCP first")
        return File(path)
    }

    override suspend fun readFile(path: String, offset: Long, length: Long): ByteArray = withContext(Dispatchers.IO) {
        val f = fileOf(path)
        try {
            RandomAccessFile(f, "r").use { raf ->
                raf.seek(offset)
                val buf = ByteArray(length.coerceIn(0, MAX_READ_BYTES).toInt())
                var total = 0
                while (total < buf.size) {
                    val n = raf.read(buf, total, buf.size - total)
                    if (n < 0) break
                    total += n
                }
                buf.copyOf(total)
            }
        } catch (e: java.io.IOException) {
            throw ToolException("IO_ERROR", e.message ?: "read failed")
        }
    }

    override suspend fun writeFile(path: String, data: ByteArray, append: Boolean) = withContext(Dispatchers.IO) {
        val f = fileOf(path)
        try {
            java.io.FileOutputStream(f, append).use { it.write(data) }
        } catch (e: java.io.IOException) {
            throw ToolException("IO_ERROR", e.message ?: "write failed")
        }
    }

    override suspend fun listDir(path: String): String = withContext(Dispatchers.IO) {
        val dir = fileOf(path)
        val items = dir.listFiles() ?: throw ToolException("IO_ERROR", "Not a readable directory: $path")
        val fmt = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US)
        items.sortedWith(compareBy({ !it.isDirectory }, { it.name.lowercase() })).joinToString("\n") {
            val kind = if (it.isDirectory) "d" else "-"
            "$kind ${it.length().toString().padStart(10)} ${fmt.format(Date(it.lastModified()))} ${it.name}"
        }
    }

    override suspend fun fileInfo(path: String): String = withContext(Dispatchers.IO) {
        val f = fileOf(path)
        if (!f.exists()) throw ToolException("IO_ERROR", "No such file or directory: $path")
        "path: $path\ntype: ${if (f.isDirectory) "directory" else "file"}\nsize: ${f.length()} bytes\n" +
            "permissions: ${if (f.canRead()) "r" else "-"}${if (f.canWrite()) "w" else "-"}${if (f.canExecute()) "x" else "-"}\n" +
            "modified: ${SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date(f.lastModified()))}"
    }

    override suspend fun canonicalize(path: String): String = withContext(Dispatchers.IO) {
        try { File(path).canonicalPath } catch (_: Exception) { path }
    }

    override fun close() {}

    companion object {
        fun hasStorageAccess(): Boolean = try { Environment.isExternalStorageManager() } catch (_: Throwable) { false }
    }
}
