package com.ditchoom.socket.quic

import kotlinx.coroutines.selects.SelectBuilder
import kotlinx.coroutines.selects.onTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.time.Duration
import kotlin.time.TimeMark
import kotlin.time.TimeSource

/**
 * The [QuicheDriver]'s two couplings to wall-clock time, isolated behind one seam so a test can drive
 * both deterministically **without** giving up the real `Dispatchers.Default` I/O the reactive loop
 * relies on:
 *
 *  - [markNow] — the monotonic mark the keepalive deadline is measured from (`lastActivity`);
 *  - [armTimeout] — the `select` clause that wakes the loop when the next quiche/keepalive timer is due; and
 *  - [withBound] — the liveness backstop on one `UdpChannel.send`.
 *
 * All three must be here, not merely the two that model protocol timing. A bound written as a bare
 * `withTimeoutOrNull` resolves against whatever dispatcher the loop happens to run on, which silently
 * reintroduces the wall clock in the Tier-1 tier this seam exists to keep free of it.
 *
 * Production uses [RealDriverClock]: monotonic time and `onTimeout`. A test clock can return a
 * controllable [TimeMark] and replace the timeout with a manually-fired rendezvous, turning the
 * keepalive/idle timing path into exact, race-free assertions instead of multi-second wall-clock
 * integration tests. See `ManualDriverClock` in commonTest.
 */
interface DriverClock {
    /** A fresh mark for measuring elapsed inactivity. Production: `TimeSource.Monotonic.markNow()`. */
    fun markNow(): TimeMark

    /**
     * Register the driver's timer on [builder] — the in-progress `select<QuicheCmd?>` that also races the
     * command channel. The registered clause MUST produce `null` (the loop's "a timer fired" sentinel)
     * when [wait] is due. Production registers `onTimeout(wait) { null }`; a manual clock can instead
     * select on a rendezvous channel the test fires by hand, ignoring [wait].
     */
    fun armTimeout(
        builder: SelectBuilder<QuicheCmd?>,
        wait: Duration,
    )

    /**
     * Run [block] under a liveness bound of [wait], yielding `null` if it did not finish in time.
     *
     * Used for exactly one thing: the `UdpChannel.send` in `QuicheDriver.flushOutgoing`, whose
     * unbounded form parked the whole driver loop (and with it every timer this interface arms) when a
     * platform stopped answering. Unlike [armTimeout] this is a *backstop*, not protocol timing — it
     * should never fire on a working system — but it belongs on the same clock as everything else, or
     * a "deterministic" test still carries a hidden multi-second wall-clock timer.
     *
     * The default is production behaviour, so [RealDriverClock] needs no override, and a virtual-time
     * clock whose driver runs on the test scheduler (`driverContext = EmptyCoroutineContext`) gets
     * virtual behaviour for free — pinned by `theSendStallBoundIsDrivenByTheVirtualClock`.
     */
    suspend fun <T> withBound(
        wait: Duration,
        block: suspend () -> T,
    ): T? = withTimeoutOrNull(wait) { block() }

    /**
     * The time to push into quiche's C library **immediately before** each connection operation, so
     * quiche's own internal `Instant::now()` reads (loss/PTO/RTT/pacing/congestion) see the same clock
     * the rest of the driver does. This is the Kotlin half of the caller-clock patch (RFC §6.1): the
     * patched libquiche routes all 72 internal clock reads through a per-thread virtual clock that this
     * value drives via [QuicheApi.setThreadVirtualTimeNanos].
     *
     * Production returns [DriverTime.Real] — nothing is pushed, quiche keeps its own wall clock, and the
     * decorator that would sync it is never even installed (zero cost, zero behaviour change). A
     * Tier-A simulation clock returns [DriverTime.Virtual] carrying the current virtual-time reading so
     * quiche becomes fully caller-clocked and loss/PTO/timeout scenarios go bit-exact.
     */
    fun quicheTime(): DriverTime = DriverTime.Real
}

/**
 * Which source quiche's C library draws its randomness from for a connection — packet-number skips and
 * PATH_CHALLENGE data. Sealed for the same reason as [DriverTime].
 *
 * **Deterministic simulation only, and internal on purpose.** RFC 9000 §8.2.1 requires PATH_CHALLENGE
 * data to be unpredictable, and quiche's packet-number skips exist so a peer cannot guess them; a seeded
 * stream makes both predictable. Nothing in a published build can construct [Seeded]: only a clock that
 * implements the internal [QuicheEntropySource] — the test sources' sim clock — ever returns one.
 */
internal sealed interface QuicheEntropy {
    /** Production: quiche draws from BoringSSL's `RAND_bytes`; nothing is injected. */
    object Os : QuicheEntropy

    /**
     * Simulation: quiche's draws follow [seed], pinned through [pin] — the loaded backend's binding of the
     * caller-entropy FFI. Each connection derives its own stream from [seed] (see [CallerClockQuicheApi]),
     * so a client and a server on one seed still draw differently.
     */
    data class Seeded(
        val seed: Long,
        val pin: QuicheRandomPin,
    ) : QuicheEntropy
}

/**
 * A [DriverClock] that also decides [QuicheEntropy]. Read once, when the driver installs
 * [CallerClockQuicheApi]; a clock that does not implement it — every production clock — leaves quiche on
 * BoringSSL. Without a seeded source a simulation is not a function of its seed: a random packet-number
 * skip changes the peer's ACK ranges, and with them the size of every ACK that follows.
 */
internal interface QuicheEntropySource {
    fun quicheEntropy(): QuicheEntropy
}

/**
 * The caller-entropy FFI (`patchQuicheForCallerEntropy`): pin the calling thread's seeded random stream in
 * libquiche, and release it. Implemented by each real backend's binding; reached only through
 * [QuicheEntropy.Seeded].
 */
internal interface QuicheRandomPin {
    fun setThreadRandomState(state: Long)

    fun clearThreadRandomState()
}

/**
 * Which clock quiche's C library should read for a connection operation. A sealed choice, not a nullable
 * `Long?`, so "use the real wall clock" (production) and "use this exact virtual instant" (sim) are two
 * distinct, exhaustively-handled cases — never an overloaded sentinel.
 */
sealed interface DriverTime {
    /** Production: quiche keeps its own internal `Instant::now()`; nothing is injected. */
    object Real : DriverTime

    /**
     * Simulation: quiche's internal clock is pinned to [nanos] — a monotonic reading in nanoseconds
     * measured from libquiche's fixed per-process anchor (absolute value is irrelevant; quiche only ever
     * subtracts two readings). Pushed via [QuicheApi.setThreadVirtualTimeNanos] on the same thread and in
     * the same synchronous frame as the quiche call, so no thread hop or virtual-time advance can slip
     * between the push and the read.
     */
    data class Virtual(
        val nanos: Long,
    ) : DriverTime
}

/** Production clock: monotonic time + coroutine `onTimeout`. Behaviour-identical to the pre-seam loop. */
object RealDriverClock : DriverClock {
    override fun markNow(): TimeMark = TimeSource.Monotonic.markNow()

    override fun armTimeout(
        builder: SelectBuilder<QuicheCmd?>,
        wait: Duration,
    ) {
        with(builder) { onTimeout(wait) { null } }
    }

    override fun quicheTime(): DriverTime = DriverTime.Real
}
