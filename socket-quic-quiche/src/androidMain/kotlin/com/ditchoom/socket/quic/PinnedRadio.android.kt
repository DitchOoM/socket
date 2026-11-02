@file:OptIn(ExperimentalDatagramApi::class)

package com.ditchoom.socket.quic

import android.net.Network
import com.ditchoom.buffer.flow.ExperimentalDatagramApi
import com.ditchoom.socket.RadioNetworkRequest
import com.ditchoom.socket.RadioNetworkState
import com.ditchoom.socket.RadioRequestRefusal
import com.ditchoom.socket.transport.NetworkKind
import com.ditchoom.socket.udp.DatagramSocketPin
import com.ditchoom.socket.udp.UdpSocket
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.time.Duration

/** `Network.bindSocket` pins a socket to either radio. */
internal actual val platformPinnableRadios: Set<NetworkKind.Radio> = setOf(NetworkKind.Wifi, NetworkKind.Cellular)

/**
 * Request [radio]'s network for as long as [lifetime] runs, and pin every socket to it once the
 * platform produces it. A request the platform refuses, or does not answer within [bound], fails the
 * connect with a [PinnedRadioUnavailableException].
 */
internal actual suspend fun pinnedRadioOpener(
    radio: NetworkKind.Radio,
    bound: Duration,
    lifetime: Job,
): ConnectedUdpOpener {
    val request = RadioNetworkRequest.open(radio)
    // Runs at once if the connection has already ended, so the request cannot outlive it.
    lifetime.invokeOnCompletion { request.close() }
    val settled =
        withTimeoutOrNull(bound) { request.state.filterIsInstance<RadioNetworkState.Answered>().first() }
            ?: throw PinnedRadioUnavailableException(radio, PinnedRadioUnavailable.NotAttached(bound))
    return when (settled) {
        is RadioNetworkState.Attached -> pinnedTo(settled.network)
        is RadioNetworkState.Refused ->
            throw PinnedRadioUnavailableException(
                radio,
                when (val reason = settled.reason) {
                    is RadioRequestRefusal.MissingPermission -> PinnedRadioUnavailable.NotPermitted(reason.cause)
                    RadioRequestRefusal.NoApplicationContext -> PinnedRadioUnavailable.NoPlatformContext
                },
            )
        // Only [lifetime] closes the request: the connect it belonged to has already been torn down.
        RadioNetworkState.Closed -> throw CancellationException("the connection ended while $radio was requested")
    }
}

/** Opens every socket pinned to [network] with `Network.bindSocket`, whatever the default network is. */
internal fun pinnedTo(network: Network): ConnectedUdpOpener {
    val pin = DatagramSocketPin { socket -> network.bindSocket(socket) }
    return ConnectedUdpOpener { remoteHost, remotePort, localHost, localPort, receiveBufferSize, bufferFactory ->
        UdpSocket.connect(remoteHost, remotePort, localHost, localPort, receiveBufferSize, bufferFactory, pin)
    }
}
