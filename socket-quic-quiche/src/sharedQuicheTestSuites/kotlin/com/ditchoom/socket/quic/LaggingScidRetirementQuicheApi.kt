package com.ditchoom.socket.quic

import kotlinx.coroutines.CompletableDeferred
import kotlin.concurrent.Volatile

/**
 * A [QuicheApi] spy that holds the server's *routing table* view of CID retirement behind quiche's
 * own, and reports when the two have diverged.
 *
 * This is the race #441 narrowed but could not close. quiche removes a retired source CID from its
 * table synchronously, inside `recv()`, the instant it processes the peer's RETIRE_CONNECTION_ID;
 * [SharedQuicheServer]'s DCID→driver map only learns of it one cross-coroutine hop later, on the
 * driver's next established wake. Any datagram that arrives inside that hop is still routed into a
 * quiche that no longer recognises its CID.
 *
 * While gated, the whole CID **readback** answers "nothing to report" — [connRetiredScids] says
 * nothing was retired and [connActiveScids] says there is no set to project — so the server keeps
 * routing the CID exactly as it does inside the real window, while the *delegate's* answer is
 * recorded and published through [awaitQuicheRetiredAnScid]. That turns a microsecond-wide race into
 * a state the test can hold open and step through, without altering a single line of the code under
 * test.
 *
 * Gating both is what keeps this spy honest after #449 turned the routing map into a projection of
 * `quiche_conn_source_ids`: suppressing only the retirement count would leave the projection free to
 * notice the shrunken set and unroute the CID on its own, closing the very window the test needs
 * open. Neither call mutates anything — the retired-id count is a pure read and `source_ids` does not
 * drain — so suppressing them loses nothing: the driver reads the true values on the first wake after
 * [ungate] and the map catches up in one step.
 *
 * ## It starts OPEN, and the test closes it
 * One projection carries both halves of the CID set, so a spy gated from construction would suppress
 * the server's spare-SCID *registrations* as well as its retirements — and a migrating peer switches
 * to one of those spares, so its PATH_CHALLENGE would miss the demux and the migration under test
 * would never happen. [gate] therefore exists to be called at a chosen moment: after the connection is
 * established (its spares issued and routed) and before the migration whose retirement is to be held
 * back. That is also a truer model of the window, which opens when quiche processes
 * RETIRE_CONNECTION_ID — not when the connection is created.
 */
internal class LaggingScidRetirementQuicheApi(
    private val delegate: QuicheApi,
) : QuicheApi by delegate {
    @Volatile
    private var gated = false
    private val firstRetirement = CompletableDeferred<Int>()

    /** Suspends until quiche itself has retired at least one source CID; returns how many. */
    suspend fun awaitQuicheRetiredAnScid(): Int = firstRetirement.await()

    /**
     * Freeze the server's routing-table view of the CID set from here on. Call once the connection is
     * established — so its spare SCIDs are already routed — and before the migration whose
     * RETIRE_CONNECTION_ID is to be held back.
     */
    fun gate() {
        gated = true
    }

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

    /**
     * While gated, report no active source ids at all. [QuicheDriver] treats that as "this backend
     * has no readback bound" and leaves the routing map exactly as it stands rather than projecting
     * an empty set over it — which is precisely the lagging-map state being held open.
     */
    override fun connActiveScids(conn: QuicheConn): Int = if (gated) 0 else delegate.connActiveScids(conn)
}

/** `QUICHE_ERR_INVALID_STATE` — what quiche returns for a destination CID it no longer recognises. */
internal const val QUICHE_ERR_INVALID_STATE = -6

/**
 * `QUICHE_ERR_OUT_OF_IDENTIFIERS` — no spare connection ID is available for the operation. What
 * `probe_path`/`migrate` answer once the CID pool is exhausted, which is the state #447's leak drove
 * a connection into permanently.
 */
internal const val QUICHE_ERR_OUT_OF_IDENTIFIERS = -18
