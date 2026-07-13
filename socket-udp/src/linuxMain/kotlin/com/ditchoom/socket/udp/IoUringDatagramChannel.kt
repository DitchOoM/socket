@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.ditchoom.socket.udp

import com.ditchoom.buffer.PlatformBuffer
import com.ditchoom.buffer.ReadBuffer
import com.ditchoom.buffer.allocateNative
import com.ditchoom.buffer.flow.Datagram
import com.ditchoom.buffer.flow.DatagramCapabilities
import com.ditchoom.buffer.flow.DatagramChannel
import com.ditchoom.buffer.flow.DatagramReadResult
import com.ditchoom.buffer.flow.DatagramSendOptions
import com.ditchoom.buffer.flow.Ecn
import com.ditchoom.buffer.flow.ExperimentalDatagramApi
import com.ditchoom.buffer.flow.SocketAddress
import com.ditchoom.buffer.nativeMemoryAccess
import com.ditchoom.socket.udp.linux.io_uring_prep_recvmsg
import com.ditchoom.socket.udp.linux.io_uring_prep_sendmsg
import com.ditchoom.socket.udp.linux.iovec
import com.ditchoom.socket.udp.linux.msghdr
import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.IntVar
import kotlinx.cinterop.alloc
import kotlinx.cinterop.convert
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.sizeOf
import kotlinx.cinterop.toCPointer
import kotlinx.cinterop.value
import platform.posix.ETIME
import platform.posix.ETIMEDOUT
import platform.posix.IPPROTO_IP
import platform.posix.IPPROTO_IPV6
import platform.posix.IPV6_TCLASS
import platform.posix.IP_TOS
import platform.posix.close
import platform.posix.memset
import platform.posix.setsockopt
import platform.posix.sockaddr
import platform.posix.sockaddr_storage
import kotlin.concurrent.AtomicInt
import kotlin.time.Duration.Companion.seconds

/**
 * Linux [DatagramChannel] backed by io_uring `recvmsg`/`sendmsg` — the real-socket lift of the quiche
 * `IoUringUdpChannel`/`IoUringUdpServerChannel`, reshaped to the public datagram trichotomy (RFC §7):
 *
 * - **per-packet source exposed** — the quiche server recovered `from` only to route it to a
 *   `PathKey`; here [receive] decodes the `recvmsg` source into a [LinuxSocketAddress] and surfaces it
 *   as [Datagram.peer]. A **connected** channel uses its fixed [connectedPeer] instead.
 * - **`PathKey`/migration dropped** — no `NewPath`, no `lastDest` cache. The send target's sockaddr is
 *   materialized from [SocketAddress] primitives into a `memScoped` scratch (RFC §4), zero-alloc.
 * - **UAF-safe teardown without a join** — the `recvmsg`/`sendmsg` scratch structs live in a per-call
 *   `memScoped` arena. [IoUringManager.submitAndWait] drains the kernel (cancel + `NonCancellable`
 *   await) before returning even on cancellation/close, so the arena is always freed after the kernel
 *   is done — a concurrent [close] (the standard "close to unblock a parked receive" pattern) closes
 *   only the fd and never races a shared buffer.
 *
 * Not thread-safe (buffer-flow contract): confine [receive] and [send] each to one coroutine.
 */
