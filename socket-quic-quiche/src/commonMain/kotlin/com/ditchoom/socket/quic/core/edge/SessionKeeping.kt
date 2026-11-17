package com.ditchoom.socket.quic.core.edge

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.socket.quic.QuicOptions
import com.ditchoom.socket.quic.QuicResumption
import com.ditchoom.socket.quic.QuicSessionCache
import com.ditchoom.socket.quic.QuicSessionKey
import com.ditchoom.socket.quic.QuicSessionMemory
import com.ditchoom.socket.quic.QuicSessionStore
import com.ditchoom.socket.quic.QuicSessionTicket
import com.ditchoom.socket.quic.QuicSessionTicketState
import com.ditchoom.socket.quic.core.conn.KeptTicket
import com.ditchoom.socket.quic.core.conn.Offer
import com.ditchoom.socket.quic.core.life.QuicheConnRef
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Runnable
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

/** Whether a client connection offers a ticket it took from its store, which goes back there if it is never sent. */
internal sealed interface Taken {
    /** None: the store had none, or the caller offers its own, which the kept one must not repeat. */
    data object NoneTaken : Taken

    class Offering(
        val ticket: QuicSessionTicket,
    ) : Taken
}

/** What a client connection offers, the ticket it took for that, and where its own ticket goes. */
internal class SessionPlan(
    val resumption: QuicResumption,
    val taken: Taken,
    val home: TicketHome,
)

/** What quiche took of a [SessionPlan], and the ticket it took from its store, by whether it has been sent. */
internal class Planned(
    val offer: Offer,
    val kept: KeptTicket,
)

/**
 * Offers [plan]'s session on [q], a client connection not yet sent on. A ticket quiche refused is dropped; one it took is
 * [KeptTicket.Unsent] until the connection writes a datagram. If the offer throws, the ticket goes back at once.
 */
internal fun offerPlanned(
    q: QuicheConnRef,
    plan: SessionPlan,
    factory: BufferFactory,
): Planned {
    val unsent =
        when (val taken = plan.taken) {
            is Taken.NoneTaken -> KeptTicket.None
            is Taken.Offering -> KeptTicket.Unsent(Runnable { plan.home.restore(taken.ticket) })
        }
    val offer = unsent.backIfThrows { offerOf(q, plan.resumption, factory) }
    val kept =
        when (offer) {
            is Offer.Offered -> unsent
            is Offer.Unusable, is Offer.NoTicket -> KeptTicket.None
        }
    return Planned(offer, kept)
}

/** How far a step of the connect before its connection starts got. */
private enum class StartStep { Running, Returned }

/** Runs [block], a step of the connect before its connection starts; if it does not return, the unsent ticket goes back. */
internal fun <T> KeptTicket.backIfThrows(block: () -> T): T {
    var step = StartStep.Running
    try {
        return block().also { step = StartStep.Returned }
    } finally {
        when (step) {
            StartStep.Returned -> Unit
            StartStep.Running ->
                when (this) {
                    is KeptTicket.Unsent -> back.run()
                    is KeptTicket.None, is KeptTicket.Sent, is KeptTicket.Returned -> Unit
                }
        }
    }
}

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
        is QuicSessionStore.NotKept -> SessionPlan(options.resumption, Taken.NoneTaken, TicketHome.NotKept)
        is QuicSessionStore.InMemory -> {
            val key = QuicSessionKey.of(serverName, port, options)
            planned(options.resumption, store.memory.take(key), TicketHome.Memory(store.memory, key))
        }
        is QuicSessionStore.Supplied -> {
            val key = QuicSessionKey.of(serverName, port, options)
            planned(options.resumption, store.cache.take(key), TicketHome.Supplied(store.cache, key))
        }
    }

private fun planned(
    own: QuicResumption,
    kept: QuicSessionTicketState,
    home: TicketHome,
): SessionPlan =
    when (own) {
        is QuicResumption.None ->
            when (kept) {
                is QuicSessionTicketState.NotIssued -> SessionPlan(QuicResumption.None, Taken.NoneTaken, home)
                is QuicSessionTicketState.Issued -> SessionPlan(QuicResumption.Resume(kept.ticket), Taken.Offering(kept.ticket), home)
            }
        is QuicResumption.Resume, is QuicResumption.ResumeWithEarlyData -> SessionPlan(own, Taken.NoneTaken, home)
    }

/** Keeps [ticket] for the next connection to its server; called on the connection's thread. */
internal fun TicketHome.keep(ticket: QuicSessionTicket) {
    when (this) {
        is TicketHome.NotKept -> Unit
        is TicketHome.Memory -> memory.keep(key, ticket)
        is TicketHome.Supplied -> CoroutineScope(Dispatchers.Default).launch { cache.keep(key, ticket) }
    }
}

/** Hands back [ticket], taken for a connection that never sent it; on the connect's or the connection's thread. */
private fun TicketHome.restore(ticket: QuicSessionTicket) {
    when (this) {
        is TicketHome.NotKept -> Unit
        is TicketHome.Memory -> memory.restore(key, ticket)
        is TicketHome.Supplied -> CoroutineScope(Dispatchers.Default).launch { cache.restore(key, ticket) }
    }
}
