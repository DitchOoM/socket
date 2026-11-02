@file:OptIn(ExperimentalDatagramApi::class, kotlinx.cinterop.ExperimentalForeignApi::class)

package com.ditchoom.socket.udp

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.deterministic
import com.ditchoom.buffer.flow.DatagramReadResult
import com.ditchoom.buffer.flow.ExperimentalDatagramApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import platform.Network.nw_interface_type_cellular
import platform.Network.nw_interface_type_wifi
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

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

    @Test
    fun aConnectRequiringAnAbsentInterfaceFailsUnreachableAtOnce() =
        runBlocking {
            val start = TimeSource.Monotonic.markNow()
            val outcome = runCatching { withTimeout(10.seconds) { connect(DOC_PEER, 9, NwRequiredInterface.Cellular) } }
            outcome.getOrNull()?.let {
                it.close()
                println("SKIPPED-VACUOUS: this host has a cellular interface, so a cellular requirement is satisfiable here")
                return@runBlocking
            }
            val error = assertIs<UdpConnectException>(outcome.exceptionOrNull()).error
            assertIs<UdpConnectError.Unreachable>(error)
            val elapsed = start.elapsedNow()
            assertTrue(elapsed < 2.seconds, "an unsatisfiable requirement must fail at once, took $elapsed")
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
        /** RFC 5737 TEST-NET-1: a connected UDP socket sends nothing, so it is never contacted. */
        const val DOC_PEER = "192.0.2.1"
        const val PAYLOAD = 0x50494E44
    }
}
