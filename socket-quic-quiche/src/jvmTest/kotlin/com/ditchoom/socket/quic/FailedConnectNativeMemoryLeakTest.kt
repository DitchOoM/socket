package com.ditchoom.socket.quic

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.deterministic
import com.ditchoom.socket.TransportConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import java.lang.management.BufferPoolMXBean
import java.lang.management.ManagementFactory
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
 * **A connect attempt that throws returns the process to the direct-buffer count it started with.**
 *
 * `buildJvmQuicConnection` encodes the peer and local sockaddrs into pinned native memory before it
 * can know whether the handshake will succeed, and frees them from the driver's `onCleanup`. That
 * covers every exit *after* the driver has started, because cancelling the connect's scope runs the
 * driver loop's `finally` → `cleanup()` → `onCleanup`. It covers nothing between the encoding and the
 * driver's start: `quiche_connect` refusing, `recv_info`/`send_info` allocation failing, the driver's
 * constructor throwing. Those exits ran the `ConnectProgress.ChannelOpen` teardown, which closed the
 * channel and freed the config and never knew the encodings existed.
 *
 * ## Why this is measured in direct buffers and not in calls
 *
 * The encodings are `BufferFactory.deterministic()` direct `ByteBuffer`s, and `freeNativeMemory()`
 * on one invokes its `Cleaner`, which is the JVM's own `Bits.unreserveMemory` — so the platform
 * `BufferPoolMXBean` named `direct` counts exactly what is still pinned. A delegate counting `free()`
 * calls would report what it was given; a buffer the JVM still accounts for cannot be faked by a
 * counter (the same argument `FailedConnectFdLeakTest` makes for descriptors, #465).
 *
 * ## Two exits, one property
 *
 * - [connectRefusedByQuiche_freesThePinnedSockaddrs]: `quiche_connect` throws, so the driver never
 *   exists. Deterministic and fast; this is the exit #544 describes, and the one that was red.
 * - [establishmentTimeout_freesThePinnedSockaddrs]: the real thing against RFC 863 discard, where the
 *   driver runs and the caller's deadline fires. #544 named this as the dominant way to leak; it is
 *   asserted here so the claim is measured rather than repeated — the driver's cleanup frees the
 *   encodings on this exit, and the test says so by passing before any fix.
 *
 * Each case takes its baseline after one warm-up attempt (the quiche singleton's load, pools and
 * dispatcher threads are paid for first) and settles until the count has been stable for
 * [SETTLE_QUIET] before reading it, because the timeout exit frees asynchronously on the driver's
 * dispatcher.
 */
class FailedConnectNativeMemoryLeakTest {
    private val options =
        QuicOptions(
            alpnProtocols = listOf("test"),
            verifyPeer = false,
            idleTimeout = CONNECT_TIMEOUT,
        )
    private val transport = TransportConfig(bufferFactory = BufferFactory.deterministic())

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

    @Test
    fun connectRefusedByQuiche_freesThePinnedSockaddrs() =
        runTest(timeout = 60.seconds) {
            withContext(Dispatchers.Default) {
                val api = ConnectRefusingApi(loadQuicheApi())
                assertLeaksNothing(attempts = REFUSED_ATTEMPTS, exit = "quiche_connect refused") {
                    runCatching {
                        buildJvmQuicConnection(RECEIVER_HOST, RECEIVER_PORT, options, transport, CONNECT_TIMEOUT, api)
                    }.isSuccess
                }
            }
        }

    @Test
    fun establishmentTimeout_freesThePinnedSockaddrs() =
        runTest(timeout = 120.seconds) {
            withContext(Dispatchers.Default) {
                assertLeaksNothing(attempts = TIMEOUT_ATTEMPTS, exit = "establishment timed out") {
                    runCatching {
                        withQuicConnection(RECEIVER_HOST, RECEIVER_PORT, options, transport, CONNECT_TIMEOUT) { }
                    }.isSuccess
                }
            }
        }

    /**
     * Runs [attempt] once as warm-up, then [attempts] times between two settled readings of the
     * direct-buffer count, requiring every attempt to fail and the count to come back to where it was.
     */
    private suspend fun assertLeaksNothing(
        attempts: Int,
        exit: String,
        attempt: suspend () -> Boolean,
    ) {
        attempt()
        val before = settledDirectBufferCount()
        var failures = 0
        repeat(attempts) { if (!attempt()) failures++ }
        val after = settledDirectBufferCount()

        assertEquals(
            attempts,
            failures,
            "every attempt must fail to establish for this measurement to mean anything — $RECEIVER is " +
                "RFC 863 discard and nothing should be listening",
        )
        val leaked = after - before
        assertTrue(
            leaked <= MAX_TOLERATED_BUFFERS,
            "a failed connect ($exit) leaks its pinned sockaddr encodings (#544): $attempts attempts left " +
                "$leaked direct buffers behind (before=$before after=$after), " +
                "${"%.1f".format(leaked.toDouble() / attempts)} per attempt; the two encodings are made " +
                "before the handshake can fail and only the driver's onCleanup freed them, which this " +
                "exit never reaches. A reconnecting client leaks per attempt, forever.",
        )
    }

    /** The JVM's own count of live direct buffers, read once it has held still for [SETTLE_QUIET]. */
    private suspend fun settledDirectBufferCount(): Long {
        val deadline = System.nanoTime() + SETTLE_BUDGET.inWholeNanoseconds
        var last = directBufferCount()
        var quietSince = System.nanoTime()
        while (System.nanoTime() < deadline) {
            delay(SETTLE_POLL)
            val now = directBufferCount()
            if (now != last) {
                last = now
                quietSince = System.nanoTime()
            } else if (System.nanoTime() - quietSince >= SETTLE_QUIET.inWholeNanoseconds) {
                return now
            }
        }
        return last
    }

    private fun directBufferCount(): Long = directPool.count

    private companion object {
        val directPool: BufferPoolMXBean =
            ManagementFactory.getPlatformMXBeans(BufferPoolMXBean::class.java).first { it.name == "direct" }

        const val RECEIVER_HOST = "127.0.0.1"

        /** RFC 863 discard. Nothing listens, so every handshake must time out. */
        const val RECEIVER_PORT = 9
        const val RECEIVER = "$RECEIVER_HOST:$RECEIVER_PORT"

        val CONNECT_TIMEOUT = 1.seconds

        /** Fast exit: enough attempts that the pre-fix leak (2 buffers each) is unmistakable. */
        const val REFUSED_ATTEMPTS = 16

        /** Slow exit: bounded by [TIMEOUT_ATTEMPTS] x [CONNECT_TIMEOUT]. */
        const val TIMEOUT_ATTEMPTS = 4

        /**
         * Post-fix the delta is 0 on both exits. A small tolerance absorbs an unrelated direct buffer
         * the JVM may create in the window (an NIO temporary, a lazily-opened channel) without coming
         * near the 2-per-attempt the defect produces.
         */
        const val MAX_TOLERATED_BUFFERS = 2

        val SETTLE_POLL = 25.milliseconds
        val SETTLE_QUIET = 300.milliseconds
        val SETTLE_BUDGET = 5.seconds
    }
}