@ExperimentalDatagramApi
internal class IoUringDatagramChannel(
    private val fd: Int,
    private val connected: Boolean,
    private val connectedPeer: LinuxSocketAddress?,
    override val localAddress: SocketAddress?,
    private val ipv6: Boolean,
    private val receiveBufferSize: Int = MAX_UDP_PAYLOAD,
) : DatagramChannel {
    private val closedFlag = AtomicInt(0)

    override val isOpen: Boolean get() = closedFlag.value == 0

    /** The classic UDP payload ceiling (65535 − 8 UDP − 20 IP). PMTU is a consumer concern. */
    override val maxWritableSize: Int = MAX_UDP_PAYLOAD

    // Phase 3a control plane: send-side ECN/DSCP via socket-wide IP_TOS / IPV6_TCLASS (mirrors the JVM
    // NIO ceiling). DF (IP_MTU_DISCOVER) and the read-side cmsg plane (ECN/TTL/PKTINFO) are Phase 3b —
    // advertised absent here so consumers degrade correctly (§7.2), never silently.
    override val capabilities: DatagramCapabilities =
        DatagramCapabilities(
            ecnSend = true,
            ecnReceive = false,
            dscpSend = true,
            dontFragment = false,
            hopLimitSend = false,
            hopLimitReceive = false,
            localAddressReceive = false,
            sourceAddressSelect = false,
            multicast = false,
        )

    // IP_TOS / IPV6_TCLASS are socket-wide (no per-datagram ancillary path in Phase 3a), so apply only
    // on change to avoid a redundant setsockopt on every send.
    private var appliedTos = Int.MIN_VALUE

    private fun applyControlPlane(options: DatagramSendOptions) {
        if (options.ecn == Ecn.Unknown && options.dscp < 0) return
        val dscpBits = if (options.dscp >= 0) options.dscp else 0
        val ecnBits = if (options.ecn != Ecn.Unknown) options.ecn.codepoint else 0
        val tos = (dscpBits shl 2) or ecnBits
        if (tos == appliedTos) return
        memScoped {
            val v = alloc<IntVar>()
            v.value = tos
            val level = if (ipv6) IPPROTO_IPV6 else IPPROTO_IP
            val optName = if (ipv6) IPV6_TCLASS else IP_TOS
            setsockopt(fd, level, optName, v.ptr, sizeOf<IntVar>().convert())
        }
        appliedTos = tos
    }

    override suspend fun receive(): DatagramReadResult {
        // One payload per received datagram, reused across the internal idle re-arm (submitAndWait
        // times out ~every second when no data arrives). Handed out on success; freed on any
        // non-delivery exit so an idle socket does not leak a 64 KiB buffer per second.
        val payload = PlatformBuffer.allocateNative(receiveBufferSize)
        val basePtr = payload.nativeMemoryAccess!!.nativeAddress.toCPointer<ByteVar>()!!
        try {
            while (true) {
                if (closedFlag.value != 0) return DatagramReadResult.Closed()
                val outcome: DatagramReadResult? =
                    memScoped {
                        val addr = alloc<sockaddr_storage>()
                        val iov = alloc<iovec>()
                        val msg = alloc<msghdr>()
                        memset(addr.ptr, 0, sizeOf<sockaddr_storage>().convert())
                        iov.iov_base = basePtr
                        iov.iov_len = payload.capacity.convert()
                        msg.msg_name = if (connected) null else addr.ptr
                        msg.msg_namelen = if (connected) 0u else sizeOf<sockaddr_storage>().convert()
                        msg.msg_iov = iov.ptr
                        msg.msg_iovlen = 1.convert()
                        msg.msg_control = null
                        msg.msg_controllen = 0u.convert()

                        val n =
                            IoUringManager.submitAndWait(1.seconds) { sqe, _ ->
                                io_uring_prep_recvmsg(sqe, fd, msg.ptr, 0u)
                            }
                        when {
                            closedFlag.value != 0 -> DatagramReadResult.Closed()
                            // UDP has no EOF: n >= 0 is a whole datagram (n == 0 is a valid empty one).
                            n >= 0 -> {
                                val peer =
                                    if (connected) {
                                        connectedPeer
                                    } else {
                                        sockaddrToLinuxSocketAddress(addr.ptr.reinterpret<sockaddr>())
                                    }
                                // Unroutable/unknown source family (spurious CQE) — skip, keep waiting.
                                if (peer == null) {
                                    null
                                } else {
                                    payload.position(0)
                                    payload.setLimit(n)
                                    DatagramReadResult.Received(
                                        Datagram(payload = payload, peer = peer),
                                    )
                                }
                            }
                            // Idle re-arm — the deadline fired with no data; loop and re-submit.
                            n == -ETIMEDOUT || n == -ETIME -> null
                            // Socket closed underneath us (EBADF / ECANCELED) or a hard error.
                            else -> DatagramReadResult.Closed(reason = n)
                        }
                    }
                if (outcome is DatagramReadResult.Received) return outcome
                if (outcome is DatagramReadResult.Closed) {
                    payload.freeNativeMemory()
                    return outcome
                }
                // outcome == null → retry with the same payload buffer.
            }
        } catch (t: Throwable) {
            payload.freeNativeMemory()
            throw t
        }
    }

    override suspend fun send(
        payload: ReadBuffer,
        to: SocketAddress?,
        options: DatagramSendOptions,
    ) {
        check(closedFlag.value == 0) { "sink is closed" }
        applyControlPlane(options)
        // Send the readable window [position, limit) straight from the buffer's native memory — no
        // copy, and reading position()/remaining() does not consume it (send-does-not-consume).
        val access = payload.nativeMemoryAccess ?: error("send requires a native-memory buffer")
        val basePtr = (access.nativeAddress + payload.position()).toCPointer<ByteVar>()!!
        val len = payload.remaining()
        memScoped {
            val iov = alloc<iovec>()
            val msg = alloc<msghdr>()
            iov.iov_base = basePtr
            iov.iov_len = len.convert()
            if (to != null) {
                val addr = alloc<sockaddr_storage>()
                val addrLen = to.writeSockaddr(addr)
                msg.msg_name = addr.ptr
                msg.msg_namelen = addrLen
            } else {
                check(connected) { "no destination: send(to = null) requires a connected channel" }
                msg.msg_name = null
                msg.msg_namelen = 0u.convert()
            }
            msg.msg_iov = iov.ptr
            msg.msg_iovlen = 1.convert()
            msg.msg_control = null
            msg.msg_controllen = 0u.convert()

            IoUringManager.submitAndWait(1.seconds) { sqe, _ ->
                io_uring_prep_sendmsg(sqe, fd, msg.ptr, 0u)
            }
        }
    }

    override fun close() {
        if (!closedFlag.compareAndSet(0, 1)) return
        // Closing the fd makes any in-flight io_uring recvmsg complete promptly (-ECANCELED / -EBADF),
        // so a parked receive returns Closed. No shared native structs to free here — each receive/send
        // owns its own memScoped arena — so this cannot race a kernel write.
        close(fd)
        IoUringManager.onSocketClosed()
    }

    companion object {
        /** 65535 − 8 (UDP header) − 20 (IPv4 header). Large enough that no real datagram is truncated. */
        private const val MAX_UDP_PAYLOAD = 65507
    }
}
