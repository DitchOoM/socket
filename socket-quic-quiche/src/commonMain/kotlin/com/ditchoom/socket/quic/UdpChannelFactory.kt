package com.ditchoom.socket.quic

/**
 * Opens additional client UDP sockets for connection migration (RFC 9000 §9).
 *
 * The originating `withQuicConnection` knows the peer; a factory captures it so the [QuicheDriver] —
 * which is platform-neutral — can open a second path socket (connected to the same peer, from a
 * different local endpoint) without knowing how sockets are created on each platform.
 *
 * Only clients migrate in QUIC v1, so only client connection setups construct one. A driver that has no
 * factory expresses that as [MigrationCapability.BackendCannotMigrate] rather than by omitting an
 * argument.
 */
@InternalQuicApi
interface UdpChannelFactory {
    /**
     * Whether this factory can actually bind the local endpoint a caller asks for.
     *
     * Not every platform can. Network.framework assigns the local endpoint itself, so on Apple the
     * `localHost`/`localPort` arguments below are advisory — see `UdpSocket.apple.kt`'s `connect`,
     * whose own comment says so. Declaring that here lets the driver **refuse** a request it cannot
     * honour instead of silently discarding it, which is the failure mode this whole change exists to
     * remove.
     */
    val localEndpointSupport: LocalEndpointSupport

    /**
     * Open a UDP socket connected to the same peer as the originating connection, from a new local
     * endpoint.
     *
     * [localHost] `null` means "the default interface" and [localPort] `0` means "any ephemeral port" —
     * the pair every automatic migration uses, and the only request every platform can satisfy. A
     * caller naming a specific endpoint gets it only when [localEndpointSupport] is
     * [LocalEndpointSupport.Bindable]; the driver checks before calling, so an implementation never has
     * to reject one here.
     *
     * The returned [NewPath.localSockAddrAddress]/[NewPath.localSockAddrLength] point at pinned native
     * memory holding the socket's *resolved* local sockaddr — resolved, because on a platform that
     * assigns the endpoint that is the only way to learn what was chosen — and
     * [NewPath.localEndpoint] is that same resolved endpoint in presentation form. The driver owns the
     * returned channel and must [UdpChannel.close] it and call [NewPath.release] when the path is torn
     * down.
     */
    suspend fun openPath(
        localHost: String?,
        localPort: Int,
    ): NewPath
}

/**
 * Whether a [UdpChannelFactory] can bind a caller-chosen local endpoint.
 *
 * A sealed pair rather than a `Boolean` because the two cases carry different *reasons* and a boolean
 * named `honorsLocalEndpoint` would be one more capability flag of exactly the kind this refactor is
 * deleting. It is also the value a diagnostic wants to print.
 */
@InternalQuicApi
sealed interface LocalEndpointSupport {
    /**
     * The platform binds what it is told (POSIX `bind` before `connect`) — JVM/Android via NIO, Linux
     * via io_uring. Any `localHost`/`localPort` request is honoured exactly.
     */
    data object Bindable : LocalEndpointSupport

    /**
     * The platform chooses the local endpoint itself and a request cannot change it — Apple, where
     * `UdpSocket.connect` hands off to `NWConnection` and NW owns endpoint assignment.
     *
     * The default request (`null`, `0`) is still fully served: "let the platform pick" is precisely what
     * this platform does, and it is what automatic migration asks for. Only an *explicit* endpoint is
     * unserviceable, and the driver refuses that rather than pretending.
     */
    data object PlatformAssigned : LocalEndpointSupport
}

/**
 * A freshly-opened migration path: the channel plus its pinned local sockaddr and a hook to free that
 * sockaddr's backing memory. The driver decodes [localSockAddrAddress] into a [PathKey] to route
 * datagrams to this socket.
 *
 * @property localEndpoint the endpoint the socket **actually bound**, in presentation form — the same
 *   fact as the pinned sockaddr, in the shape a caller can read. Every factory already resolves it (the
 *   sockaddr has to be resolved for quiche to probe the 4-tuple), so this only stops discarding it: it
 *   is what `QuicScope.migrate()` reports as [MigrationResult.Succeeded.localEndpoint], and on a
 *   [LocalEndpointSupport.PlatformAssigned] platform it is the only way the caller can learn where the
 *   connection landed.
 */
@InternalQuicApi
class NewPath(
    val channel: UdpChannel,
    val localSockAddrAddress: Long,
    val localSockAddrLength: Int,
    val localEndpoint: QuicLocalEndpoint,
    val release: () -> Unit,
)
