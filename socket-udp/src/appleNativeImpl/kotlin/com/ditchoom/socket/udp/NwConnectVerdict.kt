package com.ditchoom.socket.udp

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
 * Read an NW state change for a connect.
 *
 * `waiting` is where NW parks a connection with no viable path — an IPv6 peer over an IPv4-only
 * Wi-Fi — and it stays there, with the reason in hand, until some path appears. A connect that
 * ignored it hung for minutes below the caller's timeout and then reported only the timeout (279 s on
 * one 10-26 walk attempt). When the reason is one a POSIX `connect(2)` would have failed with at once,
 * fail with that same [UdpConnectError.Unreachable], as Linux does; any other wait stays pending.
 */
internal fun nwConnectVerdict(
    state: Int,
    errorDomain: Int,
    errorCode: Int,
): NwConnectVerdict =
    when (state) {
        NW_STATE_READY -> NwConnectVerdict.Ready
        // Typed in NW's own (domain, code) namespace: domain 1 is POSIX and the code is then the
        // errno. A `cancelled` state with no error is NW reporting that the connection went away
        // before it was ready.
        NW_STATE_FAILED, NW_STATE_CANCELLED -> NwConnectVerdict.Failed(UdpConnectError.PlatformError(errorDomain, errorCode))
        NW_STATE_WAITING ->
            if (errorDomain == NW_ERROR_DOMAIN_POSIX) {
                when (val error = connectErrnoToError(errorCode)) {
                    is UdpConnectError.Unreachable -> NwConnectVerdict.Failed(error)
                    else -> NwConnectVerdict.Pending
                }
            } else {
                NwConnectVerdict.Pending
            }
        else -> NwConnectVerdict.Pending
    }

// nw_connection_state_t values (nw_udp_helpers.h): 1=waiting, 3=ready, 4=failed, 5=cancelled.
internal const val NW_STATE_WAITING = 1
private const val NW_STATE_READY = 3
private const val NW_STATE_FAILED = 4
private const val NW_STATE_CANCELLED = 5
private const val NW_ERROR_DOMAIN_POSIX = 1
