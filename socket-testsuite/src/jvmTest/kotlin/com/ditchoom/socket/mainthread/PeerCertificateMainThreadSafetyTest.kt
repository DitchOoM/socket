package com.ditchoom.socket.mainthread

import com.ditchoom.socket.mainthread.QuicFixtures.echoRoundTrip
import com.ditchoom.socket.mainthread.QuicFixtures.serveEcho
import com.ditchoom.socket.quic.PeerCertificateSupport
import com.ditchoom.socket.quic.peerCertificates
import com.ditchoom.socket.quic.withQuicConnection
import com.ditchoom.socket.quic.withQuicServer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.test.Test
import kotlin.test.assertIs
import kotlin.time.Duration.Companion.seconds

/**
 * The WebTransport peer role from the main-like thread: bind a QUIC server that presents a generated
 * certificate, which writes it to PEM files for the TLS stack to read.
 *
 * The certificate is minted on the main-like thread too: [PeerCertificateSupport.Available.mint] moves
 * its key generation (which reads the OS entropy device) to an IO dispatcher itself.
 */
class PeerCertificateMainThreadSafetyTest {
    @Test
    fun mintAndBindWithACertificate() =
        runOnMainLike {
            val support = assertIs<PeerCertificateSupport.Available>(peerCertificates)
            support.mint().use { certificate ->
                assertOnMainLike("PeerCertificateSupport.Available.mint")
                withQuicServer(port = 0, certificate = certificate, quicOptions = QuicFixtures.options) {
                    assertOnMainLike("withQuicServer(certificate)")
                    val echo = launch { serveEcho() }
                    try {
                        withContext(Dispatchers.IO) {
                            withQuicConnection("localhost", port, QuicFixtures.options, timeout = 10.seconds) {
                                echoRoundTrip(checkThread = false)
                            }
                        }
                    } finally {
                        echo.cancel()
                    }
                }
            }
        }
}

/**
 * The control for [PeerCertificateMainThreadSafetyTest]: the deprecated, non-suspend
 * [PeerCertificateSupport.Available.generate] does block the main-like thread. Without this, a `mint`
 * that passed only because nothing in key generation registers as blocking would prove nothing. Its own
 * class, so its JVM is fresh.
 */
class DeprecatedPeerCertificateGenerateMainThreadSafetyControlTest {
    @Test
    fun generateOnTheMainLikeThreadIsCaught() {
        val support = assertIs<PeerCertificateSupport.Available>(peerCertificates)
        assertBlocksTheMainLikeThread {
            @Suppress("DEPRECATION")
            support.generate().close()
        }
    }
}
