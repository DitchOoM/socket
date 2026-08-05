package com.ditchoom.socket.udp

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.flow.DatagramReadResult
import com.ditchoom.buffer.flow.ExperimentalDatagramApi
import com.ditchoom.buffer.managed
import kotlinx.coroutines.withTimeout
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * [com.ditchoom.buffer.flow.DatagramCapabilities.requiresNativeMemoryBuffers] must describe what the
 * send path actually does (buffer #328).
 *
 * The flag exists so a consumer that allocates its own outbound datagrams — webrtc builds every STUN
 * check, TURN request and DTLS record itself — can pick a compatible [BufferFactory] at construction
 * instead of discovering the mismatch as a transmit-time failure on one platform. That only works if
 * the flag is true exactly where a heap payload really is fatal, so this asserts the biconditional
 * rather than the flag's value: **a heap-backed payload sends successfully if and only if the channel
 * says it does not require native memory.**
 *
 * Written this way it needs no per-platform expectation table and cannot rot into agreement with a
 * hardcoded one. It fails if a native channel ever forgets to advertise the requirement (the
 * dangerous direction — a consumer allocates heap and the send dies later), and equally if a channel
 * over-claims it (the wasteful direction — a consumer is pushed onto explicit-free buffers it does
 * not need). The multicast wrappers rebuild capabilities field-by-field, so this also guards the
 * flag being silently dropped on the way through `withMulticast()`.
 *
 * ### Why the probe is `managed()` and not `Default`
 *
 * An earlier revision allocated from [BufferFactory.Default], reasoning that "the factory a consumer
 * reaches for by default" was the interesting case. It passes on JVM/Node/Linux and fails on Apple —
 * because `Default` is not a heap buffer everywhere:
 *
 * - **Apple**: `MutableDataBuffer` over `NSMutableData` — *native-backed*, so `sendto` accepts it
 * - **Linux**: `ByteArrayBuffer` — GC heap, no native address, so `sendmsg` rejects it
 * - **JVM**: `DirectJvmBuffer` over `ByteBuffer.allocateDirect`
 *
 * So a Default-backed send succeeding on Apple says nothing about the channel's requirement; it says
 * Apple's default happens to satisfy it. [BufferFactory.managed] is GC heap on *every* platform
 * (`ByteArrayBuffer` on both native targets, `HeapJvmBuffer` on the JVM), which is what makes it a
 * real test of the flag rather than of a platform coincidence.
 *
 * That divergence is also the best argument for the flag existing at all: `Default` is native-capable
 * on Apple and not on Linux, so a consumer cannot reason its way to the right factory from platform
 * knowledge — it has to ask the channel.
 */
@OptIn(ExperimentalDatagramApi::class)
internal suspend fun assertNativeMemoryRequirementMatchesSendPath() {
    val sender = UdpSocket.bind("127.0.0.1", 0)
    val receiver = UdpSocket.bind("127.0.0.1", 0)
    try {
        val requiresNative = sender.capabilities.requiresNativeMemoryBuffers

        // GC heap on every platform — see the KDoc on why this is not BufferFactory.Default.
        val payload = BufferFactory.managed().allocate(5)
        payload.writeString("hello")
        payload.resetForRead()

        val outcome = runCatching { sender.send(payload, to = receiver.localAddress) }

        if (requiresNative) {
            assertTrue(
                outcome.isFailure,
                "capabilities.requiresNativeMemoryBuffers is true, so a heap payload must be " +
                    "rejected — if this send succeeded the flag is over-claiming and pushes " +
                    "consumers onto explicit-free buffers they do not need",
            )
        } else {
            assertTrue(
                outcome.isSuccess,
                "capabilities.requiresNativeMemoryBuffers is false, so a heap payload must send — " +
                    "a consumer that believed that claim would fail at transmit time: " +
                    "${outcome.exceptionOrNull()}",
            )
            // ...and it must genuinely reach the peer. A send that silently dropped the datagram would
            // satisfy the assertion above while breaking the consumer just as badly.
            val received = withTimeout(5_000) { receiver.receive() }
            val datagram = assertIs<DatagramReadResult.Received>(received).datagram
            val delivered = datagram.payload
            assertEquals("hello", delivered.readString(delivered.remaining()))
            delivered.freeNativeMemory()
        }

        payload.freeNativeMemory()
    } finally {
        runCatching { sender.close() }
        runCatching { receiver.close() }
    }
}
