package com.ditchoom.socket.quic

import com.ditchoom.socket.udp.UdpConnectError
import com.ditchoom.socket.udp.UdpConnectException

/** What refused a route probe's connect: socket-udp's classified refusal, or something it did not classify. */
internal sealed interface ProbeRefusal {
    class Connect(
        val error: UdpConnectError,
        val thrown: Throwable,
    ) : ProbeRefusal

    class Other(
        val thrown: Throwable,
    ) : ProbeRefusal
}

/** The refusal this probe failure carries, read once at the socket-udp boundary. */
@OptIn(InternalQuicApi::class)
internal fun RouteProbeFailure.ProbeRefused.refusal(): ProbeRefusal =
    when (val thrown = cause) {
        is UdpConnectException -> ProbeRefusal.Connect(thrown.error, thrown)
        else -> ProbeRefusal.Other(thrown)
    }
