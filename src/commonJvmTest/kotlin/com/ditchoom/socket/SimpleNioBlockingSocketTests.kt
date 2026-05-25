package com.ditchoom.socket

import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test

/**
 * Re-runs the [SimpleSocketTests] surface against the **blocking NIO**
 * client path (`useAsyncChannels = false`, `useNioBlocking = true`),
 * which exercises [com.ditchoom.socket.nio.BaseClientSocket] with a
 * blocking [java.nio.channels.SocketChannel] (rather than the default
 * NIO2 [java.nio.channels.AsynchronousSocketChannel]).
 *
 * The mode flags are JVM-globals. Earlier revisions toggled them in an
 * `init {}` block — but that mutation persisted across tests in the JVM
 * process and silently changed *other* tests' client paths (e.g.
 * `ExceptionConformanceTests` would start using non-blocking selector
 * reads, whose `withTimeout` produces `TimeoutCancellationException`
 * instead of the wrapped `SocketClosedException` those tests assert).
 * Save-and-restore around each test isolates the mutation to this class.
 */
class SimpleNioBlockingSocketTests {
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
    fun connectTimeoutWorks() = SimpleSocketTests().connectTimeoutWorks()

    @Test
    fun invalidHost() = SimpleSocketTests().invalidHost()

    @Test
    fun closeWorks() = SimpleSocketTests().closeWorks()

    @Test
    fun manyClientsConnectingToOneServer() = SimpleSocketTests().manyClientsConnectingToOneServer()

    @Test
    fun serverEcho() = SimpleSocketTests().serverEcho()

    @Test
    fun clientEcho() = SimpleSocketTests().clientEcho()

    @Test
    fun suspendingInputStream() = SimpleSocketTests().suspendingInputStream()
}
