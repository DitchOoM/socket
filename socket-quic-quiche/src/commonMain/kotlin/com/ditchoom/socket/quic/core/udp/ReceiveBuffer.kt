package com.ditchoom.socket.quic.core.udp

import com.ditchoom.socket.quic.PathKey
import com.ditchoom.socket.quic.core.pool.Pooled

/** One slot a datagram is received into; a [ReceiveBatch] lends it to the loop for one pass. */
internal class ReceiveBuffer(
    index: Int,
    val bytes: NativeBytes,
) : Pooled(index)

/** The local address a datagram arrived on, when the socket reports it. */
internal sealed interface ArrivedOn {
    data object Unreported : ArrivedOn

    class At(
        val local: PathKey,
    ) : ArrivedOn
}
