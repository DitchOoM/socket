@file:OptIn(ExperimentalAtomicApi::class)

package com.ditchoom.socket.quic.core.edge

import com.ditchoom.socket.quic.QuicAppErrorCode
import com.ditchoom.socket.quic.core.observe.Published
import kotlin.concurrent.atomics.AtomicReference
import kotlin.concurrent.atomics.ExperimentalAtomicApi

/** Whether a stream's reads met the peer's RESET_STREAM, and with which code. */
internal sealed interface PeerReset {
    data object NotMet : PeerReset

    class Reset(
        val code: QuicAppErrorCode,
    ) : PeerReset
}

/** The peer's reset of one stream, as its reads met it: set by the read that returned it, read by any thread. */
@Published
internal class PeerResetSeen {
    private val seen = AtomicReference<PeerReset>(PeerReset.NotMet)

    fun value(): PeerReset = seen.load()

    /** A read returned the peer's reset with [code]; the first one met stays. */
    fun met(code: QuicAppErrorCode) {
        seen.compareAndSet(PeerReset.NotMet, PeerReset.Reset(code))
    }
}
