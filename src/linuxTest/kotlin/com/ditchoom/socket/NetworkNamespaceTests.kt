package com.ditchoom.socket

import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.time.Duration.Companion.seconds

/**
 * Tests that run inside an isolated network namespace (`unshare --user --net`).
 *
 * In an isolated namespace, only the loopback interface exists and has no default route.
 * Connecting to TEST-NET addresses (RFC 5737) produces ENETUNREACH.
 *
 * These tests WILL FAIL if run outside the namespace (where a default route exists).
 * Run them via the `linuxNetNamespaceTest` Gradle task:
 *
 *   ./gradlew linuxNetNamespaceTest
 */
class NetworkNamespaceTests {
    @Test
    fun connectToUnroutableAddress_producesNetworkUnreachable() =
        runTestNoTimeSkipping {
            val ex =
                assertFailsWith<SocketConnectionException> {
                    val socket = ClientSocket.allocate()
                    socket.open(port = 80, timeout = 2.seconds, hostname = "192.0.2.1")
                }
            assertIs<SocketConnectionException.NetworkUnreachable>(ex)
        }

    @Test
    fun connectToTestNet2_producesNetworkUnreachable() =
        runTestNoTimeSkipping {
            val ex =
                assertFailsWith<SocketConnectionException> {
                    val socket = ClientSocket.allocate()
                    socket.open(port = 80, timeout = 2.seconds, hostname = "198.51.100.1")
                }
            assertIs<SocketConnectionException.NetworkUnreachable>(ex)
        }
}
