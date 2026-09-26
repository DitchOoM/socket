package com.ditchoom.socket.quic

import com.ditchoom.socket.IpFamily
import com.ditchoom.socket.NetworkMonitor
import com.ditchoom.socket.ResolvedAddress
import com.ditchoom.socket.TransportConfig
import com.ditchoom.socket.testkit.skip.SkipGate
import com.ditchoom.socket.testkit.skip.SkipReason
import com.ditchoom.socket.testkit.skip.recordSkip
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assume.assumeTrue
import java.net.InetSocketAddress
import java.nio.channels.DatagramChannel
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ThreadFactory
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/**
 * **A QUIC connection's deadlines fire when kotlinx's shared timer thread cannot.**
 *
 * kotlinx.coroutines fires every timer armed on a dispatcher without one of its own (`Dispatchers.Default`,
 * `Dispatchers.IO`, `runBlocking`'s event loop) on `DefaultExecutor`, a thread it starts lazily, stops
 * after a second of idleness and starts again on the next timer. It records the new thread before
 * starting it, so a start that fails under native-memory or thread exhaustion leaves it holding a thread
 * that never runs, and from then on no such timer fires in the process: the memory soak that found this
 * hung with every coroutine parked and no `DefaultExecutor` thread alive.
 *
 * The suite takes that thread away without exhausting anything: [SharedTimerLost] parks it inside a
 * timer callback, so nothing queued on it fires until the test gives it back. The first test proves that
 * adversary bites — a plain `withTimeoutOrNull` on `Dispatchers.Default` does not end while it holds —
 * so the others cannot pass by the adversary doing nothing. Those drive a real quiche connect at a UDP
 * socket that never answers, which only a deadline can end, and require that deadline to end it, typed.
 *
 * The library's own timer thread is made unstartable through [DeadlineTimer]'s thread factory
 * ([UnstartableThreads]): it throws the `OutOfMemoryError` the JVM throws when it cannot create a native
 * thread, inside the executor's worker start, so [DeadlineTimer.start] catches exactly what exhaustion
 * throws and a connect or bind must refuse with [DeadlineTimerUnavailableException] instead of opening
 * anything. That works on every OS. [ThreadStartFailure.StackNoPlatformReserves] makes the JVM's own
 * thread creation fail instead, which proves the injected failure is the real one's shape; Windows
 * starts that thread anyway, so there it is a recorded skip.
 *
 * Each body runs under [failIfWedged], a JVM-thread bound, because a coroutine bound would be one of the
 * timers that stopped.
 */
class DeadlinesSurviveALostTimerThreadTests {
    @Test
    fun losingTheSharedTimerThreadStopsAKotlinxDeadlineOnADispatcherWithoutItsOwnTimer() {
        val outcome = CompletableFuture<Any?>()
        SharedTimerLost().use {
            CoroutineScope(Dispatchers.Default).launch {
                outcome.complete(withTimeoutOrNull(SHORT) { awaitCancellation() })
            }
            assertFailsWith<TimeoutException>(
                "a $SHORT withTimeoutOrNull on Dispatchers.Default ended while kotlinx's shared timer thread was " +
                    "parked, so this suite's adversary does not take the timer away and proves nothing",
            ) { outcome.get(ADVERSARY_PROBE.inWholeMilliseconds, TimeUnit.MILLISECONDS) }
        }
        assertNull(
            outcome.get(WATCHDOG.inWholeMilliseconds, TimeUnit.MILLISECONDS),
            "the parked deadline must fire once the shared timer thread is given back",
        )
    }

    @Test
    fun theIdleTimerEndsAHandshakeThePeerNeverAnswers() =
        connectToASilentPeer("the idle-timer connect", idleTimeout = SHORT, establishmentBound = 5.minutes) { failure ->
            assertEquals(
                QuicError.IdleTimeout,
                failure.quicError,
                "the driver's own timer must end the handshake: $failure",
            )
        }

    @Test
    fun theEstablishmentBoundEndsAHandshakeThePeerNeverAnswers() =
        connectToASilentPeer("the bounded connect", idleTimeout = 5.minutes, establishmentBound = SHORT) { failure ->
            assertEquals(
                QuicCloseReason.ByLocal(QuicError.HandshakeTimeout(SHORT)),
                failure.closeReason,
                "the caller's establishment bound must end the handshake: $failure",
            )
        }

