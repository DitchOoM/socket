@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.ditchoom.socket.quic

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.PlatformBuffer
import com.ditchoom.buffer.nativeMemoryAccess
import com.ditchoom.socket.IoUringManager
import com.ditchoom.socket.linux.io_uring_prep_recvmsg
import com.ditchoom.socket.linux.io_uring_prep_sendmsg
import com.ditchoom.socket.linux.iovec
import com.ditchoom.socket.linux.msghdr
import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.alloc
import kotlinx.cinterop.convert
import kotlinx.cinterop.free
import kotlinx.cinterop.nativeHeap
import kotlinx.cinterop.pointed
import kotlinx.cinterop.ptr
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.sizeOf
import kotlinx.cinterop.toCPointer
import platform.posix.AF_INET
import platform.posix.AF_INET6
import platform.posix.sockaddr
import platform.posix.sockaddr_in
import platform.posix.sockaddr_in6
import platform.posix.sockaddr_storage
import platform.posix.socklen_t
import kotlin.time.Duration.Companion.seconds

/**
 * Reconstruct the network-order sockaddr bytes for [this] path key into a fresh buffer — the
 * inverse of [CinteropQuicheApi]'s sockaddr decode, used for server egress to a migrated peer's
 * new source (`sendInfo.to`). Returns the buffer + its sockaddr length, or null for an unknown
 * family. IPv4 → `sockaddr_in` (16B), IPv6 → `sockaddr_in6` (28B). Caller frees the buffer.
 */
internal fun PathKey.toSockAddrBuffer(bufferFactory: BufferFactory): Pair<PlatformBuffer, Int>? {
    // Emit `size` sockaddr bytes into the buffer's native memory (the raw send reads from its
    // nativeAddress, position-independent). [leading] holds the meaningful prefix bytes (including
    // any interior zeros); the remainder is zero-filled (buffers aren't guaranteed zero-init).
    fun build(
        size: Int,
        leading: List<Int>,
    ): Pair<PlatformBuffer, Int> {
        val buf = bufferFactory.allocate(size)
        for (i in 0 until size) buf.writeByte((if (i < leading.size) leading[i] else 0).toByte())
        buf.resetForRead()
        return buf to size
    }
    return when (family) {
        4 ->
            build(
                sizeOf<sockaddr_in>().toInt(),
                listOf(
                    AF_INET and 0xFF,
                    (AF_INET shr 8) and 0xFF, // sin_family (host order; Linux is LE)
                    (port shr 8) and 0xFF,
                    port and 0xFF, // sin_port (network order)
                    // sin_addr (network order)
                    ((lo shr 24) and 0xFF).toInt(),
                    ((lo shr 16) and 0xFF).toInt(),
                    ((lo shr 8) and 0xFF).toInt(),
                    (lo and 0xFF).toInt(),
                ),
            )
        6 ->
            build(
                sizeOf<sockaddr_in6>().toInt(),
                buildList {
                    add(AF_INET6 and 0xFF)
                    add((AF_INET6 shr 8) and 0xFF)
                    add((port shr 8) and 0xFF)
                    add(port and 0xFF)
                    repeat(4) { add(0) } // sin6_flowinfo
                    for (i in 0 until 8) add(((hi shr (56 - 8 * i)) and 0xFF).toInt()) // sin6_addr high 8
                    for (i in 0 until 8) add(((lo shr (56 - 8 * i)) and 0xFF).toInt()) // sin6_addr low 8
                },
            )
        else -> null
    }
}

/**
 * Result of [IoUringUdpServerChannel.recvFrom].
 *
 * [peerAddr] and [peerAddrLen] point to internal storage valid until the next [recvFrom] call.
 */
internal class RecvFromResult(
    val bytesReceived: Int,
    val peerAddr: CPointer<sockaddr>,
    val peerAddrLen: socklen_t,
)

/**
 * Unconnected UDP socket for QUIC server use, backed by io_uring `recvmsg`/`sendmsg`.
 *
 * [recvFrom] returns both the datagram and the sender's address (needed for QUIC connection routing).
 * [sendTo] sends a datagram to a specific peer via the same bound socket.
 *
 * The receive-side structures are pre-allocated and reused — [recvFrom] must be called from a
 * single coroutine (the server's central receive loop).
 */
