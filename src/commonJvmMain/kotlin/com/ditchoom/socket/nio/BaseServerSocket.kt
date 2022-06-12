package com.ditchoom.socket.nio

import com.ditchoom.socket.ServerSocket
import com.ditchoom.socket.SocketOptions
import com.ditchoom.socket.nio.util.aClose
import com.ditchoom.socket.nio.util.asyncSetOptions
import java.net.InetSocketAddress
import java.net.SocketAddress
import java.nio.channels.NetworkChannel

abstract class BaseServerSocket<S : NetworkChannel> : ServerSocket {
    protected var server: S? = null

    override fun port() = (server?.localAddress as? InetSocketAddress)?.port ?: -1

    override fun isOpen() = try {
        port() != null && server?.isOpen ?: false
    } catch (e: Throwable) {
        false
    }

    override suspend fun bind(
        port: Int,
        host: String?,
        socketOptions: SocketOptions?,
        backlog: Int
    ): SocketOptions {
        val socketAddress = if (port != -1) {
            InetSocketAddress(host ?: "localhost", port)
        } else {
            null
        }
        val serverLocal = serverNetworkChannel()
        val options = serverLocal.asyncSetOptions(socketOptions)
        server = bind(serverLocal, socketAddress, backlog)
        return options
    }

    abstract suspend fun bind(channel: S, socketAddress: SocketAddress?, backlog: Int): S
    abstract suspend fun serverNetworkChannel(): S

    override suspend fun close() {
        server?.aClose()
    }
}