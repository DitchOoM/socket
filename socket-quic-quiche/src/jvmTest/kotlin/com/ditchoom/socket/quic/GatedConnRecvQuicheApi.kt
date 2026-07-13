package com.ditchoom.socket.quic

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * A [QuicheApi] spy that delegates every call to a real [delegate] but can freeze the driver run
 * loop inside exactly one post-arm `connRecv`. Used by [ServerCloseRecvInfoRaceTest] to deterministically
 * pin a live driver mid-`connRecv` — holding a per-source recv_info cache ref (inFlight > 0) — while
 * the test drives `server.close()`, reproducing the exact interleaving of the #179 recv_info UAF
 * without racing a native packet.
 *
 * connRecv is a blocking native call on a Dispatchers.IO worker, so gating it with a [CountDownLatch]
 * (not a coroutine suspension) matches its real threading and blocks the run loop precisely where the
 * bug fired.
 */
internal class GatedConnRecvQuicheApi(
    private val delegate: QuicheApi,
) : QuicheApi by delegate {
    private val armed = AtomicBoolean(false)
    private val gatedOnce = AtomicBoolean(false)
    private val entered = CountDownLatch(1)
    private val release = CountDownLatch(1)

    /** Arm the gate: the next `connRecv` after this call freezes until [releaseGate]. Idempotent. */
    fun arm() = armed.set(true)

    /** Block until a `connRecv` has entered the gate (returns false on timeout). */
    fun awaitConnRecvBlocked(timeoutMs: Long): Boolean = entered.await(timeoutMs, TimeUnit.MILLISECONDS)

    /** Unfreeze the frozen `connRecv` so the run loop can finish it and drain. */
    fun releaseGate() = release.countDown()

    override fun connRecv(
        conn: QuicheConn,
        buf: Long,
        bufLen: Int,
        recvInfo: QuicheRecvInfo,
    ): Int {
        // One-shot: only the first connRecv after arming freezes; later drains pass straight through
        // so close()'s destroy→join can complete once the gate is released.
        if (armed.get() && gatedOnce.compareAndSet(false, true)) {
            entered.countDown()
            release.await()
        }
        return delegate.connRecv(conn, buf, bufLen, recvInfo)
    }
}
