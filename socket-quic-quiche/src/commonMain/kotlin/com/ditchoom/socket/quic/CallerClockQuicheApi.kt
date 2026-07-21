package com.ditchoom.socket.quic

import kotlin.time.Duration

/**
 * A [QuicheApi] decorator that pins libquiche's per-thread virtual clock (RFC §6.1 caller-clock patch)
 * **immediately before** every connection operation that can advance quiche's recovery/timing state, so
 * the C library's internal `Instant::now()` reads see the driver's virtual time instead of the real wall
 * clock. This is what turns QUIC Tier-A simulation from "trace-prefix-exact ±1 datagram" into bit-exact:
 * loss detection, PTO, RTT sampling, pacing and congestion all become caller-clocked.
 *
 * **Why a decorator and not scattered calls.** Every quiche connection call the driver makes — from the
 * control loop *and* the per-path UDP reader loops — funnels through the one [QuicheApi] instance. Wrapping
 * here sets the clock in the *same synchronous frame* as each FFI call: same OS thread, no suspension and
 * therefore no virtual-time advance can slip between the push ([QuicheApi.setThreadVirtualTimeNanos]) and
 * quiche's read. Sprinkling `sync()` across the driver's many call sites would instead risk missing one —
 * a silent clock-drift, exactly the bug class the sealed [DriverTime] design exists to prevent.
 *
 * Installed **only** when [clock] reports [DriverTime.Virtual] (see [QuicheDriver]); production keeps the
 * bare backend api and pushes nothing, so libquiche keeps its own wall clock at zero cost. Pass-through for
 * every non-connection call (config, packet parse, cert, stream iterators) via `QuicheApi by delegate`.
 */
internal class CallerClockQuicheApi(
    private val delegate: QuicheApi,
    private val clock: DriverClock,
) : QuicheApi by delegate {
    /**
     * Push the driver's current virtual instant into libquiche for this thread, then run the quiche call.
     * Reads [DriverClock.quicheTime] at this exact synchronous moment so the injected nanos match the
     * virtual clock quiche is about to observe. [DriverTime.Real] is a no-op — nothing is injected.
     */
    private inline fun <T> synced(block: () -> T): T {
        when (val t = clock.quicheTime()) {
            DriverTime.Real -> {}
            is DriverTime.Virtual -> delegate.setThreadVirtualTimeNanos(t.nanos)
        }
        return block()
    }

    override fun connRecv(
        conn: QuicheConn,
        buf: Long,
        bufLen: Int,
        recvInfo: QuicheRecvInfo,
    ): Int = synced { delegate.connRecv(conn, buf, bufLen, recvInfo) }

    override fun connSend(
        conn: QuicheConn,
        buf: Long,
        bufLen: Int,
        sendInfo: QuicheSendInfo,
    ): Int = synced { delegate.connSend(conn, buf, bufLen, sendInfo) }

    override fun connStreamRecv(
        conn: QuicheConn,
        streamId: QuicStreamId,
        buf: Long,
        bufLen: Int,
    ): StreamRecvResult = synced { delegate.connStreamRecv(conn, streamId, buf, bufLen) }

    override fun connStreamSend(
        conn: QuicheConn,
        streamId: QuicStreamId,
        buf: Long,
        bufLen: Int,
        fin: Boolean,
    ): StreamSendResult = synced { delegate.connStreamSend(conn, streamId, buf, bufLen, fin) }

    override fun connStreamShutdown(
        conn: QuicheConn,
        streamId: QuicStreamId,
        direction: Int,
        err: Long,
    ): Int = synced { delegate.connStreamShutdown(conn, streamId, direction, err) }

    override fun connDgramSend(
        conn: QuicheConn,
        buf: Long,
        bufLen: Int,
    ): Int = synced { delegate.connDgramSend(conn, buf, bufLen) }

    override fun connDgramRecv(
        conn: QuicheConn,
        buf: Long,
        bufLen: Int,
    ): StreamRecvResult = synced { delegate.connDgramRecv(conn, buf, bufLen) }

    override fun connIsTimedOut(conn: QuicheConn): Boolean = synced { delegate.connIsTimedOut(conn) }

    override fun connTimeout(conn: QuicheConn): Duration? = synced { delegate.connTimeout(conn) }

    override fun connOnTimeout(conn: QuicheConn) = synced { delegate.connOnTimeout(conn) }

    override fun connSendAckEliciting(conn: QuicheConn): Int = synced { delegate.connSendAckEliciting(conn) }

    override fun connClose(
        conn: QuicheConn,
        error: QuicError,
    ): Int = synced { delegate.connClose(conn, error) }

    override fun connProbePath(
        conn: QuicheConn,
        localAddr: Long,
        localLen: Int,
        peerAddr: Long,
        peerLen: Int,
        seqOut: Long,
    ): Int = synced { delegate.connProbePath(conn, localAddr, localLen, peerAddr, peerLen, seqOut) }

    override fun connMigrate(
        conn: QuicheConn,
        localAddr: Long,
        localLen: Int,
        peerAddr: Long,
        peerLen: Int,
        seqOut: Long,
    ): Int = synced { delegate.connMigrate(conn, localAddr, localLen, peerAddr, peerLen, seqOut) }

    override fun connMigrateSource(
        conn: QuicheConn,
        localAddr: Long,
        localLen: Int,
        seqOut: Long,
    ): Int = synced { delegate.connMigrateSource(conn, localAddr, localLen, seqOut) }

    override fun connStats(conn: QuicheConn): QuicConnStats? = synced { delegate.connStats(conn) }

    override fun connPathStats(
        conn: QuicheConn,
        pathIdx: Long,
    ): QuicPathStats? = synced { delegate.connPathStats(conn, pathIdx) }
}
