@file:OptIn(
    kotlinx.cinterop.ExperimentalForeignApi::class,
    kotlinx.coroutines.DelicateCoroutinesApi::class,
    // CloseableCoroutineDispatcher is experimental, and recvDispatcher is exposed (internal) to the #498 witness.
    kotlinx.coroutines.ExperimentalCoroutinesApi::class,
)

package com.ditchoom.socket.udp

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.ReadBuffer
import com.ditchoom.buffer.deterministic
import com.ditchoom.buffer.flow.AddressedDatagramChannel
import com.ditchoom.buffer.flow.Datagram
import com.ditchoom.buffer.flow.DatagramCapabilities
import com.ditchoom.buffer.flow.DatagramCloseReason
import com.ditchoom.buffer.flow.DatagramReadResult
import com.ditchoom.buffer.flow.DatagramSendOptions
import com.ditchoom.buffer.flow.Ecn
import com.ditchoom.buffer.flow.ExperimentalDatagramApi
import com.ditchoom.buffer.flow.HopLimit
import com.ditchoom.buffer.flow.LocalAddress
import com.ditchoom.buffer.flow.SocketAddress
import com.ditchoom.buffer.nativeMemoryAccess
import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.UIntVar
import kotlinx.cinterop.alloc
import kotlinx.cinterop.convert
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.sizeOf
import kotlinx.cinterop.toCPointer
import kotlinx.cinterop.value
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.newSingleThreadContext
import kotlinx.coroutines.withContext
import platform.posix.close
import platform.posix.memset
import platform.posix.recvfrom
import platform.posix.sendto
import platform.posix.sockaddr
import platform.posix.sockaddr_storage

/**
 * Apple (K/N) [AddressedDatagramChannel] backed by blocking POSIX `recvfrom`/`sendto` — the lift
 * of the quiche `AppleUdpServerChannel`, reshaped to the public datagram trichotomy. Per-packet source
 * is recovered from `recvfrom` and surfaced as [Datagram.peer]; every send names its destination (the
 * addressed refinement requires `to`, so a destination-less send is unrepresentable); the quiche
 * `lastDest` cache is dropped (the send target materializes from [SocketAddress] primitives into a
 * `memScoped` scratch, RFC §4). [localAddress] is plainly non-null: `UdpSocket.bind` fails fast on a
 * getsockname failure before constructing this channel.
 *
 * `recvfrom` blocks, so it runs on a dedicated single-thread dispatcher. [close] closes the fd, which
 * unblocks an in-flight `recvfrom`; the dispatcher itself is closed by whichever party is last out —
 * [close] when no receiver is in flight, otherwise the receiver, on its way out. A receiver is "in
 * flight" from the moment [LastOutHandoff.enter] admits it, which is the same atomic step that used to
 * be a bare flag read, so there is no longer a window in which a receiver has passed the closed check
 * and `close()` can take the dispatcher out from under its hop (#498: kotlinx reported that window as
 * `IllegalStateException: Dispatcher apple-udp-recv-N was closed, attempted to schedule` in place of
 * [DatagramReadResult.Closed]). The recv sockaddr scratch is per-call (`memScoped`), so a concurrent
 * [close] never races a shared write buffer. Not thread-safe: confine [receive]/[send] each to one
 * coroutine (buffer-flow contract).
 *
 * Control plane: the rich Darwin POSIX ceiling (`IP_TOS`/`IP_DONTFRAG`/`IP_RECVTOS`/`IP_PKTINFO`) is a
 * labeled follow-up (#377); this first landing advertises [DatagramCapabilities.None] (honest — the datapath
 * uses plain `recvfrom`/`sendto` with no ancillary data), so every read field is its typed absent
 * state and every advisory send field a no-op.
 *
 * @param beforeDispatch Test seam for the #498 window: runs on every receive iteration after this
 *   receiver has been admitted and before it hops onto [recvDispatcher]. Production leaves the no-op
 *   default; `PosixUdpReceiveCloseHandoffTests` parks a receiver here and runs `close()` around it.
 */
