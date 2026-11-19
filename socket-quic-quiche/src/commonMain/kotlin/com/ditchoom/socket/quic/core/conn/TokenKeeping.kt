package com.ditchoom.socket.quic.core.conn

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.ReadBuffer
import com.ditchoom.socket.quic.AddressTokenMemory
import com.ditchoom.socket.quic.QUICHE_ERR_INVALID_STATE
import com.ditchoom.socket.quic.QuicOptions
import com.ditchoom.socket.quic.QuicSessionKey
import com.ditchoom.socket.quic.core.life.QuicheConnRef
import com.ditchoom.socket.quic.core.udp.Addressed
import com.ditchoom.socket.quic.core.udp.NativeBytes

/**
 * Whether a client keeps the NEW_TOKEN tokens its servers issue and offers each on its next connection to that server.
 * Independent of the session store, so a client that keeps tickets sends no token unless it asks.
 */
internal sealed interface ClientTokens {
    /** The default: none kept, none sent. */
    data object NotKept : ClientTokens

    /** Kept in [memory]; a store per account keeps one account's connections unlinkable to another's. */
    class Kept(
        val memory: AddressTokenMemory,
    ) : ClientTokens
}

/** Where a client connection's NEW_TOKEN token comes from and goes to. */
internal sealed interface TokenHome {
    data object NotKept : TokenHome

    class Memory(
        val memory: AddressTokenMemory,
        val key: QuicSessionKey,
    ) : TokenHome

    companion object {
        /** The home of a connection to [serverName]:[port] with [options], keyed as its session ticket is. */
        fun of(
            tokens: ClientTokens,
            serverName: String,
            port: Int,
            options: QuicOptions,
        ): TokenHome =
            when (tokens) {
                is ClientTokens.NotKept -> NotKept
                is ClientTokens.Kept -> Memory(tokens.memory, QuicSessionKey.of(serverName, port, options))
            }
    }
}

/** Whether quiche took a client's token for its Initials. */
internal sealed interface TokenOffer {
    data object Offered : TokenOffer

    /** quiche refused it with [quicheError]; the Initials carry none. */
    class Refused(
        val quicheError: Int,
    ) : TokenOffer
}

/** Sets [token]'s remaining bytes on [q], created and not yet sent on; [token] itself is not consumed. */
internal fun QuicheConnRef.setToken(
    token: ReadBuffer,
    factory: BufferFactory,
): TokenOffer {
    val bytes = token.slice()
    val length = bytes.remaining()
    val buffer = factory.allocate(length)
    try {
        buffer.write(bytes)
        return when (val addressed = NativeBytes.of(buffer)) {
            is Addressed.NotAddressable -> TokenOffer.Refused(QUICHE_ERR_INVALID_STATE)
            is Addressed.Native -> {
                val rc = api.connSetToken(handle, addressed.bytes.address, length)
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
