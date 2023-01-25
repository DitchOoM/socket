package com.ditchoom.socket.nio.util

import java.net.SocketAddress
import java.nio.channels.DatagramChannel


fun DatagramChannel.remoteAddressOrNull(): SocketAddress? {
    return try {
        remoteAddress
    } catch (e: Exception) {
        null
    }
}