package com.ditchoom.socket.quic.core.edge

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.socket.quic.PathKey
import com.ditchoom.socket.quic.UdpBatching
import com.ditchoom.socket.quic.core.loop.LoopIo
import com.ditchoom.socket.quic.core.sched.CallerLanes
import com.ditchoom.socket.quic.core.sched.CallerSend
import com.ditchoom.socket.quic.core.sched.callerLanesOf
import com.ditchoom.socket.quic.core.udp.SocketBuffers
import com.ditchoom.socket.quic.core.udp.SocketPeer
import com.ditchoom.socket.quic.toPathKey

/** Where a server's datagrams come from: a socket it binds and owns, or a channel the caller lent and keeps. */
internal sealed interface ServerPort {
    class Bound(
        val bind: PathKey,
    ) : ServerPort

    /** The caller's channel, shared with whatever else runs on its port: the server never closes it. */
    class Lent(
        val channel: LentChannel,
    ) : ServerPort
}

/** The socket a server on [this] port serves on: unconnected, bound on [io]'s stack, or the lent channel. */
internal fun ServerPort.open(
    io: LoopIo,
    memory: EdgeMemory,
    buffers: SocketBuffers,
    ceiling: UdpBatching,
    maxDatagrams: Int,
    factory: BufferFactory,
): StackSocket =
    when (this) {
        is ServerPort.Bound -> openStackSocket(io, memory, bind, SocketPeer.Unconnected, buffers, ceiling, maxDatagrams)
        is ServerPort.Lent -> StackSocket.Lent(channel, LentPeers.Every, channel.local.toPathKey(), factory)
    }

/** The [count] lanes a server's callers send from, made by [make]; none on a lent channel, where only the loop sends. */
internal fun ServerPort.lanesOf(
    count: Int,
    make: (index: Int) -> CallerSend,
): CallerLanes =
    when (this) {
        is ServerPort.Bound -> callerLanesOf(count, make)
        is ServerPort.Lent -> CallerLanes.None
    }
