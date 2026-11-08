@file:OptIn(ExperimentalDatagramApi::class, kotlinx.cinterop.ExperimentalForeignApi::class, ExperimentalNativeApi::class)

package com.ditchoom.socket.udp

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.deterministic
import com.ditchoom.buffer.flow.ConnectedDatagramChannel
import com.ditchoom.buffer.flow.DatagramReadResult
import com.ditchoom.buffer.flow.ExperimentalDatagramApi
import com.ditchoom.buffer.flow.SocketAddress
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import platform.Network.nw_interface_type_cellular
import platform.Network.nw_interface_type_wifi
import kotlin.experimental.ExperimentalNativeApi
import kotlin.native.OsFamily
import kotlin.native.Platform
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertIsNot
import kotlin.test.assertNotNull
import kotlin.time.Duration.Companion.seconds

/**
 * A connect confined to one kind of interface rides that interface, or fails at once and typed when
 * the host has none. Which half is real depends on the host's radios, so each case finds out first and
 * says so when it cannot run.
 */
class AppleRequiredInterfaceConnectTests {
    private val factory = BufferFactory.deterministic()

    @Test
    fun eachRequirementIsNetworkFrameworksOwnInterfaceType() {
        assertEquals(nw_interface_type_wifi.toInt(), NwRequiredInterface.Wifi.nwInterfaceType)
        assertEquals(nw_interface_type_cellular.toInt(), NwRequiredInterface.Cellular.nwInterfaceType)
    }

    /**
     * NW never fails a connection whose required interface is missing: it reports `waiting` on an
     * unsatisfied path and then stays silent (measured on an iPhone 17 simulator: no further state
     * change in 10 s, until the caller cancels). So a connect that waited through `waiting` ends only
     * at the caller's timeout, and a typed [UdpConnectError.Unreachable] can only come from the
     * connect reading that first report.
     *
     * That is what this asserts, not a wall-clock bound. The 2 s bound it used to carry measured how
     * long NW took to deliver its first report, which is this process's first `nw_connection_t`: 1–5 ms
     * locally, also with the host's CPUs oversubscribed fivefold, but 6.4 s and 7.4 s on two CI runs
     * whose simulator lane was compiling other modules beside the test. The connect cannot settle
     * before NW reports, and no regression of ours shows up as a slow `Unreachable`.
     */
    @Test
    fun aConnectRequiringAnAbsentInterfaceFailsUnreachableWithoutWaitingForTheCallersTimeout() =
        runBlocking {
            val outcome = runCatching { withTimeout(CALLERS_TIMEOUT) { connect(DOC_PEER, 9, NwRequiredInterface.Cellular) } }
            outcome.getOrNull()?.let {
                it.close()
                println("SKIPPED-VACUOUS: this host has a cellular interface, so a cellular requirement is satisfiable here")
                return@runBlocking
            }
            val failure = outcome.exceptionOrNull()
            assertIsNot<TimeoutCancellationException>(failure, "the connect waited through NW's `waiting` until the caller's timeout")
            val error = assertIs<UdpConnectException>(failure).error
            assertIs<UdpConnectError.Unreachable>(error)
        }

    /**
     * A Wi-Fi-confined connect is ready on the Wi-Fi interface, sends from that interface's address, and
     * its datagrams go out. The peer is TEST-NET-1, so the datagram is dropped past the first router:
     * there is no peer on this host that a Wi-Fi-confined connection can reach on every Apple platform
     * (see [aWifiConfinedConnectToThisHostsOwnAddressRoundTripsOnMacosOnly]).
     */
    @Test
    fun aConnectRequiringWifiSendsFromTheWifiAddress() =
        runBlocking {
            val client = wifiConnectionOrNull() ?: return@runBlocking
            try {
                val local = assertNotNull(client.localAddress.orNull(), "a ready Wi-Fi connection reported no local address")
                assertFalse(local.isLoopback(), "a Wi-Fi-confined connection sends from ${local.host}, a loopback address")
                client.send(factory.allocate(4).also { it.writeInt(PAYLOAD) }.also { it.resetForRead() })
            } finally {
                client.close()
            }
        }

