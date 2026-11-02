@file:OptIn(com.ditchoom.buffer.flow.ExperimentalDatagramApi::class)

package com.ditchoom.socket.quic

import com.ditchoom.socket.ConnectionFailureReason
import com.ditchoom.socket.NetworkAccess
import com.ditchoom.socket.SocketConnectionException
import com.ditchoom.socket.transport.NetworkKind
import com.ditchoom.socket.udp.DeniedAccess
import com.ditchoom.socket.udp.NwRequiredInterface
import com.ditchoom.socket.udp.UdpConnectError
import com.ditchoom.socket.udp.UdpConnectException
import com.ditchoom.socket.udp.UdpSocket
import com.ditchoom.socket.udp.connect

/** Network.framework confines an `NWConnection` to either radio's interface type. */
internal val applePinnableRadios: Set<NetworkKind.Radio> = setOf(NetworkKind.Wifi, NetworkKind.Cellular)

/** The interface type an `NWConnection` pinned to this radio requires. */
internal val NetworkKind.Radio.nwRequirement: NwRequiredInterface
    get() =
        when (this) {
            NetworkKind.Wifi -> NwRequiredInterface.Wifi
            NetworkKind.Cellular -> NwRequiredInterface.Cellular
        }

/** Opens every socket confined to [radio]'s interface type, whatever the default route is. */
internal fun pinnedTo(radio: NetworkKind.Radio): ConnectedUdpOpener {
    val requirement = radio.nwRequirement
    return ConnectedUdpOpener { remoteHost, remotePort, localHost, localPort, receiveBufferSize, bufferFactory ->
        UdpSocket.connect(remoteHost, remotePort, localHost, localPort, receiveBufferSize, bufferFactory, requirement)
    }
}

/**
 * What a client connect under [policy] reports when its primary socket would not open with [refusal].
 * A pinned connection whose radio offers no path says so as [PinnedRadioUnavailable.NoPath]: NW
 * reports an absent interface as an unsatisfied path, which [UdpSocket.connect] types as
 * [UdpConnectError.Unreachable].
 */
internal fun primaryPathFailure(
    policy: MigrationPolicy,
    endpoint: QuicEndpoint,
    refusal: UdpConnectException,
): Exception =
    when (policy) {
        is MigrationPolicy.Pinned ->
            when (refusal.error) {
                is UdpConnectError.Unreachable -> PinnedRadioUnavailableException(policy.radio, PinnedRadioUnavailable.NoPath(refusal))
                is UdpConnectError.Denied -> PinnedRadioUnavailableException(policy.radio, PinnedRadioUnavailable.NotPermitted(refusal))
                else -> connectFailure(endpoint, refusal)
            }
        MigrationPolicy.Forbidden, MigrationPolicy.Manual, MigrationPolicy.Automatic -> connectFailure(endpoint, refusal)
    }

/**
 * The typed connect failure for a primary socket NW would not open. Only [UdpConnectError] says why,
 * so it decides the [ConnectionFailureReason]: no route and a user's denial are not a refusal.
 */
internal fun connectFailure(
    endpoint: QuicEndpoint,
    refusal: UdpConnectException,
): SocketConnectionException =
    when (val error = refusal.error) {
        is UdpConnectError.Unreachable ->
            SocketConnectionException.HostUnreachable("${endpoint.address.ip}:${endpoint.port}: ${error.describe()}", refusal)
        is UdpConnectError.Denied ->
            SocketConnectionException.Other(
                ConnectionFailureReason.AccessDenied(error.access.toNetworkAccess()),
                "${endpoint.address.ip}:${endpoint.port}: ${error.describe()}",
                refusal,
            )
        is UdpConnectError.AddressInUse, is UdpConnectError.LocalAddressUnavailable, is UdpConnectError.NotPermitted,
        is UdpConnectError.SocketUnavailable, is UdpConnectError.OsError, is UdpConnectError.PlatformError, is UdpConnectError.Transport,
        -> SocketConnectionException.Refused(endpoint.address.ip, endpoint.port, refusal, platformError = refusal.message)
    }

private fun DeniedAccess.toNetworkAccess(): NetworkAccess =
    when (this) {
        DeniedAccess.LocalNetwork -> NetworkAccess.LocalNetwork
        DeniedAccess.CellularData -> NetworkAccess.CellularData
        DeniedAccess.WifiData -> NetworkAccess.WifiData
    }
