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
import com.ditchoom.socket.udp.linux.cmsg_nxthdr
import com.ditchoom.socket.udp.linux.cmsghdr
import com.ditchoom.socket.udp.linux.io_uring_prep_cancel64
import com.ditchoom.socket.udp.linux.io_uring_prep_nop
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
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
import kotlin.concurrent.AtomicLong
import kotlin.time.Duration.Companion.seconds

/** 65535 − 8 (UDP header) − 20 (IPv4 header). Large enough that no real datagram is truncated. */
private const val MAX_UDP_PAYLOAD = 65507

/** Ancillary-data scratch — ample for IP_TOS(1) + IP_TTL(4) + IP_PKTINFO(12) each in a cmsghdr. */
private const val CONTROL_BUFFER_SIZE = 256

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
                    // struct in_pktinfo { int ipi_ifindex; struct in_addr ipi_spec_dst; struct in_addr ipi_addr; }
                    // ipi_addr (the datagram's destination IP) is at offset 8.
                    level == IPPROTO_IP && type == IP_PKTINFO ->
                        localAddress = LocalAddress.of(ipv4LocalAddress(data.reinterpret(), 8, localPort))
                    level == IPPROTO_IPV6 && type == IPV6_TCLASS ->
                        ecn = Ecn.fromCodepoint(data.reinterpret<IntVar>().pointed.value)
                    level == IPPROTO_IPV6 && type == IPV6_HOPLIMIT ->
                        hopLimit = HopLimit.of(data.reinterpret<IntVar>().pointed.value)
                    // struct in6_pktinfo { struct in6_addr ipi6_addr; unsigned ipi6_ifindex; } — addr at offset 0.
                    level == IPPROTO_IPV6 && type == IPV6_PKTINFO ->
                        localAddress = LocalAddress.of(ipv6LocalAddress(data.reinterpret(), 0, localPort))
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
            msg.msg_control = null
            msg.msg_controllen = 0u.convert()

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
            if (res < 0) throw DatagramSendException(sendErrnoToError(-res, attempted = len, limit = maxWritableSize))
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
