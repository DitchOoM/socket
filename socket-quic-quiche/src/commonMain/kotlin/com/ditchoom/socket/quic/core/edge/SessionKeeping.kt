package com.ditchoom.socket.quic.core.edge

import com.ditchoom.socket.quic.QuicOptions
import com.ditchoom.socket.quic.QuicResumption
import com.ditchoom.socket.quic.QuicSessionCache
import com.ditchoom.socket.quic.QuicSessionKey
import com.ditchoom.socket.quic.QuicSessionMemory
import com.ditchoom.socket.quic.QuicSessionStore
import com.ditchoom.socket.quic.QuicSessionTicket
import com.ditchoom.socket.quic.QuicSessionTicketState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/** Where a client connection's session ticket goes once its server issues it. */
internal sealed interface TicketHome {
    /** Nowhere: the caller reads it from the connection, or the connection is a server's. */
    data object NotKept : TicketHome

    /** The library's memory, kept on the connection's own thread: lock-free, and no caller code. */
    class Memory(
        val memory: QuicSessionMemory,
        val key: QuicSessionKey,
    ) : TicketHome

    /** The app's cache, kept on a background dispatcher: never caller code on the connection's thread. */
    class Supplied(
        val cache: QuicSessionCache,
        val key: QuicSessionKey,
    ) : TicketHome
}

/** What a client connection offers, and where its own ticket goes. */
internal class SessionPlan(
    val resumption: QuicResumption,
    val home: TicketHome,
)

/**
 * The session a client connection to [serverName]:[port] offers. The kept ticket for its server is taken out whatever
 * the connection offers, so no ticket goes out twice: it is offered as [QuicResumption.Resume] when the caller offers
 * none of its own, and discarded when the caller does. Never 0-RTT: only a caller's own block writes early data.
 */
internal fun sessionPlan(
    options: QuicOptions,
    serverName: String,
    port: Int,
): SessionPlan =
    when (val store = options.sessionStore) {
        is QuicSessionStore.NotKept -> SessionPlan(options.resumption, TicketHome.NotKept)
        is QuicSessionStore.InMemory -> {
            val key = QuicSessionKey.of(serverName, port, options)
            SessionPlan(offered(options.resumption, store.memory.take(key)), TicketHome.Memory(store.memory, key))
        }
        is QuicSessionStore.Supplied -> {
            val key = QuicSessionKey.of(serverName, port, options)
            SessionPlan(offered(options.resumption, store.cache.take(key)), TicketHome.Supplied(store.cache, key))
        }
    }

private fun offered(
    own: QuicResumption,
    kept: QuicSessionTicketState,
): QuicResumption =
    when (own) {
        is QuicResumption.None ->
            when (kept) {
                is QuicSessionTicketState.NotIssued -> QuicResumption.None
                is QuicSessionTicketState.Issued -> QuicResumption.Resume(kept.ticket)
            }
        is QuicResumption.Resume, is QuicResumption.ResumeWithEarlyData -> own
    }

/** Keeps [ticket] for the next connection to its server; called on the connection's thread. */
internal fun TicketHome.keep(ticket: QuicSessionTicket) {
    when (this) {
        is TicketHome.NotKept -> Unit
        is TicketHome.Memory -> memory.keep(key, ticket)
        is TicketHome.Supplied -> CoroutineScope(Dispatchers.Default).launch { cache.keep(key, ticket) }
    }
}
