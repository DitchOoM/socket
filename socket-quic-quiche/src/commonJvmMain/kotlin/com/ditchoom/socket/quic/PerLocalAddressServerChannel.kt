@file:OptIn(ExperimentalDatagramApi::class)

package com.ditchoom.socket.quic

import com.ditchoom.buffer.ReadBuffer
import com.ditchoom.buffer.flow.AddressedDatagramChannel
import com.ditchoom.buffer.flow.Datagram
import com.ditchoom.buffer.flow.DatagramCapabilities
import com.ditchoom.buffer.flow.DatagramReadResult
import com.ditchoom.buffer.flow.DatagramSendOptions
import com.ditchoom.buffer.flow.ExperimentalDatagramApi
import com.ditchoom.buffer.flow.LocalAddress
import com.ditchoom.buffer.flow.SocketAddress
import com.ditchoom.socket.udp.DatagramSendError
import com.ditchoom.socket.udp.DatagramSendException
import com.ditchoom.socket.udp.SourceAddressRejection
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ClosedSendChannelException
import kotlinx.coroutines.launch
import java.net.InetAddress
import java.util.concurrent.ConcurrentHashMap
import kotlin.concurrent.Volatile
import kotlin.coroutines.CoroutineContext

/**
 * A wildcard server bind that answers **from the address the client dialled**.
 *
 * ## The defect
 *
 * A wildcard-bound UDP socket receives datagrams sent to any of the host's addresses, but when it
 * replies the kernel picks the source address by route lookup — and the choice differs per OS.
 * Same server and same `connect()`ed client, on the two addresses of one interface:
 *
 * | host | client local addr | reply source | connected client |
 * |---|---|---|---|
 * | Linux | `127.0.0.2` | `127.0.0.1` | accepted |
 * | macOS | `127.0.0.2` | **`127.0.0.2`** | **dropped** |
 *
 * Darwin selects the source matching the destination, Linux the interface primary. A client whose
 * socket is `connect()`ed — which is correct, and is what [NioUdpChannelFactory.openPath] does —
 * discards a reply from any other source, so it presents as a silent timeout — `PathNotValidated`
 * after the path-validation deadline.
 *
 * ## The fix, and why it is sockets rather than ancillary data
 *
 * The standard remedy is `IP_PKTINFO`: capture the destination on receive, set it as the source on
 * send. JVM NIO cannot express it at all — there is no socket option for it and no access to
 * ancillary data (`NioDatagramChannel`: `// no IP_PKTINFO on NIO`) — so on this platform the only
 * way to pin a source is to **bind a socket to it**, which the kernel then enforces: a bound socket
 * cannot send from another address. One socket per local address, and a reply goes back out of the
 * socket the request arrived on.
 *
 * That is why this advertises `localAddressReceive` and `sourceAddressSelect` as **true**. It is not
 * a claim about the underlying sockets — each of those still reports `false` — but about this
 * composite, which genuinely knows every datagram's destination (the socket it arrived on) and can
 * genuinely choose a reply's source (by picking that socket again).
 *
 * ## How a reply finds its socket
 *
 * By quiche's own `send_info.from`, handed down as [DatagramSendOptions.fromLocal], when the server
 * names one; a named address no socket is bound to is refused, typed, never sent from another socket.
 * Otherwise by the peer: the socket its most recent datagram arrived on, remembered in
 * [replyRoutes].
 *
 * ## What this narrows
 *
 * A wildcard bind promises *every* address, and this delivers every address that could be
 * enumerated and bound at bind time. Two honest gaps, both absent with real cmsg support:
 *
 *  - An address that appears **after** the bind (DHCP, a VPN coming up, a container attaching) is
 *    not served until the server is rebound.
 *  - IPv6 link-local is excluded, because binding one needs a scope id
 *    (see [enumerateLocalUnicastAddresses]).
 *
 * ## Concurrency
 *
 * One reader coroutine per socket, fanning into a **rendezvous** channel: no buffering, so a slow
 * consumer applies backpressure to the readers rather than accumulating pooled receive buffers, and
 * there is no queue to leak on close. A reader hands each payload to that channel and never touches
 * it again: the receiver owns what it takes, and the channel frees what nobody took (see [inbound]).
 *
 * A receive on a closed composite **yields** [DatagramReadResult.Closed] rather than throwing — the
 * contract every member keeps (`NioDatagramChannel` spells it out at its own close race) and the
 * one the server's reader loop is written against.
 */
