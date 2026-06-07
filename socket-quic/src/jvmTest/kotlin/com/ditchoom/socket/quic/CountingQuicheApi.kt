package com.ditchoom.socket.quic

/**
 * A [QuicheApi] spy that delegates every call to a real [delegate] backend (via Kotlin interface
 * delegation) but counts reactive-keepalive PINGs. Lets an integration test assert that the driver,
 * running against the **real** native binding, actually invoked `quiche_conn_send_ack_eliciting` —
 * tightening the seam left by the stub-based driver-logic tests in `ReactiveDriverTests`.
 */
internal class CountingQuicheApi(
    private val delegate: QuicheApi,
) : QuicheApi by delegate {
    @Volatile
    var ackElicitingCount = 0
        private set

    override fun connSendAckEliciting(conn: QuicheConn): Int {
        ackElicitingCount++
        return delegate.connSendAckEliciting(conn)
    }
}
