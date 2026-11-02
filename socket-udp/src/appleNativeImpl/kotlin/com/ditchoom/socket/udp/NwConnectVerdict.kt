package com.ditchoom.socket.udp

import kotlinx.coroutines.CancellableContinuation
import kotlin.concurrent.AtomicInt
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** What one `nw_connection_t` state change means for a connect still waiting on it. */
internal sealed interface NwConnectVerdict {
    data object Ready : NwConnectVerdict

    data class Failed(
        val error: UdpConnectError,
    ) : NwConnectVerdict

    /** Not ready yet, and nothing says it cannot become ready: keep waiting. */
    data object Pending : NwConnectVerdict
}

/**
 * Settles a connect's continuation from NW state changes, exactly once.
 *
 * NW calls the state handler on a concurrent queue, and back-to-back changes (waiting → preparing →
 * waiting arrives within milliseconds) can run on two threads at once. A plain "already resumed" flag
 * let both read `false` and both resume; Kotlin/Native aborts the process on the second — three
 * iPhone walk crashes on 10-30/10-31, all `alreadyResumedError` in this handler.
 */
internal class NwConnectSettle(
    private val continuation: CancellableContinuation<Unit>,
) {
    private val settled = AtomicInt(0)

    fun on(verdict: NwConnectVerdict) {
        if (verdict == NwConnectVerdict.Pending || !settled.compareAndSet(0, 1)) return
        when (verdict) {
            NwConnectVerdict.Ready -> continuation.resume(Unit)
            is NwConnectVerdict.Failed -> continuation.resumeWithException(UdpConnectException(verdict.error))
            NwConnectVerdict.Pending -> Unit
        }
    }
}

/**
 * Read an NW state change for a connect.
 *
 * `waiting` is where NW parks a connection with no viable path — an IPv6 peer over an IPv4-only
 * Wi-Fi — and it stays there until some path appears. It carries no error in that case (observed:
 * waiting → preparing → waiting, every one with an empty error), so the only witness is the
 * connection's path, which NW reports `unsatisfied`. A connect that ignored it hung below the
 * caller's timeout and then reported only the timeout (279 s on one 10-26 walk attempt). Fail
 * instead with the [UdpConnectError.Unreachable] a POSIX `connect(2)` reports at once on Linux —
 * with the errno when NW gave one, [ERRNO_NOT_SURFACED] when only the path said so.
 */
internal fun nwConnectVerdict(
    state: Int,
    errorDomain: Int,
    errorCode: Int,
    pathStatus: Int,
): NwConnectVerdict =
    when (state) {
        NW_STATE_READY -> NwConnectVerdict.Ready
        // Typed in NW's own (domain, code) namespace: domain 1 is POSIX and the code is then the
        // errno. A `cancelled` state with no error is NW reporting that the connection went away
        // before it was ready.
        NW_STATE_FAILED, NW_STATE_CANCELLED -> NwConnectVerdict.Failed(UdpConnectError.PlatformError(errorDomain, errorCode))
        NW_STATE_WAITING -> waitingVerdict(errorDomain, errorCode, pathStatus)
        else -> NwConnectVerdict.Pending
    }

private fun waitingVerdict(
    errorDomain: Int,
    errorCode: Int,
    pathStatus: Int,
): NwConnectVerdict {
    if (errorDomain == NW_ERROR_DOMAIN_POSIX) {
        val error = connectErrnoToError(errorCode)
        if (error is UdpConnectError.Unreachable) return NwConnectVerdict.Failed(error)
    }
    return if (pathStatus == NW_PATH_UNSATISFIED) {
        NwConnectVerdict.Failed(UdpConnectError.Unreachable(ERRNO_NOT_SURFACED))
    } else {
        NwConnectVerdict.Pending
    }
}

// nw_connection_state_t values (nw_udp_helpers.h): 1=waiting, 3=ready, 4=failed, 5=cancelled.
internal const val NW_STATE_WAITING = 1
private const val NW_STATE_READY = 3
private const val NW_STATE_FAILED = 4
private const val NW_STATE_CANCELLED = 5
private const val NW_ERROR_DOMAIN_POSIX = 1

// nw_path_status_t values: 1=satisfied, 2=unsatisfied, 3=satisfiable.
internal const val NW_PATH_SATISFIED = 1
internal const val NW_PATH_UNSATISFIED = 2
internal const val NW_PATH_SATISFIABLE = 3
