@file:OptIn(kotlin.concurrent.atomics.ExperimentalAtomicApi::class)

package com.ditchoom.socket.quic

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.ReadBuffer
import com.ditchoom.buffer.managed
import kotlin.concurrent.atomics.AtomicReference
import kotlin.time.Duration.Companion.days
import kotlin.time.TimeMark
import kotlin.time.TimeSource

/** Whether a NEW_TOKEN token was kept for a server. */
internal sealed interface KeptToken {
    data object None : KeptToken

    class Held(
        val token: ReadBuffer,
    ) : KeptToken
}

/**
 * A client's NEW_TOKEN tokens (RFC 9000 §8.1.3): one per server, newest wins, at most [SERVERS] servers, none older
 * than [MAX_AGE]. Each is taken once, so no token links two connection attempts; a taken token stays lent until a
 * newer one replaces it, so [restore] can hand back one no datagram carried. Lock-free, like [QuicSessionMemory].
 */
internal class AddressTokenMemory(
    private val timeSource: TimeSource = TimeSource.Monotonic,
) {
    /** A server's token: [Held] to be taken, or [Lent] to a connection, which may hand it back unsent. */
    private sealed interface Entry {
        val token: ReadBuffer
        val at: TimeMark

        class Held(
            override val token: ReadBuffer,
            override val at: TimeMark,
        ) : Entry

        class Lent(
            override val token: ReadBuffer,
            override val at: TimeMark,
        ) : Entry
    }

    private val held = AtomicReference<Map<QuicSessionKey, Entry>>(emptyMap())

    fun take(server: QuicSessionKey): KeptToken {
        while (true) {
            val now = held.load()
            val found =
                when (val entry = now[server]) {
                    is Entry.Held -> entry
                    is Entry.Lent, null -> return KeptToken.None
                }
            val fresh = found.at.elapsedNow() <= MAX_AGE
            val next = if (fresh) now.replacing(server, Entry.Lent(found.token, found.at)) else now - server
            if (held.compareAndSet(now, next)) return if (fresh) KeptToken.Held(found.token) else KeptToken.None
        }
    }

    /** Copies [token]'s remaining bytes and keeps them for [server] in place of an older token. */
    fun keep(
        server: QuicSessionKey,
        token: ReadBuffer,
    ) {
        val copy = BufferFactory.managed().allocate(token.remaining())
        copy.write(token)
        copy.resetForRead()
        val entry = Entry.Held(copy, timeSource.markNow())
        while (true) {
            val now = held.load()
            val next = LinkedHashMap<QuicSessionKey, Entry>(now)
            next.remove(server)
            next[server] = entry
            if (next.size > SERVERS) next.remove(next.keys.first())
            if (held.compareAndSet(now, next)) return
        }
    }

    /** Hands back [token], which [take] returned for [server] and no datagram carried, unless a newer one was kept. */
    fun restore(
        server: QuicSessionKey,
        token: ReadBuffer,
    ) {
        while (true) {
            val now = held.load()
            val lent =
                when (val entry = now[server]) {
                    is Entry.Lent -> if (entry.token === token) entry else return
                    is Entry.Held, null -> return
                }
            if (held.compareAndSet(now, now.replacing(server, Entry.Held(lent.token, lent.at)))) return
        }
    }

    /** A copy with [server]'s entry replaced in place, so the order of keeping is unchanged. */
    private fun Map<QuicSessionKey, Entry>.replacing(
        server: QuicSessionKey,
        entry: Entry,
    ): Map<QuicSessionKey, Entry> = LinkedHashMap(this).also { it[server] = entry }

    companion object {
        val MAX_AGE = 7.days
        const val SERVERS = 64

        /** Beside [QuicSessionMemory.Process]: the process's tokens, for a client that asks to keep them. */
        val Process = AddressTokenMemory()
    }
}
