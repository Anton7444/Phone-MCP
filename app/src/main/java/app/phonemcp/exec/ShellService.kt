package app.phonemcp.exec

import app.phonemcp.IShellService
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.FileOutputStream
import java.io.RandomAccessFile
import kotlin.system.exitProcess

/**
 * Started by Shizuku in its own process with shell (uid 2000) or root identity.
 * Must stay free of Android framework state: there is no Application here.
 */
class ShellService() : IShellService.Stub() {

    private val hasTimeout: Boolean by lazy {
        ProcessRunner.run(listOf("sh", "-c", "command -v timeout"), null, 3000, 1024).exit == 0
    }

    override fun destroy() {
        exitProcess(0)
    }

    override fun exec(cmd: String, timeoutMs: Long, stdin: ByteArray?, maxBytes: Long): ByteArray {
        return try {
            val cap = maxBytes.coerceIn(1024L, 400_000L).toInt()
            val wrapped = ShellCmd.wrap(cmd, timeoutMs, hasTimeout)
            val grace = if (wrapped != cmd) ShellCmd.GRACE_MS else 0L
            val raw = ProcessRunner.run(listOf("sh", "-c", wrapped), stdin, timeoutMs + grace, cap)
            val bos = ByteArrayOutputStream()
            DataOutputStream(bos).use { d ->
                d.writeInt(raw.exit)
                d.writeBoolean(raw.timedOut)
                d.writeBoolean(raw.truncated)
                d.writeInt(raw.out.size); d.write(raw.out)
                d.writeInt(raw.err.size); d.write(raw.err)
            }
            bos.toByteArray()
        } catch (t: Throwable) {
            val msg = (t.message ?: t.javaClass.simpleName).toByteArray()
            val bos = ByteArrayOutputStream()
            DataOutputStream(bos).use { d ->
                d.writeInt(127); d.writeBoolean(false); d.writeBoolean(false)
                d.writeInt(0); d.writeInt(msg.size); d.write(msg)
            }
            bos.toByteArray()
        }
    }

    override fun readChunk(path: String, offset: Long, length: Int): ByteArray {
        return try {
            RandomAccessFile(path, "r").use { f ->
                f.seek(offset)
                val buf = ByteArray(length.coerceIn(0, 512 * 1024))
                var total = 0
                while (total < buf.size) {
                    val n = f.read(buf, total, buf.size - total)
                    if (n < 0) break
                    total += n
                }
                byteArrayOf(0) + buf.copyOf(total)
            }
        } catch (t: Throwable) {
            byteArrayOf(1) + (t.message ?: t.javaClass.simpleName).toByteArray()
        }
    }

    override fun writeChunk(path: String, data: ByteArray, append: Boolean): ByteArray {
        return try {
            FileOutputStream(path, append).use { it.write(data) }
            byteArrayOf(0)
        } catch (t: Throwable) {
            byteArrayOf(1) + (t.message ?: t.javaClass.simpleName).toByteArray()
        }
    }
}
