@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.ditchoom.socket.udp

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.ReadBuffer
import com.ditchoom.buffer.deterministic
import com.ditchoom.buffer.flow.ConnectedDatagramChannel
import com.ditchoom.buffer.flow.Datagram
import com.ditchoom.buffer.flow.DatagramCapabilities
import com.ditchoom.buffer.flow.DatagramReadResult
import com.ditchoom.buffer.flow.DatagramSendOptions
import com.ditchoom.buffer.flow.Ecn
import com.ditchoom.buffer.flow.ExperimentalDatagramApi
import com.ditchoom.buffer.flow.HopLimit
import com.ditchoom.buffer.flow.LocalAddress
import com.ditchoom.buffer.flow.SocketAddress
import com.ditchoom.buffer.nativeMemoryAccess
import com.ditchoom.socket.udp.nw.nw_udp_cancel
import com.ditchoom.socket.udp.nw.nw_udp_receive
import com.ditchoom.socket.udp.nw.nw_udp_send
import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.convert
import kotlinx.cinterop.toCPointer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import platform.Network.nw_connection_t
import platform.posix.memcpy
import kotlin.concurrent.AtomicInt
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Apple (K/N) [ConnectedDatagramChannel] over an `nw_connection_t` in UDP mode — the lift of the
 * quiche `AppleNwUdpChannel`, reshaped to the public datagram trichotomy. NWConnection (not a raw POSIX
 * socket) keeps Apple's NWPath awareness (Wi-Fi↔cellular handoff) and a deterministic [close]. The peer
 * is fixed at construction (the connected refinement has no destination parameter at all), so
 * [Datagram.peer] is [peer] and `send(payload)` targets it; [localAddress] is the typed maybe-known
 * [LocalAddress] (NW may not surface one before traffic flows).
 *
 * The connection must already be `ready` at construction (see `UdpSocket.connect`). Control plane is
 * **managed** by NW — no raw ECN/DF/PKTINFO — so [capabilities] is [DatagramCapabilities.None] (§7.1
 * Apple-NW-client row); send `options` are a documented no-op and read fields are the typed absent
 * states.
 */
@ExperimentalDatagramApi
internal class NwUdpDatagramChannel(
    private val conn: nw_connection_t,
    override val peer: SocketAddress,
    override val localAddress: LocalAddress,
    private val receiveBufferSize: Int = MAX_UDP_PAYLOAD,
    private val bufferFactory: BufferFactory = BufferFactory.deterministic(),
) : ConnectedDatagramChannel {
    private val closedFlag = AtomicInt(0)

    // Completes when NW terminally closed/failed (a receive callback delivered nil content or an error).
    // Set on the libdispatch callback thread, read on the reader coroutine.
    private val terminal = CompletableDeferred<Unit>()

    override val isOpen: Boolean get() = closedFlag.value == 0 && !terminal.isCompleted

    override val maxWritableSize: Int = MAX_UDP_PAYLOAD

    override val capabilities: DatagramCapabilities = DatagramCapabilities.None

    /**
     * Receive one datagram. The `nw_connection_receive_message` callback fires on a libdispatch (foreign)
     * thread and does the byte copy there, so the payload lifetime is fenced against it: NW runs the
     * handler exactly once (data / error / cancel), and we only return after [done] resolves, so the copy
     * is always finished before the payload is published or freed. On cancellation we cancel the
     * connection (so the callback fires with nil content — no copy) and wait non-cancellably for it before
     * unwinding, so the payload is never freed while the callback could still write into it.
     */
    override suspend fun receive(): DatagramReadResult {
        if (closedFlag.value != 0 || terminal.isCompleted) return DatagramReadResult.Closed()
        val payload = bufferFactory.allocate(receiveBufferSize)
        val dst = payload.nativeMemoryAccess!!.nativeAddress.toCPointer<ByteVar>()!!
        val done = CompletableDeferred<Int>()
        nw_udp_receive(conn) { content, _, errorCode ->
            if (content == null || errorCode != 0) {
                terminal.complete(Unit)
                done.complete(-1)
            } else {
                val available = content.length.toInt()
                val len = if (available > payload.capacity) payload.capacity else available
                if (len > 0) memcpy(dst, content.bytes, len.convert())
                done.complete(len)
            }
        }
        val n =
            try {
                done.await()
            } catch (e: CancellationException) {
                nw_udp_cancel(conn)
                withContext(NonCancellable) { done.await() }
                payload.freeNativeMemory()
                throw e
            }
        if (n < 0) {
            payload.freeNativeMemory()
            return DatagramReadResult.Closed()
        }
        payload.position(0)
        payload.setLimit(n)
        // All five args explicit: a defaulted localAddress rides the default-args bridge and boxes the
        // value class (see LocalAddress's KDoc) — NW reports no per-datagram read control plane anyway.
        return DatagramReadResult.Received(
            Datagram(
                payload = payload,
                peer = peer,
                ecn = Ecn.Unknown,
                localAddress = LocalAddress.Unknown,
                hopLimit = HopLimit.Unknown,
            ),
        )
    }

    override suspend fun send(
        payload: ReadBuffer,
        options: DatagramSendOptions,
    ) {
        check(closedFlag.value == 0) { "sink is closed" }
        // Connected NW channel: every send targets the fixed peer (NW is point-to-point). nw_udp_send
        // copies the bytes into a dispatch_data buffer synchronously, so the caller's buffer is safe the
        // moment the call returns.
        val access = payload.nativeMemoryAccess ?: error("send requires a native-memory buffer")
        val ptr = (access.nativeAddress + payload.position()).toCPointer<ByteVar>()!!
        val len = payload.remaining()
        suspendCancellableCoroutine<Unit> { continuation ->
            nw_udp_send(conn, ptr, len) { errorDomain, errorCode ->
                // The completion carries (domain, code) and used to be discarded on the theory that a
                // transient NW failure is non-fatal. It is not the sink's call to make: resuming as if
                // the datagram went out tells quiche's congestion controller a packet is in flight that
                // never left the host. Report it and let the consumer decide.
                if (errorCode == 0) {
                    continuation.resume(Unit)
                } else {
                    continuation.resumeWithException(
                        DatagramSendException(DatagramSendError.PlatformError(errorDomain, errorCode)),
                    )
                }
            }
        }
    }

    override fun close() {
        if (!closedFlag.compareAndSet(0, 1)) return
        nw_udp_cancel(conn)
    }

    companion object {
        private const val MAX_UDP_PAYLOAD = 65507
    }
}