internal class IoUringUdpServerChannel(
    private val fd: Int,
) {
    // Pre-allocated structures for recvmsg — reused by the single-threaded receive loop
    private val recvAddr = nativeHeap.alloc<sockaddr_storage>()
    private val recvIov = nativeHeap.alloc<iovec>()
    private val recvMsg: msghdr

    init {
        // nativeHeap.alloc does not zero-init. Without this, ss_family is garbage until the
        // first successful recvmsg overwrites it — and on any recvmsg that fails to write
        // msg_name (error CQE, spurious wake, 0-byte result), the garbage family gets handed
        // to quiche and SIGABRTs through Rust's std_addr_from_c panic. Explicit zero means
        // the family check below catches the bug cleanly instead of aborting the process.
        platform.posix.memset(recvAddr.ptr, 0, sizeOf<sockaddr_storage>().convert())
        val msg = nativeHeap.alloc<msghdr>()
        msg.msg_name = recvAddr.ptr
        msg.msg_namelen = sizeOf<sockaddr_storage>().convert()
        msg.msg_iov = recvIov.ptr
        msg.msg_iovlen = 1.convert()
        msg.msg_control = null
        msg.msg_controllen = 0u.convert()
        recvMsg = msg
    }

    /**
     * Receive one UDP datagram into [buffer].
     *
     * Suspends via io_uring until a packet arrives. Returns the bytes received and the
     * sender's address. The address points to internal storage — copy it before the next call.
     *
     * Returns a [RecvFromResult] with non-positive [RecvFromResult.bytesReceived] if the
     * recvmsg returned no data or an error; callers should skip / retry rather than pass
     * the address to quiche (whose FFI panics on sa_family ∉ {AF_INET, AF_INET6}).
     */
    suspend fun recvFrom(buffer: PlatformBuffer): RecvFromResult {
        val ptr = buffer.nativeMemoryAccess!!.nativeAddress.toCPointer<ByteVar>()!!
        recvIov.iov_base = ptr
        recvIov.iov_len = buffer.capacity.convert()
        recvMsg.msg_namelen = sizeOf<sockaddr_storage>().convert()
        // Re-zero before every recvmsg: if the kernel doesn't update msg_name (rare but
        // observed on spurious CQEs), we catch it at the ss_family check below rather than
        // propagate the previous call's address as if it were this call's.
        platform.posix.memset(recvAddr.ptr, 0, sizeOf<sockaddr_storage>().convert())

        val bytesReceived =
            IoUringManager.submitAndWait(1.seconds) { sqe, _ ->
                io_uring_prep_recvmsg(sqe, fd, recvMsg.ptr, 0u)
            }
        if (bytesReceived <= 0) {
            return RecvFromResult(
                bytesReceived = bytesReceived,
                peerAddr = recvAddr.ptr.reinterpret(),
                peerAddrLen = 0u,
            )
        }
        // Determine actual address length from the family (recvmsg doesn't shrink msg_namelen).
        // If the family is neither AF_INET nor AF_INET6, recvmsg didn't write a valid peer
        // address — signal the caller by returning bytesReceived=-1 so the packet is skipped.
        val family = recvAddr.ss_family.toInt()
        val addrLen: socklen_t =
            when (family) {
                AF_INET -> sizeOf<sockaddr_in>().convert()
                AF_INET6 -> sizeOf<sockaddr_in6>().convert()
                else ->
                    return RecvFromResult(
                        bytesReceived = -1,
                        peerAddr = recvAddr.ptr.reinterpret(),
                        peerAddrLen = 0u,
                    )
            }
        return RecvFromResult(
            bytesReceived = bytesReceived,
            peerAddr = recvAddr.ptr.reinterpret(),
            peerAddrLen = addrLen,
        )
    }

    /**
     * Send [len] bytes from [buffer] to [peerAddr].
     *
     * Thread-safe — allocates a per-call msghdr so concurrent sends from
     * different [QuicheDriver]s don't interfere.
     */
    suspend fun sendTo(
        buffer: PlatformBuffer,
        len: Int,
        peerAddr: CPointer<sockaddr>,
        peerAddrLen: socklen_t,
    ) {
        val ptr = buffer.nativeMemoryAccess!!.nativeAddress.toCPointer<ByteVar>()!!
        val iov = nativeHeap.alloc<iovec>()
        val msg = nativeHeap.alloc<msghdr>()
        try {
            iov.iov_base = ptr
            iov.iov_len = len.convert()
            msg.msg_name = peerAddr
            msg.msg_namelen = peerAddrLen
            msg.msg_iov = iov.ptr
            msg.msg_iovlen = 1.convert()
            msg.msg_control = null
            msg.msg_controllen = 0u.convert()

            IoUringManager.submitAndWait(1.seconds) { sqe, _ ->
                io_uring_prep_sendmsg(sqe, fd, msg.ptr, 0u)
            }
        } finally {
            nativeHeap.free(msg)
            nativeHeap.free(iov)
        }
    }

    /**
     * Close the socket FD only. The caller must subsequently:
     *   1. Join the receive-loop coroutine (so any suspended recvFrom returns from
     *      submitAndWait — the kernel will deliver -ECANCELED / -EBADF for in-flight
     *      io_uring recvmsg SQEs once the fd is closed).
     *   2. Call [freeBuffers].
     * Freeing the pre-allocated recv structures inside this same call is a use-after-
     * free trap (kernel may still be writing into them after fd close but before the
     * CQE is delivered to userspace) — glibc catches it as "malloc(): unsorted double
     * linked list corrupted" and the test process aborts.
     */
    fun closeFd() {
        platform.posix.close(fd)
    }

    /**
     * Free the pre-allocated recv structures. MUST only be called after the receive
     * loop has fully exited — otherwise the kernel and/or user code may still be
     * referencing this memory.
     */
    fun freeBuffers() {
        nativeHeap.free(recvMsg)
        nativeHeap.free(recvIov)
        nativeHeap.free(recvAddr)
    }

    /**
     * Convenience for tests / single-threaded callers with no in-flight receive loop:
     * close the fd and free buffers in one shot. Production server uses
     * [closeFd] + receive-loop join + [freeBuffers] explicitly to avoid use-after-free.
     */
    fun close() {
        closeFd()
        freeBuffers()
    }
}

