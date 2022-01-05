package com.ditchoom.socket

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.isActive
import kotlin.time.ExperimentalTime

@ExperimentalUnsignedTypes
@ExperimentalTime
class MockServerSocket(): ServerSocket {
    private var isBound = false
    private var port :UShort? = null
    private val clients = HashMap<UShort, MockClientSocket>()

    override suspend fun accept():ClientSocket {
        val remoteSocket = clientsToAccept.receive()
        val serverToClient = MockClientSocket()
        serverToClient.remote = remoteSocket
        remoteSocket.remote = serverToClient
        serverToClient.localPortInternal = remoteSocket.remotePortInternal
        serverToClient.remotePortInternal = remoteSocket.localPortInternal
        remoteSocket.isOpenInternal = true
        serverToClient.isOpenInternal = true
        clients[remoteSocket.remotePortInternal!!] = serverToClient
        return serverToClient
    }

    override suspend fun bind(
        port: UShort?,
        host: String?,
        socketOptions: SocketOptions?,
        backlog: UInt
    ): SocketOptions {
        if (servers.contains(port)) {
            throw Exception("Port already used")
        }
        val actualPort = port ?: MockClientSocket.lastFakePortUsed++.toUShort()
        servers[actualPort] = this
        isBound = true
        this.port = actualPort
        return socketOptions ?: SocketOptions()
    }


    override fun isOpen(): Boolean = isBound

    override fun port(): UShort?  = port

    override suspend fun close() {
        for (client in clients) {
            client.value.close()
            client.value.remote.close()
        }
        clients.clear()
        isBound = false
    }

    companion object {
        val servers = HashMap<UShort, MockServerSocket>()
        val clientsToAccept = Channel<MockClientSocket>()
    }
}