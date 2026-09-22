@file:OptIn(ExperimentalDatagramApi::class)

package com.ditchoom.socket.quic

import com.ditchoom.buffer.flow.DatagramReadResult
import com.ditchoom.buffer.flow.ExperimentalDatagramApi

/**
 * The undelivered-element hook of every coroutine channel that hands a received datagram from one
 * coroutine to another: a payload that never reached a receiver goes back to its pool.
 *
 * Only the channel knows whether a hand-off happened. A `send` can throw `CancellationException`
 * after its receiver already has the datagram, so the sender cannot tell a completed hand-off from a
 * failed one; with this hook it never has to, and never frees a payload once it has offered it.
 */
internal fun freeUndeliveredDatagram(result: DatagramReadResult) {
    when (result) {
        is DatagramReadResult.Received -> result.datagram.payload.freeNativeMemory()
        is DatagramReadResult.Closed -> Unit
    }
}
