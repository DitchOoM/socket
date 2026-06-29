@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class, kotlinx.coroutines.DelicateCoroutinesApi::class)

package com.ditchoom.socket.quic

import com.ditchoom.buffer.PlatformBuffer
import com.ditchoom.buffer.nativeMemoryAccess
import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.convert
import kotlinx.cinterop.toCPointer
import kotlinx.coroutines.newSingleThreadContext
import kotlinx.coroutines.withContext

/**
 * Apple (Kotlin/Native) [UdpChannel] over a connected POSIX UDP socket.
 *
 * Phase-0 quiche-on-Apple datapath: the simplest mechanism that moves datagrams. The pivot's stated
 * production datapath is `NWConnection` in UDP mode (for NWPath connection-migration awareness); this
 * POSIX path is the "emergency fallback" the same [UdpChannel] seam keeps swappable, and is sufficient
 * to prove the engine + run the loopback suites on macOS. The migration-aware NW datapath is a tracked
 * follow-up.
 *
 * [receive]'s blocking `recv` runs on a dedicated single-thread dispatcher so it never blocks the
 * driver's `select` loop; [close]ing the fd makes the in-flight `recv` return -1, which the driver's
 * `udpReaderLoop` treats as a closed channel and exits.
 */
internal class AppleUdpChannel(
    private val fd: Int,
) : UdpChannel {
    private val recvDispatcher = newSingleThreadContext("apple-udp-recv-$fd")

    override suspend fun receive(buffer: PlatformBuffer): Int {
        val ptr = buffer.nativeMemoryAccess!!.nativeAddress.toCPointer<ByteVar>()!!
        return withContext(recvDispatcher) {
            platform.posix.recv(fd, ptr, buffer.capacity.convert(), 0).toInt()
        }
    }

    override suspend fun send(
        buffer: PlatformBuffer,
        len: Int,
        dest: PathKey?,
    ) {
        // Connected client socket — always sends to the connected peer; server-egress [dest] routing
        // (quiche's sendInfo.to for a migrated peer) does not apply here (the server side uses
        // AppleUdpServerChannel + sendto). UDP send to loopback does not block in practice.
        val ptr = buffer.nativeMemoryAccess!!.nativeAddress.toCPointer<ByteVar>()!!
        platform.posix.send(fd, ptr, len.convert(), 0)
    }

    override fun close() {
        platform.posix.close(fd)
        recvDispatcher.close()
    }
}
