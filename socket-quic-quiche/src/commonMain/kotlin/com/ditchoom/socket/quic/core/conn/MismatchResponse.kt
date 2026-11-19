package com.ditchoom.socket.quic.core.conn

/**
 * What a connection does with a datagram whose due time is a [Due.ClockMismatch]: chosen by whoever builds the endpoint,
 * carried by its [PacingRule], so no state outside the connection decides it.
 */
internal sealed interface MismatchResponse {
    /** Sends it at once, unpaced; the mismatch is counted and recorded. What a running library does. */
    data object SendNow : MismatchResponse

    /** Ends the connection, processing failed with a [PacingClockMismatch], so the test that caused it fails. */
    data object Fail : MismatchResponse
}

/**
 * What a connection that ends on a [MismatchResponse.Fail] carries as its cause: a datagram was due [aheadNanos] ahead of
 * the loop's clock, so libquiche and the loop read different clocks. Carried by the end, never thrown.
 */
internal class PacingClockMismatch(
    val aheadNanos: Long,
) : Throwable()
