@file:OptIn(ExperimentalAtomicApi::class)

package com.ditchoom.socket.quic.core.conn

import com.ditchoom.socket.quic.core.observe.Published
import kotlin.concurrent.atomics.AtomicReference
import kotlin.concurrent.atomics.ExperimentalAtomicApi

/**
 * What a connection that ends on a [MismatchResponse.Fail] carries as its cause: a datagram was due [aheadNanos] ahead of
 * the loop's clock, so libquiche and the loop read different clocks. Carried by the end, never thrown.
 */
internal class PacingClockMismatch(
    val aheadNanos: Long,
) : Throwable()

/** What a connection does with a datagram whose due time is a [PacingClockMismatch]. */
internal sealed interface MismatchResponse {
    /** Sends it at once, unpaced; the mismatch is counted and recorded. What a running library does. */
    data object SendNow : MismatchResponse

    /** Ends the connection, processing failed with a [PacingClockMismatch], so the test that caused it fails. */
    data object Fail : MismatchResponse

    /** Where the response is chosen: [SendNow] unless a test run asked for [Fail]. */
    @Published
    companion object {
        private val chosen = AtomicReference<MismatchResponse>(SendNow)

        fun current(): MismatchResponse = chosen.load()

        /** Test runs only: from now on a mismatch ends the connection that sees it. */
        fun failTheTest() = choose(Fail)

        /** Test runs only: makes [response] the answer from now on; returns the one it replaced. */
        fun choose(response: MismatchResponse): MismatchResponse = chosen.exchange(response)
    }
}
