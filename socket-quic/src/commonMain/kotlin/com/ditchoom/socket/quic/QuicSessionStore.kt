@file:OptIn(kotlin.concurrent.atomics.ExperimentalAtomicApi::class)

package com.ditchoom.socket.quic

import kotlin.concurrent.atomics.AtomicReference
import kotlin.time.Duration.Companion.days
import kotlin.time.TimeMark
import kotlin.time.TimeSource

/**
 * Where a client keeps the session tickets its servers issue, so that reconnecting to a server it has talked to before
 * resumes the session (RFC 8446 §2.2): one round trip, no certificate chain on the wire, with nothing for the app to do.
 *
 * A kept ticket is offered as [QuicResumption.Resume] — never with 0-RTT, which only a
 * [QuicResumption.ResumeWithEarlyData] the app writes sends. Each ticket is sent at most once, because a ticket sent
 * twice lets an observer link the two connections (RFC 8446 §C.4); the ticket the resumed connection receives replaces
 * it. A connect that fails before any of its datagrams left hands its ticket back, so the next one still resumes. A
 * connection that offers a ticket of the app's own discards the one kept for its server.
 *
 * ```kotlin
 * // The default: tickets live in this process's memory.
 * QuicOptions(alpnProtocols = listOf("h3"))
 * // Never resume on its own.
 * QuicOptions(alpnProtocols = listOf("h3"), sessionStore = QuicSessionStore.NotKept)
 * // A store per signed-in account, so the accounts' connections cannot be linked through a ticket.
 * QuicOptions(alpnProtocols = listOf("h3"), sessionStore = QuicSessionStore.InMemory(QuicSessionMemory()))
 * ```
 */
sealed interface QuicSessionStore {
    /** No ticket is kept: a connection resumes only from a ticket the app offers in [QuicOptions.resumption]. */
    data object NotKept : QuicSessionStore

    /** Tickets are kept in [memory], the library's own store, on the connection's own thread. */
    class InMemory(
        val memory: QuicSessionMemory,
    ) : QuicSessionStore

    /**
     * Tickets are kept in the app's [cache]. [QuicSessionCache.take] runs on the connecting coroutine and
     * [QuicSessionCache.keep] and [QuicSessionCache.restore] on a background dispatcher, never on a connection's thread.
     */
    class Supplied(
        val cache: QuicSessionCache,
    ) : QuicSessionStore

    companion object {
        /** This process's memory, shared by every connection that does not choose another store. */
        val Default: QuicSessionStore = InMemory(QuicSessionMemory.Process)
    }
}

/**
 * An app's own ticket store, for [QuicSessionStore.Supplied]. A ticket holds the session's resumption secret: keep it
 * like a credential.
 */
interface QuicSessionCache {
    /**
     * Removes the ticket kept for [server] and returns it, or [QuicSessionTicketState.NotIssued]. Must be atomic: a
     * ticket handed to two connections is offered twice. Return none older than [QuicSessionMemory.MAX_AGE].
     */
    fun take(server: QuicSessionKey): QuicSessionTicketState

    /** Keeps [ticket] for [server] in place of any older one. */
    fun keep(
        server: QuicSessionKey,
        ticket: QuicSessionTicket,
    )

    /**
     * Hands back [ticket], which [take] returned for [server] and no connection sent: its connect failed before any
     * datagram left. Keep it as it was, age included, unless a ticket was kept for [server] since.
     */
    fun restore(
        server: QuicSessionKey,
        ticket: QuicSessionTicket,
    )
}

/**
 * Which server a ticket resumes: the name TLS sent, the port, the application protocols offered, and everything that
 * decides whether the server's certificate is trusted. Resuming skips the certificate, so a ticket from a connection
 * that checked less is never offered by one that checks more. Equal only within one process.
 */
