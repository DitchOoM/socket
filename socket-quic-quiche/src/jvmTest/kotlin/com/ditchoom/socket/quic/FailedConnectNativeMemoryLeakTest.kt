package com.ditchoom.socket.quic

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.ByteOrder
import com.ditchoom.buffer.CloseableBuffer
import com.ditchoom.buffer.PlatformBuffer
import com.ditchoom.buffer.deterministic
import com.ditchoom.socket.IpFamily
import com.ditchoom.socket.ResolvedAddress
import com.ditchoom.socket.TransportConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import java.util.Collections
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * Regression test for #544: a QUIC connect that fails to establish must free the two pinned sockaddr
 * encodings it made for quiche, on every exit.
 *
 * ## The property under test
 *
 * **Every native buffer a failed connect attempt allocates is freed by the time the attempt is over.**
 *
 * `buildJvmQuicConnection` encodes the peer and local sockaddrs into pinned native memory before it
 * can know whether the handshake will succeed, and frees them from the driver's `onCleanup`. That
 * covers every exit *after* the driver has started, because cancelling the connect's scope runs the
 * driver loop's `finally` → `cleanup()` → `onCleanup`. It covers nothing between the encoding and the
 * driver's start: `quiche_connect` refusing, `recv_info`/`send_info` allocation failing, the driver's
 * constructor throwing. Those exits ran the `ConnectProgress.ChannelOpen` teardown, which closed the
 * channel and freed the config and never knew the encodings existed.
 *
 * ## What is measured: the buffers the connect allocated, each asked whether it was freed
 *
 * The connect allocates every native buffer from its `TransportConfig.bufferFactory`. This test hands
 * it a [TrackingFactory] over `BufferFactory.deterministic()`, which keeps each buffer it hands out,
 * and then asks each one [CloseableBuffer.isFreed] — the buffer's own release state, set by the
 * `freeNativeMemory()` that returns its memory. A buffer the connect dropped without freeing reads
 * `false` for the life of the process, whatever else the JVM is doing.
 *
 * The test used to read the JVM's `direct` [java.lang.management.BufferPoolMXBean] count instead, and
 * that count includes memory the connect does not own. `DatagramChannel.connect` drains the socket
 * through a 100-byte heap buffer, which the JDK substitutes with a temporary direct buffer from a
 * per-thread cache (`sun.nio.ch.Util`), allocated on a thread's first use and kept until that thread
 * dies. Each attempt that connects on a worker thread which has never done so adds one direct buffer
 * the connect never allocated. Mid-suite the shared coroutine scheduler has many such workers, so the
 * count rose by 0–3 across four attempts: the release run's `before=17 after=20`.
 *
 * Every attempt must also have encoded its two sockaddrs through the tracked factory, so the check
 * cannot pass by the connect allocating them somewhere this test does not look.
 *
 * ## Two exits, one property
 *
 * - [connectRefusedByQuiche_freesThePinnedSockaddrs]: `quiche_connect` throws, so the driver never
 *   exists. Deterministic and fast; this is the exit #544 describes.
 * - [establishmentTimeout_freesThePinnedSockaddrs]: the real thing against RFC 863 discard, where the
 *   driver runs and the caller's deadline fires. The driver's cleanup frees the encodings on this exit,
 *   on the driver's dispatcher after the connect has thrown, so the check waits up to [SETTLE_BUDGET]
 *   for them.
 */
class FailedConnectNativeMemoryLeakTest {
    private val options =
        QuicOptions(
            alpnProtocols = listOf("test"),
            verifyPeer = false,
            idleTimeout = CONNECT_TIMEOUT,
        )

    /** A [QuicheApi] whose `quiche_connect` refuses, so establishment fails before a driver exists. */
    private class ConnectRefusingApi(
        delegate: QuicheApi,
    ) : QuicheApi by delegate {
        override fun connect(
            serverNameAddr: Long,
            serverNameLen: Int,
            scidAddr: Long,
            scidLen: Int,
            localAddr: Long,
            localAddrLen: Int,
            peerAddr: Long,
            peerAddrLen: Int,
            config: QuicheConfig,
        ): QuicheConn = throw IllegalStateException("quiche_connect refused (test)")
    }

    /** One buffer the connect was handed, and where it was allocated. */
    private class Allocation(
        val buffer: PlatformBuffer,
        val site: Throwable,
    ) {
        val isSockAddrEncoding: Boolean get() = site.stackTrace.any { it.methodName == SOCKADDR_ENCODER }
        val isFreed: Boolean get() = (buffer as CloseableBuffer).isFreed
    }

    /** A [BufferFactory] that keeps every buffer it hands out, so each can be asked whether it was freed. */
    private class TrackingFactory(
        private val delegate: BufferFactory,
    ) : BufferFactory {
        private val allocations: MutableList<Allocation> = Collections.synchronizedList(mutableListOf())

        fun snapshot(): List<Allocation> = synchronized(allocations) { allocations.toList() }

        override fun allocate(
            size: Int,
            byteOrder: ByteOrder,
        ): PlatformBuffer =
            delegate.allocate(size, byteOrder).also {
                allocations += Allocation(it, Throwable("allocated $size bytes"))
            }

        override fun wrap(
            array: ByteArray,
            byteOrder: ByteOrder,
        ): PlatformBuffer = delegate.wrap(array, byteOrder)
    }

