package com.ditchoom.socket.quic

import com.ditchoom.socket.IpFamily
import com.ditchoom.socket.ResolvedAddress
import com.ditchoom.socket.TransportConfig
import com.ditchoom.socket.transport.NetworkKind
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/** A desktop JVM cannot pin a socket to a radio, says so, and refuses a pinned connect typed. */
class JvmPinnedRadioRefusalTests {
    @Test
    fun theDesktopEngineAdvertisesNoPinnableRadio() {
        assertTrue(QuicheEngine.capabilities.pinnableRadios.isEmpty())
    }

    @Test
    fun aPinnedConnectIsRefusedBeforeASocketOpens() =
        runBlocking {
            for (radio in listOf(NetworkKind.Wifi, NetworkKind.Cellular)) {
                val refused =
                    assertFailsWith<PinnedRadioRefusedException> {
                        QuicheEngine.connect(
                            QuicClientBinding.OwnSocket,
                            QuicEndpoint(ResolvedAddress("127.0.0.1", IpFamily.V4), 9),
                            "localhost",
                            QuicOptions(alpnProtocols = listOf("test"), migration = MigrationPolicy.Pinned(radio)),
                            TransportConfig(),
                            5.seconds,
                        )
                    }
                assertEquals(radio, refused.radio)
                assertEquals(PinnedRadioRefusal.EngineCannotPin, refused.refusal)
            }
        }
}
