package com.ditchoom.socket.http3

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.concurrent.Volatile

/**
 * The per-connection authority on what the peer may do to a critical unidirectional stream — the
 * control stream and the two QPACK instruction streams (RFC 9114 §6.2 / §6.2.1, RFC 9204 §4.2). One
 * instance per connection, shared by both roles: the client's router and the server's uni-stream
 * handler face the same peer behaviour, and the RFCs write these rules for "either" endpoint rather
 * than per role.
 *
 * Two things are policed here, both connection errors:
 * - opening a **second** instance of one ([claim] — `H3_STREAM_CREATION_ERROR`), and
 * - **closing** one ([peerClosed] — `H3_CLOSED_CRITICAL_STREAM`).
 *
 * ## Why this is enforced rather than assumed
 *
 * Both roles route each peer-initiated stream in its own coroutine, then dispatch on the stream-type
 * prefix with no memory of what came before. So a peer that opens two QPACK **encoder** streams gets
 * two coroutines feeding one `QpackDecoder`, and the decoder is written for a single feeder: it
 * captures the table's insert count under one lock and reports it under another, which is safe only
 * while nothing else can insert in between. The same shape applies to a second control stream (two
 * SETTINGS readers) and a second decoder stream (two writers into one `QpackEncoder`).
 *
 * The RFCs call the duplicate a connection error, which is also the cheapest correct answer: one set,
 * one lock, and every downstream single-reader assumption is restored at the door instead of being
 * defended separately in each component.
 */
internal class CriticalStreamGuard {
    private val mutex = Mutex()
    private val claimed = mutableSetOf<CriticalStreamType>()

    // Whether this connection has ended — see [connectionEnded] for what sets it and why an
    // end-of-stream means nothing without it. Volatile because the router/handler coroutines that read
    // it are not the one that writes it.
    @Volatile
    private var ended = false

    /**
     * Claim [type] for this connection. Returns `null` when this is its first instance — the caller
     * proceeds — or the [Http3Violation] to abort the connection with when the peer has opened it
     * before. A violation rather than a `Boolean`, so the caller cannot forget which error code the
     * duplicate carries.
     */
    suspend fun claim(type: CriticalStreamType): Http3Violation? =
        mutex.withLock {
            if (claimed.add(type)) null else Http3Violation.DuplicateCriticalStream(type)
        }

    /**
     * Record that this connection has ended: its peer-stream flow completed, which is the one fact that
     * says so ([com.ditchoom.socket.quic.QuicScope.streams] — "Completes when the connection closes").
     * From here on nothing the peer does to a critical stream is a violation, because the peer is no
     * longer the one ending them.
     *
     * An endpoint aborting the connection itself needs no second call: the abort latch is first-wins in
     * both roles, so a critical stream ending in the wake of our own CONNECTION_CLOSE reaches an
     * `abortConnection` that returns immediately, and the reported error stays the real one.
     */
    fun connectionEnded() {
        ended = true
    }

    /**
     * The reader of this connection's [type] stream saw end-of-stream. Returns the [Http3Violation] to
     * abort the connection with, or `null` when the connection itself has already ended and this is
     * teardown rather than the peer closing a critical stream.
     *
     * **The distinction is not visible in the read result**, which is why it is made here rather than at
     * each reader. `QuicheDriver` answers a read parked on a stream whose connection has gone away with
     * `ReadResult.End` — its `StreamRecvResult.ConnectionGone` arm, byte-for-byte the value a peer's FIN
     * produces (a typed connection-gone read result needs buffer's `ReadResult` to gain a case,
     * DitchOoM/buffer#376). Reading `End` as the peer's FIN unconditionally would therefore report every
     * clean close as a protocol violation the peer never committed — which is worse than the silence
     * #530 fixed, because it accuses. [connectionEnded] is the fact that tells them apart.
     *
     * Returning the violation rather than a `Boolean` for the same reason [claim] does: the caller
     * cannot end up choosing the error code itself.
     */
    fun peerClosed(type: CriticalStreamType): Http3Violation? = if (ended) null else Http3Violation.ClosedCriticalStream(type)
}