    /**
     * Traffic to this host's own address never leaves it, and Network.framework does not agree with
     * itself on whether such a path is Wi-Fi. A Wi-Fi-required UDP connection to the address the Wi-Fi
     * interface sends from, measured on one Mac on Wi-Fi:
     *
     *  - macOS 26: path satisfied on `en0` (the route to the own address is scoped to `en0`), `ready`,
     *    and datagrams arrive.
     *  - iOS 26.5 simulator on that same Mac: path `unsatisfied`, its only interface `lo0` (type
     *    loopback), state `waiting` — so the connect fails [UdpConnectError.Unreachable] at once.
     *
     * This test used to send to the own address on every platform and assert delivery, so it failed with
     * "no route to the peer" on every iOS simulator run whose host was on Wi-Fi. The product was right:
     * NW said there is no Wi-Fi path to that peer, and the connect reported exactly that. Each platform's
     * answer is pinned here, so a change in either shows up as a failure that names it.
     */
    @Test
    fun aWifiConfinedConnectToThisHostsOwnAddressRoundTripsOnMacosOnly() =
        runBlocking {
            val probe = wifiConnectionOrNull() ?: return@runBlocking
            val wifiAddress =
                try {
                    assertNotNull(probe.localAddress.orNull(), "a ready Wi-Fi connection reported no local address").host
                } finally {
                    probe.close()
                }
            val server = UdpSocket.bind(null, 0, bufferFactory = factory)
            try {
                val outcome =
                    runCatching { withTimeout(10.seconds) { connect(wifiAddress, server.localAddress.port, NwRequiredInterface.Wifi) } }
                if (Platform.osFamily != OsFamily.MACOSX) {
                    outcome.getOrNull()?.close()
                    val error =
                        assertIs<UdpConnectException>(
                            outcome.exceptionOrNull(),
                            "${Platform.osFamily}: expected NW to see only lo0",
                        ).error
                    assertIs<UdpConnectError.Unreachable>(error)
                    return@runBlocking
                }
                val client = outcome.getOrThrow()
                try {
                    client.send(factory.allocate(4).also { it.writeInt(PAYLOAD) }.also { it.resetForRead() })
                    val received =
                        withTimeout(5.seconds) { withContext(Dispatchers.Default) { server.receive() } }
                    val datagram = assertIs<DatagramReadResult.Received>(received).datagram
                    try {
                        assertEquals(PAYLOAD, datagram.payload.readInt())
                    } finally {
                        datagram.payload.freeNativeMemory()
                    }
                } finally {
                    client.close()
                }
            } finally {
                server.close()
            }
        }

    /** A ready Wi-Fi-confined connection to TEST-NET-1, or `null` (said out loud) when this host has no Wi-Fi route. */
    private suspend fun wifiConnectionOrNull(): ConnectedDatagramChannel? =
        runCatching { withTimeout(10.seconds) { connect(DOC_PEER, 9, NwRequiredInterface.Wifi) } }.getOrElse {
            println("SKIPPED-VACUOUS: this host has no Wi-Fi interface with a route ($it)")
            null
        }

    private fun SocketAddress.isLoopback(): Boolean = host == "::1" || host.startsWith("127.")

    private suspend fun connect(
        host: String,
        port: Int,
        requirement: NwRequiredInterface,
    ) = UdpSocket.connect(host, port, null, 0, 1500, factory, requirement)

    private companion object {
        /** Long enough that only NW's own report, never the caller's timeout, ends a correct connect. */
        val CALLERS_TIMEOUT = 20.seconds

        /**
         * RFC 5737 TEST-NET-1: never routed, so the one datagram [aConnectRequiringWifiSendsFromTheWifiAddress]
         * sends there is dropped; every other connect here sends nothing.
         */
        const val DOC_PEER = "192.0.2.1"
        const val PAYLOAD = 0x50494E44
    }
}
