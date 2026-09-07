@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.ditchoom.socket.udp

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.ReadBuffer
import com.ditchoom.buffer.deterministic
import com.ditchoom.buffer.flow.AddressFamily
import com.ditchoom.buffer.flow.AddressedDatagramChannel
import com.ditchoom.buffer.flow.ConnectedDatagramChannel
import com.ditchoom.buffer.flow.Datagram
import com.ditchoom.buffer.flow.DatagramCapabilities
import com.ditchoom.buffer.flow.DatagramChannel
import com.ditchoom.buffer.flow.DatagramCloseReason
import com.ditchoom.buffer.flow.DatagramReadResult
import com.ditchoom.buffer.flow.DatagramSendOptions
import com.ditchoom.buffer.flow.Ecn
import com.ditchoom.buffer.flow.EcnPreference
import com.ditchoom.buffer.flow.ExperimentalDatagramApi
import com.ditchoom.buffer.flow.HopLimit
import com.ditchoom.buffer.flow.LocalAddress
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
import com.ditchoom.socket.udp.linux.cmsg_len
import com.ditchoom.socket.udp.linux.cmsg_nxthdr
import com.ditchoom.socket.udp.linux.cmsg_space
import com.ditchoom.socket.udp.linux.cmsghdr
import com.ditchoom.socket.udp.linux.io_uring_prep_cancel64
import com.ditchoom.socket.udp.linux.io_uring_prep_nop
import com.ditchoom.socket.udp.linux.io_uring_prep_recvmsg
import com.ditchoom.socket.udp.linux.io_uring_prep_sendmsg
import com.ditchoom.socket.udp.linux.iovec
import com.ditchoom.socket.udp.linux.msghdr
import com.ditchoom.socket.udp.linux.socket_bind
import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.IntVar
import kotlinx.cinterop.MemScope
import kotlinx.cinterop.alloc
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.convert
import kotlinx.cinterop.get
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.plus
import kotlinx.cinterop.pointed
import kotlinx.cinterop.ptr
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.set
import kotlinx.cinterop.sizeOf
import kotlinx.cinterop.toCPointer
import kotlinx.cinterop.value
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import platform.posix.AF_INET
import platform.posix.AF_INET6
import platform.posix.EADDRNOTAVAIL
import platform.posix.ETIME
import platform.posix.ETIMEDOUT
import platform.posix.IPPROTO_IP
import platform.posix.IPPROTO_IPV6
import platform.posix.IPPROTO_UDP
import platform.posix.IPV6_TCLASS
import platform.posix.IP_TOS
import platform.posix.IP_TTL
import platform.posix.SOCK_DGRAM
import platform.posix.close
import platform.posix.errno
import platform.posix.memset
import platform.posix.setsockopt
import platform.posix.sockaddr
import platform.posix.sockaddr_storage
import platform.posix.socket
import kotlin.concurrent.AtomicLong
import kotlin.time.Duration.Companion.seconds

/** 65535 − 8 (UDP header) − 20 (IPv4 header). Large enough that no real datagram is truncated. */
private const val MAX_UDP_PAYLOAD = 65507

/** Ancillary-data scratch — ample for IP_TOS(1) + IP_TTL(4) + IP_PKTINFO(12) each in a cmsghdr. */
private const val CONTROL_BUFFER_SIZE = 256

/**
 * `struct in_pktinfo { int ipi_ifindex; struct in_addr ipi_spec_dst; struct in_addr ipi_addr; }` — 12
 * bytes. The kernel reads a *different address field* in each direction, which is why both are named
 * here: on receive it fills `ipi_addr` (offset 8) with the datagram's destination IP, and on send it
 * reads `ipi_spec_dst` (offset 4) as the source to leave from.
 *
 * `ipi_ifindex` (offset 0) is deliberately left 0 on send: IPv4 has no scoped addresses, so an IPv4
 * source names a device unambiguously and there is nothing for the caller to disambiguate. Pinning the
 * address and leaving the device to the route lookup is what a multi-homed reply wants. IPv6 is not
 * like this — see [IN6_PKTINFO_IFINDEX_OFFSET].
 */
private const val IN_PKTINFO_SIZE = 12
private const val IN_PKTINFO_SPEC_DST_OFFSET = 4
private const val IN_PKTINFO_ADDR_OFFSET = 8

/**
 * `struct in6_pktinfo { struct in6_addr ipi6_addr; unsigned int ipi6_ifindex; }` — 20 bytes. Unlike
 * the IPv4 struct this one has a single address field, used as the destination on receive and as the
 * source on send, so [IN6_PKTINFO_ADDR_OFFSET] serves both.
 *
 * `ipi6_ifindex` (offset 16) carries the address's scope id in **both** directions, and it is not
 * optional the way its IPv4 counterpart is: `ip6_datagram_send_ctl` returns `EINVAL` unconditionally
 * for a link-local source when no interface is named, because `fe80::/10` is only an address on a
 * link. Receiving it and sending it back are the same field, which is what lets a reply leave from a
 * link-local address the receive loop recorded.
 */
private const val IN6_PKTINFO_SIZE = 20
private const val IN6_PKTINFO_ADDR_OFFSET = 0
private const val IN6_PKTINFO_IFINDEX_OFFSET = 16

/**
 * The low half of `::ffff:0.0.0.0` packed the way [LinuxSocketAddress] packs an address — the
 * v4-mapped spelling of "no address", which a dual-stack socket can be handed instead of `::`.
 */