    @Test
    fun aConnectWhoseTimerThreadCannotStartIsRefusedTyped() =
        failIfWedged(WATCHDOG, "the connect with an unstartable timer") {
            skipOnMissingNativeLib(DeadlinesSurviveALostTimerThreadTests::class) {
                DatagramChannel.open().use { silent ->
                    silent.bind(InetSocketAddress(LOOPBACK, 0))
                    val refused =
                        assertFailsWith<DeadlineTimerUnavailableException> {
                            buildJvmQuicConnection(
                                QuicEndpoint(ResolvedAddress(LOOPBACK, IpFamily.V4), silent.localPort()),
                                LOOPBACK,
                                options(idleTimeout = 5.minutes),
                                TransportConfig(),
                                5.minutes,
                                loadQuicheApi(),
                                QuicheDriverTuning(driverContext = DeadlineTimer(UnstartableThreads()).over(Dispatchers.Default)),
                            )
                        }
                    assertIs<OutOfMemoryError>(refused.cause, "the refusal must carry what the thread start threw")
                }
            }
        }

    @Test
    fun aBindWhoseTimerThreadCannotStartIsRefusedTyped() =
        failIfWedged(WATCHDOG, "the bind with an unstartable timer") {
            skipOnMissingNativeLib(DeadlinesSurviveALostTimerThreadTests::class) {
                val refused =
                    assertFailsWith<DeadlineTimerUnavailableException> {
                        buildJvmQuicServer(
                            QuicPortBinding.Own(port = 0, host = LOOPBACK),
                            QuicTlsConfig(certChainPath = certPath("cert.crt"), privKeyPath = certPath("cert.key")),
                            options(idleTimeout = 5.minutes),
                            QuicheDriverTuning(driverContext = DeadlineTimer(UnstartableThreads()).over(Dispatchers.Default)),
                        )
                    }
                assertIs<OutOfMemoryError>(refused.cause, "the refusal must carry what the thread start threw")
            }
        }

    @Test
    fun aTimerWhoseThreadFailedToStartStartsOnTheNextAttempt() = failToStartThenStart(ThreadStartFailure.NativeThreadRefused)

    @Test
    fun aTimerWhoseThreadTheJvmCouldNotCreateStartsOnTheNextAttempt() {
        if (isWindows()) {
            recordSkip(
                DeadlinesSurviveALostTimerThreadTests::class,
                SkipReason.HostBehaviourDiffers(
                    "Windows starts a thread whose requested stack no platform can reserve, so Thread.start " +
                        "does not fail; the injected-failure tests cover DeadlineTimer's handling here",
                ),
                SkipGate.HostCannotProvideIt("a JVM thread start that fails on an unreservable stack"),
            )
            assumeTrue("Windows starts a thread with an unreservable stack", false)
        }
        failToStartThenStart(ThreadStartFailure.StackNoPlatformReserves)
    }

    /** One start of a [DeadlineTimer]'s thread fails with [failure]; the next [DeadlineTimer.start] must start it. */
    private fun failToStartThenStart(failure: ThreadStartFailure) {
        val threads = UnstartableThreads(failure, failures = 1)
        val timer = DeadlineTimer(threads)
        val refused = assertFailsWith<DeadlineTimerUnavailableException> { timer.start() }
        assertIs<OutOfMemoryError>(refused.cause, "the refusal must carry what the thread start threw")
        timer.start()
        val fired = CountDownLatch(1)
        timer.schedule(SHORT.inWholeMilliseconds) { fired.countDown() }
        assertEquals(
            true,
            fired.await(WATCHDOG.inWholeMilliseconds, TimeUnit.MILLISECONDS),
            "a timer whose first thread start failed must not be left without a thread for good",
        )
        assertEquals(2, threads.attempts.get(), "the second start must be a new thread, and the only other one")
    }

    @Test
    fun aDelayInTheProductionDriverContextFiresWhileTheSharedTimerThreadIsLost() =
        SharedTimerLost().use {
            failIfWedged(WATCHDOG, "a delay in the production driver context", productionDriverContext) {
                delay(SHORT)
            }
        }

    /**
     * Connects at a bound UDP socket that reads nothing — no ICMP, no reply, no handshake — with
     * kotlinx's shared timer thread lost for the whole attempt, and hands [verdict] the failure.
     */
    private fun connectToASilentPeer(
        label: String,
        idleTimeout: Duration,
        establishmentBound: Duration,
        verdict: (QuicCloseException) -> Unit,
    ) = SharedTimerLost().use {
        failIfWedged(WATCHDOG, label) {
            skipOnMissingNativeLib(DeadlinesSurviveALostTimerThreadTests::class) {
                DatagramChannel.open().use { silent ->
                    silent.bind(InetSocketAddress(LOOPBACK, 0))
                    val failure =
                        assertFailsWith<QuicCloseException> {
                            QuicheEngine.connect(
                                QuicClientBinding.OwnSocket,
                                QuicEndpoint(ResolvedAddress(LOOPBACK, IpFamily.V4), silent.localPort()),
                                LOOPBACK,
                                options(idleTimeout),
                                TransportConfig(),
                                establishmentBound,
                            )
                        }
                    verdict(failure)
                }
            }
        }
    }

