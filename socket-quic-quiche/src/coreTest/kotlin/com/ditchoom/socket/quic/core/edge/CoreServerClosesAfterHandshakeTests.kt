package com.ditchoom.socket.quic.core.edge

import com.ditchoom.socket.IpFamily
import com.ditchoom.socket.ResolvedAddress
import com.ditchoom.socket.TransportConfig
import com.ditchoom.socket.quic.PeerCertificate
import com.ditchoom.socket.quic.QuicConnect
import com.ditchoom.socket.quic.QuicConnection
import com.ditchoom.socket.quic.QuicConnectionEnd
import com.ditchoom.socket.quic.QuicConnectionLife
import com.ditchoom.socket.quic.QuicEndpoint
import com.ditchoom.socket.quic.QuicError
import com.ditchoom.socket.quic.QuicFullHandshakeReason
import com.ditchoom.socket.quic.QuicListener
import com.ditchoom.socket.quic.QuicOptions
import com.ditchoom.socket.quic.QuicPeer
import com.ditchoom.socket.quic.QuicPeerLeaf
import com.ditchoom.socket.quic.QuicResumptionOutcome
import com.ditchoom.socket.quic.QuicServe
import com.ditchoom.socket.quic.ServerCertVerdict
import com.ditchoom.socket.quic.ServerCertVerifier
import com.ditchoom.socket.quic.connectQuicOnCore
import com.ditchoom.socket.quic.core.testing.coreTest
import com.ditchoom.socket.quic.serveQuicOnCore
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * A server whose handler returns the moment it is handed the connection, through the public entry points on every
 * platform: the client's connect returns the connection, already ended or about to be, and what the handshake settled
 * stays readable after the end: how it used a ticket, and the leaf the server presented. The server's chain is checked
 * from the copy the handshake took, so a check still running when the server's close lands reads the whole chain.
 */
class CoreServerClosesAfterHandshakeTests {
    @Test
    fun theConnectReturnsTheConnectionAndItsHandshakeFactsOutliveTheServersClose() =
        coreTest("CoreServerClosesAfterHandshakeTests.theConnectReturnsTheConnectionAndItsHandshakeFactsOutliveTheServersClose") {
            mint().use { served ->
                closingAtOnce(served) { listener, _ ->
                    val pinned = options.copy(serverCertificateHashes = listOf(served.hash))
                    val client = connected(connect(listener.port, pinned))
                    val end = withTimeout(WAIT) { client.awaitEnd() }
                    assertEquals<QuicConnectionEnd>(QuicConnectionEnd.ByPeer(QuicError.NoError), end, "the handler's return closed it gracefully")
                    assertIs<QuicConnectionLife.Ended>(client.life())
                    assertEquals(QuicResumptionOutcome.FullHandshake(QuicFullHandshakeReason.NoTicketOffered), client.handshake.resumption)
                    assertEquals("test", client.handshake.negotiatedAlpn)
                    assertEquals(served.hash, assertIs<QuicPeerLeaf.Presented>(client.handshake.peerLeaf).sha256)
                }
            }
        }

    @Test
    fun aCheckStillRunningWhenTheServersCloseLandsReadsTheWholeChain() =
        coreTest("CoreServerClosesAfterHandshakeTests.aCheckStillRunningWhenTheServersCloseLandsReadsTheWholeChain") {
            mint().use { served ->
                closingAtOnce(served) { listener, returned ->
                    var read = ByteArray(0)
                    var chainLength = 0
                    // The check answers only once the server's handler returned and its CONNECTION_CLOSE had time to land.
                    val checking =
                        ServerCertVerifier { chain ->
                            returned.await()
                            delay(CLOSE_LANDS)
                            read = bytesOf(chain.leaf)
                            chainLength = chain.certificates.size
                            ServerCertVerdict.Trusted
                        }
                    val client = connected(connect(listener.port, options.copy(serverCertVerifiers = listOf(checking))))
                    assertIs<QuicConnectionLife.Ended>(client.life(), "the server's close landed while it was being checked")
                    assertContentEquals(bytesOf(served.der()), read, "the leaf the check read after the close")
                    assertEquals(1, chainLength, "a minted certificate is its own chain")
                    assertEquals(served.hash, assertIs<QuicPeerLeaf.Presented>(client.handshake.peerLeaf).sha256)
                }
            }
        }

    /** Serves [served] with a handler that returns at once; [body] gets the listener and when the handler returned. */
    private suspend fun closingAtOnce(
        served: PeerCertificate,
        body: suspend (QuicListener, CompletableDeferred<Unit>) -> Unit,
    ) {
        val returned = CompletableDeferred<Unit>()
        val outcome =
            served.withPemFiles { tls ->
                serveQuicOnCore(0, LOOPBACK, tls, options) { listener ->
                    coroutineScope {
                        val serving = launch { listener.handleConnections { returned.complete(Unit) } }
                        try {
                            body(listener, returned)
                        } finally {
                            serving.cancel()
                        }
                    }
                }
            }
        assertIs<QuicServe.Serving<Unit>>(outcome)
    }

    private suspend fun connect(
        port: Int,
        options: QuicOptions,
    ): QuicConnect<QuicConnection> =
        connectQuicOnCore(
            QuicPeer.Candidates(listOf(QuicEndpoint(ResolvedAddress(LOOPBACK, IpFamily.V4), port)), "localhost"),
            options,
            TransportConfig(),
            WAIT,
        )

    private fun connected(connect: QuicConnect<QuicConnection>): QuicConnection =
        assertIs<QuicConnect.Connected<QuicConnection>>(connect, "a server that closed right after its handshake: $connect").value

    private companion object {
        const val LOOPBACK = "127.0.0.1"
        val WAIT = 10.seconds
        val CLOSE_LANDS = 300.milliseconds
        val options = QuicOptions(alpnProtocols = listOf("test"), verifyPeer = false, idleTimeout = 10.seconds)
    }
}
