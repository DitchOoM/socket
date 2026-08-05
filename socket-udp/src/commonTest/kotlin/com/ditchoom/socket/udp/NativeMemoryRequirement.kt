package com.ditchoom.socket.udp

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.Default
import com.ditchoom.buffer.flow.DatagramReadResult
import com.ditchoom.buffer.flow.ExperimentalDatagramApi
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
 * rather than the flag's value: **a `BufferFactory.Default` payload sends successfully if and only if
 * the channel says it does not require native memory.**
 *
 * Written this way it needs no per-platform expectation table and cannot rot into agreement with a
 * hardcoded one. It fails if a native channel ever forgets to advertise the requirement (the
 * dangerous direction — a consumer allocates heap and the send dies later), and equally if a channel
 * over-claims it (the wasteful direction — a consumer is pushed onto explicit-free buffers it does
 * not need). The multicast wrappers rebuild capabilities field-by-field, so this also guards the
 * flag being silently dropped on the way through `withMulticast()`.
 */
@OptIn(ExperimentalDatagramApi::class)
internal suspend fun assertNativeMemoryRequirementMatchesSendPath() {
    val sender = UdpSocket.bind("127.0.0.1", 0)
    val receiver = UdpSocket.bind("127.0.0.1", 0)
    try {
        val requiresNative = sender.capabilities.requiresNativeMemoryBuffers

        // Deliberately BufferFactory.Default, NOT defaultDatagramBufferFactory: Default is the factory
        // a consumer reaches for when it has not been told otherwise, and on Kotlin/Native it is a
        // GC-heap buffer with no native address behind it. That divergence is the whole subject here.
        val payload = BufferFactory.Default.allocate(5)
        payload.writeString("hello")
        payload.resetForRead()

        val outcome = runCatching { sender.send(payload, to = receiver.localAddress) }

        if (requiresNative) {
            assertTrue(
                outcome.isFailure,
                "capabilities.requiresNativeMemoryBuffers is true, so a BufferFactory.Default payload " +
                    "must be rejected — if this send succeeded the flag is over-claiming and pushes " +
                    "consumers onto explicit-free buffers they do not need",
            )
        } else {
            assertTrue(
                outcome.isSuccess,
                "capabilities.requiresNativeMemoryBuffers is false, so a BufferFactory.Default payload " +
                    "must send — a consumer that believed that claim would fail at transmit time: " +
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
