package com.ditchoom.socket.quic

import kotlin.concurrent.atomics.AtomicLong
import kotlin.concurrent.atomics.ExperimentalAtomicApi
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
    /** Which end this connection is, so a client and its server on one seed never draw the same sequence. */
    role: QuicRole,
) : QuicheApi by delegate {
    /**
     * This connection's seed for quiche's own random draws, or none when they stay BoringSSL's. Each call
     * pins a state derived from it and from [calls], so the draws depend only on this connection's own
     * call sequence — not on the thread, nor on what another connection did in between.
     */
    private val entropy: QuicheEntropy =
        when (val e = clock.quicheEntropy()) {
            QuicheEntropy.Os -> e
            is QuicheEntropy.Seeded ->
                QuicheEntropy.Seeded(
                    mix(
                        e.seed xor
                            when (role) {
                                QuicRole.Client -> CLIENT_STREAM
                                QuicRole.Server -> SERVER_STREAM
                            },
                    ),
                )
        }

    @OptIn(ExperimentalAtomicApi::class)
    private val calls = AtomicLong(0L)

    /**
     * Push the driver's current virtual instant into libquiche for this thread, then run the quiche call.
     * Reads [DriverClock.quicheTime] at this exact synchronous moment so the injected nanos match the
     * virtual clock quiche is about to observe. [DriverTime.Real] is a no-op — nothing is injected.
     */
    private inline fun <T> synced(block: () -> T): T =
        when (val t = clock.quicheTime()) {
            DriverTime.Real -> block()
            is DriverTime.Virtual -> {
                delegate.setThreadVirtualTimeNanos(t.nanos)
                pinEntropy()
                try {
                    block()
                } finally {
                    unpinEntropy()
                    // The pin must not outlive the call. Driver coroutines migrate across pooled
                    // dispatcher threads, so a pin left behind poisons that OS thread for every
                    // later REAL-clock connection scheduled onto it — those then read a frozen
                    // instant on some calls and the real clock on others, thread by thread.
                    // cleanup()'s clearThreadVirtualTime() can only ever clear the one thread it
                    // happens to run on; scoping here is what actually bounds the pin.
                    delegate.clearThreadVirtualTime()
                }
            }
        }

    @OptIn(ExperimentalAtomicApi::class)
    private fun pinEntropy() =
        when (val e = entropy) {
            QuicheEntropy.Os -> Unit
            is QuicheEntropy.Seeded -> delegate.setThreadRandomState(mix(e.seed + calls.fetchAndAdd(1L) * GOLDEN_GAMMA))
        }

    private fun unpinEntropy() =
        when (entropy) {
            QuicheEntropy.Os -> Unit
            is QuicheEntropy.Seeded -> delegate.clearThreadRandomState()
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
    ): ProbeOutcome = synced { delegate.connProbePath(conn, localAddr, localLen, peerAddr, peerLen) }

    override fun connMigrate(
        conn: QuicheConn,
        localAddr: Long,
        localLen: Int,
        peerAddr: Long,
        peerLen: Int,
    ): MigrateOutcome = synced { delegate.connMigrate(conn, localAddr, localLen, peerAddr, peerLen) }

    override fun connRetireDcid(
        conn: QuicheConn,
        dcidSeq: Long,
    ): Int = synced { delegate.connRetireDcid(conn, dcidSeq) }

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

    override fun connPeerTransportParams(conn: QuicheConn): PeerTransportParams = synced { delegate.connPeerTransportParams(conn) }
}

private const val CLIENT_STREAM = 0x434C49454E54L // "CLIENT"

private const val SERVER_STREAM = 0x534552564552L // "SERVER"

private const val GOLDEN_GAMMA = -0x61c8864680b583ebL // 0x9E3779B97F4A7C15

/** splitmix64's finalizer: spreads adjacent inputs (call counts, roles) across the whole state space. */
private fun mix(x: Long): Long {
    var z = x
    z = (z xor (z ushr 30)) * -0x40a7b892e31b1a47L // 0xBF58476D1CE4E5B9
    z = (z xor (z ushr 27)) * -0x6b2fb644ecceee15L // 0x94D049BB133111EB
    return z xor (z ushr 31)
}
