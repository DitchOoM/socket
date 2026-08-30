@file:OptIn(ExperimentalDatagramApi::class)

package com.ditchoom.socket.quic

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.flow.ConnectedDatagramChannel
import com.ditchoom.buffer.flow.ExperimentalDatagramApi
import com.ditchoom.buffer.flow.SocketAddress
import com.ditchoom.socket.udp.SocketAddressCodec
import com.ditchoom.socket.udp.UdpSocket
import kotlin.coroutines.cancellation.CancellationException

/**
 * Common [UdpChannelFactory] over `:socket-udp` (Phase 6). Opens a new connected [UdpSocket.connect]
 * channel to the same [peer], bound to a chosen local endpoint, for active connection migration —
 * replacing the per-platform `NioUdpChannelFactory` / `IoUringUdpChannelFactory`.
 *
 * [peer] is already resolved, so reconnecting passes its numeric [SocketAddress.host] and is a literal
 * parse to the same address (no DNS). The new path's local sockaddr is encoded via [codec] into pinned
 * native memory that the driver decodes into a [PathKey] to route datagrams to this socket; the driver
 * frees it via [NewPath.release] when the path is torn down.
 *
 * [recvBufferFactory] is the driver's `recvBufPool` (a [BufferFactory]): the migrated path's channel
 * allocates each datagram from it so a migrated path is as copy-free as the primary. [bufferFactory] is
 * the leaf factory used only for the tiny sockaddr encoding — never the pool, or a 28-byte sockaddr would
 * check out a 1350-byte pooled buffer for its whole lifetime.
 *
 * [localEndpointSupport] is supplied by the platform's connection setup rather than inferred here,
 * because one shared factory sits over three different `UdpSocket.connect` actuals and they do not agree:
 * the JVM and Linux actuals `bind` before `connect`, while the Apple actual hands the endpoint to
 * `NWConnection` and its own comment calls `localHost`/`localPort` "advisory". The call site is the only
 * place that knows which actual it is compiled against, so it is the only place that can answer honestly.
 *
 * [openChannel] is how every socket here is opened — the route probe and the path itself — and it
 * defaults to the platform's [UdpSocket.connect]. It is a parameter so that a test can refuse a probe
 * the host would happily serve: every member of [RouteProbeFailure] is a condition (no descriptors, a
 * sandbox, a `getsockname` that fails) which cannot be provoked on a real socket on demand, and a
 * decision no test can drive is how #482 lived unnoticed under a green suite.
 */
