package com.ditchoom.socket.quic

import com.ditchoom.socket.transport.NetworkKind
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame

/**
 * What [MigrationPolicy.Pinned] means below the public API: every socket opened through the pin, and
 * the peer told this endpoint will not move.
 */
class PinnedRadioPolicyTests {
    @Test
    fun aPinnedConnectionOpensEverySocketThroughItsRadiosPin() =
        runTest {
            for (radio in listOf(NetworkKind.Wifi, NetworkKind.Cellular)) {
                val asked = mutableListOf<NetworkKind.Radio>()
                val pin = ConnectedUdpOpener { _, _, _, _, _, _ -> error("never opened here") }
                val opener =
                    MigrationPolicy.Pinned(radio).pathOpener {
                        asked += it
                        pin
                    }
                assertSame(pin, opener)
                assertEquals(listOf<NetworkKind.Radio>(radio), asked)
            }
        }

    @Test
    fun anUnpinnedConnectionOpensThroughThePlatformAndAsksForNoRadio() =
        runTest {
            for (policy in listOf(MigrationPolicy.Forbidden, MigrationPolicy.Manual, MigrationPolicy.Automatic)) {
                val opener = policy.pathOpener { error("$policy asked for a radio") }
                assertSame(ConnectedUdpOpener.Platform, opener, "$policy")
            }
        }

    @Test
    fun aPinnedConnectionAdvertisesDisableActiveMigration() {
        assertEquals(true, disableActiveMigrationUnder(MigrationPolicy.Pinned(NetworkKind.Wifi)))
        assertEquals(true, disableActiveMigrationUnder(MigrationPolicy.Forbidden))
        assertEquals(false, disableActiveMigrationUnder(MigrationPolicy.Automatic))
    }

    private fun disableActiveMigrationUnder(policy: MigrationPolicy): Boolean {
        val written = mutableListOf<Boolean>()
        val calls =
            object : QuicConfigCalls by SimQuicConfigCalls(StubQuicheApi(), QuicheConfig(1L)) {
                override fun setDisableActiveMigration(v: Boolean) {
                    written += v
                }
            }
        applyQuicOptions(QuicOptions(alpnProtocols = listOf("test"), migration = policy), calls, QuicRole.Client)
        return written.single()
    }
}
