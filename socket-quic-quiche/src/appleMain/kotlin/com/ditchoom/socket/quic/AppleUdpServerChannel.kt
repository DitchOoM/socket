@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class, kotlinx.coroutines.DelicateCoroutinesApi::class)

package com.ditchoom.socket.quic

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.PlatformBuffer
import com.ditchoom.buffer.nativeMemoryAccess
import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.UIntVar
import kotlinx.cinterop.alloc
import kotlinx.cinterop.convert
import kotlinx.cinterop.free
import kotlinx.cinterop.nativeHeap
import kotlinx.cinterop.pointed
import kotlinx.cinterop.ptr
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.sizeOf
import kotlinx.cinterop.toCPointer
import kotlinx.cinterop.value
import kotlinx.coroutines.newSingleThreadContext
import kotlinx.coroutines.withContext
import platform.posix.AF_INET
import platform.posix.AF_INET6
import platform.posix.sockaddr
import platform.posix.sockaddr_in
import platform.posix.sockaddr_in6
import platform.posix.sockaddr_storage
import platform.posix.socklen_t

/**
 * Reconstruct the BSD/Darwin network-order sockaddr bytes for [this] path key into a fresh buffer —
 * the inverse of [CinteropQuicheApi]'s BSD sockaddr decode, used for server egress to a migrated
 * peer's new source (`sendInfo.to`). Returns the buffer + its sockaddr length, or null for an unknown
 * family. IPv4 → `sockaddr_in` (16B), IPv6 → `sockaddr_in6` (28B). Caller frees the buffer.
 *
 * BSD layout: byte0 = sa_len, byte1 = sa_family (AF_INET=2 / AF_INET6=30), then sin_port (BE) at
 * offset 2, sin_addr at offset 4 / sin6_addr at offset 8 — differs from Linux only in the first two
 * bytes (Linux has no sa_len and stores sa_family as a uint16 LE at offset 0).
 */
internal fun PathKey.toSockAddrBuffer(bufferFactory: BufferFactory): Pair<PlatformBuffer, Int>? {
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
                    sizeOf<sockaddr_in>().toInt() and 0xFF, // sin_len
                    AF_INET and 0xFF, // sin_family (single byte on BSD)
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
                    add(sizeOf<sockaddr_in6>().toInt() and 0xFF) // sin6_len
                    add(AF_INET6 and 0xFF) // sin6_family
                    add((port shr 8) and 0xFF)
                    add(port and 0xFF) // sin6_port (network order)
                    repeat(4) { add(0) } // sin6_flowinfo
                    for (i in 0 until 8) add(((hi shr (56 - 8 * i)) and 0xFF).toInt()) // sin6_addr high 8
                    for (i in 0 until 8) add(((lo shr (56 - 8 * i)) and 0xFF).toInt()) // sin6_addr low 8
                },
            )
        else -> null
    }
}

/**
 * Result of [AppleUdpServerChannel.recvFrom].
 *
 * [peerAddr] and [peerAddrLen] point to internal storage valid until the next [recvFrom] call.
 */
internal class RecvFromResult(
    val bytesReceived: Int,
    val peerAddr: CPointer<sockaddr>,
    val peerAddrLen: socklen_t,
)

/**
 * Apple (Kotlin/Native) unconnected UDP socket for QUIC server use, backed by blocking POSIX
 * `recvfrom`/`sendto`. Mirrors the linux `IoUringUdpServerChannel` (the io_uring recvmsg/sendmsg are
 * replaced with POSIX calls; the contract is identical). See [AppleUdpChannel] for the POSIX-vs-NW
 * datapath note.
 *
 * [recvFrom] returns both the datagram and the sender's address (needed for QUIC connection routing),
 * blocking on a dedicated single-thread dispatcher; [closeFd] unblocks any in-flight `recvfrom`. It
 * must be called from a single coroutine (the server's central receive loop) — the recv address
 * storage is shared and reused per call.
 */
