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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlin.concurrent.Volatile

/**
 * A wildcard server bind that answers **from the address the client dialled** (#556).
 *
 * ## The defect
 *
 * A wildcard-bound UDP socket receives datagrams sent to any of the host's addresses, but when it
 * replies the kernel picks the source address by route lookup — and the choice differs per OS.
 * Measured, same server and same `connect()`ed client, on the two addresses of one interface:
 *
 * | host | client local addr | reply source | connected client |
 * |---|---|---|---|
 * | Linux | `127.0.0.2` | `127.0.0.1` | accepted |
 * | macOS | `127.0.0.2` | **`127.0.0.2`** | **dropped** |
 *
 * Darwin selects the source matching the destination, Linux the interface primary. A client whose
 * socket is `connect()`ed — which is correct, and is what [NioUdpChannelFactory.openPath] does —
 * discards a reply from any other source, so it presents as a silent timeout. #555 was this,
 * reported as `PathNotValidated` after the path-validation deadline.
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
 * By the peer: the socket its most recent datagram arrived on. That is a heuristic standing in for
 * the exact answer, which is quiche's own `send_info.from` handed down as
 * [DatagramSendOptions.fromLocal] — honoured here when present, but the shared server does not yet
 * send it (it also feeds quiche a single fixed `recv_info.to`, so quiche could not yet supply a
 * per-path `from` even if asked). Threading that through is the cross-platform half of #556; once it
 * lands this class routes statelessly and the [replyRoute] map goes away.
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
 * there is no queue to leak on close. The one datagram a reader may be holding when the scope is
 * cancelled is freed by that reader, because a pooled payload whose consumer never arrives is
 * exactly the native leak #538 was.
 *
 * A receive on a closed composite **yields** [DatagramReadResult.Closed] rather than throwing — the
 * contract every member keeps (`NioDatagramChannel` spells it out at its own close race) and the
 * one the server's reader loop is written against.
 */
internal class PerLocalAddressServerChannel private constructor(
    private val members: List<AddressedDatagramChannel>,
) : AddressedDatagramChannel {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** Rendezvous: see the concurrency note. Carries the socket so a reply can retrace the path. */
    private val inbound = Channel<Pair<AddressedDatagramChannel, DatagramReadResult>>(Channel.RENDEZVOUS)

    /**
     * peer -> the socket its last datagram arrived on, i.e. the local address it dialled.
     *
     * Keyed by the numeric host and port because [SocketAddress] is an interface with no equality
     * contract, so it cannot be a map key.
     *
     * **Bounded, least-recently-seen first.** The key is whatever source a datagram *claims*, and UDP
     * lets a peer claim anything: left unbounded, a spray of spoofed sources would grow this without
     * limit — the same attack [ServerConnectionRegistry] bounds its recv_info cache against. Evicting
     * a live peer costs at most one reply routed by the fallback, because its next datagram puts it
     * back; so the bound is sized well past any plausible concurrent-peer count rather than to the
     * recv_info cache's, whose entries are native allocations and far dearer than a short string.
     *
     * Guarded by its own monitor: the receive loop writes, every connection's driver reads.
     */
    private val replyRoute =
        object : LinkedHashMap<String, AddressedDatagramChannel>(16, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, AddressedDatagramChannel>): Boolean =
                size > MAX_REPLY_ROUTES
        }

    /** How many peers currently hold a route. Exists so the bound on [replyRoute] can be tested. */
    internal val routeCount: Int get() = synchronized(replyRoute) { replyRoute.size }

    @Volatile
    private var closed = false

    init {
        for (member in members) {
            scope.launch {
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
                    try {
                        inbound.send(member to result)
                    } catch (e: CancellationException) {
                        // Nobody will take this datagram, so nobody will free it. Pooled receive
                        // buffers are native memory (#538): free it here or it is gone for good.
                        (result as? DatagramReadResult.Received)?.datagram?.payload?.freeNativeMemory()
                        throw e
                    } catch (_: Throwable) {
                        (result as? DatagramReadResult.Received)?.datagram?.payload?.freeNativeMemory()
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
        val key = datagram.peer.routeKey()
        synchronized(replyRoute) { replyRoute[key] = member }
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
     * [DatagramSendOptions.fromLocal] wins when the caller names one, so an explicit choice is
     * honoured; otherwise the peer's own route is used. A peer with neither (a server sending first,
     * which QUIC never does, or one whose route the bound evicted) falls back to the first member,
     * which is what a wildcard bind would have done anyway.
     */
    override suspend fun send(
        payload: ReadBuffer,
        to: SocketAddress,
        options: DatagramSendOptions,
    ) {
        val explicit = options.fromLocal?.let { wanted -> members.firstOrNull { it.localAddress.routeKey() == wanted.routeKey() } }
        val routed = explicit ?: synchronized(replyRoute) { replyRoute[to.routeKey()] }
        val member = routed ?: members.first()
        // fromLocal is consumed here, by choosing the socket; passing it down to a member that
        // reports sourceAddressSelect=false would be asking for something it silently ignores.
        member.send(payload, to, if (options.fromLocal == null) options else options.withoutFromLocal())
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
         * Upper bound on [replyRoute]. An entry is a short string and a reference, so this caps a
         * spoofed-source spray at well under a megabyte while covering far more concurrent peers than
         * any one server socket plausibly serves. See the field's doc for what eviction costs.
         */
        internal const val MAX_REPLY_ROUTES = 4096

        /** Stable identity for a [SocketAddress], which is an interface with no equality contract. */
        internal fun SocketAddress.routeKey(): String = "$host/$port"

        private fun DatagramSendOptions.withoutFromLocal(): DatagramSendOptions =
            DatagramSendOptions(
                ecn = ecn,
                dscp = dscp,
                dontFragment = dontFragment,
                hopLimit = hopLimit,
                fromLocal = null,
            )

        /** Wrap [members], or hand back the single socket unchanged when there is nothing to compose. */
        internal fun of(members: List<AddressedDatagramChannel>): AddressedDatagramChannel =
            if (members.size == 1) members.single() else PerLocalAddressServerChannel(members)
    }
}
