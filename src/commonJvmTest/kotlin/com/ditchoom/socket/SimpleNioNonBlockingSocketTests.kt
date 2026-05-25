package com.ditchoom.socket

import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test

/**
 * Re-runs the [SimpleSocketTests] surface against the **non-blocking NIO**
 * client path (`useAsyncChannels = false`, `useNioBlocking = false`),
 * which exercises [com.ditchoom.socket.nio.BaseClientSocket] with a
 * selector-driven non-blocking [java.nio.channels.SocketChannel].
 *
 * See [SimpleNioBlockingSocketTests] for the rationale behind the
 * save/restore around each test — these mode flags are JVM-process
 * globals and leaking them silently changes other tests' I/O paths.
 */
class SimpleNioNonBlockingSocketTests {
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
