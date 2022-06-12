package com.ditchoom.socket

import kotlinx.coroutines.channels.Channel

class MockServerSocket : ServerSocket {
    private var isBound = false
    private var port: Int = -1
    private val clients = HashMap<Int, MockClientSocket>()

    override suspend fun accept(): ClientSocket {
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
        port: Int,
        host: String?,
        socketOptions: SocketOptions?,
        backlog: Int
    ): SocketOptions {
        if (servers.contains(port)) {
            throw Exception("Port already used")
        }
        val actualPort = if (port == -1) {
            MockClientSocket.lastFakePortUsed++
        } else {
            port
        }
        servers[actualPort] = this
        isBound = true
        this.port = actualPort
        return socketOptions ?: SocketOptions()
    }


    override fun isOpen(): Boolean = isBound

    override fun port(): Int = port

    override suspend fun close() {
        for (client in clients) {
            client.value.close()
            client.value.remote.close()
        }
        clients.clear()
        isBound = false
    }

    companion object {
        val servers = HashMap<Int, MockServerSocket>()
        val clientsToAccept = Channel<MockClientSocket>()
    }
}