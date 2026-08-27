@file:OptIn(ExperimentalDatagramApi::class)

package com.ditchoom.socket.quic

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.flow.ExperimentalDatagramApi
import com.ditchoom.buffer.flow.SocketAddress
import com.ditchoom.socket.udp.SocketAddressCodec
import com.ditchoom.socket.udp.UdpSocket

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
 */
@OptIn(InternalQuicApi::class)
internal class UdpSocketChannelFactory(
    private val peer: SocketAddress,
    private val codec: SocketAddressCodec,
    private val bufferFactory: BufferFactory,
    private val recvBufferFactory: BufferFactory,
    private val receiveBufferSize: Int,
    override val localEndpointSupport: LocalEndpointSupport,
) : UdpChannelFactory {
    override suspend fun openPath(
        localHost: String?,
        localPort: Int,
    ): NewPath {
        // An unnamed source binds the address the route would choose, never the wildcard — see
        // [routeSourceAddress]. A caller that named one gets exactly that, unchanged.
        val bindHost = localHost ?: routeSourceAddress()
        val channel = UdpSocket.connect(peer.host, peer.port, bindHost, localPort, receiveBufferSize, recvBufferFactory)
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
     * The source address the routing table would choose for [peer], or `null` when there is none to
     * name — the platform assigns the endpoint itself, or the route cannot be determined. The bind then
     * falls back to the platform default, exactly as before.
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
     * Only for [LocalEndpointSupport.Bindable] actuals — the JVM and Linux `connect`s, which bind
     * before connecting. Apple's assigns the endpoint through `NWConnection` and documents
     * `localHost`/`localPort` as advisory, so naming a source there would be a hint the platform is
     * free to ignore rather than the fix, and it is skipped.
     *
     * Resolved per call rather than reused from the connection's current path: a migration usually
     * happens *because* the old path died, so the route's answer now is the interface that is actually
     * up. The probe sends nothing — a UDP `connect` only fixes the 4-tuple locally — and is closed
     * before the real bind.
     */
    internal suspend fun routeSourceAddress(): String? {
        if (localEndpointSupport != LocalEndpointSupport.Bindable) return null
        return try {
            val probe = UdpSocket.connect(peer.host, routeProbePort, null, 0, receiveBufferSize, recvBufferFactory)
            try {
                probe.localAddress.orNull()?.host
            } finally {
                probe.close()
            }
        } catch (_: Exception) {
            // No route, or a sandbox that forbids the probe: fall back to the unnamed bind and let the
            // real connect report the truth rather than inventing an error here.
            null
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
