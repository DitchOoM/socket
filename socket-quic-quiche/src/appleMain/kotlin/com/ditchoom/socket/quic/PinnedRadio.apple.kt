@file:OptIn(com.ditchoom.buffer.flow.ExperimentalDatagramApi::class)

package com.ditchoom.socket.quic

import com.ditchoom.socket.SocketConnectionException
import com.ditchoom.socket.transport.NetworkKind
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
                else -> refused(endpoint, refusal)
            }
        MigrationPolicy.Forbidden, MigrationPolicy.Manual, MigrationPolicy.Automatic -> refused(endpoint, refusal)
    }

private fun refused(
    endpoint: QuicEndpoint,
    refusal: UdpConnectException,
) = SocketConnectionException.Refused(endpoint.address.ip, endpoint.port, platformError = refusal.message)