internal class AppleUdpServerChannel(
    private val fd: Int,
) {
    private val recvDispatcher = newSingleThreadContext("apple-udp-srv-recv-$fd")

    // Pre-allocated recv address storage, reused by the single-threaded receive loop.
    private val recvAddr = nativeHeap.alloc<sockaddr_storage>()

    // socklen_t on Darwin is uint32 → UIntVar (kotlinx.cinterop has no `socklen_tVar` alias here).
    private val recvAddrLen = nativeHeap.alloc<UIntVar>()

    init {
        // alloc does not zero-init; a garbage ss_family handed to quiche SIGABRTs through Rust's
        // std_addr_from_c. Zero so the family check below catches a non-updated address cleanly.
        platform.posix.memset(recvAddr.ptr, 0, sizeOf<sockaddr_storage>().convert())
    }

    suspend fun recvFrom(buffer: PlatformBuffer): RecvFromResult {
        val ptr = buffer.nativeMemoryAccess!!.nativeAddress.toCPointer<ByteVar>()!!
        platform.posix.memset(recvAddr.ptr, 0, sizeOf<sockaddr_storage>().convert())
        recvAddrLen.value = sizeOf<sockaddr_storage>().convert()
        val bytesReceived =
            withContext(recvDispatcher) {
                platform.posix
                    .recvfrom(fd, ptr, buffer.capacity.convert(), 0, recvAddr.ptr.reinterpret(), recvAddrLen.ptr)
                    .toInt()
            }
        if (bytesReceived <= 0) {
            return RecvFromResult(bytesReceived, recvAddr.ptr.reinterpret(), 0u)
        }
        // Derive the address length from the family. recvfrom shrinks recvAddrLen, but quiche's FFI
        // panics on sa_family ∉ {AF_INET, AF_INET6}; skip the packet (bytesReceived=-1) if so.
        val family = recvAddr.ss_family.toInt()
        val addrLen: socklen_t =
            when (family) {
                AF_INET -> sizeOf<sockaddr_in>().convert()
                AF_INET6 -> sizeOf<sockaddr_in6>().convert()
                else -> return RecvFromResult(-1, recvAddr.ptr.reinterpret(), 0u)
            }
        return RecvFromResult(bytesReceived, recvAddr.ptr.reinterpret(), addrLen)
    }

    /** Send [len] bytes from [buffer] to [peerAddr]. Thread-safe (`sendto` is stateless). */
    suspend fun sendTo(
        buffer: PlatformBuffer,
        len: Int,
        peerAddr: CPointer<sockaddr>,
        peerAddrLen: socklen_t,
    ) {
        val ptr = buffer.nativeMemoryAccess!!.nativeAddress.toCPointer<ByteVar>()!!
        platform.posix.sendto(fd, ptr, len.convert(), 0, peerAddr, peerAddrLen)
    }

    /**
     * Close the socket FD only — this unblocks any in-flight [recvFrom] (`recvfrom` returns -1). The
     * caller must then join the receive loop before calling [freeBuffers]; freeing the recv storage
     * while a `recvfrom` may still be writing into it is a use-after-free.
     */
    fun closeFd() {
        platform.posix.close(fd)
    }

    /** Free the pre-allocated recv structures + recv dispatcher. Only after the receive loop exited. */
    fun freeBuffers() {
        nativeHeap.free(recvAddr)
        nativeHeap.free(recvAddrLen)
        recvDispatcher.close()
    }

    /** Convenience for single-threaded callers with no in-flight receive loop. */
    fun close() {
        closeFd()
        freeBuffers()
    }
}

/**
 * [UdpChannel] adapter for a single QUIC server-side connection. Sends via the shared
 * [AppleUdpServerChannel] to a fixed peer address; [receive] is unsupported (the server's central
 * loop delivers packets to each driver). Owns [peerAddr] (a nativeHeap copy) and frees it on [close].
 * Mirrors the linux `ServerConnectionUdpChannel`.
 */
internal class ServerConnectionUdpChannel(
    private val serverChannel: AppleUdpServerChannel,
    private val peerAddr: CPointer<sockaddr_storage>,
    private val peerAddrLen: socklen_t,
    private val bufferFactory: BufferFactory,
) : UdpChannel {
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
