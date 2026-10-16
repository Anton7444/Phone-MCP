package app.phonemcp.exec

import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/** Android-free helpers shared by the app process (root) and the Shizuku UserService process. */
object ShellCmd {
    fun quote(s: String): String = "'" + s.replace("'", "'\\''") + "'"

    /** Wraps a command in toybox `timeout` so child processes die with it. */
    fun wrap(cmd: String, timeoutMs: Long, hasTimeout: Boolean): String {
        if (!hasTimeout) return cmd
        val secs = ((timeoutMs + 999) / 1000).coerceAtLeast(1)
        return "timeout -k 2 $secs sh -c ${quote(cmd)}"
    }

    const val GRACE_MS = 4000L
}

object ProcessRunner {
    class Raw(
        val exit: Int,
        val out: ByteArray,
        val err: ByteArray,
        val timedOut: Boolean,
        val truncated: Boolean
    )

    private class Bounded(private val max: Int) {
        val buf = ByteArrayOutputStream()
        @Volatile var truncated = false
        fun drain(input: InputStream) {
            val tmp = ByteArray(8192)
            try {
                while (true) {
                    val n = input.read(tmp)
                    if (n < 0) break
                    val room = max - buf.size()
                    if (room > 0) buf.write(tmp, 0, minOf(n, room))
                    if (n > room) truncated = true
                }
            } catch (_: Exception) {
            }
        }
    }

    /** Never throws for ordinary failures: a process that cannot start becomes exit 127 with the reason in stderr. */
    fun run(argv: List<String>, stdin: ByteArray?, timeoutMs: Long, maxBytes: Int): Raw {
        val process = try {
            ProcessBuilder(argv).start()
        } catch (e: Exception) {
            return Raw(127, ByteArray(0), (e.message ?: "cannot start process").toByteArray(), false, false)
        }
        val out = Bounded(maxBytes)
        val err = Bounded(maxBytes)
        val tOut = thread(isDaemon = true) { out.drain(process.inputStream) }
        val tErr = thread(isDaemon = true) { err.drain(process.errorStream) }
        val tIn = thread(isDaemon = true) {
            try {
                process.outputStream.use { os -> if (stdin != null && stdin.isNotEmpty()) os.write(stdin) }
            } catch (_: Exception) {
            }
        }
        val finished = try {
            process.waitFor(timeoutMs, TimeUnit.MILLISECONDS)
        } catch (e: InterruptedException) {
            process.destroyForcibly()
            throw e
        }
        if (!finished) process.destroyForcibly()
        tOut.join(2000)
        tErr.join(2000)
        tIn.join(500)
        val exit = if (finished) process.exitValue() else -1
        return Raw(exit, out.buf.toByteArray(), err.buf.toByteArray(), !finished, out.truncated || err.truncated)
    }
}
