package com.ditchoom.socket.quic.core.udp

import com.ditchoom.socket.quic.PathKey
import com.ditchoom.socket.quic.core.pool.Pooled

/** One slot a datagram is received into; a [ReceiveBatch] lends it to the loop for one pass. */
internal class ReceiveBuffer(
    index: Int,
    val bytes: NativeBytes,
) : Pooled(index)

/**
 * The local address a datagram arrived on, when the socket reports it: a socket bound to the wildcard does, so that a
 * reply leaves from the address its peer sent to; a socket bound to one address has nothing else to report.
 */
internal sealed interface ArrivedOn {
    data object Unreported : ArrivedOn

    class At(
        val local: PathKey,
    ) : ArrivedOn
}

/** The address a reply to the datagram leaves from: the one it arrived on, else the socket's [bound] address. */
internal fun ArrivedOn.replyFrom(bound: PathKey): PathKey =
    when (this) {
        is ArrivedOn.At -> local
        is ArrivedOn.Unreported -> bound
    }
