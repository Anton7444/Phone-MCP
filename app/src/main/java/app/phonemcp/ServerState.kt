package app.phonemcp

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

enum class RunStatus { STOPPED, STARTING, RUNNING, ERROR }
enum class TunnelStatus { OFF, STARTING, UP, RESTARTING, ERROR }
enum class StartError { NO_MODE, PROBE_FAILED, PORT_IN_USE, INTERNAL }
enum class TunnelError { NO_BINARY, TIMEOUT, NO_TOKEN, NO_HOSTNAME, NO_NETWORK, PROCESS_FAILED }

data class ServerSnapshot(
    val status: RunStatus = RunStatus.STOPPED,
    val startError: StartError? = null,
    val probeFailure: ProbeFailure? = null,
    val errorDetail: String = "",
    val mode: PrivilegeMode? = null,
    val port: Int = 0,
    val tunnelType: TunnelType = TunnelType.QUICK,
    val tunnelStatus: TunnelStatus = TunnelStatus.OFF,
    val tunnelError: TunnelError? = null,
    val tunnelDetail: String = "",
    /** Public base URL without the secret path, e.g. https://x.trycloudflare.com */
    val publicBase: String? = null,
    val startedAt: Long = 0,
    val calls: Int = 0,
    val wakeLockHeld: Boolean = false,
    val wifiLockHeld: Boolean = false,
    /** True after a running tunnel came back with a different URL. */
    val urlChanged: Boolean = false,
    /** SHA-256 of the LAN-mode TLS certificate; clients pin this. Null outside LAN mode. */
    val tlsFingerprint: String? = null
) {
    val active get() = status == RunStatus.STARTING || status == RunStatus.RUNNING
}

/** Single source of truth shared by the service, notification and UI. */
class ServerState {
    private val _flow = MutableStateFlow(ServerSnapshot())
    val flow: StateFlow<ServerSnapshot> = _flow.asStateFlow()
    val value get() = _flow.value

    fun update(f: (ServerSnapshot) -> ServerSnapshot) = _flow.update(f)
}
