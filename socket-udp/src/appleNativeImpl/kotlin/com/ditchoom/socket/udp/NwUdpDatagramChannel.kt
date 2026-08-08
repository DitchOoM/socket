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
import kotlinx.cinterop.IntVar
import kotlinx.cinterop.UIntVar
import kotlinx.cinterop.alloc
import kotlinx.cinterop.convert
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.sizeOf
import kotlinx.cinterop.toCPointer
import kotlinx.cinterop.value
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import platform.Network.nw_connection_t
import platform.posix.AF_INET
import platform.posix.IPPROTO_UDP
import platform.posix.SOCK_DGRAM
import platform.posix.SOL_SOCKET
import platform.posix.SO_SNDBUF
import platform.posix.close
import platform.posix.getsockopt
import platform.posix.memcpy
import platform.posix.socket
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

    // NW gives no socket fd, so unlike the POSIX path this channel cannot widen SO_SNDBUF — it is
    // stuck with Darwin's default UDP datagram ceiling. Advertising the theoretical 65507 here would
    // be the same lie the POSIX path used to tell: a send at the advertised size fails EMSGSIZE
    // (surfaced as the POSIX-domain NW error 40). Report what the kernel will actually accept,
    // queried rather than hardcoded, since net.inet.udp.maxdgram is a tunable sysctl.
    override val maxWritableSize: Int = darwinUdpSendCeiling()

    // No control plane (NW manages ECN/DF/PKTINFO itself, §7.1) — but NOT DatagramCapabilities.None:
    // `None` asserts requiresNativeMemoryBuffers = false, and `nw_udp_send` takes a raw base pointer
    // (send errors outright if payload.nativeMemoryAccess is absent). The one capability this channel
    // does claim is that requirement.
    override val capabilities: DatagramCapabilities = DatagramCapabilities(requiresNativeMemoryBuffers = true)

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
        // Parity guard: NW reports failures in its own (domain, code) namespace, so an oversized
        // payload would surface as PlatformError rather than the TooLarge every other backend reports.
        if (len > maxWritableSize) throw DatagramSendException(DatagramSendError.TooLarge(len, maxWritableSize))
        suspendCancellableCoroutine<Unit> { continuation ->
            nw_udp_send(conn, ptr, len) { errorDomain, errorCode ->
                // The completion carries (domain, code) and used to be discarded on the theory that a
                // transient NW failure is non-fatal. It is not the sink's call to make: resuming as if
                // the datagram went out tells quiche's congestion controller a packet is in flight that
                // never left the host. Report it and let the consumer decide.
                if (errorCode == 0) {
                    continuation.resume(Unit)
                } else {
                    // A POSIX-domain NW error carries a real errno, so it maps onto the same typed set
                    // every other backend uses — otherwise the identical condition would surface as an
                    // opaque PlatformError here and TooLarge everywhere else, and a consumer branching
                    // on the reason could not rely on it. Non-POSIX domains (dns, tls) have no errno
                    // and keep their own namespace.
                    val error =
                        if (errorDomain == NW_ERROR_DOMAIN_POSIX) {
                            sendErrnoToError(errorCode, attempted = len, limit = maxWritableSize)
                        } else {
                            DatagramSendError.PlatformError(errorDomain, errorCode)
                        }
                    continuation.resumeWithException(DatagramSendException(error))
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

        /** `nw_error_domain_posix` — the NW error domain whose `code` is a plain POSIX errno. */
        private const val NW_ERROR_DOMAIN_POSIX = 1

        /**
         * Darwin's largest sendable UDP datagram, clamped to the protocol ceiling.
         *
         * Measured rather than hardcoded: a throwaway UDP socket's *default* `SO_SNDBUF` is exactly the
         * limit NW's own socket inherits (Darwin seeds it from the `net.inet.udp.maxdgram` tunable,
         * 9216 stock). Reading it keeps an admin who retuned the sysctl from being handed a wrong
         * number in either direction. `sysctlbyname` would say the same thing but is not bound in
         * Kotlin/Native's `platform.posix`.
         */
        private fun darwinUdpSendCeiling(): Int {
            val fd = socket(AF_INET, SOCK_DGRAM, IPPROTO_UDP)
            if (fd < 0) return MAX_UDP_PAYLOAD
            return try {
                memScoped {
                    val value = alloc<IntVar>()
                    val length = alloc<UIntVar>() // socklen_t is uint32 on Darwin (no socklen_tVar alias)
                    length.value = sizeOf<IntVar>().convert()
                    val ok = getsockopt(fd, SOL_SOCKET, SO_SNDBUF, value.ptr, length.ptr) == 0
                    if (ok && value.value > 0) minOf(MAX_UDP_PAYLOAD, value.value) else MAX_UDP_PAYLOAD
                }
            } finally {
                close(fd)
            }
        }
    }
}
