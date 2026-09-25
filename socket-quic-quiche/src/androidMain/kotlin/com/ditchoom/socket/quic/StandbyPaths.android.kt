@file:OptIn(ExperimentalDatagramApi::class)

package com.ditchoom.socket.quic

import android.net.Network
import com.ditchoom.buffer.flow.ExperimentalDatagramApi
import com.ditchoom.socket.CellularStandby
import com.ditchoom.socket.CellularStandbyHold
import com.ditchoom.socket.CellularStandbyState
import com.ditchoom.socket.NetworkMonitor
import com.ditchoom.socket.canRouteOffLink
import com.ditchoom.socket.networkId
import com.ditchoom.socket.transport.NetworkId
import com.ditchoom.socket.udp.DatagramSocketPin
import com.ditchoom.socket.udp.UdpSocket
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.job

/**
 * Under [StandbyLink.KeepCellularReady], hold [CellularStandby.processDefault] for as long as
 * [connection] is open, and offer the cellular network it attaches as a [StandbyPath].
 *
 * Only a connection whose reactor will run holds anything: [MigrationPolicy.Automatic], a monitor that
 * can observe the network, and a socket of its own to open more from.
 */
internal actual fun standbyPathsFor(
    options: QuicOptions,
    monitor: NetworkMonitor,
    connection: JvmQuicConnection,
): StandbyPaths {
    when (options.migration) {
        MigrationPolicy.Forbidden, MigrationPolicy.Manual -> return StandbyPaths.None
        MigrationPolicy.Automatic -> Unit
    }
    when (options.standbyLink) {
        StandbyLink.OnDemand -> return StandbyPaths.None
        StandbyLink.KeepCellularReady -> Unit
    }
    if (monitor === NetworkMonitor.AlwaysAvailable) return StandbyPaths.None
    val factory =
        when (val origin = connection.pathOrigin) {
            is ClientPathOrigin.Owned -> origin.factory
            ClientPathOrigin.SharedPort -> return StandbyPaths.None
        }
    val hold = CellularStandby.processDefault().hold()
    // Runs at once if the connection has already ended, so the hold cannot outlive it.
    connection.coroutineContext.job.invokeOnCompletion { hold.close() }
    return CellularStandbyPaths(hold, factory, connection.quicheDriver)
}

/** [StandbyPaths] over one [CellularStandbyHold]. */
private class CellularStandbyPaths(
    private val hold: CellularStandbyHold,
    private val factory: UdpSocketChannelFactory,
    private val driver: QuicheDriver,
) : StandbyPaths {
    override val current: StandbyPath get() = hold.state.value.asStandbyPath()

    override val changes: Flow<StandbyPath> = hold.state.map { it.asStandbyPath() }

    /**
     * A cellular network the platform attached, under the same filters the reactor applies to the
     * default network: nothing it cannot name, and nothing traffic will not cross.
     */
    private fun CellularStandbyState.asStandbyPath(): StandbyPath =
        when (this) {
            is CellularStandbyState.Attached ->
                if (link.canRouteOffLink && link.networkId != NetworkId.Unidentified) {
                    val pinned = factory.openingWith(pinnedTo(network))
                    StandbyPath.Ready(link.networkId) { driver.migrateVia(PathVia.Standby(pinned)) }
                } else {
                    StandbyPath.Unavailable
                }
            CellularStandbyState.Released, is CellularStandbyState.Requesting, is CellularStandbyState.Refused -> StandbyPath.Unavailable
        }
}

/** Opens every socket pinned to [network] with `Network.bindSocket`, whatever the default network is. */
private fun pinnedTo(network: Network): ConnectedUdpOpener {
    val pin = DatagramSocketPin { socket -> network.bindSocket(socket) }
    return ConnectedUdpOpener { remoteHost, remotePort, localHost, localPort, receiveBufferSize, bufferFactory ->
        UdpSocket.connect(remoteHost, remotePort, localHost, localPort, receiveBufferSize, bufferFactory, pin)
    }
}