    @Test
    fun connectRefusedByQuiche_freesThePinnedSockaddrs() =
        runTest(timeout = 60.seconds) {
            withContext(Dispatchers.Default) {
                val api = ConnectRefusingApi(loadQuicheApi())
                assertLeaksNothing(attempts = REFUSED_ATTEMPTS, exit = "quiche_connect refused") { transport ->
                    runCatching {
                        buildJvmQuicConnection(
                            QuicEndpoint(ResolvedAddress(RECEIVER_HOST, IpFamily.V4), RECEIVER_PORT),
                            RECEIVER_HOST,
                            options,
                            transport,
                            CONNECT_TIMEOUT,
                            api,
                        )
                    }.isSuccess
                }
            }
        }

    @Test
    fun establishmentTimeout_freesThePinnedSockaddrs() =
        runTest(timeout = 120.seconds) {
            withContext(Dispatchers.Default) {
                assertLeaksNothing(attempts = TIMEOUT_ATTEMPTS, exit = "establishment timed out") { transport ->
                    runCatching {
                        withQuicConnection(RECEIVER_HOST, RECEIVER_PORT, options, transport, CONNECT_TIMEOUT) { }
                    }.isSuccess
                }
            }
        }

    /**
     * Runs [attempt] [attempts] times through one [TrackingFactory], requiring every attempt to fail,
     * to have encoded its two sockaddrs through that factory, and to have freed everything it allocated.
     */
    private suspend fun assertLeaksNothing(
        attempts: Int,
        exit: String,
        attempt: suspend (TransportConfig) -> Boolean,
    ) {
        val factory = TrackingFactory(BufferFactory.deterministic())
        val transport = TransportConfig(bufferFactory = factory)
        var failures = 0
        repeat(attempts) { if (!attempt(transport)) failures++ }
        val unfreed = unfreedOnceSettled(factory)
        val allocations = factory.snapshot()

        assertEquals(
            attempts,
            failures,
            "every attempt must fail to establish for this measurement to mean anything — $RECEIVER is " +
                "RFC 863 discard and nothing should be listening",
        )
        assertEquals(
            SOCKADDRS_PER_ATTEMPT * attempts,
            allocations.count { it.isSockAddrEncoding },
            "each attempt must encode its peer and local sockaddrs through the connection's buffer factory, " +
                "or this test is not looking at the buffers #544 is about",
        )
        assertTrue(
            unfreed.isEmpty(),
            "a failed connect ($exit) leaks native buffers (#544): $attempts attempts left ${unfreed.size} of " +
                "${allocations.size} allocated buffers unfreed (${unfreed.count { it.isSockAddrEncoding }} of " +
                "them sockaddr encodings); the two encodings are made before the handshake can fail and only " +
                "the driver's onCleanup freed them, which this exit never reaches. A reconnecting client leaks " +
                "per attempt, forever. Allocation sites:\n" +
                unfreed.joinToString("\n") { it.siteSummary() },
        )
    }

    /** The first frames of where [this] was allocated. */
    private fun Allocation.siteSummary(): String =
        site
            .stackTraceToString()
            .lines()
            .take(SITE_FRAMES)
            .joinToString("\n")

    /**
     * The buffers [factory] handed out that are still unfreed, read once none are left or [SETTLE_BUDGET]
     * has passed: the timeout exit frees on the driver's dispatcher after the connect has already thrown.
     */
    private suspend fun unfreedOnceSettled(factory: TrackingFactory): List<Allocation> {
        val deadline = System.nanoTime() + SETTLE_BUDGET.inWholeNanoseconds
        while (true) {
            val unfreed = factory.snapshot().filterNot { it.isFreed }
            if (unfreed.isEmpty() || System.nanoTime() >= deadline) return unfreed
            delay(SETTLE_POLL)
        }
    }

    private companion object {
        const val RECEIVER_HOST = "127.0.0.1"

        /** RFC 863 discard. Nothing listens, so every handshake must time out. */
        const val RECEIVER_PORT = 9
        const val RECEIVER = "$RECEIVER_HOST:$RECEIVER_PORT"

        val CONNECT_TIMEOUT = 1.seconds

        /** The peer's and the local sockaddr, each encoded once per attempt. */
        const val SOCKADDRS_PER_ATTEMPT = 2

        /** The function that encodes a sockaddr into native memory for quiche. */
        const val SOCKADDR_ENCODER = "encodeToNative"

        const val SITE_FRAMES = 8

        const val REFUSED_ATTEMPTS = 16

        /** Slow exit: bounded by [TIMEOUT_ATTEMPTS] x [CONNECT_TIMEOUT]. */
        const val TIMEOUT_ATTEMPTS = 4

        val SETTLE_POLL = 25.milliseconds
        val SETTLE_BUDGET = 5.seconds
    }
}