/**
 * [UdpChannel] adapter for a single QUIC server-side connection.
 *
 * Sends outgoing packets via the shared [IoUringUdpServerChannel] to a fixed peer address.
 * Does not support [receive] — the server's central receive loop delivers packets
 * to each connection's [QuicheDriver] directly.
 *
 * Owns [peerAddr] (a nativeHeap-allocated copy) and frees it on [close].
 */
internal class IoUringServerConnectionUdpChannel(
    private val serverChannel: IoUringUdpServerChannel,
    private val peerAddr: CPointer<sockaddr_storage>,
    private val peerAddrLen: socklen_t,
    private val bufferFactory: BufferFactory,
) : UdpChannel {
    // 1-entry cache for the last sendInfo.to reconstruction. After a migration the server targets
    // the same new source on consecutive datagrams, so this keeps egress alloc-free in steady
    // state. Mirrors NioUdpChannel's lastDestKey/lastDestAddr cache.
    private var lastDestKey: PathKey? = null
    private var lastDestBuf: PlatformBuffer? = null
    private var lastDestLen: Int = 0

    override suspend fun receive(buffer: PlatformBuffer): Int =
        error("Server connections receive via central loop, not per-connection UdpChannel")

    override suspend fun send(
        buffer: PlatformBuffer,
        len: Int,
        dest: PathKey?,
    ) {
        // [dest] is quiche's sendInfo.to: after a peer migrates, replies must follow it to its new
        // source. Reconstruct that sockaddr (cached) and send there; with no dest, use the fixed peer.
        if (dest != null && dest.family != 0) {
            if (dest != lastDestKey) {
                lastDestBuf?.freeNativeMemory()
                val reconstructed = dest.toSockAddrBuffer(bufferFactory)
                lastDestBuf = reconstructed?.first
                lastDestLen = reconstructed?.second ?: 0
                lastDestKey = dest
            }
            val destBuf = lastDestBuf
            if (destBuf != null) {
                val destPtr = destBuf.nativeMemoryAccess!!.nativeAddress.toCPointer<sockaddr>()!!
                serverChannel.sendTo(buffer, len, destPtr, lastDestLen.convert())
                return
            }
        }
        serverChannel.sendTo(buffer, len, peerAddr.reinterpret(), peerAddrLen)
    }

    /** Frees the per-connection peer address copy + any cached dest. Does NOT close the shared socket. */
    override fun close() {
        lastDestBuf?.freeNativeMemory()
        lastDestBuf = null
        nativeHeap.free(peerAddr.pointed)
    }
}
