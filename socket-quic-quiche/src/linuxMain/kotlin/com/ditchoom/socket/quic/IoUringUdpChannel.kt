@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.ditchoom.socket.quic

import com.ditchoom.buffer.PlatformBuffer
import com.ditchoom.buffer.nativeMemoryAccess
import com.ditchoom.socket.IoUringManager
import com.ditchoom.socket.linux.io_uring_prep_recv
import com.ditchoom.socket.linux.io_uring_prep_send
import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.convert
import kotlinx.cinterop.toCPointer
import kotlin.time.Duration.Companion.seconds

/**
 * Linux [UdpChannel] backed by io_uring.
 *
 * [receive] suspends via [IoUringManager.submitAndWait] until a datagram arrives — zero CPU when idle.
 * [send] submits an io_uring send and awaits completion.
 */
internal class IoUringUdpChannel(
    private val fd: Int,
) : UdpChannel {
    override suspend fun receive(buffer: PlatformBuffer): Int {
        val ptr = buffer.nativeMemoryAccess!!.nativeAddress.toCPointer<ByteVar>()!!
        return IoUringManager.submitAndWait(1.seconds) { sqe, _ ->
            io_uring_prep_recv(sqe, fd, ptr, buffer.capacity.convert(), 0)
        }
    }

    override suspend fun send(
        buffer: PlatformBuffer,
        len: Int,
        dest: PathKey?,
    ): SendOutcome {
        // Connected socket — always sends to the connected peer, so [dest] (server egress routing to
        // sendInfo.to) does not apply here.
        //
        // This does NOT mean Linux lacks server-side egress routing. This class is not on any
        // production path: since the Phase 6 :socket-udp adapter cutover the Linux client builds
        // UdpSocketChannelFactory and the Linux server runs SharedQuicheServer, whose per-connection
        // egress is the shared ServerConnectionUdpChannel (commonMain) — and that one does resolve
        // [dest] to a migrated peer, on every platform. What remains here is the userspace proxy
        // plumbing the linuxTest impairment/passive-migration suites build on. The older comment cited
        // #374 for "Linux server-side migration is unimplemented", which was wrong about this file.
        val ptr = buffer.nativeMemoryAccess!!.nativeAddress.toCPointer<ByteVar>()!!
        return sendOutcomeOf {
            IoUringManager.submitAndWait(1.seconds) { sqe, _ ->
                io_uring_prep_send(sqe, fd, ptr, len.convert(), 0)
            }
        }
    }

    override fun close() {
        platform.posix.close(fd)
    }
}
