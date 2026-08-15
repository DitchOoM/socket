package com.ditchoom.socket.http3

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Admits the **first** instance of each critical unidirectional stream and rejects every later one
 * (RFC 9114 §6.2, RFC 9204 §4.2). One instance per connection, shared by both roles: the client's
 * router and the server's uni-stream handler face the same peer behaviour.
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
}
