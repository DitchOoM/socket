package com.ditchoom.socket.nio2

import com.ditchoom.buffer.AllocationZone
import com.ditchoom.socket.nio.BaseServerSocket
import com.ditchoom.socket.nio2.util.aAccept
import com.ditchoom.socket.nio2.util.aBind
import com.ditchoom.socket.nio2.util.openAsyncServerSocketChannel
import java.net.SocketAddress
import java.nio.channels.AsynchronousServerSocketChannel


class AsyncServerSocket(override val allocationZone: AllocationZone = AllocationZone.Direct) :
    BaseServerSocket<AsynchronousServerSocketChannel>() {
    override suspend fun accept() = AsyncServerToClientSocket(allocationZone, server!!.aAccept())

    override suspend fun bind(channel: AsynchronousServerSocketChannel, socketAddress: SocketAddress?, backlog: Int) =
        channel.aBind(socketAddress, backlog)

    override suspend fun serverNetworkChannel() = openAsyncServerSocketChannel()

}