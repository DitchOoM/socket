@file:OptIn(com.ditchoom.buffer.flow.ExperimentalDatagramApi::class)

package com.ditchoom.socket.quic

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.Charset
import com.ditchoom.buffer.deterministic
import com.ditchoom.socket.TransportConfig
import com.ditchoom.socket.transport.NetworkKind
import com.ditchoom.socket.udp.NwRequiredInterface
import com.ditchoom.socket.udp.UdpConnectError
import com.ditchoom.socket.udp.UdpConnectException
import com.ditchoom.socket.udp.UdpSocket
import com.ditchoom.socket.udp.connect
import kotlinx.coroutines.launch
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

/**
 * A QUIC connection pinned to a radio rides it, or fails at once and typed where the host has none.
 * Which half is real depends on the host's radios — a Mac usually has Wi-Fi and never cellular — so
 * each case finds out first and says so when it cannot run.
 */
class ApplePinnedRadioTests {
    private val opts = QuicOptions(alpnProtocols = listOf("test"), verifyPeer = false, idleTimeout = 10.seconds)

    @Test
    fun eachRadioRequiresItsOwnInterfaceType() {
        assertEquals(NwRequiredInterface.Wifi, NetworkKind.Wifi.nwRequirement)
        assertEquals(NwRequiredInterface.Cellular, NetworkKind.Cellular.nwRequirement)
        assertEquals(setOf<NetworkKind.Radio>(NetworkKind.Wifi, NetworkKind.Cellular), QuicheEngine.capabilities.pinnableRadios)
    }

    @Test
    fun aConnectPinnedToAnAbsentRadioFailsTypedAtOnce() =
        runQuicTest {
            if (addressOn(NwRequiredInterface.Cellular) != null) {
                println("SKIPPED-VACUOUS: this host has a cellular interface, so a cellular pin is satisfiable here")
                return@runQuicTest
            }
            val start = TimeSource.Monotonic.markNow()
            val failure =
                assertFailsWith<PinnedRadioUnavailableException> {
                    QuicheEngine.connect(
                        QuicClientBinding.OwnSocket,
                        DOC_PEER,
                        443,
                        opts.copy(migration = MigrationPolicy.Pinned(NetworkKind.Cellular)),
                        TransportConfig(),
                        15.seconds,
                    )
                }
            val elapsed = start.elapsedNow()
            assertEquals(NetworkKind.Cellular, failure.radio)
            val noPath = assertIs<PinnedRadioUnavailable.NoPath>(failure.reason)
            assertIs<UdpConnectError.Unreachable>(assertIs<UdpConnectException>(noPath.cause).error)
            assertTrue(elapsed < 2.seconds, "an absent radio must fail the connect at once, took $elapsed")
        }

    @Test
    fun aConnectPinnedToWifiCarriesAStreamAndRefusesToMigrate() =
        runQuicTest {
            // Loopback is never a Wi-Fi interface, so the server is dialled at this host's own Wi-Fi address.
            val wifiAddress =
                addressOn(NwRequiredInterface.Wifi) ?: run {
                    println("SKIPPED-VACUOUS: this host has no Wi-Fi interface with a route")
                    return@runQuicTest
                }
            val tls = QuicTlsConfig(certChainPath = AppleTestCerts.path("cert.crt"), privKeyPath = AppleTestCerts.path("cert.key"))
            val server = QuicheEngine.bind(QuicPortBinding.Own(port = 0), tlsConfig = tls, quicOptions = opts, timeout = 15.seconds)
            try {
                val serverJob =
                    launch {
                        server.connections {
                            val stream = acceptStream()
                            stream.read(5.seconds) { stream.write(it, 5.seconds) }
                            stream.close()
                        }
                    }
                val pinned = opts.copy(migration = MigrationPolicy.Pinned(NetworkKind.Wifi))
                val conn =
                    QuicheEngine.connect(
                        QuicClientBinding.OwnSocket,
                        wifiAddress,
                        server.port,
                        pinned,
                        TransportConfig(),
                        15.seconds,
                    )
                try {
                    val stream = conn.openStream()
                    val sendBuf = BufferFactory.deterministic().allocate(6)
                    sendBuf.writeString("pinned", Charset.UTF8)
                    sendBuf.resetForRead()
                    stream.write(sendBuf, 5.seconds)
                    val response = stream.read(5.seconds) { it.readString(it.remaining(), Charset.UTF8) }
                    assertEquals("pinned", assertIs<ScopedRead.Data<String>>(response).value)
                    stream.close()
                    assertEquals(MigrationResult.Unmoved.Impossible.PolicyForbids, conn.migrate(MigrationTarget.FreshLocalEndpoint))
                } finally {
                    conn.close()
                }
                serverJob.cancel()
            } finally {
                server.close()
            }
        }

    /** The address this host sends from on an interface of [kind], or `null` when it has none. */
    private suspend fun addressOn(kind: NwRequiredInterface): String? {
        val probe =
            try {
                UdpSocket.connect(DOC_PEER, 9, null, 0, 1500, BufferFactory.deterministic(), kind)
            } catch (e: UdpConnectException) {
                return null
            }
        try {
            return probe.localAddress.orNull()?.host ?: error("a ready connection reported no local address")
        } finally {
            probe.close()
        }
    }

    private companion object {
        /** RFC 5737 TEST-NET-1: a connected UDP socket sends nothing, so it is never contacted. */
        const val DOC_PEER = "192.0.2.1"
    }
}
