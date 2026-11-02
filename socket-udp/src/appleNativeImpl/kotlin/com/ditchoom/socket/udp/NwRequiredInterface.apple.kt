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
    connectNw(
        remoteHost,
        remotePort,
        receiveBufferSize,
        bufferFactory,
        nw_udp_create_requiring(remoteHost, remotePort.toString(), requiredInterface.nwInterfaceType),
    )
