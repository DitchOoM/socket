package com.ditchoom.socket.nio

import com.ditchoom.buffer.AllocationZone
import com.ditchoom.buffer.JvmBuffer
import com.ditchoom.buffer.PlatformBuffer
import com.ditchoom.buffer.ReadBuffer
import com.ditchoom.buffer.allocate
import com.ditchoom.socket.ClientToServerSocket
import com.ditchoom.socket.EMPTY_BUFFER
import com.ditchoom.socket.nio.util.aClose
import com.ditchoom.socket.nio.util.aConfigureBlocking
import com.ditchoom.socket.nio.util.localAddressOrNull
import com.ditchoom.socket.nio.util.buildInetAddress
import com.ditchoom.socket.nio.util.read
import com.ditchoom.socket.nio.util.remoteAddressOrNull
import com.ditchoom.socket.nio.util.write
import java.net.InetSocketAddress
import java.net.StandardSocketOptions
import java.nio.channels.DatagramChannel
import java.nio.channels.Selector
import kotlin.time.Duration

class DatagramSocket(blocking: Boolean = false) {
    private val datagramChannel = DatagramChannel.open().also { it.configureBlocking(blocking) }
    private val selector = if (blocking) Selector.open() else null

    suspend fun bind(port: Int, hostname: String?) {
        datagramChannel.bind(if (port < 1) {
            buildInetAddress(port, hostname)
        } else {
            null
        })
    }

    fun isOpen(): Boolean = datagramChannel.isOpen
    fun localPort(): Int = (datagramChannel.localAddressOrNull() as? InetSocketAddress)?.port ?: -1
    fun remotePort(): Int = (datagramChannel.remoteAddressOrNull() as? InetSocketAddress)?.port ?: -1

    fun sendBufferSize(): Int = datagramChannel.getOption(StandardSocketOptions.SO_SNDBUF)
    fun receiveBufferSize(): Int = datagramChannel.getOption(StandardSocketOptions.SO_RCVBUF)

    suspend fun read(timeout: Duration): ReadBuffer {
        val buffer = PlatformBuffer.allocate(
            receiveBufferSize(),
            AllocationZone.Direct) as JvmBuffer
        datagramChannel.read(buffer.byteBuffer, selector, timeout)
        return buffer
    }

    suspend fun write(buffer: ReadBuffer, timeout: Duration): Int =
        datagramChannel.write((buffer as JvmBuffer).byteBuffer, selector, timeout)

    suspend fun close() {
        datagramChannel.aClose()
    }
}