package com.ditchoom.socket

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.ByteOrder
import com.ditchoom.buffer.Default
import com.ditchoom.buffer.PlatformBuffer
import com.ditchoom.buffer.flow.ReadResult
import kotlinx.coroutines.runBlocking
import java.net.ServerSocket
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/**
 * Regression test for the read-path buffer accumulation that caused intermittent
 * `OutOfMemoryError: Cannot reserve … direct buffer memory` under high read throughput
 * (JVM Autobahn case 9.6.1). `readRaw` used to allocate a fresh GC-reclaimed buffer from
 * `config.bufferFactory` on every read; it now draws from a per-connection [ReadBufferSource] pool,
 * and the consumer returns each buffer via `freeNativeMemory()` after consuming it — so the receive
 * buffers are RECYCLED, not re-allocated.
 *
 * The assertion is deterministic: a counting [BufferFactory] records how many buffers the socket
 * actually allocates. With pooling that is a handful (only pool misses); pre-fix it was ≈ one per read.
 */
class ReadBufferPoolingTest {
    /** Counts real allocations, delegating everything else to the platform-default factory. */
    private class CountingBufferFactory(
        private val delegate: BufferFactory = BufferFactory.Default,
    ) : BufferFactory by delegate {
        val allocations = AtomicInteger(0)

        override fun allocate(
            size: Int,
            byteOrder: ByteOrder,
        ): PlatformBuffer {
            allocations.incrementAndGet()
            return delegate.allocate(size, byteOrder)
        }
    }

    @Test
    fun receiveBuffersAreRecycledNotAllocatedPerRead() {
        val server = ServerSocket(0)
        val port = server.localPort
        thread(isDaemon = true, name = "tcp-echo") {
            try {
                val c = server.accept()
                val inp = c.getInputStream()
                val out = c.getOutputStream()
                val buf = ByteArray(64 * 1024)
                while (true) {
                    val n = inp.read(buf)
                    if (n < 0) break
                    out.write(buf, 0, n)
                    out.flush()
                }
            } catch (_: Exception) {
                // echo ends when the client disconnects
            }
        }

        val counting = CountingBufferFactory()
        val msgSize = 128 * 1024
        val payload = BufferFactory.Default.allocate(msgSize).apply { writeBytes(ByteArray(msgSize) { 0x41 }) }
        var totalReads = 0

        runBlocking {
            val socket = ClientSocket.connect(port, "localhost", TransportConfig(bufferFactory = counting))
            try {
                repeat(300) {
                    payload.resetForRead()
                    socket.write(payload, 10.seconds)
                    var got = 0
                    while (got < msgSize) {
                        val r = socket.read(10.seconds)
                        assertTrue(r is ReadResult.Data, "unexpected non-data read: $r")
                        got += r.buffer.remaining()
                        totalReads++
                        // The consumer hands ownership back after consuming — recycles to the pool.
                        (r.buffer as PlatformBuffer).freeNativeMemory()
                    }
                }
            } finally {
                socket.close()
            }
        }
        server.close()

        val allocations = counting.allocations.get()
        assertTrue(totalReads >= 300, "sanity: expected many reads, got $totalReads")
        // Pooling ⇒ allocate() is called only on the handful of pool misses, NOT once per read.
        assertTrue(
            allocations < 20,
            "receive buffers should be pooled/recycled: expected < 20 allocations, got $allocations over $totalReads reads",
        )
    }

    /** Records the size of every allocation, delegating to the platform-default factory. */
    private class SizeRecordingBufferFactory(
        private val delegate: BufferFactory = BufferFactory.Default,
    ) : BufferFactory by delegate {
        val sizes: MutableSet<Int> = ConcurrentHashMap.newKeySet()

        override fun allocate(
            size: Int,
            byteOrder: ByteOrder,
        ): PlatformBuffer {
            sizes.add(size)
            return delegate.allocate(size, byteOrder)
        }
    }

    /**
     * An explicit [IoTuning.readBufferSize] must size the JVM receive buffer, not the socket's
     * SO_RCVBUF — parity with the Linux path's `getEffectiveReadBufferSize`. Before the fix the
     * NIO/NIO2 read path always sized to SO_RCVBUF and silently ignored the override.
     */
    @Test
    fun readBufferSizeOverrideDrivesReceiveBufferAllocation() {
        // Small SO_RCVBUF, large override: their size-class roundings can't collide, so the
        // recorded allocation size unambiguously reflects which one the read path used.
        val soRcvBuf = 64 * 1024
        val overrideSize = 500_000
        val expectedRounded = 512 * 1024 // BufferSizeClass.roundUp(500_000) = next power of two

        val server = ServerSocket(0)
        val port = server.localPort
        thread(isDaemon = true, name = "tcp-echo-override") {
            try {
                val c = server.accept()
                val inp = c.getInputStream()
                val out = c.getOutputStream()
                val buf = ByteArray(64 * 1024)
                while (true) {
                    val n = inp.read(buf)
                    if (n < 0) break
                    out.write(buf, 0, n)
                    out.flush()
                }
            } catch (_: Exception) {
                // echo ends when the client disconnects
            }
        }

        val recorder = SizeRecordingBufferFactory()
        val msgSize = 128 * 1024
        val payload = BufferFactory.Default.allocate(msgSize).apply { writeBytes(ByteArray(msgSize) { 0x41 }) }

        runBlocking {
            val config =
                TransportConfig(
                    bufferFactory = recorder,
                    io = IoTuning(receiveBuffer = soRcvBuf, readBufferSize = overrideSize),
                )
            val socket = ClientSocket.connect(port, "localhost", config)
            try {
                repeat(5) {
                    payload.resetForRead()
                    socket.write(payload, 10.seconds)
                    var got = 0
                    while (got < msgSize) {
                        val r = socket.read(10.seconds)
                        assertTrue(r is ReadResult.Data, "unexpected non-data read: $r")
                        got += r.buffer.remaining()
                        (r.buffer as PlatformBuffer).freeNativeMemory()
                    }
                }
            } finally {
                socket.close()
            }
        }
        server.close()

        // Every receive-buffer allocation is sized to the override (rounded), never SO_RCVBUF.
        assertTrue(recorder.sizes.isNotEmpty(), "sanity: expected at least one receive-buffer allocation")
        assertEquals(
            setOf(expectedRounded),
            recorder.sizes,
            "receive buffers must be sized by the readBufferSize override, got sizes=${recorder.sizes}",
        )
    }
}
