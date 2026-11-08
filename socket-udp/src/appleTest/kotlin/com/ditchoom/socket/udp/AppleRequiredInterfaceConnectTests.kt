@file:OptIn(ExperimentalDatagramApi::class, kotlinx.cinterop.ExperimentalForeignApi::class)

package com.ditchoom.socket.udp

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.deterministic
import com.ditchoom.buffer.flow.DatagramReadResult
import com.ditchoom.buffer.flow.ExperimentalDatagramApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import platform.Network.nw_interface_type_cellular
import platform.Network.nw_interface_type_wifi
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertIsNot
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

    @Test
    fun aConnectRequiringWifiCarriesDatagramsOverWifi() =
        runBlocking {
            val wifiAddress = wifiAddressOrNull() ?: return@runBlocking
            val server = UdpSocket.bind(null, 0, bufferFactory = factory)
            try {
                // The host's own Wi-Fi address: loopback is never a Wi-Fi interface, so NW would refuse it.
                val client = withTimeout(10.seconds) { connect(wifiAddress, server.localAddress.port, NwRequiredInterface.Wifi) }
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

    /** The address this host's Wi-Fi interface sends from, or `null` (said out loud) when it has none. */
    private suspend fun wifiAddressOrNull(): String? {
        val probe =
            runCatching { withTimeout(10.seconds) { connect(DOC_PEER, 9, NwRequiredInterface.Wifi) } }.getOrElse {
                println("SKIPPED-VACUOUS: this host has no Wi-Fi interface with a route ($it)")
                return null
            }
        try {
            return probe.localAddress.orNull()?.host ?: error("a ready Wi-Fi connection reported no local address")
        } finally {
            probe.close()
        }
    }

    private suspend fun connect(
        host: String,
        port: Int,
        requirement: NwRequiredInterface,
    ) = UdpSocket.connect(host, port, null, 0, 1500, factory, requirement)

    private companion object {
        /** Long enough that only NW's own report, never the caller's timeout, ends a correct connect. */
        val CALLERS_TIMEOUT = 20.seconds

        /** RFC 5737 TEST-NET-1: a connected UDP socket sends nothing, so it is never contacted. */
        const val DOC_PEER = "192.0.2.1"
        const val PAYLOAD = 0x50494E44
    }
}
