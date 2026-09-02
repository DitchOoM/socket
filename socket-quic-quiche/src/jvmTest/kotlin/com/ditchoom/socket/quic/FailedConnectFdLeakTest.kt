package com.ditchoom.socket.quic

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.deterministic
import com.ditchoom.socket.TransportConfig
import com.ditchoom.socket.testkit.skip.SkipGate
import com.ditchoom.socket.testkit.skip.SkipReason
import com.ditchoom.socket.testkit.skip.recordSkip
import com.sun.management.UnixOperatingSystemMXBean
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import java.lang.management.ManagementFactory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/**
 * Regression test for #465: a QUIC connect that fails to establish must not leak its UDP socket.
 *
 * ## The property under test
 *
 * **A connect attempt that throws returns the process to the descriptor count it started with.**
 *
 * `withQuicConnection` opens a connected `:socket-udp` channel before it can know whether the
 * handshake will succeed. On success the connection owns that channel and closes it from
 * `JvmQuicConnection.onRelease`. On failure `close()` is never reached, so the establishment-failure
 * branch has to release it — and before #465 it did not, freeing the quiche config and cancelling the
 * scope while leaving the channel open. `QuicheDriver.cleanup()` does not cover it either, and
 * deliberately so: it tears down non-primary migration path channels but the primary one belongs to
 * the connection, not the driver.
 *
 * ## Why this is measured in descriptors and not in calls
 *
 * The instrument has to distinguish "we called something that looks like cleanup" from "the operating
 * system got the descriptor back". A delegate counting `close()` calls would have passed against the
 * defect for the same reason #397's counting delegate did — the failure path simply never reached the
 * call, and a counter reports what it is given. Descriptors returned to the process cannot be faked by
 * a stub, a counter or a mock, which is what makes this an end-to-end assertion rather than a
 * self-consistent one.
 *
 * Measured before the fix, byte-identical across three runs:
 * ```
 * [FDPROBE] before=76 after=108 delta=32 over 8 failed connects   -> exactly 4.0 per attempt
 * ```
 * Four rather than one because the leaked object is a whole connected channel: the `DatagramChannel`
 * plus its NIO `Selector` (kqueue descriptor and the wakeup pipe pair).
 *
 * ## Deterministic, and not merely "usually zero"
 *
 * The descriptors are eventually reclaimed by a GC `Cleaner`, never by a code path — which is exactly
 * why this went unnoticed and why the test must not depend on collection. It does not: it measures a
 * bounded window of [ATTEMPTS] attempts with no allocation pressure between the two readings, so a
 * `Cleaner` has no reason to run inside it. [WARMUP_ATTEMPTS] runs first so that any lazily-created
 * infrastructure — the quiche singleton's dlopen, pools, dispatcher threads — is already paid for
 * before the baseline is taken and cannot be mistaken for a leak.
 *
 * ## Fails for the right reason
 *
 * `127.0.0.1:9` is RFC 863 discard: nothing listens, so every attempt must time out. The test asserts
 * that all [ATTEMPTS] actually threw. Without that, a machine where something happened to be bound to
 * port 9 would establish connections, close them cleanly, leak nothing, and report a meaningless pass.
 *
 * ## Unix only, and it says so
 *
 * The instrument is [UnixOperatingSystemMXBean.getOpenFileDescriptorCount], which the JVM implements
 * only where descriptors are the OS's own currency. On Windows the MXBean is not that type and the
 * cast throws `ClassCastException` — a broken test, not a finding about the code — so the platform is
 * reported as a typed skip instead.
 *
 * This surfaced the first time the Windows lane ever ran (#515). That lane's Gradle step is gated on
 * a `quiche_jni.dll` which had never been produced on any run, so the step had never executed and
 * this test had never met a non-Unix host.
 */
