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
 * The certificate is minted off the main-like thread: [PeerCertificateSupport.Available.generate] is
 * not a suspend function, so it cannot move its key generation (which reads the OS entropy device)
 * anywhere else, and a caller on a UI thread has to.
 */
class PeerCertificateMainThreadSafetyTest {
    @Test
    fun generateAndBindWithACertificate() =
        runOnMainLike {
            val support = assertIs<PeerCertificateSupport.Available>(peerCertificates)
            withContext(Dispatchers.IO) { support.generate() }.use { certificate ->
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
