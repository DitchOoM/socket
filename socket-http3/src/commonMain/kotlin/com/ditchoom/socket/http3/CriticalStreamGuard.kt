package com.ditchoom.socket.http3

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

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
     * The violation for the reader of this connection's [type] stream having seen end-of-stream.
     *
     * End-of-stream is the peer's FIN and nothing else: a read on a stream whose connection ends without
     * one — our close, the peer's CONNECTION_CLOSE, an idle timeout — throws the connection's
     * [com.ditchoom.socket.quic.QuicCloseException] instead, and the routers treat that as the connection
     * ending. So the verdict comes from what the read returned, never from when it returned: RFC 9114
     * §6.2.1 and RFC 9204 §4.2 make closing a critical stream a connection error "at any point".
     *
     * Returned rather than chosen by the caller for the same reason as [claim]: the error code travels
     * with the violation.
     */
    fun peerClosed(type: CriticalStreamType): Http3Violation = Http3Violation.ClosedCriticalStream(type)
}
