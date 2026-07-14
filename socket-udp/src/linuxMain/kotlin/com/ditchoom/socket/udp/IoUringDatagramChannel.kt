@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.ditchoom.socket.udp

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.ReadBuffer
import com.ditchoom.buffer.deterministic
import com.ditchoom.buffer.flow.AddressFamily
import com.ditchoom.buffer.flow.Datagram
import com.ditchoom.buffer.flow.DatagramCapabilities
import com.ditchoom.buffer.flow.DatagramChannel
import com.ditchoom.buffer.flow.DatagramReadResult
import com.ditchoom.buffer.flow.DatagramSendOptions
import com.ditchoom.buffer.flow.Ecn
import com.ditchoom.buffer.flow.ExperimentalDatagramApi
import com.ditchoom.buffer.flow.SocketAddress
import com.ditchoom.buffer.nativeMemoryAccess
import com.ditchoom.socket.udp.linux.IPV6_DONTFRAG
import com.ditchoom.socket.udp.linux.IPV6_HOPLIMIT
import com.ditchoom.socket.udp.linux.IPV6_PKTINFO
import com.ditchoom.socket.udp.linux.IPV6_RECVHOPLIMIT
import com.ditchoom.socket.udp.linux.IPV6_RECVPKTINFO
import com.ditchoom.socket.udp.linux.IPV6_RECVTCLASS
import com.ditchoom.socket.udp.linux.IPV6_UNICAST_HOPS
import com.ditchoom.socket.udp.linux.IP_MTU_DISCOVER
import com.ditchoom.socket.udp.linux.IP_PKTINFO
import com.ditchoom.socket.udp.linux.IP_PMTUDISC_DO
import com.ditchoom.socket.udp.linux.IP_PMTUDISC_DONT
import com.ditchoom.socket.udp.linux.IP_RECVTOS
import com.ditchoom.socket.udp.linux.IP_RECVTTL
import com.ditchoom.socket.udp.linux.cmsg_data
import com.ditchoom.socket.udp.linux.cmsg_firsthdr
import com.ditchoom.socket.udp.linux.cmsg_nxthdr
import com.ditchoom.socket.udp.linux.cmsghdr
import com.ditchoom.socket.udp.linux.io_uring_prep_recvmsg
import com.ditchoom.socket.udp.linux.io_uring_prep_sendmsg
import com.ditchoom.socket.udp.linux.iovec
import com.ditchoom.socket.udp.linux.msghdr
import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.IntVar
import kotlinx.cinterop.alloc
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.convert
import kotlinx.cinterop.get
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.plus
import kotlinx.cinterop.pointed
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
import platform.posix.IP_TTL
import platform.posix.close
import platform.posix.memset
import platform.posix.setsockopt
import platform.posix.sockaddr
import platform.posix.sockaddr_storage
import kotlin.concurrent.AtomicInt
import kotlin.time.Duration.Companion.seconds