private const val V4_MAPPED_UNSPECIFIED_LO = 0x0000FFFF00000000L

/**
 * "This channel has no submission in flight." `IoUringManager.nextUserData()` counts up from 1 and 0 is
 * reserved for the eventfd wake, so 0 can never be a live `user_data` and needs no separate flag.
 */
private const val NO_OP_IN_FLIGHT = 0L

/**
 * Shared core of the Linux io_uring datagram channels — the `recvmsg`/`sendmsg` machinery behind
 * [ConnectedIoUringDatagramChannel] and [AddressedIoUringDatagramChannel]. The real-socket lift of the
 * quiche `IoUringUdpChannel`/`IoUringUdpServerChannel`, reshaped to the public datagram trichotomy
 * (RFC §7) with the **full Linux control plane** (§7.1's richest platform):
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
 * - **UAF-safe teardown without a join** — recv/send scratch lives in a per-call `memScoped` arena and
 *   [IoUringManager.submitAndWait] drains the kernel before returning even on cancel/close, so no
 *   teardown races a shared buffer. The *descriptor* is owned by [LastOutHandoff] rather than by
 *   [close]: every read, write and control op is admitted, and whoever is last out closes the fd, so a
 *   submission prepared on the poller thread can never name a number the process has recycled (#526).
 *
 * The addressing mode is fixed at construction ([connectedPeer] non-null = connected): the wrappers
 * add only the mode-specific send arity, so the base type can no longer express "send without knowing
 * the mode" — the old nullable-`to` conflation is gone.
 *
 * Not thread-safe (buffer-flow contract): confine [receive] and the send path each to one coroutine.
 */
