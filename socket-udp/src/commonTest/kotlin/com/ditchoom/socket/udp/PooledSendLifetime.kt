package com.ditchoom.socket.udp

import com.ditchoom.buffer.ByteOrder
import com.ditchoom.buffer.flow.DatagramReadResult
import com.ditchoom.buffer.flow.ExperimentalDatagramApi
import com.ditchoom.buffer.pool.BufferPool
import kotlinx.coroutines.withTimeout
import kotlin.test.assertEquals
import kotlin.test.assertIs

/**
 * The send-side pooled-buffer lifetime contract (#277): sending a payload allocated from a [BufferPool]
 * must not take a reference the send path never gives back. If it does, the chunk's refcount never
 * reaches zero, `freeNativeMemory()` does not return it to the pool, and **one send permanently removes
 * one chunk from the pool** — invisible to a caller watching only its own handles, since a
 * `PooledBuffer` reports itself freed either way.
 *
 * The body lives here rather than in a platform suite because the invariant is platform-neutral and the
 * JVM/Node regression it guards is not: JVM/NIO and Node used to stage the send through
 * `ReadBuffer.slice()`, whose pooled form is a reference-holding `TrackedSlice`; Linux and Apple always
 * resolved `nativeAddress + position()` and took no reference. Each platform's real-socket suite calls
 * this with its own runner (`runBlocking` / `GlobalScope.promise`), the same split the conformance
 * suites use.
 */
@OptIn(ExperimentalDatagramApi::class)
internal suspend fun assertSendReturnsPooledChunkToPool() {
    // Seed the pool from the module's own per-platform factory, not BufferFactory.Default. On
    // Kotlin/Native the default factory yields a GC-heap buffer with no native memory behind it, and
    // the Linux and Apple send paths resolve `nativeMemoryAccess` to get the datagram's address — so a
    // Default-backed pooled buffer fails there with "send requires a native-memory buffer" while
    // working fine on JVM and Node. Using the same factory the module itself would pick keeps this
    // test about pool accounting rather than about buffer provenance.
    val pool = BufferPool(maxPoolSize = 16, defaultBufferSize = 8 * 1024, factory = defaultDatagramBufferFactory)

    // Control: acquire and release WITHOUT sending. Establishes that this pool recycles at all, so a
    // failure below is attributable to the send and not to the pool or the release call.
    val control = pool.allocate(64, ByteOrder.BIG_ENDIAN)
    control.writeString("hello")
    control.resetForRead()
    control.freeNativeMemory()
    assertEquals(1, pool.stats().currentPoolSize, "control: an unsent buffer must return to the pool")

    val sender = UdpSocket.bind("127.0.0.1", 0)
    val receiver = UdpSocket.bind("127.0.0.1", 0)
    try {
        // Subject: same allocate, same release — the only difference is that it was sent.
        val subject = pool.allocate(64, ByteOrder.BIG_ENDIAN)
        subject.writeString("hello")
        subject.resetForRead()
        val before = subject.remaining()

        sender.send(subject, to = receiver.localAddress)

        // The fix must not be "stop slicing and let the send consume the payload": the window the send
        // transmits has to be a view, so the caller's cursor is where it was and the bytes are intact.
        assertEquals(before, subject.remaining(), "send must not consume the caller's payload buffer")
        val received = withTimeout(5_000) { receiver.receive() }
        val datagram = assertIs<DatagramReadResult.Received>(received).datagram
        val payload = datagram.payload
        assertEquals("hello", payload.readString(payload.remaining()))
        payload.freeNativeMemory()

        subject.freeNativeMemory()
        assertEquals(1, pool.stats().currentPoolSize, "a sent buffer must return to the pool too (#277)")
    } finally {
        runCatching { sender.close() }
        runCatching { receiver.close() }
    }
}
