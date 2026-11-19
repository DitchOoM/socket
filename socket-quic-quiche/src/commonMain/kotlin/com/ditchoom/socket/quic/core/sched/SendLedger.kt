@file:OptIn(ExperimentalAtomicApi::class)

package com.ditchoom.socket.quic.core.sched

import com.ditchoom.socket.quic.core.observe.Published
import com.ditchoom.socket.quic.core.udp.SendRefusal
import kotlin.concurrent.atomics.AtomicReference
import kotlin.concurrent.atomics.ExperimentalAtomicApi

/** What a socket's sends have come to. */
internal sealed interface SendRecord {
    /** Nothing was handed to the socket, or nothing was refused. */
    data object Quiet : SendRecord

    /** Every datagram the network was handed was refused; [last] is the latest refusal. */
    class Refused(
        val last: SendRefusal,
    ) : SendRecord

    /** The network took a datagram. */
    data object Taking : SendRecord
}

/** A socket's [SendRecord]: written by whichever thread sends, readable from any thread. [Taking] is final. */
@Published
internal class SendLedger {
    private val state = AtomicReference<SendRecord>(SendRecord.Quiet)

    fun view(): SendRecord = state.load()

    /** The network took a datagram. */
    fun taken() {
        if (state.load() !== SendRecord.Taking) state.store(SendRecord.Taking)
    }

    /** The network refused a datagram for [why]; ignored once it has taken one. */
    fun refused(why: SendRefusal) {
        while (true) {
            val seen = state.load()
            if (seen === SendRecord.Taking || state.compareAndSet(seen, SendRecord.Refused(why))) return
        }
    }
}
