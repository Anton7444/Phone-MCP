package app.phonemcp.exec

import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.IBinder
import app.phonemcp.BuildConfig
import app.phonemcp.PRIVILEGED_CAPABILITIES
import app.phonemcp.IShellService
import app.phonemcp.PrivilegeMode
import app.phonemcp.ProbeFailure
import app.phonemcp.ProbeResult
import app.phonemcp.ToolException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import rikka.shizuku.Shizuku
import java.io.ByteArrayInputStream
import java.io.DataInputStream
import kotlin.coroutines.resume

data class ShizukuCheck(
    val installed: Boolean,
    val running: Boolean,
    val tooOld: Boolean,
    val granted: Boolean,
    val uidOk: Boolean,
    val output: String
) {
    val ok get() = installed && running && !tooOld && granted && uidOk
}

class ShizukuExecutor(private val ctx: Context) : ShellBackedExecutor() {
    override val mode = PrivilegeMode.SHIZUKU
    override val capabilities = PRIVILEGED_CAPABILITIES

    @Volatile private var service: IShellService? = null
    @Volatile private var waiting: CompletableDeferred<IShellService>? = null
    private val bindMutex = Mutex()
    private var bound = false

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            if (binder == null || !binder.pingBinder()) return
            val s = IShellService.Stub.asInterface(binder)
            service = s
            waiting?.complete(s)
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            service = null
        }
    }

    private fun userServiceArgs() = Shizuku.UserServiceArgs(ComponentName(ctx.packageName, ShellService::class.java.name))
        .processNameSuffix("shell")
        .daemon(false)
        .debuggable(BuildConfig.DEBUG)
        .version(BuildConfig.VERSION_CODE)

    // ---- state helpers (all safe to call when Shizuku is absent or dead) -------------------------

    fun isInstalled(): Boolean = try {
        ctx.packageManager.getPackageInfo(PACKAGE, 0); true
    } catch (_: PackageManager.NameNotFoundException) {
        false
    } catch (_: Exception) {
        false
    }

    fun isRunning(): Boolean = try { Shizuku.pingBinder() } catch (_: Throwable) { false }

    fun isTooOld(): Boolean = try { Shizuku.isPreV11() } catch (_: Throwable) { false }

    fun hasPermission(): Boolean = try {
        Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
    } catch (_: Throwable) {
        false
    }

    suspend fun requestPermission(): Boolean {
        if (!isRunning()) return false
        if (hasPermission()) return true
        return suspendCancellableCoroutine { cont ->
            val listener = object : Shizuku.OnRequestPermissionResultListener {
                override fun onRequestPermissionResult(requestCode: Int, grantResult: Int) {
                    if (requestCode != REQUEST_CODE) return
                    Shizuku.removeRequestPermissionResultListener(this)
                    if (cont.isActive) cont.resume(grantResult == PackageManager.PERMISSION_GRANTED)
                }
            }
            Shizuku.addRequestPermissionResultListener(listener)
            cont.invokeOnCancellation { Shizuku.removeRequestPermissionResultListener(listener) }
            try {
                Shizuku.requestPermission(REQUEST_CODE)
            } catch (_: Throwable) {
                Shizuku.removeRequestPermissionResultListener(listener)
                if (cont.isActive) cont.resume(false)
            }
        }
    }

    // ---- binding the UserService ------------------------------------------------------------------

    private suspend fun requireService(): IShellService = bindMutex.withLock {
        service?.let { if (it.asBinder().pingBinder()) return@withLock it }
        service = null
        if (!isRunning()) throw ToolException("MODE_NOT_SUPPORTED", "Shizuku is not running")
        if (!hasPermission()) throw ToolException("MODE_NOT_SUPPORTED", "Shizuku permission not granted")
        val d = CompletableDeferred<IShellService>()
        waiting = d
        try {
            Shizuku.bindUserService(userServiceArgs(), connection)
            bound = true
        } catch (t: Throwable) {
            throw ToolException("MODE_NOT_SUPPORTED", "Cannot bind Shizuku service: ${t.message}")
        }
        withTimeoutOrNull(10_000) { d.await() }
            ?: throw ToolException("MODE_NOT_SUPPORTED", "Shizuku service did not start in time")
    }

    override suspend fun execRaw(cmd: String, timeoutMs: Long, stdin: ByteArray?, maxBytes: Int): ProcessRunner.Raw =
        withContext(Dispatchers.IO) {
            val svc = requireService()
            val bytes = try {
                svc.exec(cmd, timeoutMs, stdin ?: ByteArray(0), maxBytes.toLong())
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                service = null
                throw ToolException("EXEC_FAILED", "Shizuku call failed: ${e.message}")
            }
            DataInputStream(ByteArrayInputStream(bytes)).use { d ->
                val exit = d.readInt()
                val timedOut = d.readBoolean()
                val truncated = d.readBoolean()
                val out = ByteArray(d.readInt()).also { d.readFully(it) }
                val err = ByteArray(d.readInt()).also { d.readFully(it) }
                ProcessRunner.Raw(exit, out, err, timedOut, truncated)
            }
        }

    override suspend fun readFile(path: String, offset: Long, length: Long): ByteArray = withContext(Dispatchers.IO) {
        val svc = requireService()
        val out = java.io.ByteArrayOutputStream()
        var off = offset
        var remaining = length
        while (remaining > 0) {
            val n = minOf(remaining, CHUNK.toLong()).toInt()
            val res = try {
                svc.readChunk(path, off, n)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                service = null
                throw ToolException("EXEC_FAILED", "Shizuku call failed: ${e.message}")
            }
            if (res.isEmpty() || res[0].toInt() != 0) {
                throw ToolException("IO_ERROR", if (res.size > 1) String(res, 1, res.size - 1) else "read failed")
            }
            val got = res.size - 1
            if (got == 0) break
            out.write(res, 1, got)
            off += got
            remaining -= got
            if (got < n) break
        }
        out.toByteArray()
    }

    override suspend fun writeFile(path: String, data: ByteArray, append: Boolean) = withContext(Dispatchers.IO) {
        val svc = requireService()
        var pos = 0
        var first = true
        do {
            val end = minOf(pos + CHUNK, data.size)
            val res = try {
                svc.writeChunk(path, data.copyOfRange(pos, end), if (first) append else true)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                service = null
                throw ToolException("EXEC_FAILED", "Shizuku call failed: ${e.message}")
            }
            if (res.isEmpty() || res[0].toInt() != 0) {
                throw ToolException("IO_ERROR", if (res.size > 1) String(res, 1, res.size - 1) else "write failed")
            }
            first = false
            pos = end
        } while (pos < data.size)
    }

    // ---- probing -----------------------------------------------------------------------------------

    /** [askPermission] shows the Shizuku permission dialog when it is the only thing missing. */
    suspend fun detailedCheck(askPermission: Boolean): ShizukuCheck {
        val installed = isInstalled()
        val running = isRunning()
        val old = running && isTooOld()
        if (!installed || !running || old) return ShizukuCheck(installed, running, old, false, false, "")
        var granted = hasPermission()
        if (!granted && askPermission) granted = requestPermission()
        if (!granted) return ShizukuCheck(true, true, false, false, false, "")
        return try {
            val r = exec("id", 15_000)
            val text = (r.stdout + r.stderr).trim()
            ShizukuCheck(true, true, false, true, r.exit == 0 && Regex("""uid=(2000|0)\b""").containsMatchIn(text), text)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            ShizukuCheck(true, true, false, true, false, e.message ?: "")
        }
    }

    /** Same verdicts as [probe] but never runs a command: safe to call every few seconds. */
    fun lightProbe(): ProbeResult = when {
        !isInstalled() -> ProbeResult(false, ProbeFailure.SHIZUKU_MISSING)
        !isRunning() -> ProbeResult(false, ProbeFailure.SHIZUKU_NOT_RUNNING)
        isTooOld() -> ProbeResult(false, ProbeFailure.SHIZUKU_OLD)
        !hasPermission() -> ProbeResult(false, ProbeFailure.SHIZUKU_NO_PERMISSION)
        else -> ProbeResult(true)
    }

    override suspend fun probe(): ProbeResult {
        val c = detailedCheck(askPermission = false)
        return when {
            !c.installed -> ProbeResult(false, ProbeFailure.SHIZUKU_MISSING)
            !c.running -> ProbeResult(false, ProbeFailure.SHIZUKU_NOT_RUNNING)
            c.tooOld -> ProbeResult(false, ProbeFailure.SHIZUKU_OLD)
            !c.granted -> ProbeResult(false, ProbeFailure.SHIZUKU_NO_PERMISSION)
            !c.uidOk -> ProbeResult(false, ProbeFailure.TEST_FAILED, c.output)
            else -> ProbeResult(true, null, c.output)
        }
    }

    override fun close() {
        if (bound) {
            try { Shizuku.unbindUserService(userServiceArgs(), connection, true) } catch (_: Throwable) {}
            bound = false
        }
        service = null
    }

    companion object {
        const val PACKAGE = "moe.shizuku.privileged.api"
        private const val REQUEST_CODE = 4242
        private const val CHUNK = 256 * 1024
    }
}