/**
 * Linux [DatagramChannel] backed by io_uring `recvmsg`/`sendmsg` — the real-socket lift of the quiche
 * `IoUringUdpChannel`/`IoUringUdpServerChannel`, reshaped to the public datagram trichotomy (RFC §7)
 * with the **full Linux control plane** (§7.1's richest platform):
 *
 * - **per-packet source exposed** — [receive] decodes the `recvmsg` source into a [LinuxSocketAddress]
 *   as [Datagram.peer]; a **connected** channel uses its fixed [connectedPeer].
 * - **read-side ancillary data** — `IP_RECVTOS`/`IP_RECVTTL`/`IP_PKTINFO` (v6: `IPV6_RECVTCLASS`/
 *   `RECVHOPLIMIT`/`RECVPKTINFO`) are enabled on the socket, and each `recvmsg` walks the returned
 *   cmsgs to populate [Datagram.ecn] / [Datagram.hopLimit] / [Datagram.localAddress].
 * - **send-side control plane** — ECN/DSCP via socket-wide `IP_TOS`/`IPV6_TCLASS`, Don't-Fragment via
 *   `IP_MTU_DISCOVER`/`IPV6_DONTFRAG`, TTL via `IP_TTL`/`IPV6_UNICAST_HOPS` (all applied on change).
 * - **`PathKey`/migration dropped** — the send target's sockaddr is materialized from [SocketAddress]
 *   primitives into a `memScoped` scratch (RFC §4), zero-alloc.
 * - **UAF-safe teardown without a join** — recv/send scratch lives in a per-call `memScoped` arena;
 *   [IoUringManager.submitAndWait] drains the kernel before returning even on cancel/close, so a
 *   concurrent [close] closes only the fd and never races a shared buffer.
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
    private val bufferFactory: BufferFactory = BufferFactory.deterministic(),
) : DatagramChannel {
    private val closedFlag = AtomicInt(0)

    /** The bound local port, stamped onto an `IP_PKTINFO`-derived [Datagram.localAddress]. */
    private val localPort: Int = localAddress?.port ?: 0

    init {
        enableReceiveControlPlane()
    }

    override val isOpen: Boolean get() = closedFlag.value == 0

    /** The classic UDP payload ceiling (65535 − 8 UDP − 20 IP). PMTU is a consumer concern. */
    override val maxWritableSize: Int = MAX_UDP_PAYLOAD

    // Linux is §7.1's richest platform: the full send + receive control plane is implemented, so only
    // per-send source-IP selection (IP_PKTINFO on send / sourceAddressSelect) and multicast are absent.
    override val capabilities: DatagramCapabilities =
        DatagramCapabilities(
            ecnSend = true,
            ecnReceive = true,
            dscpSend = true,
            dontFragment = true,
            hopLimitSend = true,
            hopLimitReceive = true,
            localAddressReceive = true,
            sourceAddressSelect = false, // send-side IP_PKTINFO (fromLocal) is a later additive minor
            multicast = false, // design-for, defer (§10.3)
        )

    /** Request per-packet ancillary data (ECN/TTL/dst-IP) so [receive] can populate the read plane. */
    private fun enableReceiveControlPlane() {
        memScoped {
            val on = alloc<IntVar>()
            on.value = 1
            val len = sizeOf<IntVar>().convert<platform.posix.socklen_t>()
            if (ipv6) {
                setsockopt(fd, IPPROTO_IPV6, IPV6_RECVTCLASS, on.ptr, len)
                setsockopt(fd, IPPROTO_IPV6, IPV6_RECVHOPLIMIT, on.ptr, len)
                setsockopt(fd, IPPROTO_IPV6, IPV6_RECVPKTINFO, on.ptr, len)
            } else {
                setsockopt(fd, IPPROTO_IP, IP_RECVTOS, on.ptr, len)
                setsockopt(fd, IPPROTO_IP, IP_RECVTTL, on.ptr, len)
                setsockopt(fd, IPPROTO_IP, IP_PKTINFO, on.ptr, len)
            }
        }
    }

    // Socket-wide send options (IP_TOS / DF / TTL) applied only on change to avoid a redundant
    // setsockopt on every send.
    private var appliedTos = Int.MIN_VALUE
    private var appliedDf: Boolean? = null
    private var appliedTtl = Int.MIN_VALUE

    private fun setIntOption(
        level: Int,
        optName: Int,
        value: Int,
    ) {
        memScoped {
            val v = alloc<IntVar>()
            v.value = value
            setsockopt(fd, level, optName, v.ptr, sizeOf<IntVar>().convert())
        }
    }

    private fun applyControlPlane(options: DatagramSendOptions) {
        if (options.ecn != Ecn.Unknown || options.dscp >= 0) {
            val dscpBits = if (options.dscp >= 0) options.dscp else 0
            val ecnBits = if (options.ecn != Ecn.Unknown) options.ecn.codepoint else 0
            val tos = (dscpBits shl 2) or ecnBits
            if (tos != appliedTos) {
                setIntOption(if (ipv6) IPPROTO_IPV6 else IPPROTO_IP, if (ipv6) IPV6_TCLASS else IP_TOS, tos)
                appliedTos = tos
            }
        }
        if (options.dontFragment != appliedDf) {
            if (ipv6) {
                setIntOption(IPPROTO_IPV6, IPV6_DONTFRAG, if (options.dontFragment) 1 else 0)
            } else {
                setIntOption(IPPROTO_IP, IP_MTU_DISCOVER, if (options.dontFragment) IP_PMTUDISC_DO else IP_PMTUDISC_DONT)
            }
            appliedDf = options.dontFragment
        }
        if (options.hopLimit >= 0 && options.hopLimit != appliedTtl) {
            setIntOption(if (ipv6) IPPROTO_IPV6 else IPPROTO_IP, if (ipv6) IPV6_UNICAST_HOPS else IP_TTL, options.hopLimit)
            appliedTtl = options.hopLimit
        }
    }

    /** Parsed read-side control plane from a `recvmsg`'s cmsgs. */
    private class ControlPlane(
        val ecn: Ecn,
        val hopLimit: Int,
        val localAddress: SocketAddress?,
    )

    private fun parseControlPlane(msg: CPointer<msghdr>): ControlPlane {
        var ecn = Ecn.Unknown
        var hopLimit = -1
        var localAddress: SocketAddress? = null
        var cmsg: CPointer<cmsghdr>? = cmsg_firsthdr(msg)
        while (cmsg != null) {
            val header = cmsg.pointed
            val level = header.cmsg_level
            val type = header.cmsg_type
            val data = cmsg_data(cmsg)
            if (data != null) {
                when {
                    level == IPPROTO_IP && type == IP_TOS -> ecn = Ecn.fromCodepoint(data[0].toInt())
                    level == IPPROTO_IP && type == IP_TTL -> hopLimit = data.reinterpret<IntVar>().pointed.value
                    // struct in_pktinfo { int ipi_ifindex; struct in_addr ipi_spec_dst; struct in_addr ipi_addr; }
                    // ipi_addr (the datagram's destination IP) is at offset 8.
                    level == IPPROTO_IP && type == IP_PKTINFO ->
                        localAddress = ipv4LocalAddress(data.reinterpret(), 8, localPort)
                    level == IPPROTO_IPV6 && type == IPV6_TCLASS ->
                        ecn = Ecn.fromCodepoint(data.reinterpret<IntVar>().pointed.value)
                    level == IPPROTO_IPV6 && type == IPV6_HOPLIMIT -> hopLimit = data.reinterpret<IntVar>().pointed.value
                    // struct in6_pktinfo { struct in6_addr ipi6_addr; unsigned ipi6_ifindex; } — addr at offset 0.
                    level == IPPROTO_IPV6 && type == IPV6_PKTINFO ->
                        localAddress = ipv6LocalAddress(data.reinterpret(), 0, localPort)
                }
            }
            cmsg = cmsg_nxthdr(msg, cmsg)
        }
        return ControlPlane(ecn, hopLimit, localAddress)
    }

    override suspend fun receive(): DatagramReadResult {
        // One payload per received datagram, reused across the internal idle re-arm (submitAndWait
        // times out ~every second when no data arrives). Handed out on success; freed on any
        // non-delivery exit so an idle socket does not leak a 64 KiB buffer per second.
        val payload = bufferFactory.allocate(receiveBufferSize)
        val basePtr = payload.nativeMemoryAccess!!.nativeAddress.toCPointer<ByteVar>()!!
        try {
            while (true) {
                if (closedFlag.value != 0) return DatagramReadResult.Closed()
                val outcome: DatagramReadResult? =
                    memScoped {
                        val addr = alloc<sockaddr_storage>()
                        val iov = alloc<iovec>()
                        val msg = alloc<msghdr>()
                        val control = allocArray<ByteVar>(CONTROL_BUFFER_SIZE)
                        memset(addr.ptr, 0, sizeOf<sockaddr_storage>().convert())
                        iov.iov_base = basePtr
                        iov.iov_len = payload.capacity.convert()
                        msg.msg_name = if (connected) null else addr.ptr
                        msg.msg_namelen = if (connected) 0u else sizeOf<sockaddr_storage>().convert()
                        msg.msg_iov = iov.ptr
                        msg.msg_iovlen = 1.convert()
                        msg.msg_control = control
                        msg.msg_controllen = CONTROL_BUFFER_SIZE.convert()

                        val n =
                            IoUringManager.submitAndWait(1.seconds) { sqe, _ ->
                                io_uring_prep_recvmsg(sqe, fd, msg.ptr, 0u)
                            }
                        when {
                            closedFlag.value != 0 -> DatagramReadResult.Closed()
                            // UDP has no EOF: n >= 0 is a whole datagram (n == 0 is a valid empty one).
                            n >= 0 -> {
                                val peer =
                                    if (connected) connectedPeer else sockaddrToLinuxSocketAddress(addr.ptr.reinterpret<sockaddr>())
                                // Unroutable/unknown source family (spurious CQE) — skip, keep waiting.
                                if (peer == null) {
                                    null
                                } else {
                                    val cp = parseControlPlane(msg.ptr)
                                    payload.position(0)
                                    payload.setLimit(n)
                                    DatagramReadResult.Received(
                                        Datagram(
                                            payload = payload,
                                            peer = peer,
                                            ecn = cp.ecn,
                                            localAddress = cp.localAddress,
                                            hopLimit = cp.hopLimit,
                                        ),
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

    /** Build an IPv4 [SocketAddress] from 4 network-order address bytes at [ptr]+[offset] with [port]. */
    private fun ipv4LocalAddress(
        ptr: CPointer<ByteVar>,
        offset: Int,
        port: Int,
    ): SocketAddress {
        var lo = 0L
        for (i in 0 until 4) lo = (lo shl 8) or ((ptr + offset)!![i].toLong() and 0xFF)
        val b0 = (lo shr 24) and 0xFF
        val b1 = (lo shr 16) and 0xFF
        val b2 = (lo shr 8) and 0xFF
        val b3 = lo and 0xFF
        return LinuxSocketAddress("$b0.$b1.$b2.$b3", port, AddressFamily.IPv4, 0L, lo)
    }

    /** Build an IPv6 [SocketAddress] from 16 network-order address bytes at [ptr]+[offset] with [port]. */
    private fun ipv6LocalAddress(
        ptr: CPointer<ByteVar>,
        offset: Int,
        port: Int,
    ): SocketAddress {
        var hi = 0L
        var lo = 0L
        for (i in 0 until 8) hi = (hi shl 8) or ((ptr + offset)!![i].toLong() and 0xFF)
        for (i in 0 until 8) lo = (lo shl 8) or ((ptr + offset)!![8 + i].toLong() and 0xFF)
        val groups = IntArray(8)
        for (i in 0 until 4) groups[i] = ((hi shr (48 - 16 * i)) and 0xFFFF).toInt()
        for (i in 0 until 4) groups[4 + i] = ((lo shr (48 - 16 * i)) and 0xFFFF).toInt()
        return LinuxSocketAddress(groups.joinToString(":") { it.toString(16) }, port, AddressFamily.IPv6, hi, lo)
    }

    companion object {
        /** 65535 − 8 (UDP header) − 20 (IPv4 header). Large enough that no real datagram is truncated. */
        private const val MAX_UDP_PAYLOAD = 65507

        /** Ancillary-data scratch — ample for IP_TOS(1) + IP_TTL(4) + IP_PKTINFO(12) each in a cmsghdr. */
        private const val CONTROL_BUFFER_SIZE = 256
    }
}
