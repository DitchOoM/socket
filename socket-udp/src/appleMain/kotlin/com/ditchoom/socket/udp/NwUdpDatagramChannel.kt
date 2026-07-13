@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.ditchoom.socket.udp

import com.ditchoom.buffer.PlatformBuffer
import com.ditchoom.buffer.ReadBuffer
import com.ditchoom.buffer.allocateNative
import com.ditchoom.buffer.nativeMemoryAccess
import com.ditchoom.buffer.flow.Datagram
import com.ditchoom.buffer.flow.DatagramCapabilities
import com.ditchoom.buffer.flow.DatagramChannel
import com.ditchoom.buffer.flow.DatagramReadResult
import com.ditchoom.buffer.flow.DatagramSendOptions
import com.ditchoom.buffer.flow.ExperimentalDatagramApi
import com.ditchoom.buffer.flow.SocketAddress
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

/**
 * Apple (K/N) **connected** [DatagramChannel] over an `nw_connection_t` in UDP mode — the lift of the
 * quiche `AppleNwUdpChannel`, reshaped to the public datagram trichotomy. NWConnection (not a raw POSIX
 * socket) keeps Apple's NWPath awareness (Wi-Fi↔cellular handoff) and a deterministic [close]. The peer
 * is fixed (the connected remote), so [Datagram.peer] is [connectedPeer] and `send(to = null)` targets
 * it.
 *
 * The connection must already be `ready` at construction (see `UdpSocket.connect`). Control plane is
 * **managed** by NW — no raw ECN/DF/PKTINFO — so [capabilities] is [DatagramCapabilities.None] (§7.1
 * Apple-NW-client row); send `options` are a documented no-op and read fields are sentinels.
 */
@ExperimentalDatagramApi
internal class NwUdpDatagramChannel(
    private val conn: nw_connection_t,
    private val connectedPeer: SocketAddress,
    override val localAddress: SocketAddress?,
    private val receiveBufferSize: Int = MAX_UDP_PAYLOAD,
) : DatagramChannel {
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
        val payload = PlatformBuffer.allocateNative(receiveBufferSize)
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
        return DatagramReadResult.Received(Datagram(payload = payload, peer = connectedPeer))
    }

    override suspend fun send(
        payload: ReadBuffer,
        to: SocketAddress?,
        options: DatagramSendOptions,
    ) {
        check(closedFlag.value == 0) { "sink is closed" }
        // Connected NW channel: send(to = null) targets the fixed peer; a non-null [to] must be the same
        // peer (NW is point-to-point). nw_udp_send copies the bytes into a dispatch_data buffer
        // synchronously, so the caller's buffer is safe the moment the call returns.
        val access = payload.nativeMemoryAccess ?: error("send requires a native-memory buffer")
        val ptr = (access.nativeAddress + payload.position()).toCPointer<ByteVar>()!!
        val len = payload.remaining()
        suspendCancellableCoroutine { continuation ->
            nw_udp_send(conn, ptr, len) { _, _ ->
                // Best-effort UDP send: a transient NW failure is non-fatal. Resume unconditionally.
                continuation.resume(Unit)
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