class QuicSessionKey private constructor(
    val serverName: String,
    val port: Int,
    val alpnProtocols: List<String>,
    private val trust: Trust,
) {
    private data class Trust(
        val verifyPeer: Boolean,
        val anchors: List<String>,
        val hashes: List<CertificateHash>,
        val hashVerification: CertificateHashVerification,
        val verifiers: List<ServerCertVerifier>,
        val apple: AppleTrustSource,
    )

    override fun equals(other: Any?): Boolean =
        other is QuicSessionKey &&
            serverName == other.serverName &&
            port == other.port &&
            alpnProtocols == other.alpnProtocols &&
            trust == other.trust

    override fun hashCode(): Int = ((serverName.hashCode() * 31 + port) * 31 + alpnProtocols.hashCode()) * 31 + trust.hashCode()

    override fun toString(): String = "QuicSessionKey($serverName:$port, $alpnProtocols)"

    companion object {
        /** The key of a client connection to [serverName] on [port] made with [options]. */
        fun of(
            serverName: String,
            port: Int,
            options: QuicOptions,
        ): QuicSessionKey =
            QuicSessionKey(
                serverName,
                port,
                options.alpnProtocols,
                Trust(
                    options.verifyPeer,
                    options.trustedCaCertificatesPem,
                    options.serverCertificateHashes,
                    options.certificateHashVerification,
                    options.serverCertVerifiers,
                    options.appleTrustSource,
                ),
            )
    }
}

/**
 * The library's ticket store: one ticket per server, newest wins, at most [SERVERS] servers (the least recently kept
 * leaves first), none older than [MAX_AGE]. A taken ticket stays lent until a newer one replaces it, so [restore] can
 * hand it back with its age. Lock-free: every change publishes a new immutable snapshot. A ticket the
 * server issued for less than [MAX_AGE] is not offered after its own lifetime either: TLS checks it before offering.
 * [timeSource] measures a ticket's age.
 */
class QuicSessionMemory(
    private val timeSource: TimeSource = TimeSource.Monotonic,
) : QuicSessionCache {
    /** A server's ticket: [Held] to be taken, or [Lent] to a connection, which may hand it back unsent. */
    private sealed interface Entry {
        val ticket: QuicSessionTicket
        val at: TimeMark

        class Held(
            override val ticket: QuicSessionTicket,
            override val at: TimeMark,
        ) : Entry

        class Lent(
            override val ticket: QuicSessionTicket,
            override val at: TimeMark,
        ) : Entry
    }

    private val held = AtomicReference<Map<QuicSessionKey, Entry>>(emptyMap())

    override fun take(server: QuicSessionKey): QuicSessionTicketState {
        while (true) {
            val now = held.load()
            val found =
                when (val entry = now[server]) {
                    is Entry.Held -> entry
                    is Entry.Lent, null -> return QuicSessionTicketState.NotIssued
                }
            val fresh = found.at.elapsedNow() <= MAX_AGE
            val next = if (fresh) now.replacing(server, Entry.Lent(found.ticket, found.at)) else now - server
            if (held.compareAndSet(now, next)) {
                return if (fresh) QuicSessionTicketState.Issued(found.ticket) else QuicSessionTicketState.NotIssued
            }
        }
    }

    override fun keep(
        server: QuicSessionKey,
        ticket: QuicSessionTicket,
    ) {
        val entry = Entry.Held(ticket, timeSource.markNow())
        while (true) {
            val now = held.load()
            val next = LinkedHashMap<QuicSessionKey, Entry>(now)
            next.remove(server)
            next[server] = entry
            if (next.size > SERVERS) next.remove(next.keys.first())
            if (held.compareAndSet(now, next)) return
        }
    }

    override fun restore(
        server: QuicSessionKey,
        ticket: QuicSessionTicket,
    ) {
        while (true) {
            val now = held.load()
            val lent =
                when (val entry = now[server]) {
                    is Entry.Lent -> if (entry.ticket === ticket) entry else return
                    is Entry.Held, null -> return
                }
            if (held.compareAndSet(now, now.replacing(server, Entry.Held(lent.ticket, lent.at)))) return
        }
    }

    /** A copy with [server]'s entry replaced in place, so the order of keeping is unchanged. */
    private fun Map<QuicSessionKey, Entry>.replacing(
        server: QuicSessionKey,
        entry: Entry,
    ): Map<QuicSessionKey, Entry> = LinkedHashMap(this).also { it[server] = entry }

    companion object {
        /** The longest a ticket may be used (RFC 8446 §4.6.1). */
        val MAX_AGE = 7.days

        /** The most servers one memory keeps a ticket for. */
        const val SERVERS = 64

        /** This process's memory: [QuicSessionStore.Default]. */
        val Process = QuicSessionMemory()
    }
}