internal class PerLocalAddressServerChannel private constructor(
    private val members: List<AddressedDatagramChannel>,
    readerContext: CoroutineContext,
) : AddressedDatagramChannel {
    private val scope = CoroutineScope(SupervisorJob() + readerContext)

    /**
     * Rendezvous: see the concurrency note. Carries the socket so a reply can retrace the path.
     *
     * The single arbiter of a payload in flight. A reader's `send` throws `CancellationException` both
     * when [close] cancels it before the hand-off and when the hand-off completed but the reader had
     * not yet resumed — the receiver then holds a datagram the server may already have routed to a
     * connection. Only the channel can tell the two apart, so only the channel frees: exactly the
     * payloads no receiver took.
     */
    private val inbound =
        Channel<Pair<AddressedDatagramChannel, DatagramReadResult>>(Channel.RENDEZVOUS) { (_, result) ->
            freeUndeliveredDatagram(result)
        }

    /** peer -> the socket its last datagram arrived on, i.e. the local address it dialled. */
    private val replyRoutes = ReplyRoutes(MAX_REPLY_ROUTES)

    /** How many peers currently hold a route. Exists so the bound on [replyRoutes] can be tested. */
    internal val routeCount: Int get() = replyRoutes.size

    @Volatile
    private var closed = false

    init {
        for (member in members) {
            val local = member.localAddress
            scope.launch(CoroutineName("quic-server/member-reader/${local.host}:${local.port}")) {
                while (true) {
                    val result =
                        try {
                            member.receive()
                        } catch (e: CancellationException) {
                            throw e
                        } catch (_: Throwable) {
                            // A single socket failing must not take the server down: the others still
                            // serve their addresses. The reader retires; close() reports the rest.
                            break
                        }
                    // The payload is [inbound]'s from here, delivered or not.
                    try {
                        inbound.send(member to result)
                    } catch (_: ClosedSendChannelException) {
                        break
                    }
                    if (result is DatagramReadResult.Closed) break
                }
            }
        }
    }

    override val isOpen: Boolean get() = !closed && members.any { it.isOpen }

    /**
     * The composite's own capabilities, not a member's.
     *
     * Everything except the two this class implements is taken from a member, because those describe
     * the sockets and are unchanged by there being several of them.
     */
    override val capabilities: DatagramCapabilities =
        members.first().capabilities.let { base ->
            DatagramCapabilities(
                ecnSend = base.ecnSend,
                ecnReceive = base.ecnReceive,
                dscpSend = base.dscpSend,
                dontFragment = base.dontFragment,
                hopLimitSend = base.hopLimitSend,
                hopLimitReceive = base.hopLimitReceive,
                localAddressReceive = true,
                sourceAddressSelect = true,
                multicast = base.multicast,
                requiresNativeMemoryBuffers = base.requiresNativeMemoryBuffers,
            )
        }

    /**
     * Representative only: every member shares one port, which is what callers read this for
     * (`SharedQuicheServer.port`). The per-datagram local address — the one that carries information
     * — rides on [Datagram.localAddress], which is now always known.
     */
    override val localAddress: SocketAddress = members.first().localAddress

    override val maxWritableSize: Int get() = members.minOf { it.maxWritableSize }

    override suspend fun receive(): DatagramReadResult {
        // Closed yields Closed, never a ClosedReceiveChannelException: see the class doc. A parked
        // receive wakes here with the channel closed when close() runs underneath it.
        val (member, result) = inbound.receiveCatching().getOrNull() ?: return DatagramReadResult.Closed()
        if (result !is DatagramReadResult.Received) return result
        val datagram = result.datagram
        replyRoutes.arrived(datagram.peer, member)
        // Stamp the destination the client actually dialled. The wildcard socket could not know it;
        // this composite does, because the socket it arrived on is bound to exactly that address.
        return DatagramReadResult.Received(
            Datagram(
                payload = datagram.payload,
                peer = datagram.peer,
                ecn = datagram.ecn,
                localAddress = LocalAddress.of(member.localAddress),
                hopLimit = datagram.hopLimit,
            ),
        )
    }

    /**
     * Send from the address this peer dialled — the whole point of the class.
     *
     * A named [DatagramSendOptions.fromLocal] leaves by the member bound to that address, matched by
     * address alone (every member shares one port, and an IPv4-mapped spelling is its IPv4 address). A
     * named address no member is bound to is refused as [SourceAddressRejection.NotBound] and nothing is
     * sent. A wildcard names no address.
     *
     * With no source named, the peer's own route is used. A peer without one (a server sending first,
     * which QUIC never does, or one whose route the bound evicted) leaves by the first member, which is
     * what a wildcard bind would have done anyway.
     */
    override suspend fun send(
        payload: ReadBuffer,
        to: SocketAddress,
        options: DatagramSendOptions,
    ) {
        val member =
            when (val named = options.fromLocal) {
                null -> replyRoutes.routeTo(to, otherwise = members.first())
                else -> namedMembers[named] ?: memberNamed(named, to)
            }
        // fromLocal is consumed here, by choosing the socket; passing it down to a member that
        // reports sourceAddressSelect=false would be asking for something it silently ignores.
        member.send(payload, to, if (options.fromLocal == null) options else options.withoutFromLocal())
    }

    /**
     * The member each source a send has named is bound to, so a source is read and matched once and
     * found by itself after that. Only sources that matched a member are kept, and no more than
     * [MAX_NAMED_SOURCES] of them: the addresses quiche names are this host's own, a handful.
     */
    private val namedMembers = ConcurrentHashMap<SocketAddress, AddressedDatagramChannel>()

    private fun memberNamed(
        fromLocal: SocketAddress,
        to: SocketAddress,
    ): AddressedDatagramChannel =
        when (val source = sourceOf(fromLocal)) {
            Source.Unnamed -> replyRoutes.routeTo(to, otherwise = members.first())
            is Source.Named -> {
                val member =
                    members.getOrElse(memberAddresses.indexOf(source.address)) {
                        throw DatagramSendException(
                            DatagramSendError.SourceAddressUnavailable(source.host, SourceAddressRejection.NotBound),
                        )
                    }
                if (namedMembers.size < MAX_NAMED_SOURCES) namedMembers[fromLocal] = member
                member
            }
        }

    /** Each member's bound address, in [members] order, as the JDK compares addresses. */
    private val memberAddresses: List<InetAddress> = members.map { it.localAddress.inetAddress() }

    /** What a send's `fromLocal` asks for. */
    private sealed interface Source {
        /** No source, or a wildcard: the reply follows the peer's route. */
        data object Unnamed : Source

        /** Leave from [address]; [host] is how the caller spelled it. */
        class Named(
            val address: InetAddress,
            val host: String,
        ) : Source
    }

    private fun sourceOf(fromLocal: SocketAddress): Source {
        val address = fromLocal.inetAddress()
        return if (address.isAnyLocalAddress) Source.Unnamed else Source.Named(address, fromLocal.host)
    }

    override fun close() {
        if (closed) return
        closed = true
        scope.cancel()
        inbound.close()
        for (member in members) {
            try {
                member.close()
            } catch (_: Throwable) {
                // Closing the rest matters more than reporting one failure.
            }
        }
    }

    internal companion object {
        /**
         * Upper bound on [replyRoutes]. An entry is two references, so this caps a spoofed-source spray
         * at well under a megabyte while covering far more concurrent peers than any one server socket
         * plausibly serves. See [ReplyRoutes] for what eviction costs.
         */
        internal const val MAX_REPLY_ROUTES = 4096

        /** Upper bound on the named sources remembered with their member; past it a source is matched per send. */
        internal const val MAX_NAMED_SOURCES = 256

        /** The address a numeric [SocketAddress] names; a literal, so no lookup. Any zone suffix is dropped. */
        private fun SocketAddress.inetAddress(): InetAddress = InetAddress.getByName(host.substringBefore('%'))

        private fun DatagramSendOptions.withoutFromLocal(): DatagramSendOptions =
            DatagramSendOptions(
                ecn = ecn,
                dscp = dscp,
                dontFragment = dontFragment,
                hopLimit = hopLimit,
                fromLocal = null,
            )

        /**
         * Wrap [members], or hand back the single socket unchanged when there is nothing to compose.
         * [readerContext] is where the member readers run: `Dispatchers.IO` in production, a stepped
         * dispatcher in a test that needs to hold a reader between two of its steps.
         */
        internal fun of(
            members: List<AddressedDatagramChannel>,
            readerContext: CoroutineContext = Dispatchers.IO,
        ): AddressedDatagramChannel = if (members.size == 1) members.single() else PerLocalAddressServerChannel(members, readerContext)
    }
}
