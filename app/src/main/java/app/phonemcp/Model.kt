package app.phonemcp

enum class PrivilegeMode { ROOT, SHIZUKU, BASIC }

enum class TunnelType { QUICK, NAMED, TAILSCALE, LAN }

/** What a backend can do. Drives which MCP tools are advertised. */
enum class Capability { SHELL, FILES, SCREENSHOT, LOGCAT, DEVICE_INFO, UI_CONTROL, NOTIFICATIONS, SMS_CONTACTS }

enum class ProbeFailure {
    SU_MISSING, ROOT_DENIED, NOT_ROOT,
    SHIZUKU_MISSING, SHIZUKU_NOT_RUNNING, SHIZUKU_OLD, SHIZUKU_NO_PERMISSION, SHIZUKU_BIND_FAILED,
    TEST_FAILED, NO_STORAGE_ACCESS
}

data class ProbeResult(val ok: Boolean, val failure: ProbeFailure? = null, val detail: String = "")

data class ExecResult(
    val exit: Int,
    val stdout: String,
    val stderr: String,
    val timedOut: Boolean,
    val truncated: Boolean
)

/** Thrown by tools; [code] is returned to the AI client as the error prefix (BLOCKED, PATH_NOT_ALLOWED, ...). */
class ToolException(val code: String, message: String) : Exception(message)

/** Everything a shell-identity backend (Root, Shizuku) can offer. */
val PRIVILEGED_CAPABILITIES: Set<Capability> = Capability.values().toSet()

/** What the tool list can contain for a mode, used by the Settings screen before any server is running. */
fun capabilitiesFor(mode: PrivilegeMode?, storageAccess: Boolean): Set<Capability> = when (mode) {
    PrivilegeMode.ROOT, PrivilegeMode.SHIZUKU -> PRIVILEGED_CAPABILITIES
    PrivilegeMode.BASIC -> if (storageAccess) setOf(Capability.DEVICE_INFO, Capability.FILES) else setOf(Capability.DEVICE_INFO)
    null -> emptySet()
}
