package app.phonemcp.exec

import app.phonemcp.PRIVILEGED_CAPABILITIES
import app.phonemcp.PrivilegeMode
import app.phonemcp.ProbeFailure
import app.phonemcp.ProbeResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

data class RootCheck(
    val suFound: Boolean,
    val granted: Boolean,
    val uidOk: Boolean,
    val output: String
) {
    val ok get() = suFound && granted && uidOk
}

class RootExecutor : ShellBackedExecutor() {
    override val mode = PrivilegeMode.ROOT
    override val capabilities = PRIVILEGED_CAPABILITIES

    @Volatile private var hasTimeout: Boolean? = null

    private suspend fun timeoutAvailable(): Boolean {
        hasTimeout?.let { return it }
        val r = withContext(Dispatchers.IO) { ProcessRunner.run(listOf(SU, "-c", "command -v timeout"), null, 10_000, 1024) }
        return (r.exit == 0).also { hasTimeout = it }
    }

    override suspend fun execRaw(cmd: String, timeoutMs: Long, stdin: ByteArray?, maxBytes: Int): ProcessRunner.Raw {
        val wrap = timeoutAvailable()
        val wrapped = ShellCmd.wrap(cmd, timeoutMs, wrap)
        val grace = if (wrap) ShellCmd.GRACE_MS else 0L
        return withContext(Dispatchers.IO) { ProcessRunner.run(listOf(SU, "-c", wrapped), stdin, timeoutMs + grace, maxBytes) }
    }

    /** Three-step check used by the wizard: su exists, permission granted, `id` says uid=0. */
    suspend fun detailedCheck(): RootCheck = withContext(Dispatchers.IO) {
        val candidatesExist = SU_PATHS.any { File(it).exists() }
        // Generous timeout: the root manager shows a prompt the user has to tap.
        val r = ProcessRunner.run(listOf(SU, "-c", "id"), null, 60_000, 4096)
        val text = (String(r.out) + String(r.err)).trim()
        val notFound = r.exit == 127 && !candidatesExist
        if (notFound) return@withContext RootCheck(false, false, false, text)
        val granted = r.exit == 0
        RootCheck(true, granted, granted && Regex("""uid=0\b""").containsMatchIn(text), text)
    }

    override suspend fun probe(): ProbeResult {
        val c = detailedCheck()
        return when {
            !c.suFound -> ProbeResult(false, ProbeFailure.SU_MISSING, c.output)
            !c.granted -> ProbeResult(false, ProbeFailure.ROOT_DENIED, c.output)
            !c.uidOk -> ProbeResult(false, ProbeFailure.NOT_ROOT, c.output)
            else -> ProbeResult(true, null, c.output)
        }
    }

    override fun close() {}

    companion object {
        private const val SU = "su"
        val SU_PATHS = listOf(
            "/system/bin/su", "/system/xbin/su", "/sbin/su", "/su/bin/su", "/product/bin/su",
            "/debug_ramdisk/su", "/apex/com.android.runtime/bin/su", "/data/adb/ksu/bin/su", "/data/adb/magisk/su"
        )

        /** Cheap check for the mode-selection badge (no prompt). */
        fun suDetected(): Boolean = SU_PATHS.any { File(it).exists() } ||
            (System.getenv("PATH") ?: "").split(':').any { File(it, "su").exists() }
    }
}
