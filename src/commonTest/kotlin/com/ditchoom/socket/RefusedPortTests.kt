package com.ditchoom.socket

import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/** The refused-connect target is one no listener on the host can hold. */
class RefusedPortTests {
    @Test
    fun theRefusedPortIsOutsideEveryEphemeralRange() {
        assertTrue(
            REFUSED_PORT in 1 until FIRST_NON_RESERVED_PORT,
            "port $REFUSED_PORT can be handed to an ephemeral listener, so a 'refused' connect can reach some test's server",
        )
    }

    @Test
    fun noEphemeralListenerIsHandedTheRefusedPort() =
        runTestNoTimeSkipping {
            val servers = List(LISTENERS) { ServerSocket.allocate() }
            try {
                for (server in servers) {
                    server.bind()
                    assertTrue(server.port() >= FIRST_NON_RESERVED_PORT, "an ephemeral listener was handed port ${server.port()}")
                }
            } finally {
                servers.forEach { it.close() }
            }
        }

    @Test
    fun aConnectToTheRefusedPortFailsFast() =
        runTestNoTimeSkipping {
            if (!networkCapabilities().transports.contains(TransportKind.TCP)) return@runTestNoTimeSkipping
            if (isWindowsJvm()) return@runTestNoTimeSkipping // NIO2 holds a refused connect past the budget
            val outcome =
                runCatching {
                    ClientSocket.allocate(TransportConfig(connectTimeout = 2.seconds)).also {
                        it.open(port = REFUSED_PORT, hostname = "127.0.0.1")
                    }
                }
            outcome.getOrNull()?.close()
            assertTrue(outcome.exceptionOrNull() is SocketException, "a connect to 127.0.0.1:$REFUSED_PORT gave $outcome")
        }

    private companion object {
        const val LISTENERS = 8
    }
}