@OptIn(InternalQuicApi::class)
internal class UdpSocketChannelFactory(
    private val peer: SocketAddress,
    private val codec: SocketAddressCodec,
    private val bufferFactory: BufferFactory,
    private val recvBufferFactory: BufferFactory,
    private val receiveBufferSize: Int,
    override val localEndpointSupport: LocalEndpointSupport,
    private val openChannel: ConnectedUdpOpener = ConnectedUdpOpener.Platform,
) : UdpChannelFactory {
    override suspend fun openPath(
        localHost: String?,
        localPort: Int,
    ): NewPath {
        // An unnamed source binds the address the route would choose, never the wildcard — see
        // [routeSourceAddress]. A caller that named one gets exactly that, unchanged, and no probe is
        // taken on its behalf. Exhaustive on purpose: the three answers demand opposite handling, and
        // when they shared a `null` the one that meant "the probe failed" silently took the branch
        // meant for "this platform names the endpoint itself" — which is the unnamed bind #434
        // removed (#523). A new member must state its own answer here rather than inherit one.
        val bindHost =
            if (localHost != null) {
                localHost
            } else {
                when (val source = routeSourceAddress()) {
                    is RouteSource.Resolved -> source.host
                    // The platform picks; there is no source to name and asking for one is meaningless.
                    RouteSource.PlatformAssigned -> null
                    // Fail the open. See [UnresolvedRouteSourceException] for why this is not a fallback.
                    is RouteSource.Unresolved -> throw UnresolvedRouteSourceException(source.reason)
                }
            }
        val channel = openChannel.open(peer.host, peer.port, bindHost, localPort, receiveBufferSize, recvBufferFactory)
        val local = channel.localAddress.orNull() ?: error("connected migration path has no local address")
        val encoded = codec.encodeToNative(local, bufferFactory)
        return NewPath(
            channel = DatagramChannelUdpChannel(channel),
            localSockAddrAddress = encoded.address,
            localSockAddrLength = encoded.length,
            // The same resolved local address the sockaddr above encodes, in presentation form —
            // `UdpSocket.connect` reports it on every platform, Apple included, which is what makes
            // Succeeded name a real endpoint even where the platform picked it.
            localEndpoint = QuicLocalEndpoint(local.host, local.port),
            release = { encoded.free() },
        )
    }

    /**
     * What source address the routing table would choose for [peer] — as a [RouteSource], which says
     * *which* of the three answers it is.
     *
     * This used to be a `String?` whose `null` meant "the platform assigns the endpoint itself" **and**
     * "the probe failed" **and**, because the failure was swallowed by a bare `catch`, "bind the
     * wildcard anyway" — the one configuration this whole function exists to avoid. #483 removed the
     * only trigger that fired in practice; the shape survived, and #523 removes it: the platform's
     * answer and the probe's failure are different facts and now say so.
     *
     * **Why an unnamed bind is not good enough.** A bind with no source address chooses the ephemeral
     * port knowing only the *local* side, so the kernel can return a port whose full 4-tuple
     * `(source, peer)` is already held by another socket. Every path on a migrating connection connects
     * to the **same** peer, and a path stays open until it is retired, so each migration draws against
     * the ports its own predecessors hold. The collision does not surface from `bind` — it surfaces
     * from `connect`, as `EADDRINUSE`, which the driver reports as
     * `MigrationResult.Unmoved.Failed.LocalPathUnavailable`. That leaf reads as "this host has no local
     * path to offer" when the truth is "we drew one colliding port and never looked again": a transient
     * condition wearing a terminal type, and a migration failed for no reason on a connection that had
     * every reason to succeed.
     *
     * Binding the specific source address lets the kernel exclude exactly the ports already used
     * against it. Measured on macOS with 3000 sockets connected to one peer: **263 `connect` failures
     * with an unnamed bind, 0 with the resolved source address.** Skipping the bind entirely does not
     * help (245 failures) — the JVM binds the unnamed address implicitly first. No retry loop is
     * needed, and none is wanted: a retry would paper over the collision instead of not causing it.
     *
     * **Why the bind is never the one refusing, and why the exception says otherwise.** A bind with
     * `port = 0` asks the kernel to pick, and it picks from what it can see; it answers `EADDRNOTAVAIL`
     * when nothing is left, never `EADDRINUSE`. Only an explicitly requested port can be refused that
     * way. The blindness is in *what* the unnamed bind's lookup matches: for `port = 0` the kernel
     * compares candidate ports against sockets holding the *same* local address, and a wildcard source
     * matches none of the specific ones — so it hands out a port whose 4-tuple against [peer] is
     * already taken, and the refusal arrives one syscall later. A named source is compared against the
     * sockets that actually hold ports on it. It is the `port = 0` search and not the wildcard address
     * that skips them: the same wildcard *naming* a held port is refused at the bind, measured 5 of 5.
     * Measured on macOS/JDK 21, holders wildcard-bound and connected to one peer, 2000 ephemeral draws
     * to that peer per row:
     *
     * | holders | draws | refused at `bind0` | refused at `connect0` |
     * |---|---|---|---|
     * | 250, unnamed bind | 2000 | 0 | 159 |
     * | 250, named bind | 2000 | 0 | 0 |
     * | 1000, unnamed bind | 2000 | 0 | 230 |
     * | 1000, named bind | 2000 | 0 | 0 |
     *
     * So do not read the exception class as the answer to "which syscall": the JDK raises
     * `java.net.BindException("Address already in use")` for `EADDRINUSE` from `bind0` and `connect0`
     * alike, and only the stack frame tells them apart. #463 reads a failure of this fix's own
     * regression test as bind-side for that reason; on these numbers a bind-side `EADDRINUSE` on an
     * ephemeral port does not happen, and the two failures of that test whose frame was recorded (#483)
     * were both `connect0`.
     *
     * **Why the probe does not go to [peer]'s own port.** The probe is itself an unnamed bind, so
     * sending it at `peer.port` would put it in the very 4-tuple space the paths contend for — and its
     * exposure would grow with the number of open paths, weakening this fix exactly as the migration it
     * protects gets harder. Worse, a probe that lost that draw was swallowed to `null`, which handed the
     * *real* connect the unnamed bind this function exists to avoid: one lost draw silently restored the
     * defect. Measured on macOS against 1000 paths held to one peer, per 2000 probes:
     * **127 `EADDRINUSE` at `peer.port`, 0 at any other port** — and the source address reported is the
     * same, because a route is chosen by destination *address*, not by port. Nothing is ever sent, so
     * the port is contacted only in the sense that a connected UDP socket names it.
     *
     * Only [LocalEndpointSupport.Bindable] actuals probe at all — the JVM and Linux `connect`s, which
     * bind before connecting. Apple's assigns the endpoint through `NWConnection` and documents
     * `localHost`/`localPort` as advisory, so naming a source there would be a hint the platform is
     * free to ignore rather than the fix; that answer is [RouteSource.PlatformAssigned], which is a
     * member and not a failure.
     *
     * Resolved per call rather than reused from the connection's current path: a migration usually
     * happens *because* the old path died, so the route's answer now is the interface that is actually
     * up. The probe sends nothing — a UDP `connect` only fixes the 4-tuple locally — and is closed
     * before the real bind.
     */
    internal suspend fun routeSourceAddress(): RouteSource {
        if (localEndpointSupport != LocalEndpointSupport.Bindable) return RouteSource.PlatformAssigned
        val probe =
            try {
                openChannel.open(peer.host, routeProbePort, null, 0, receiveBufferSize, recvBufferFactory)
            } catch (cancellation: CancellationException) {
                // Cancellation is not a route answer. The old bare `catch (_: Exception)` swallowed it
                // too, so cancelling a migration mid-probe reported a resolved-enough route and bound
                // the wildcard — a cancelled coroutine quietly opening the socket #434 removed.
                throw cancellation
            } catch (refusal: Exception) {
                return RouteSource.Unresolved(RouteProbeFailure.ProbeRefused(refusal))
            }
        // Read before closing, close before the real bind: the probe must never be one of the sockets
        // the path draws against.
        val local =
            try {
                probe.localAddress.orNull()
            } finally {
                probe.close()
            }
        return when {
            local == null -> RouteSource.Unresolved(RouteProbeFailure.SourceAddressUnknown)
            namesNoInterface(local.host) -> RouteSource.Unresolved(RouteProbeFailure.SourceAddressUnnamed(local.host))
            else -> RouteSource.Resolved(local.host)
        }
    }

    /**
     * Where [routeSourceAddress] points its probe: any port [peer] does not use, so the probe's draw is
     * disjoint from every path's. Both candidates are ports nothing answers on, which is incidental —
     * a connected UDP socket sends nothing, so neither is contacted either way.
     */
    private val routeProbePort: Int =
        if (peer.port == ROUTE_PROBE_PORT) ROUTE_PROBE_PORT_ALT else ROUTE_PROBE_PORT

    private companion object {
        /** RFC 863 discard. */
        const val ROUTE_PROBE_PORT = 9

        /** RFC 862 echo — used only when [peer] is itself on [ROUTE_PROBE_PORT]. */
        const val ROUTE_PROBE_PORT_ALT = 7
    }
}

