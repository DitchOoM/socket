package com.ditchoom.socket.quic

import kotlinx.coroutines.CompletableDeferred
import kotlin.concurrent.Volatile

/**
 * A [QuicheApi] spy that holds the server's *routing table* view of CID retirement behind quiche's
 * own, and reports when the two have diverged.
 *
 * This is the race #441 narrowed but could not close. quiche removes a retired source CID from its
 * table synchronously, inside `recv()`, the instant it processes the peer's RETIRE_CONNECTION_ID;
 * [SharedQuicheServer]'s DCID→driver map only learns of it one cross-coroutine hop later, when the
 * driver's next established wake calls [QuicheApi.connRetiredScids] and drains. Any datagram that
 * arrives inside that hop is still routed into a quiche that no longer recognises its CID.
 *
 * While gated, [connRetiredScids] answers "nothing retired" — so the server keeps routing the CID
 * exactly as it does inside the real window — while the *delegate's* answer is recorded and
 * published through [awaitQuicheRetiredAnScid]. That turns a microsecond-wide race into a state the
 * test can hold open and step through, without altering a single line of the code under test.
 *
 * The count is a pure read (`quiche_conn_retired_scids`), never a drain, so suppressing it loses
 * nothing: the ids stay queued in quiche and the driver collects them all on the first wake after
 * [ungate].
 */
internal class LaggingScidRetirementQuicheApi(
    private val delegate: QuicheApi,
) : QuicheApi by delegate {
    @Volatile
    private var gated = true
    private val firstRetirement = CompletableDeferred<Int>()

    /** Suspends until quiche itself has retired at least one source CID; returns how many. */
    suspend fun awaitQuicheRetiredAnScid(): Int = firstRetirement.await()

    /** Let the server's routing table catch up with quiche again. */
    fun ungate() {
        gated = false
    }

    /**
     * Every `quiche_conn_recv` return code seen since [recordRecvResults], so the test can assert on
     * the *mechanism* and not just the symptom: the withheld packet must be absorbed (a byte count),
     * never rejected with [QUICHE_ERR_INVALID_STATE], which is the code `recv()` turns into a
     * PROTOCOL_VIOLATION CONNECTION_CLOSE.
     */
    @Volatile
    var recvResults: List<Int> = emptyList()
        private set

    @Volatile
    private var recording = false

    fun recordRecvResults() {
        recording = true
    }

    override fun connRecv(
        conn: QuicheConn,
        buf: Long,
        bufLen: Int,
        recvInfo: QuicheRecvInfo,
    ): Int =
        delegate.connRecv(conn, buf, bufLen, recvInfo).also {
            // Copy-on-write rather than a concurrent list: `java.util.concurrent` does not exist on
            // Kotlin/Native, and connRecv is called only from the driver coroutine — the single thread
            // allowed to touch the connection — so the read-modify-write has one writer by construction.
            if (recording) recvResults = recvResults + it
        }

    override fun connRetiredScids(conn: QuicheConn): Int {
        val actual = delegate.connRetiredScids(conn)
        if (actual > 0) firstRetirement.complete(actual)
        return if (gated) 0 else actual
    }
}

/** `QUICHE_ERR_INVALID_STATE` — what quiche returns for a destination CID it no longer recognises. */
internal const val QUICHE_ERR_INVALID_STATE = -6
