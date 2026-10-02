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
 * A connected io_uring UDP channel for the linuxTest proxies (impairment, passive migration). Not a
 * production channel: production Linux QUIC sends and receives through socket-udp's channels, which close a
 * descriptor only when the last submit on it has ended. This one closes its descriptor at once, so a test
 * closes it only once nothing is submitting on it.
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
        target: SendTarget,
    ): SendOutcome {
        // Connected socket — always sends to the connected peer, so a [SendTarget.ServerReply] does not apply.
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
