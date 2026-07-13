package com.ditchoom.socket

import com.ditchoom.socket.harness.WriteTimeoutContractTests
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test

/**
 * Re-runs the [WriteTimeoutContractTests] matrix against the JVM's non-default client I/O paths, so the
 * full write contract is proven on every JVM write path, not just the [allocate] default (NIO2).
 *
 * The base [WriteTimeoutContractTests] already exercises the JVM **NIO2** path (`useAsyncChannels = true`).
 * These subclasses flip the process-global mode knobs — with the same save/restore discipline as
 * [SimpleNioBlockingSocketTests] — to reach the other two rows:
 *
 *  - **NIO blocking** (`useNioBlocking = true`): the write path historically **dropped the deadline**
 *    (`SocketChannelExtensions.write` ignores `timeout` on the blocking branch), so a `Bounded` write to a
 *    non-draining peer hung — surfacing as [com.ditchoom.socket.harness.WriteOutcome.WatchdogExpired] on the
 *    §2/§3/§4 assertions. This is the only place that enforcement gap is provable, since blocking is never
 *    the default.
 *  - **NIO selector** (non-blocking): the selector write path enforces via `OP_WRITE` readiness; here to
 *    lock that it stays conformant (enforced + [com.ditchoom.socket.SocketTimeoutException] + destructive).
 *
 * See the class KDoc on [WriteTimeoutContractTests] for the default (NIO2) expectations.
 */
class NioBlockingWriteTimeoutContractTests {
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

    @Test
    fun untilClosedWriteToNonDrainingPeerSuspends() = WriteTimeoutContractTests().untilClosedWriteToNonDrainingPeerSuspends()

    @Test
    fun boundedWriteToNonDrainingPeerTimesOut() = WriteTimeoutContractTests().boundedWriteToNonDrainingPeerTimesOut()

    @Test
    fun boundedWriteTimeoutThrowsSocketTimeoutException() = WriteTimeoutContractTests().boundedWriteTimeoutThrowsSocketTimeoutException()

    @Test
    fun boundedWriteTimeoutClosesConnection() = WriteTimeoutContractTests().boundedWriteTimeoutClosesConnection()
}

/** The JVM selector (non-blocking) write path. Expected enforced + destructive — see class KDoc above. */
class NioSelectorWriteTimeoutContractTests {
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
    fun untilClosedWriteToNonDrainingPeerSuspends() = WriteTimeoutContractTests().untilClosedWriteToNonDrainingPeerSuspends()

    @Test
    fun boundedWriteToNonDrainingPeerTimesOut() = WriteTimeoutContractTests().boundedWriteToNonDrainingPeerTimesOut()

    @Test
    fun boundedWriteTimeoutThrowsSocketTimeoutException() = WriteTimeoutContractTests().boundedWriteTimeoutThrowsSocketTimeoutException()

    @Test
    fun boundedWriteTimeoutClosesConnection() = WriteTimeoutContractTests().boundedWriteTimeoutClosesConnection()
}
