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

/** Whether a refusal turned a quiet socket's record into [SendRecord.Refused]: the news its connection judges. */
internal enum class FirstRefusal { First, Again }

/** Whether the news of a socket's first refusal waits to be handed to its connection. */
internal enum class RefusalNews { Due, None }

/** A record and, once refused, whether the news of the first refusal is still due. */
private sealed interface LedgerMark {
    data object Quiet : LedgerMark

    class Refused(
        val record: SendRecord.Refused,
        val news: RefusalNews,
    ) : LedgerMark

    /** Final: news still due is dropped, as a handshake the network took from is never judged. */
    data object Taking : LedgerMark
}

private fun LedgerMark.record(): SendRecord =
    when (this) {
        is LedgerMark.Quiet -> SendRecord.Quiet
        is LedgerMark.Refused -> record
        is LedgerMark.Taking -> SendRecord.Taking
    }

/**
 * A socket's [SendRecord]: written by whichever thread sends, readable from any thread. [Taking] is final. The first
 * refusal of a quiet socket is news, taken once by the loop ([takeNews]) whichever thread's send drew it.
 */
@Published
internal class SendLedger {
    private val state = AtomicReference<LedgerMark>(LedgerMark.Quiet)

    fun view(): SendRecord = state.load().record()

    /** The network took a datagram. */
    fun taken() {
        if (state.load() !== LedgerMark.Taking) state.store(LedgerMark.Taking)
    }

    /** The network refused a datagram for [why]; ignored once it has taken one. */
    fun refused(why: SendRefusal): FirstRefusal {
        while (true) {
            val seen = state.load()
            val next =
                when (seen) {
                    is LedgerMark.Taking -> return FirstRefusal.Again
                    is LedgerMark.Quiet -> LedgerMark.Refused(SendRecord.Refused(why), RefusalNews.Due)
                    is LedgerMark.Refused -> LedgerMark.Refused(SendRecord.Refused(why), seen.news)
                }
            if (state.compareAndSet(seen, next)) return if (seen is LedgerMark.Quiet) FirstRefusal.First else FirstRefusal.Again
        }
    }

    /** Loop thread: the news of the first refusal, [RefusalNews.Due] once; a plain read while there is none. */
    fun takeNews(): RefusalNews {
        while (true) {
            val seen = state.load()
            if (seen !is LedgerMark.Refused || seen.news == RefusalNews.None) return RefusalNews.None
            if (state.compareAndSet(seen, LedgerMark.Refused(seen.record, RefusalNews.None))) return RefusalNews.Due
        }
    }
}
