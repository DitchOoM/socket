package com.ditchoom.socket.quic

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
