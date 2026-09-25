package com.ditchoom.socket.udp

import java.net.DatagramSocket

/**
 * Pins a datagram socket to one network before it binds, for [UdpSocket.connect]'s `pin` overload.
 * On Android this is `Network.bindSocket(socket)`.
 */
fun interface DatagramSocketPin {
    fun pin(socket: DatagramSocket)
}
