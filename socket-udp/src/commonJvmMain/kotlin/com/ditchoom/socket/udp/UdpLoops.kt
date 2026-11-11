@file:OptIn(InternalDatagramLoopApi::class)

package com.ditchoom.socket.udp

/**
 * The loops a NIO datagram channel waits for readiness on when the coroutine using it does not run on
 * a [DatagramEventLoop] of its own (an application receiving on `Dispatchers.IO`, say). Each channel is
 * homed on one, round robin. The QUIC transport runs its channels on its own loops and never uses
 * these. A loop's thread exists only while something waits on it.
 */
internal val udpLoopGroup: DatagramEventLoopGroup =
    DatagramEventLoopGroup(size = maxOf(1, Runtime.getRuntime().availableProcessors()), namePrefix = "udp-loop")
