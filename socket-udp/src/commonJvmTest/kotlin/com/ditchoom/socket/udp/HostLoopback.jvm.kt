package com.ditchoom.socket.udp

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress

/**
 * The JVM/Android [HostLoopback]: `java.net.DatagramSocket`, deliberately *not* the `java.nio`
 * `DatagramChannel` this module's backend is built on. A probe sharing its subject's API would fail
 * alongside it and report a host limit for a library bug, which is the one way this seam could do harm.
 *
 * `sendBufferSize`/`receiveBufferSize` are raised to fit, matching what the JDK does internally for the
 * NIO backend — see [HostLoopback] on why an unconfigured probe would under-report on Darwin.
 */
internal val hostLoopback =
    HostLoopback { size ->
        withContext(Dispatchers.IO) { rawLoopbackCarries(size) }
    }

private fun rawLoopbackCarries(size: Int): Boolean {
    val loopback = InetAddress.getByName("127.0.0.1")
    DatagramSocket(0, loopback).use { receiver ->
        receiver.soTimeout = RECEIVE_TIMEOUT_MILLIS
        runCatching { receiver.receiveBufferSize = maxOf(receiver.receiveBufferSize, size) }
        DatagramSocket().use { sender ->
            runCatching { sender.sendBufferSize = maxOf(sender.sendBufferSize, size) }
            val payload = ByteArray(size) { 0x41 }
            try {
                sender.send(DatagramPacket(payload, size, loopback, receiver.localPort))
            } catch (_: IOException) {
                return false // the host refused it outright (EMSGSIZE and friends)
            }
            val landing = DatagramPacket(ByteArray(size + 1), size + 1)
            return try {
                receiver.receive(landing)
                landing.length == size
            } catch (_: IOException) {
                false // SocketTimeoutException — sent, never arrived: this host drops it
            }
        }
    }
}

private const val RECEIVE_TIMEOUT_MILLIS = 1_000