@ExperimentalDatagramApi
internal class PosixUdpDatagramChannel(
    private val fd: Int,
    override val localAddress: SocketAddress,
    private val receiveBufferSize: Int = MAX_UDP_PAYLOAD,
    private val bufferFactory: BufferFactory = BufferFactory.deterministic(),
    private val beforeDispatch: suspend () -> Unit = {},
) : AddressedDatagramChannel {
    /** Who closes [recvDispatcher] — the last party out — decided in one CAS; see [LastOutHandoff]. */
    private val handoff = LastOutHandoff()

    /** `internal` only so the #498 witness can prove it is closed once the channel is; not an API. */
    internal val recvDispatcher = newSingleThreadContext("apple-udp-recv-$fd")

    override val isOpen: Boolean get() = !handoff.closed

    override val maxWritableSize: Int = MAX_UDP_PAYLOAD

    // Not DatagramCapabilities.None: `None` asserts requiresNativeMemoryBuffers = false, and `sendto`
    // takes a raw base pointer (send errors outright if payload.nativeMemoryAccess is absent). No
    // control plane otherwise — this path uses plain recvfrom/sendto with no ancillary data.
    override val capabilities: DatagramCapabilities = DatagramCapabilities(requiresNativeMemoryBuffers = true)

    override suspend fun receive(): DatagramReadResult {
        val payload = bufferFactory.allocate(receiveBufferSize)
        val ptr = payload.nativeMemoryAccess!!.nativeAddress.toCPointer<ByteVar>()!!
        try {
            while (true) {
                // Admission, not a flag read: the CAS that observes "open" also counts this receiver
                // in, so close() sees it and hands the dispatcher's close to it instead of racing it.
                when (handoff.enter()) {
                    LastOutHandoff.Admission.Refused -> {
                        payload.freeNativeMemory()
                        return DatagramReadResult.Closed()
                    }
                    LastOutHandoff.Admission.Admitted -> Unit
                }
                val outcome: DatagramReadResult? =
                    try {
                        beforeDispatch()
                        memScoped {
                            val addr = alloc<sockaddr_storage>()
                            val addrLen = alloc<UIntVar>() // socklen_t is uint32 on Darwin (no socklen_tVar alias)
                            memset(addr.ptr, 0, sizeOf<sockaddr_storage>().convert())
                            addrLen.value = sizeOf<sockaddr_storage>().convert()
                            val n =
                                withContext(recvDispatcher) {
                                    recvfrom(fd, ptr, payload.capacity.convert(), 0, addr.ptr.reinterpret(), addrLen.ptr)
                                        .toInt()
                                }
                            when {
                                handoff.closed -> DatagramReadResult.Closed()
                                n >= 0 -> {
                                    val peer = sockaddrToAppleSocketAddress(addr.ptr.reinterpret<sockaddr>())
                                    if (peer == null) {
                                        null // unroutable family — skip, keep waiting
                                    } else {
                                        payload.position(0)
                                        payload.setLimit(n)
                                        // All five args explicit: a defaulted localAddress rides the
                                        // default-args bridge and boxes the value class (LocalAddress KDoc);
                                        // no ancillary data on this datapath, so all typed absent states.
                                        DatagramReadResult.Received(
                                            Datagram(
                                                payload = payload,
                                                peer = peer,
                                                ecn = Ecn.Unknown,
                                                localAddress = LocalAddress.Unknown,
                                                hopLimit = HopLimit.Unknown,
                                            ),
                                        )
                                    }
                                }
                                else -> DatagramReadResult.Closed(DatagramCloseReason.OsError(n))
                            }
                        }
                    } finally {
                        leaveRecvDispatcher()
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
     * This receiver's departure. If it was the last party out of a closed channel, it closes the
     * dispatcher — and does so off the dispatcher's own thread, because `MultiWorkerDispatcher.close()`
     * joins its worker, and a caller that resumed *on* that worker (only a `Dispatchers.Unconfined`
     * caller can) would otherwise wait on itself. [NonCancellable] because the usual reason control is
     * here at all is the caller's cancellation, and a cancelled hop would skip the close and leak the
     * worker thread. A failing close propagates: it is the caller's end-of-life report, not noise.
     */
    private suspend fun leaveRecvDispatcher() {
        when (handoff.exit()) {
            LastOutHandoff.Departure.NotLast -> Unit
            LastOutHandoff.Departure.LastOut -> withContext(NonCancellable + Dispatchers.Default) { recvDispatcher.close() }
        }
    }

    override suspend fun send(
        payload: ReadBuffer,
        to: SocketAddress,
        options: DatagramSendOptions,
    ) {
        check(!handoff.closed) { "sink is closed" }
        val access = payload.nativeMemoryAccess ?: error("send requires a native-memory buffer")
        val ptr = (access.nativeAddress + payload.position()).toCPointer<ByteVar>()!!
        val len = payload.remaining()
        // Parity guard: the same condition reports the same typed reason on every backend.
        if (len > maxWritableSize) throw DatagramSendException(DatagramSendError.TooLarge(len, maxWritableSize))
        memScoped {
            val addr = alloc<sockaddr_storage>()
            val addrLen = to.writeSockaddr(addr)
            // Check the result. A discarded `sendto` return is a datagram that vanishes between a
            // clean return and the wire — for quiche, a packet its congestion controller counts as
            // in flight but which never left the host.
            val sent = sendto(fd, ptr, len.convert(), 0, addr.ptr.reinterpret(), addrLen).toLong()
            if (sent < 0) throw DatagramSendException(sendErrnoToError(attempted = len, limit = maxWritableSize))
        }
    }

    override fun close() {
        when (handoff.close()) {
            LastOutHandoff.Closing.AlreadyClosed -> Unit
            // A receiver is in flight: closing the fd unblocks its recvfrom (returns -1) or fails the
            // one it is about to make, its loop returns Closed, and it closes the dispatcher on the
            // way out. The recv scratch is per-call memScoped, so nothing shared is freed here.
            LastOutHandoff.Closing.HandedOff -> close(fd)
            // Nobody is in flight and nobody can be admitted now: the dispatcher is idle, and this is
            // the only party left to close it. Not wrapped — a failing close is reported, not lost.
            LastOutHandoff.Closing.LastOut -> {
                close(fd)
                recvDispatcher.close()
            }
        }
    }

    companion object {
        private const val MAX_UDP_PAYLOAD = 65507
    }
}
