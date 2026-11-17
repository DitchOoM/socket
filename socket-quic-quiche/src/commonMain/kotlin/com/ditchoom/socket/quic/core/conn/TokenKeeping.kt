package com.ditchoom.socket.quic.core.conn

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.socket.quic.AddressTokenMemory
import com.ditchoom.socket.quic.KeptToken
import com.ditchoom.socket.quic.QUICHE_ERR_INVALID_STATE
import com.ditchoom.socket.quic.QuicSessionKey
import com.ditchoom.socket.quic.QuicSessionMemory
import com.ditchoom.socket.quic.QuicSessionStore
import com.ditchoom.socket.quic.core.life.QuicheConnRef
import com.ditchoom.socket.quic.core.udp.Addressed
import com.ditchoom.socket.quic.core.udp.NativeBytes

/**
 * Where a client connection's NEW_TOKEN token goes, beside its session ticket and under the same store choice: only
 * [QuicSessionStore.Default]'s memory keeps tokens; an app's own memory or cache does not yet, so its partition never
 * leaks into the shared one.
 */
internal sealed interface TokenHome {
    data object NotKept : TokenHome

    class Memory(
        val memory: AddressTokenMemory,
        val key: QuicSessionKey,
    ) : TokenHome

    companion object {
        fun of(
            store: QuicSessionStore,
            key: QuicSessionKey,
        ): TokenHome =
            when (store) {
                is QuicSessionStore.InMemory ->
                    if (store.memory === QuicSessionMemory.Process) Memory(AddressTokenMemory.Process, key) else NotKept
                is QuicSessionStore.NotKept, is QuicSessionStore.Supplied -> NotKept
            }
    }
}

/** What a client offered quiche for its Initials. */
internal sealed interface TokenOffer {
    data object NoToken : TokenOffer

    data object Offered : TokenOffer

    /** quiche refused it with [quicheError]; the Initials carry none. */
    class Refused(
        val quicheError: Int,
    ) : TokenOffer
}

/** Takes the token kept for this server and sets it on [q], created and not yet sent on. Once per connection attempt. */
internal fun TokenHome.offer(
    q: QuicheConnRef,
    factory: BufferFactory,
): TokenOffer {
    val token =
        when (this) {
            is TokenHome.NotKept -> return TokenOffer.NoToken
            is TokenHome.Memory ->
                when (val kept = memory.take(key)) {
                    is KeptToken.None -> return TokenOffer.NoToken
                    is KeptToken.Held -> kept.token.slice()
                }
        }
    val length = token.remaining()
    val buffer = factory.allocate(length)
    try {
        buffer.write(token)
        return when (val addressed = NativeBytes.of(buffer)) {
            is Addressed.NotAddressable -> TokenOffer.Refused(QUICHE_ERR_INVALID_STATE)
            is Addressed.Native -> {
                val rc = q.api.connSetToken(q.handle, addressed.bytes.address, length)
                if (rc < 0) TokenOffer.Refused(rc) else TokenOffer.Offered
            }
        }
    } finally {
        buffer.freeNativeMemory()
    }
}

/** Keeps the token at [bytes]; called on the connection's thread. */
internal fun TokenHome.keep(
    bytes: NativeBytes,
    length: Int,
) = when (this) {
    is TokenHome.NotKept -> Unit
    is TokenHome.Memory -> {
        val token = bytes.owner
        token.position(0)
        token.setLimit(length)
        memory.keep(key, token)
    }
}