@ExperimentalDatagramApi
internal abstract class IoUringDatagramChannelCore(
    private val fd: Int,
    /** The fixed peer of a connected channel; `null` = addressed mode (per-packet sources). */
    protected val connectedPeer: LinuxSocketAddress?,
    /** The bound local port, stamped onto an `IP_PKTINFO`-derived [Datagram.localAddress]. */
    private val localPort: Int,
    private val ipv6: Boolean,
    private val receiveBufferSize: Int = MAX_UDP_PAYLOAD,
    private val bufferFactory: BufferFactory = BufferFactory.deterministic(),
    /**
     * Test seam: runs inside [receive]'s admission, *after* this receiver is counted in and before its
     * submission is handed to the poller. That is exactly the window #526 lives in — the descriptor
     * number used to be read there by code running on another thread, after `close()` had freed it —
     * and it cannot be reached from outside, so a test that cannot park here cannot drive the defect at
     * all. Production passes nothing and pays an empty suspend call per receive.
     */
    private val beforeSubmit: suspend () -> Unit = {},
) : DatagramChannel {
    /**
     * Who releases the descriptor — the last party out — in one CAS; see [LastOutHandoff].
     *
     * A `closedFlag` cannot do this job here. `receive()` read the flag and then called
     * `IoUringManager.submitAndWait { sqe, _ -> io_uring_prep_recvmsg(sqe, fd, …) }`, and that lambda
     * does not run at the call site: it rides a channel to the process-global poller thread, which
     * invokes it in its drain loop. So the descriptor number was read after a channel hand-off *and* a
     * poller iteration, while `close()` had already run `close(fd)` — and any `socket()`/`open()`/
     * `accept()` in the process that recycled the number in between made the submission read, or
     * `sendmsg` write, **another socket** (#526, the same defect as Apple's #507 with a wider window).
     */
    private val handoff = LastOutHandoff()

    /**
     * The `user_data` of this channel's in-flight receive submission, or [NO_OP_IN_FLIGHT].
     *
     * Written by the receive `prepareOp` and read by [close]'s cancel `prepareOp` — **both on the
     * poller thread**, which is what makes the pair race-free without a lock. See [close].
     */
    private val inFlightReceive = AtomicLong(NO_OP_IN_FLIGHT)

    /** The `user_data` of this channel's in-flight send submission, or [NO_OP_IN_FLIGHT]. */
    private val inFlightSend = AtomicLong(NO_OP_IN_FLIGHT)

    /** Connected mode: `recvmsg` skips the source sockaddr and `sendmsg` omits `msg_name`. */
    private val connected get() = connectedPeer != null

    init {
        enableReceiveControlPlane()
    }

    override val isOpen: Boolean get() = !handoff.closed

    /** The classic UDP payload ceiling (65535 − 8 UDP − 20 IP). PMTU is a consumer concern. */
    override val maxWritableSize: Int = MAX_UDP_PAYLOAD

    // Linux is §7.1's richest platform: the full send + receive control plane is implemented — as of
    // #556's step 2, send-side source selection included — so multicast is the only absent capability
    // left here, and [MulticastIoUringDatagramChannel] flips that one on for a channel that has joined.
    override val capabilities: DatagramCapabilities =
        DatagramCapabilities(
            ecnSend = true,
            ecnReceive = true,
            dscpSend = true,
            dontFragment = true,
            hopLimitSend = true,
            hopLimitReceive = true,
            localAddressReceive = true,
            // Honoured, not merely tolerated: [sendAdmitted] attaches an IP_PKTINFO / IPV6_PKTINFO
            // control message built from DatagramSendOptions.fromLocal, and *refuses* a source it
            // cannot pin instead of sending from the kernel's choice as though it had. Advertising
            // this while quietly ignoring the option would be worse than advertising it absent — a
            // consumer would stop compensating for something that never started working (#558).
            sourceAddressSelect = true,
            multicast = false, // design-for, defer (§10.3)
            // sendmsg's iovec is a raw base pointer: sendDatagram takes payload.nativeMemoryAccess
            // and errors if it is absent. BufferFactory.Default on K/N Linux is a GC buffer with no
            // native address, so a consumer allocating its own outbound datagrams must be told.
            requiresNativeMemoryBuffers = true,
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
        if (options.ecn != EcnPreference.OsDefault || options.dscp >= 0) {
            val dscpBits = if (options.dscp >= 0) options.dscp else 0
            val ecnBits = if (options.ecn != EcnPreference.OsDefault) options.ecn.codepoint else 0
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

    /** Parsed read-side control plane from a `recvmsg`'s cmsgs — typed absent states, no sentinels. */
    private class ControlPlane(
        val ecn: Ecn,
        val hopLimit: HopLimit,
        val localAddress: LocalAddress,
    )

    private fun parseControlPlane(msg: CPointer<msghdr>): ControlPlane {
        var ecn = Ecn.Unknown
        var hopLimit = HopLimit.Unknown
        var localAddress = LocalAddress.Unknown
        var cmsg: CPointer<cmsghdr>? = cmsg_firsthdr(msg)
        while (cmsg != null) {
            val header = cmsg.pointed
            val level = header.cmsg_level
            val type = header.cmsg_type
            val data = cmsg_data(cmsg)
            if (data != null) {
                when {
                    level == IPPROTO_IP && type == IP_TOS -> ecn = Ecn.fromCodepoint(data[0].toInt())
                    // Kernel-reported TTL / hop limit is always a valid octet — HopLimit.of accepts it.
                    level == IPPROTO_IP && type == IP_TTL ->
                        hopLimit = HopLimit.of(data.reinterpret<IntVar>().pointed.value)
                    // ipi_addr — the datagram's destination IP. Layout at [IN_PKTINFO_ADDR_OFFSET].
                    level == IPPROTO_IP && type == IP_PKTINFO ->
                        localAddress =
                            LocalAddress.of(ipv4LocalAddress(data.reinterpret(), IN_PKTINFO_ADDR_OFFSET, localPort))
                    level == IPPROTO_IPV6 && type == IPV6_TCLASS ->
                        ecn = Ecn.fromCodepoint(data.reinterpret<IntVar>().pointed.value)
                    level == IPPROTO_IPV6 && type == IPV6_HOPLIMIT ->
                        hopLimit = HopLimit.of(data.reinterpret<IntVar>().pointed.value)
                    // ipi6_addr + ipi6_ifindex. The interface index used to be dropped here, which
                    // made every received link-local local address unusable as a reply source: the
                    // send path would have had nothing to put back in ipi6_ifindex, and the kernel
                    // refuses an unscoped link-local source outright.
                    level == IPPROTO_IPV6 && type == IPV6_PKTINFO ->
                        localAddress =
                            LocalAddress.of(
                                ipv6LocalAddress(
                                    data.reinterpret(),
                                    IN6_PKTINFO_ADDR_OFFSET,
                                    localPort,
                                    ifIndexAt(data.reinterpret(), IN6_PKTINFO_IFINDEX_OFFSET),
                                ),
                            )
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
                // Admission, not a flag read: the CAS that observes "open" also counts this receiver in,
                // so nothing can release the descriptor between here and the poller thread preparing the
                // submission that names it. Taken per iteration — an idle re-arm that spans a close
                // leaves and is refused on the next lap rather than pinning the descriptor open.
                when (handoff.enter()) {
                    LastOutHandoff.Admission.Refused -> {
                        payload.freeNativeMemory()
                        return DatagramReadResult.Closed()
                    }
                    LastOutHandoff.Admission.Admitted -> Unit
                }
                val outcome: DatagramReadResult? =
                    try {
                        beforeSubmit()
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
                                IoUringManager.submitAndWait(1.seconds) { sqe, userData ->
                                    // Runs on the poller thread — see [close] for why that is what makes
                                    // this safe. Naming the descriptor is conditional on the close not
                                    // having happened yet; a nop wakes this receiver at once instead.
                                    if (handoff.closed) {
                                        io_uring_prep_nop(sqe)
                                    } else {
                                        inFlightReceive.value = userData
                                        io_uring_prep_recvmsg(sqe, fd, msg.ptr, 0u)
                                    }
                                }
                            inFlightReceive.value = NO_OP_IN_FLIGHT
                            when {
                                // Before the `n >= 0` arm on purpose: a nop completes with 0, which would
                                // otherwise read as a valid empty datagram.
                                handoff.closed -> DatagramReadResult.Closed()
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
                                else -> DatagramReadResult.Closed(DatagramCloseReason.OsError(n))
                            }
                        }
                    } finally {
                        leave()
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

    /**
     * Shared `sendmsg` path. [target] carries the addressed wrapper's REQUIRED per-send destination;
     * the connected wrapper passes `null` and the kernel routes to the `connect()`ed peer — so the
     * null-target branch is only reachable in connected mode by construction (no runtime guard).
     */
    protected suspend fun sendDatagram(
        payload: ReadBuffer,
        target: SocketAddress?,
        options: DatagramSendOptions,
    ) {
        // Admission, not a flag read — the same reason receive() takes one: applyControlPlane's
        // setsockopt and the sendmsg submission both name the descriptor, and the submission does it
        // from the poller thread. A refused sender never touches it and reports the closed sink it
        // always did.
        when (handoff.enter()) {
            LastOutHandoff.Admission.Refused -> error("sink is closed")
            LastOutHandoff.Admission.Admitted -> Unit
        }
        try {
            sendAdmitted(payload, target, options)
        } finally {
            leave()
        }
    }

    private suspend fun sendAdmitted(
        payload: ReadBuffer,
        target: SocketAddress?,
        options: DatagramSendOptions,
    ) {
        // First, so that a source refused *here* leaves the socket exactly as it found it —
        // applyControlPlane's setsockopts are socket-wide and outlive this call, and a send that never
        // happens should not have changed the next one's TOS. This covers only the refusals this
        // library decides ([SourcePin]); one the kernel makes necessarily comes after the options were
        // applied, since applying them is part of making the call that gets refused. That is not a
        // hole: those options are the caller's own request and are re-applied on change, so the socket
        // is left in the state the caller asked for either way.
        val pin = sourcePinFor(options.fromLocal)
        applyControlPlane(options)
        // Send the readable window [position, limit) straight from the buffer's native memory — no
        // copy, and reading position()/remaining() does not consume it (send-does-not-consume).
        val access = payload.nativeMemoryAccess ?: error("send requires a native-memory buffer")
        val basePtr = (access.nativeAddress + payload.position()).toCPointer<ByteVar>()!!
        val len = payload.remaining()
        // Parity guard: the same condition reports the same typed reason on every backend.
        if (len > maxWritableSize) throw DatagramSendException(DatagramSendError.TooLarge(len, maxWritableSize))
        memScoped {
            val iov = alloc<iovec>()
            val msg = alloc<msghdr>()
            iov.iov_base = basePtr
            iov.iov_len = len.convert()
            if (target != null) {
                val addr = alloc<sockaddr_storage>()
                val addrLen = target.writeSockaddr(addr)
                msg.msg_name = addr.ptr
                msg.msg_namelen = addrLen
            } else {
                // Connected mode by construction — omitting msg_name routes to the connect()ed peer.
                msg.msg_name = null
                msg.msg_namelen = 0u.convert()
            }
            msg.msg_iov = iov.ptr
            msg.msg_iovlen = 1.convert()
            when (pin) {
                // No source named: no ancillary data at all, and the kernel routes and picks — the
                // default, and every send this channel made before #556.
                SourcePin.OsRouting -> {
                    msg.msg_control = null
                    msg.msg_controllen = 0u.convert()
                }
                is SourcePin.Pinned -> writePktInfo(msg, pin.address)
            }

            // Check the CQE result. io_uring reports failure as a negative `res` carrying -errno, and
            // discarding it made a failed sendmsg indistinguishable from a delivered datagram — which
            // for quiche means a packet counted as in flight that never left the host.
            val res =
                IoUringManager.submitAndWait(1.seconds) { sqe, userData ->
                    // On the poller thread, exactly like receive's: a close that got here first means
                    // this send must not name the descriptor, and a nop retires the submission instead.
                    if (handoff.closed) {
                        io_uring_prep_nop(sqe)
                    } else {
                        inFlightSend.value = userData
                        io_uring_prep_sendmsg(sqe, fd, msg.ptr, 0u)
                    }
                }
            inFlightSend.value = NO_OP_IN_FLIGHT
            // Checked before `res`: a nop reports 0, which would otherwise read as a delivered datagram.
            if (handoff.closed) error("sink is closed")
            if (res < 0) throw DatagramSendException(sendFailure(-res, attempted = len, pin = pin))
        }
    }

    /**
     * What one send must do about [DatagramSendOptions.fromLocal] on *this* socket.
     *
     * A sealed pair rather than the option's own nullable address, because the two cases are different
     * syscall shapes — no control message at all, versus a `PKTINFO` cmsg the kernel reads a source out
     * of — and because the third outcome, a source this socket could never leave from, is a typed
     * failure reported to the caller rather than a value the send path could forget to branch on.
     */
    private sealed interface SourcePin {
        /** No source named. The kernel routes and picks; the default, and the pre-#556 behaviour. */
        data object OsRouting : SourcePin

        /** Leave from [address], already normalized into this socket's own address family. */
        class Pinned(
            val address: LinuxSocketAddress,
        ) : SourcePin
    }

    /**
     * Decide what [fromLocal] means for this socket: nothing, a source to pin, or a refusal.
     *
     * **Every input reaches a decision here, and none is forwarded on the chance that the kernel will
     * do something sensible with it.** That is the whole discipline of the capability: a channel
     * advertising `sourceAddressSelect` and then handing the kernel something it quietly ignores would
     * report success for a datagram that left from the wrong address — #556 again, one layer down and
     * harder to see. Each of the three refusable shapes was measured, not assumed:
     *
     *  - **A wildcard** (`0.0.0.0`, `::`, and the `::ffff:0.0.0.0` spelling a dual-stack socket can be
     *    handed) is not a source, it is the *absence* of one, and the kernel treats it as such — its
     *    `if (fl4->saddr)` and `addr_type != IPV6_ADDR_ANY` guards skip an all-zero pin and choose for
     *    themselves. Forwarding it would be a silent no-op reported as an honoured request, and it is
     *    not hypothetical: a server whose receive path falls back to its own wildcard bind address
     *    produces exactly this. Answered as [SourcePin.OsRouting], which is what it means — and
     *    answered *before* the family check, because a wildcard names no address for a family to
     *    disagree about.
     *  - **The wrong address family** is refused, because the kernel does not: it walks past an
     *    `IPV6_PKTINFO` cmsg on an `AF_INET` socket and sends from whatever routing preferred.
     *  - **An unscoped IPv6 link-local** is refused for what it actually is. `fe80::/10` is only an
     *    address on a link, and `ip6_datagram_send_ctl` answers `EINVAL` when no interface is named.
     *    Letting that reach the kernel would produce a failure this backend cannot attribute — `bind`
     *    answers `EINVAL` for the same address, so the locality probe could not clear it either — and
     *    the honest name for it is not "no interface holds this address", which is false about an
     *    address the host demonstrably holds. A *scoped* link-local is pinned like any other source;
     *    the scope arrives on [LinuxSocketAddress.scopeId], which the receive path now preserves.
     *
     * Throwing rather than returning a refusal keeps the send path's `when` to the two shapes that
     * actually reach a syscall, and puts the refusal in the same channel as every other send failure —
     * a typed [DatagramSendError], never a message to parse.
     */
    private fun sourcePinFor(fromLocal: SocketAddress?): SourcePin {
        if (fromLocal == null) return SourcePin.OsRouting
        val source = fromLocal.asLinuxAddress()
        if (source.namesNoAddress()) return SourcePin.OsRouting
        if ((source.family == AddressFamily.IPv6) != ipv6) throw refusal(source, SourceAddressRejection.WrongFamily)
        if (source.isUnscopedLinkLocal()) throw refusal(source, SourceAddressRejection.UnscopedLinkLocal)
        return SourcePin.Pinned(source)
    }

    /** The one place a source refusal is built, so every one of them reports the same shape. */
    private fun refusal(
        source: LinuxSocketAddress,
        reason: SourceAddressRejection,
    ) = DatagramSendException(DatagramSendError.SourceAddressUnavailable(source.host, reason))

    /**
     * Is this the unspecified address — the one the kernel reads as "choose for me"?
     *
     * Three spellings, not one. `0.0.0.0` and `::` are the obvious pair; `::ffff:0.0.0.0` is the third,
     * and it is the awkward one, because whether the kernel ignores it or rejects it depends on the
     * *destination*: a v4-mapped destination re-enters the IPv4 sender, which reads the low 32 bits
     * and finds zero, while a native IPv6 destination sees an address that is not `IPV6_ADDR_ANY`,
     * fails `ipv6_chk_addr` and returns `EINVAL` (both measured). Neither outcome is what a caller
     * writing "no particular source" meant, so it is decided here instead of left to the destination.
     */
    private fun LinuxSocketAddress.namesNoAddress(): Boolean =
        when (family) {
            AddressFamily.IPv4 -> lo == 0L
            AddressFamily.IPv6 -> hi == 0L && (lo == 0L || lo == V4_MAPPED_UNSPECIFIED_LO)
        }

    /**
     * An IPv6 link-local (`fe80::/10`) with no interface index — an address that cannot be used as a
     * source, and cannot even be bound, until something says on which link it means what it says.
     */
    private fun LinuxSocketAddress.isUnscopedLinkLocal(): Boolean {
        if (family != AddressFamily.IPv6 || scopeId != 0) return false
        val firstByte = (hi ushr 56) and 0xFF
        val secondByte = (hi ushr 48) and 0xFF
        return firstByte == 0xFEL && (secondByte and 0xC0L) == 0x80L
    }

    /**
     * Attach to [msg] the control message that pins [source] as this datagram's source address:
     * `IP_PKTINFO` on an IPv4 socket, `IPV6_PKTINFO` on an IPv6 one.
     *
     * The cmsg is built in the caller's `memScoped` arena — the same lifetime as the `msghdr` and the
     * `iovec` it belongs to, which is what makes it safe against the io_uring hand-off: [MemScope]
     * outlives `submitAndWait`, so the kernel is never reading a scratch this coroutine has left.
     *
     * Address bytes are written at named offsets rather than through the cinterop structs for the same
     * reason [parseControlPlane] reads them that way — one description of each layout, used in both
     * directions, so a wrong offset shows up as a failing round trip instead of two files agreeing
     * with each other and disagreeing with the kernel.
     */
    private fun MemScope.writePktInfo(
        msg: msghdr,
        source: LinuxSocketAddress,
    ) {
        val infoSize = if (ipv6) IN6_PKTINFO_SIZE else IN_PKTINFO_SIZE
        val space = cmsg_space(infoSize.convert()).toInt()
        val control = allocArray<ByteVar>(space)
        // allocArray does not zero, and the kernel reads every byte the cmsg covers — the interface
        // index included, where garbage would name a device instead of leaving the choice to routing.
        memset(control, 0, space.convert())
        // Both fields before asking for the first header: CMSG_FIRSTHDR answers null unless
        // msg_controllen already says the buffer is big enough to hold a cmsghdr.
        msg.msg_control = control
        msg.msg_controllen = space.convert()
        val cmsg = cmsg_firsthdr(msg.ptr)!!.pointed
        cmsg.cmsg_level = if (ipv6) IPPROTO_IPV6 else IPPROTO_IP
        cmsg.cmsg_type = if (ipv6) IPV6_PKTINFO else IP_PKTINFO
        cmsg.cmsg_len = cmsg_len(infoSize.convert()).convert()
        val data = cmsg_data(cmsg.ptr)!!.reinterpret<ByteVar>()
        if (ipv6) {
            val addr = (data + IN6_PKTINFO_ADDR_OFFSET)!!
            for (i in 0 until 8) addr[i] = ((source.hi shr (56 - 8 * i)) and 0xFF).toByte()
            for (i in 0 until 8) addr[8 + i] = ((source.lo shr (56 - 8 * i)) and 0xFF).toByte()
            // ipi6_ifindex, host byte order. 0 when the address carries no scope, which is correct for
            // every global address and fatal for a link-local — hence the refusal in [sourcePinFor]
            // rather than a zero written here and an unattributable EINVAL from the kernel.
            val ifIndex = (data + IN6_PKTINFO_IFINDEX_OFFSET)!!
            for (i in 0 until 4) ifIndex[i] = ((source.scopeId ushr (8 * i)) and 0xFF).toByte()
        } else {
            val addr = (data + IN_PKTINFO_SPEC_DST_OFFSET)!!
            for (i in 0 until 4) addr[i] = ((source.lo shr (24 - 8 * i)) and 0xFF).toByte()
        }
    }

    /**
     * Classify a failed `sendmsg`, deciding first whether the *source* this send named is what the
     * kernel turned down.
     *
     * The kernel has no errno for "that source is not mine". A non-local IPv4 `ipi_spec_dst` makes the
     * output route lookup fail and comes back `ENETUNREACH`; the IPv6 path checks the address against
     * the interface list itself and answers `EINVAL`. Passing either through [sendErrnoToError] would
     * publish a lie a consumer acts on: `ENETUNREACH` is [DatagramSendError.Unreachable], the local
     * "no path to the peer" verdict a migration trigger and an ICE agent both branch on, and the peer
     * is not the problem at all here.
     *
     * Assuming the source is to blame whenever a pinned send fails would be the opposite lie — a
     * genuinely unreachable destination would stop reporting itself. So the two are told apart by
     * asking the kernel the question it has no errno for, once, on a path that has already failed:
     * [localityOf]. Its three answers map to three different reports, and the third — "could not
     * establish it" — is reported as itself rather than folded into either of the other two.
     */
    private fun sendFailure(
        errnoCode: Int,
        attempted: Int,
        pin: SourcePin,
    ): DatagramSendError {
        if (pin !is SourcePin.Pinned) return sendErrnoToError(errnoCode, attempted = attempted, limit = maxWritableSize)
        return when (val locality = localityOf(pin.address)) {
            // The host has the address, so whatever went wrong is not that it lacks it: the send's own
            // errno is the answer, exactly as it would be for an unpinned send.
            AddressLocality.Holds -> sendErrnoToError(errnoCode, attempted = attempted, limit = maxWritableSize)
            AddressLocality.DoesNotHold ->
                DatagramSendError.SourceAddressUnavailable(
                    pin.address.host,
                    SourceAddressRejection.NotAssigned(errnoCode),
                )
            is AddressLocality.Unprobed ->
                DatagramSendError.SourceAddressUnavailable(
                    pin.address.host,
                    SourceAddressRejection.Undetermined(sendErrno = errnoCode, probeErrno = locality.errno),
                )
        }
    }

    /** What [localityOf] could establish about an address. A verdict, so "unknown" is not a guess. */
    private sealed interface AddressLocality {
        /** This host has the address: the probe bound it. */
        data object Holds : AddressLocality

        /** This host does not have it: the probe was refused with the errno that means exactly that. */
        data object DoesNotHold : AddressLocality

        /** The probe could not answer, and [errno] is why. Never silently one of the other two. */
        data class Unprobed(
            val errno: Int,
        ) : AddressLocality
    }

    /**
     * Can this host use [address] as a local address? Asked by binding a throwaway socket to it.
     *
     * **Why `bind` and not `getifaddrs`.** `getifaddrs` enumerates addresses *configured on
     * interfaces*, which is not the question. The kernel's own test, in `__ip_dev_find`, is
     * `inet_addr_type(saddr) == RTN_LOCAL` — a lookup in the local routing table — and that is exactly
     * what `inet_bind` checks. The two agree where an enumeration does not: `127.0.0.2` appears in no
     * interface's address list, yet the kernel pins from it happily and `bind` accepts it (both
     * measured), so an enumeration-based probe would report the whole of `127.0.0.0/8` — and any
     * address given a `local` route — as one this host does not have.
     *
     * Its one residual is `ip_nonlocal_bind` (and `IP_FREEBIND`, which this probe never sets): with the
     * sysctl on, `bind` accepts an address the host does not have, the verdict is [Holds], and the
     * send's own errno is reported unchanged. That is a less specific answer, never an inverted one —
     * it never claims the host lacks an address it has. Closing it would mean reimplementing the FIB
     * lookup in userspace, which is a larger promise than this diagnostic is worth.
     *
     * **On port 0, never the address's own.** A `fromLocal` derived from a received datagram carries
     * the port that datagram arrived on — a port this very socket is holding — so binding the pair
     * would come back `EADDRINUSE` and read as a verdict about the address.
     *
     * **Every other refusal is [AddressLocality.Unprobed], not [AddressLocality.DoesNotHold].**
     * `EADDRNOTAVAIL` is the only errno that means "nobody has this address". `EINVAL` (a scope the
     * kernel will not accept), `EACCES`, `EAFNOSUPPORT`, and `EADDRINUSE`/`EAGAIN` from ephemeral port
     * pressure are all reasons the *probe* failed, and reading any of them as an answer about the
     * address is how a diagnostic turns into a false report.
     *
     * Only ever reached from [sendFailure], so the socket-per-failure cost is paid on a path that has
     * already lost a datagram. The descriptor is opened and closed inside this call and is never named
     * by an io_uring submission, so it stays outside the [LastOutHandoff] lifetime entirely.
     */
    private fun localityOf(address: LinuxSocketAddress): AddressLocality =
        memScoped {
            val storage = alloc<sockaddr_storage>()
            val portless =
                LinuxSocketAddress(address.host, 0, address.family, address.hi, address.lo, address.scopeId)
            val len = portless.writeSockaddr(storage)
            val probeFd = socket(if (ipv6) AF_INET6 else AF_INET, SOCK_DGRAM, IPPROTO_UDP)
            if (probeFd < 0) return@memScoped AddressLocality.Unprobed(errno)
            try {
                if (socket_bind(probeFd, storage.ptr.reinterpret(), len) == 0) {
                    AddressLocality.Holds
                } else {
                    // Read immediately: close() below would overwrite it.
                    val code = errno
                    if (code == EADDRNOTAVAIL) AddressLocality.DoesNotHold else AddressLocality.Unprobed(code)
                }
            } finally {
                close(probeFd)
            }
        }

    /**
     * Borrow the descriptor for [block] under the same admission every read and write passes — a
     * **scoped** borrow rather than an `enter()`/`exit()` pair a caller could forget half of.
     * [MulticastIoUringDatagramChannel] takes its whole control plane through this: it delegates its
     * data plane to this channel already, and the descriptor it `setsockopt`s is this channel's, so it
     * must be admitted like a `send` rather than reading the number out of a field (#527's shape).
     *
     * A borrower admitted here cannot have the descriptor released under it, and one that arrives after
     * [close] is [DescriptorUse.Refused] without a syscall, so it can never name a number the process
     * has since recycled. [block] may suspend; the borrow is released however it ends, cancellation
     * included.
     */
    internal suspend fun <T> withDescriptor(block: suspend (fd: Int) -> T): DescriptorUse<T> {
        when (handoff.enter()) {
            LastOutHandoff.Admission.Refused -> return DescriptorUse.Refused
            LastOutHandoff.Admission.Admitted -> Unit
        }
        return try {
            DescriptorUse.Ran(block(fd))
        } finally {
            leave()
        }
    }

    /** What [withDescriptor] decided — a sealed answer, so "refused" is never a value the block could return. */
    internal sealed interface DescriptorUse<out T> {
        /** The caller was admitted and its block ran with the descriptor; [value] is what it produced. */
        data class Ran<out T>(
            val value: T,
        ) : DescriptorUse<T>

        /** The channel is closed: the block never ran, and never named the descriptor. */
        data object Refused : DescriptorUse<Nothing>
    }

    /**
     * This party's departure. If it was the last one out of a closed channel, it releases the
     * descriptor.
     *
     * The hop off this thread is not decoration: the release ends in [IoUringManager.onSocketClosed],
     * which on the last socket runs `cleanup()`, which `runBlocking`-joins the poller job. A caller
     * that resumed *on* the poller thread — a `Dispatchers.Unconfined` receiver, resumed by the very
     * `deferred.complete()` the poller makes — would otherwise join itself. [NonCancellable] because
     * the usual reason control is here is the caller's cancellation, and a cancelled hop would skip the
     * release and leak the descriptor.
     */
    private suspend fun leave() {
        when (handoff.exit()) {
            LastOutHandoff.Departure.NotLast -> Unit
            LastOutHandoff.Departure.LastOut -> withContext(NonCancellable + Dispatchers.Default) { releaseDescriptor() }
        }
    }

    /** Reached by exactly one party — the CAS that lands the word on closed-and-empty. */
    private fun releaseDescriptor() {
        close(fd)
        IoUringManager.onSocketClosed()
    }

    /**
     * Refuses every further party, retires the submission any party already inside is parked on, and
     * leaves like any of them — releasing the descriptor only if it turns out to be last out.
     *
     * **Why the descriptor is not closed here.** It used to be, and that is #526: `close(fd)` while a
     * receiver was between its flag check and the poller preparing `io_uring_prep_recvmsg(sqe, fd, …)`
     * let the submission name a number the process may have recycled, reading — or, for `sendmsg`,
     * writing — another socket. Closing the descriptor is therefore the last party's job, and this
     * closer is counted in like a user because it is one until it has finished waking the others.
     *
     * **Why the wake is a cancel by `user_data` and not by fd.** `io_uring_prep_cancel_fd` exists, but
     * it would put the descriptor number back in a submission prepared later on the poller thread —
     * the very hazard being removed. Cancelling by `user_data` names no descriptor at all.
     *
     * **Why reading [inFlightReceive] inside the `prepareOp` closes the window rather than shrinking
     * it.** Both this cancel's prepare and a receiver's prepare run in the poller's single drain loop,
     * so they are ordered against each other, and either order is correct:
     *
     *  - the receiver prepared first → it stored its `user_data`, which this cancel then reads and
     *    retires;
     *  - this cancel prepared first → it finds nothing to cancel, and the receiver's prepare, running
     *    afterwards, sees `handoff.closed` (set before this request was ever enqueued) and prepares a
     *    nop instead of naming the descriptor.
     *
     * Read at the call site instead, the first case would race and the receiver would park for its full
     * idle re-arm. There is one residual: if the ring is full the request is completed `-EBUSY` and its
     * `prepareOp` never runs, so an in-flight receive is not retired early and returns on its own
     * deadline. That costs latency, never correctness — the descriptor is still held open by its
     * admission until that receiver leaves.
     */
    override fun close() {
        when (handoff.close()) {
            LastOutHandoff.Closing.AlreadyClosed -> Unit
            LastOutHandoff.Closing.Admitted -> {
                IoUringManager.submitNoWaitUnsafe { sqe ->
                    val parked = inFlightReceive.value
                    val sending = inFlightSend.value
                    when {
                        parked != NO_OP_IN_FLIGHT -> io_uring_prep_cancel64(sqe, parked.toULong(), 0)
                        sending != NO_OP_IN_FLIGHT -> io_uring_prep_cancel64(sqe, sending.toULong(), 0)
                        else -> io_uring_prep_nop(sqe)
                    }
                }
                when (handoff.exit()) {
                    LastOutHandoff.Departure.NotLast -> Unit
                    // Nobody else was inside, so this is the ordinary close: release inline. Unlike
                    // [leave] this is not running on a coroutine the poller resumed, so the join in
                    // cleanup() cannot be a self-join.
                    LastOutHandoff.Departure.LastOut -> releaseDescriptor()
                }
            }
        }
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

    /** Read a host-order 32-bit interface index (`ipi6_ifindex`) from [ptr]+[offset]. */
    private fun ifIndexAt(
        ptr: CPointer<ByteVar>,
        offset: Int,
    ): Int {
        var index = 0
        for (i in 0 until 4) index = index or (((ptr + offset)!![i].toInt() and 0xFF) shl (8 * i))
        return index
    }

    /**
     * Build an IPv6 [SocketAddress] from 16 network-order address bytes at [ptr]+[offset] with [port],
     * scoped to interface [scopeId] (0 = unscoped).
     */
    private fun ipv6LocalAddress(
        ptr: CPointer<ByteVar>,
        offset: Int,
        port: Int,
        scopeId: Int,
    ): SocketAddress {
        var hi = 0L
        var lo = 0L
        for (i in 0 until 8) hi = (hi shl 8) or ((ptr + offset)!![i].toLong() and 0xFF)
        for (i in 0 until 8) lo = (lo shl 8) or ((ptr + offset)!![8 + i].toLong() and 0xFF)
        val groups = IntArray(8)
        for (i in 0 until 4) groups[i] = ((hi shr (48 - 16 * i)) and 0xFFFF).toInt()
        for (i in 0 until 4) groups[4 + i] = ((lo shr (48 - 16 * i)) and 0xFFFF).toInt()
        return LinuxSocketAddress(groups.joinToString(":") { it.toString(16) }, port, AddressFamily.IPv6, hi, lo, scopeId)
    }
}

/**
 * Connected mode ([UdpSocket.connect]): one fixed [peer], sends take no destination (the kernel routes
 * to the `connect()`ed peer), and [localAddress] is the typed maybe-known [LocalAddress] — getsockname
 * failing does not invalidate an otherwise usable connected socket.
 */
@ExperimentalDatagramApi
internal class ConnectedIoUringDatagramChannel(
    fd: Int,
    peer: LinuxSocketAddress,
    override val localAddress: LocalAddress,
    ipv6: Boolean,
    receiveBufferSize: Int = MAX_UDP_PAYLOAD,
    bufferFactory: BufferFactory = BufferFactory.deterministic(),
) : IoUringDatagramChannelCore(fd, peer, localAddress.orNull()?.port ?: 0, ipv6, receiveBufferSize, bufferFactory),
    ConnectedDatagramChannel {
    override val peer: SocketAddress = peer

    override suspend fun send(
        payload: ReadBuffer,
        options: DatagramSendOptions,
    ) = sendDatagram(payload, target = null, options)
}

/**
 * Addressed mode ([UdpSocket.bind]/[UdpSocket.bindMulticast]): many peers, every send names its
 * destination, and [localAddress] is plainly non-null — bind fails fast before construction when
 * getsockname cannot report the bound endpoint.
 */
@ExperimentalDatagramApi
internal class AddressedIoUringDatagramChannel(
    fd: Int,
    override val localAddress: SocketAddress,
    ipv6: Boolean,
    receiveBufferSize: Int = MAX_UDP_PAYLOAD,
    bufferFactory: BufferFactory = BufferFactory.deterministic(),
    beforeSubmit: suspend () -> Unit = {},
) : IoUringDatagramChannelCore(fd, null, localAddress.port, ipv6, receiveBufferSize, bufferFactory, beforeSubmit),
    AddressedDatagramChannel {
    override suspend fun send(
        payload: ReadBuffer,
        to: SocketAddress,
        options: DatagramSendOptions,
    ) = sendDatagram(payload, to, options)
}