/**
 * Whether [host] names no interface — the wildcard, in either family and in any of its written forms
 * (`0.0.0.0`, `::`, `0:0:0:0:0:0:0:0`, `0000:0000:…`).
 *
 * Every one of those is written with nothing but zeros and separators, and every address that *does*
 * name an interface has at least one non-zero digit in it, so this needs no parser and no family
 * switch. An empty host names nothing either, and answers `true` for the same reason.
 */
private fun namesNoInterface(host: String): Boolean = host.all { it == '0' || it == '.' || it == ':' }

/**
 * How [UdpSocketChannelFactory] opens a connected UDP socket: [UdpSocket.connect]'s shape, with the
 * defaults spelled out so an implementation cannot silently disagree about them.
 *
 * The single seam through which both sockets in that factory are opened — the route probe and the
 * migration path — so a test can serve one and refuse the other, which is the only way to drive
 * [RouteProbeFailure]'s members: a real host will not exhaust its descriptors, deny a socket or fail
 * `getsockname` because a test asked it to.
 */
internal fun interface ConnectedUdpOpener {
    suspend fun open(
        remoteHost: String,
        remotePort: Int,
        localHost: String?,
        localPort: Int,
        receiveBufferSize: Int,
        bufferFactory: BufferFactory,
    ): ConnectedDatagramChannel

    companion object {
        /** The real thing: the platform's `UdpSocket.connect` actual. */
        val Platform: ConnectedUdpOpener =
            ConnectedUdpOpener { remoteHost, remotePort, localHost, localPort, receiveBufferSize, bufferFactory ->
                UdpSocket.connect(remoteHost, remotePort, localHost, localPort, receiveBufferSize, bufferFactory)
            }
    }
}
