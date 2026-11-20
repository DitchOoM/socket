package com.ditchoom.socket.quic.core.conn

/**
 * What a connection does when its first datagram finds libquiche's clock is not the loop's ([ClockVerdict.Differs]):
 * chosen by whoever builds the endpoint, carried by its [PacingRule], so no state outside the connection decides it.
 */
internal sealed interface MismatchResponse {
    /** The connection runs unpaced, its send times being on a clock it does not read; counted and recorded. What a running library does. */
    data object Unpaced : MismatchResponse

    /** Ends the connection, processing failed with a [PacingClockMismatch], so the test that caused it fails. */
    data object Fail : MismatchResponse
}

/**
 * What a connection that ends on a [MismatchResponse.Fail] carries as its cause: its first datagram was stamped
 * [offsetNanos] off the loop's clock (ahead when positive, behind when negative), so libquiche and the loop read
 * different clocks. Carried by the end, never thrown.
 */
internal class PacingClockMismatch(
    val offsetNanos: Long,
) : Throwable()