class FailedConnectFdLeakTest {
    @Test
    fun aConnectThatFailsToEstablishReturnsItsFileDescriptors() =
        runTest(timeout = 120.seconds) {
            val osBean = ManagementFactory.getOperatingSystemMXBean()
            if (osBean !is UnixOperatingSystemMXBean) {
                // HostCannotProvideIt, not a lane fault: no setting installs POSIX descriptor
                // accounting on Windows, so gating on it would only make that lane permanently red.
                recordSkip(
                    FailedConnectFdLeakTest::class,
                    SkipReason.HostBehaviourDiffers(
                        "this host's OperatingSystemMXBean is ${osBean::class.java.name}, not a " +
                            "UnixOperatingSystemMXBean, so open-descriptor counts are unavailable and the " +
                            "leak this test measures cannot be observed here",
                    ),
                    SkipGate.HostCannotProvideIt(
                        "UnixOperatingSystemMXBean.openFileDescriptorCount (POSIX descriptor accounting)",
                    ),
                )
                return@runTest
            }
            withContext(Dispatchers.Default) {
                val options =
                    QuicOptions(
                        alpnProtocols = listOf("test"),
                        verifyPeer = false,
                        idleTimeout = CONNECT_TIMEOUT,
                    )
                val transport = TransportConfig(bufferFactory = BufferFactory.deterministic())

                repeat(WARMUP_ATTEMPTS) { attemptConnect(options, transport) }

                val before = openFileDescriptors()
                var failures = 0
                repeat(ATTEMPTS) { if (!attemptConnect(options, transport)) failures++ }
                val after = openFileDescriptors()

                assertEquals(
                    ATTEMPTS,
                    failures,
                    "every attempt must fail to establish for this measurement to mean anything — " +
                        "$RECEIVER is RFC 863 discard and nothing should be listening. If something " +
                        "is bound there these connections established and closed cleanly, and a pass " +
                        "would prove nothing about the failure path.",
                )

                val leaked = after - before
                assertTrue(
                    leaked <= MAX_TOLERATED_DESCRIPTORS,
                    "a failed connect leaks its UDP socket (#465): $ATTEMPTS attempts that all failed " +
                        "to establish left $leaked file descriptors behind " +
                        "(before=$before after=$after), which is " +
                        "${"%.1f".format(leaked.toDouble() / ATTEMPTS)} per attempt; at most " +
                        "$MAX_TOLERATED_DESCRIPTORS in total is expected. withQuicConnection opens a " +
                        "connected UDP channel before it can know the handshake will fail, and only " +
                        "JvmQuicConnection.onRelease closes it — which a failed establishment never " +
                        "reaches. A client retrying against a server that is down exhausts its " +
                        "descriptor limit.",
                )
            }
        }

    /** Returns `true` if the connection established, `false` if it failed — which is the expected case. */
    private suspend fun attemptConnect(
        options: QuicOptions,
        transport: TransportConfig,
    ): Boolean =
        runCatching {
            withQuicConnection(RECEIVER_HOST, RECEIVER_PORT, options, transport, CONNECT_TIMEOUT) { }
        }.isSuccess

    /** Only reached past the [UnixOperatingSystemMXBean] check the test opens with. */
    private fun openFileDescriptors(): Long =
        (ManagementFactory.getOperatingSystemMXBean() as UnixOperatingSystemMXBean).openFileDescriptorCount

    private companion object {
        const val RECEIVER_HOST = "127.0.0.1"

        /** RFC 863 discard. Nothing listens, so every handshake must time out. */
        const val RECEIVER_PORT = 9
        const val RECEIVER = "$RECEIVER_HOST:$RECEIVER_PORT"

        val CONNECT_TIMEOUT = 1.seconds

        /** Pays for the quiche dlopen, pools and dispatcher threads before the baseline is taken. */
        const val WARMUP_ATTEMPTS = 1

        /**
         * Enough attempts that the pre-fix leak (4 descriptors each, so 32) is an order of magnitude
         * clear of the tolerance, while keeping the test to roughly [ATTEMPTS] x [CONNECT_TIMEOUT].
         */
        const val ATTEMPTS = 8

        /**
         * Post-fix the measured delta is 0. A small non-zero tolerance absorbs an unrelated descriptor
         * the JVM may open during the window (a lazily-opened jar, a JIT log) without coming anywhere
         * near the 32 the defect produced.
         */
        const val MAX_TOLERATED_DESCRIPTORS = 2
    }
}
