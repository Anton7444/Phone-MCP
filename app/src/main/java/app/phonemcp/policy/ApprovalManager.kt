package app.phonemcp.policy

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.atomic.AtomicLong

data class ApprovalRequest(
    val id: Long,
    val command: String,
    val risks: Set<Risk>,
    val source: String,
    val createdAt: Long,
    val timeoutMs: Long,
    val step: Int = 1,
    val steps: Int = 1
)

enum class ApprovalOutcome { APPROVED, DENIED, TIMEOUT }

/**
 * Suspends a tool call until the phone's owner decides. One request is shown at a time;
 * the rest queue. Timeout counts as deny.
 */
class ApprovalManager(
    private val onShown: (ApprovalRequest) -> Unit,
    private val onCleared: () -> Unit
) {
    private val ids = AtomicLong(1)
    private val mutex = Mutex()
    private val _pending = MutableStateFlow<ApprovalRequest?>(null)
    val pending: StateFlow<ApprovalRequest?> = _pending.asStateFlow()
    @Volatile private var deferred: CompletableDeferred<Boolean>? = null

    suspend fun request(
        command: String, risks: Set<Risk>, source: String, timeoutMs: Long = DEFAULT_TIMEOUT_MS, step: Int = 1, steps: Int = 1
    ): ApprovalOutcome =
        mutex.withLock {
            val req = ApprovalRequest(ids.getAndIncrement(), command, risks, source, System.currentTimeMillis(), timeoutMs, step, steps)
            val d = CompletableDeferred<Boolean>()
            deferred = d
            _pending.value = req
            try {
                onShown(req)
                when (withTimeoutOrNull(timeoutMs) { d.await() }) {
                    true -> ApprovalOutcome.APPROVED
                    false -> ApprovalOutcome.DENIED
                    null -> ApprovalOutcome.TIMEOUT
                }
            } finally {
                _pending.value = null
                deferred = null
                onCleared()
            }
        }

    /** Called from the UI after biometric success (allow) or immediately (deny). */
    fun resolve(id: Long, allow: Boolean) {
        if (_pending.value?.id == id) deferred?.complete(allow)
    }

    companion object {
        const val DEFAULT_TIMEOUT_MS = 60_000L
    }
}
