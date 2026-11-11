@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.ditchoom.socket.udp

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.flow.ConnectedDatagramChannel
import com.ditchoom.buffer.flow.ExperimentalDatagramApi
import com.ditchoom.socket.udp.nw.nw_udp_create_requiring

@ExperimentalDatagramApi
actual suspend fun UdpSocket.connect(
    remoteHost: String,
    remotePort: Int,
    localHost: String?,
    localPort: Int,
    receiveBufferSize: Int,
    bufferFactory: BufferFactory,
    requiredInterface: NwRequiredInterface,
): ConnectedDatagramChannel =
    connectRequiring(remoteHost, remotePort, receiveBufferSize, bufferFactory, requiredInterface, NwConnectTrace())

/** The required-interface connect with the caller's [trace], so a test can read the timeline of a connect that succeeded slowly. */
internal suspend fun UdpSocket.connectRequiring(
    remoteHost: String,
    remotePort: Int,
    receiveBufferSize: Int,
    bufferFactory: BufferFactory,
    requiredInterface: NwRequiredInterface,
    trace: NwConnectTrace,
): ConnectedDatagramChannel =
    connectNw(remoteHost, remotePort, receiveBufferSize, bufferFactory, trace) {
        nw_udp_create_requiring(remoteHost, remotePort.toString(), requiredInterface.nwInterfaceType)
    }