    private fun options(idleTimeout: Duration) =
        QuicOptions(
            alpnProtocols = listOf("test"),
            verifyPeer = false,
            idleTimeout = idleTimeout,
            migration = MigrationPolicy.Forbidden,
            networkMonitor = NetworkMonitorSource.Supplied(NetworkMonitor.AlwaysAvailable),
        )

    private fun isWindows(): Boolean = System.getProperty("os.name").startsWith("Windows", ignoreCase = true)

    private fun DatagramChannel.localPort(): Int = (localAddress as InetSocketAddress).port

    private fun certPath(name: String): String {
        val url =
            this::class.java.classLoader.getResource("certs/$name")
                ?: error("Test cert not found: certs/$name")
        return java.io.File(url.toURI()).absolutePath
    }

    /**
     * Holds kotlinx's shared timer thread inside a timer callback until [close], so no timer queued on it
     * fires meanwhile. Fails construction unless the thread it holds is that thread.
     */
    private class SharedTimerLost : AutoCloseable {
        private val giveBack = CountDownLatch(1)

        init {
            val held = CompletableFuture<Thread>()
            // Unconfined: the delay resumes on the thread that fired it, which then blocks in giveBack.
            CoroutineScope(Dispatchers.Unconfined).launch {
                delay(1)
                held.complete(Thread.currentThread())
                giveBack.await()
            }
            val thread = held.get(WATCHDOG.inWholeMilliseconds, TimeUnit.MILLISECONDS)
            if (!thread.name.startsWith(KOTLINX_SHARED_TIMER_THREAD)) {
                giveBack.countDown()
                throw AssertionError("expected to hold $KOTLINX_SHARED_TIMER_THREAD, held ${thread.name}")
            }
        }

        override fun close() = giveBack.countDown()
    }

    /** How an [UnstartableThreads] thread fails to start. */
    private sealed interface ThreadStartFailure {
        /**
         * The factory throws what the JVM throws when the OS refuses a native thread. It is thrown inside
         * the executor's worker start, which backs the worker out as it does for a failed `Thread.start`.
         * Works on every OS.
         */
        data object NativeThreadRefused : ThreadStartFailure

        /**
         * The thread asks for a stack no platform reserves, so the JVM's own native thread creation fails
         * in `Thread.start`. POSIX refuses it; Windows clamps the size and starts the thread.
         */
        data object StackNoPlatformReserves : ThreadStartFailure
    }

    /** Threads that fail to start as [failure] says for the first [failures] attempts, then start. */
    private class UnstartableThreads(
        private val failure: ThreadStartFailure = ThreadStartFailure.NativeThreadRefused,
        private val failures: Int = Int.MAX_VALUE,
    ) : ThreadFactory {
        val attempts = AtomicInteger()

        override fun newThread(task: Runnable): Thread =
            if (attempts.incrementAndGet() > failures) {
                Thread(task, "quic-deadline-timer (test)").apply { isDaemon = true }
            } else {
                when (failure) {
                    ThreadStartFailure.NativeThreadRefused -> throw OutOfMemoryError(NATIVE_THREAD_REFUSED)
                    ThreadStartFailure.StackNoPlatformReserves ->
                        Thread(null, task, "quic-deadline-timer (unstartable)", UNRESERVABLE_STACK_BYTES).apply { isDaemon = true }
                }
            }
    }

    private companion object {
        const val LOOPBACK = "127.0.0.1"

        /** HotSpot's message when the OS refuses a native thread. */
        const val NATIVE_THREAD_REFUSED =
            "unable to create native thread: possibly out of memory or process/resource limits reached"

        /** An exbibyte: more address space than any POSIX platform will reserve for one thread's stack. */
        const val UNRESERVABLE_STACK_BYTES = 1L shl 60

        /** The deadline under test: short, so a working one ends each case in well under a second. */
        val SHORT = 300.milliseconds

        /** How long the adversary check waits to see that a deadline did not fire. */
        val ADVERSARY_PROBE = 2.seconds

        /** The JVM-thread bound on each case: a working deadline ends it ~50x sooner. */
        val WATCHDOG = 15.seconds
    }
}
