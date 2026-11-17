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
 * than [MAX_AGE]. Each is taken once, so no token links two connection attempts. Lock-free, like [QuicSessionMemory].
 */
internal class AddressTokenMemory(
    private val timeSource: TimeSource = TimeSource.Monotonic,
) {
    private class Held(
        val token: ReadBuffer,
        val at: TimeMark,
    )

    private val held = AtomicReference<Map<QuicSessionKey, Held>>(emptyMap())

    fun take(server: QuicSessionKey): KeptToken {
        while (true) {
            val now = held.load()
            val found = now[server] ?: return KeptToken.None
            if (held.compareAndSet(now, now - server)) {
                return if (found.at.elapsedNow() > MAX_AGE) KeptToken.None else KeptToken.Held(found.token)
            }
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
        val entry = Held(copy, timeSource.markNow())
        while (true) {
            val now = held.load()
            val next = LinkedHashMap<QuicSessionKey, Held>(now)
            next.remove(server)
            next[server] = entry
            if (next.size > SERVERS) next.remove(next.keys.first())
            if (held.compareAndSet(now, next)) return
        }
    }

    companion object {
        val MAX_AGE = 7.days
        const val SERVERS = 64

        /** Beside [QuicSessionMemory.Process]: the default store's tokens. */
        val Process = AddressTokenMemory()
    }
}
