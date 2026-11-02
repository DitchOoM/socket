package com.ditchoom.socket.udp

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.flow.ConnectedDatagramChannel
import com.ditchoom.buffer.flow.ExperimentalDatagramApi

// In `appleMain`, not `appleNativeImpl`, so `:socket-quic-quiche`'s own `appleMain` resolves it from
// metadata — see AppleUdpShared.kt. No platform-width types here.

/**
 * The kind of interface an Apple [UdpSocket.connect] is confined to — Network.framework's
 * `nw_parameters_set_required_interface_type`.
 */
enum class NwRequiredInterface(
    /** The `nw_interface_type_t` value. */
    internal val nwInterfaceType: Int,
) {
    Wifi(nwInterfaceType = 1),
    Cellular(nwInterfaceType = 2),
}

/**
 * [UdpSocket.connect], confined to an interface of [requiredInterface]'s kind whatever the default
 * route is. With no such interface up, or none that reaches [remoteHost], NW reports the path
 * unsatisfied and the connect fails at once with [UdpConnectError.Unreachable]. [localHost] and
 * [localPort] are advisory, as for every Apple connect.
 */
@ExperimentalDatagramApi
expect suspend fun UdpSocket.connect(
    remoteHost: String,
    remotePort: Int,
    localHost: String?,
    localPort: Int,
    receiveBufferSize: Int,
    bufferFactory: BufferFactory,
    requiredInterface: NwRequiredInterface,
): ConnectedDatagramChannel
