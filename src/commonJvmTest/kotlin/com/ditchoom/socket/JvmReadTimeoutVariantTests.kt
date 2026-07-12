package com.ditchoom.socket

import com.ditchoom.socket.harness.ReadTimeoutContractTests
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test

/**
 * Re-runs the [ReadTimeoutContractTests] matrix against the JVM's non-default client I/O paths, so
 * the full six-implementation table of `RFC_READ_TIMEOUT_CONTRACT.md` §2 is proven, not just the
 * platform defaults.
 *
 * The base [ReadTimeoutContractTests] already exercises the JVM **NIO2** path (the `allocate()`
 * default, `useAsyncChannels = true`). These subclasses flip the process-global mode knobs — with
 * the same save/restore discipline as [SimpleNioBlockingSocketTests] — to reach the other two rows:
 *
 *  - **NIO blocking** (`useNioBlocking = true`): **Phase 3 landed** — the read now honors the
 *    deadline via the orphaned-read single-flight (`BaseClientSocket.blockingReadRaw`, RFC §3.2), so
 *    all five assertions are green (enforced *and* non-destructive; the orphaned read owns its buffer,
 *    so a timed-out read survives to be re-awaited by the next `read()`). Its Phase-1 RED was Axis 1 —
 *    the bounded read never fired, surfacing as [com.ditchoom.socket.harness.ReadOutcome.WatchdogExpired].
 *    This was the *only* place the enforcement divergence was provable, since blocking is never the default.
 *  - **NIO selector** (non-blocking): the JVM reference path — enforced, non-destructive,
 *    `SocketTimeoutException`. Expected all-green today; here to lock that it stays the reference.
 *
 * See the color table on [ReadTimeoutContractTests] for the default (NIO2) expectations.
 */
class NioBlockingReadTimeoutContractTests {
    private var savedAsync = true
    private var savedBlocking = false

    @BeforeTest
    fun overrideClientMode() {
        savedAsync = useAsyncChannels
        savedBlocking = useNioBlocking
        useAsyncChannels = false
        useNioBlocking = true
    }

    @AfterTest
    fun restoreClientMode() {
        useAsyncChannels = savedAsync
        useNioBlocking = savedBlocking
    }

    // Phase 3: the blocking path now enforces the deadline via the orphaned-read single-flight
    // (was Axis 1 RED — hung → WatchdogExpired).
    @Test
    fun boundedReadOfSilentPeerTimesOut() = ReadTimeoutContractTests().boundedReadOfSilentPeerTimesOut()

    @Test
    fun readTimeoutThrowsSocketTimeoutException() = ReadTimeoutContractTests().readTimeoutThrowsSocketTimeoutException()

    @Test
    fun connectionSurvivesReadTimeoutForReading() = ReadTimeoutContractTests().connectionSurvivesReadTimeoutForReading()

    @Test
    fun untilClosedReadOfSilentPeerDoesNotTimeOut() = ReadTimeoutContractTests().untilClosedReadOfSilentPeerDoesNotTimeOut()

    @Test
    fun connectionSurvivesReadTimeoutForWriting() = ReadTimeoutContractTests().connectionSurvivesReadTimeoutForWriting()
}

/** The JVM reference path (non-blocking selector). Expected all-green — see class KDoc above. */
class NioSelectorReadTimeoutContractTests {
    private var savedAsync = true
    private var savedBlocking = false

    @BeforeTest
    fun overrideClientMode() {
        savedAsync = useAsyncChannels
        savedBlocking = useNioBlocking
        useAsyncChannels = false
        useNioBlocking = false
    }

    @AfterTest
    fun restoreClientMode() {
        useAsyncChannels = savedAsync
        useNioBlocking = savedBlocking
    }

    @Test
    fun boundedReadOfSilentPeerTimesOut() = ReadTimeoutContractTests().boundedReadOfSilentPeerTimesOut()

    @Test
    fun readTimeoutThrowsSocketTimeoutException() = ReadTimeoutContractTests().readTimeoutThrowsSocketTimeoutException()

    @Test
    fun connectionSurvivesReadTimeoutForReading() = ReadTimeoutContractTests().connectionSurvivesReadTimeoutForReading()

    @Test
    fun untilClosedReadOfSilentPeerDoesNotTimeOut() = ReadTimeoutContractTests().untilClosedReadOfSilentPeerDoesNotTimeOut()

    @Test
    fun connectionSurvivesReadTimeoutForWriting() = ReadTimeoutContractTests().connectionSurvivesReadTimeoutForWriting()
}
