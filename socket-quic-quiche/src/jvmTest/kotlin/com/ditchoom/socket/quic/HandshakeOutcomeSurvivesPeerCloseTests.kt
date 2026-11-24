package com.ditchoom.socket.quic

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.Charset
import com.ditchoom.buffer.deterministic
import com.ditchoom.buffer.flow.writeFully
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.seconds

/**
 * **A block that outlives its connection's close can still read how the handshake went.**
 *
 * [QuicScope.resumption] and [QuicScope.negotiatedAlpn] promise to be readable inside a block, and a
 * block outlives a peer's close while a reply is unread. They used to be read from the published state,
 * which is `Closed` by then, so a server that replied and returned at once made a client's read of
 * `resumption` throw `resumption is only available while the connection is established`.
 *
 * Nothing here relies on timing: the block waits for `Closed` before it reads.
 */
class HandshakeOutcomeSurvivesPeerCloseTests {
    private fun certPath(name: String): String =
        java.io
            .File(
                this::class.java.classLoader
                    .getResource("certs/$name")!!
                    .toURI(),
            ).absolutePath

    private val options =
        QuicOptions(
            alpnProtocols = listOf("outcome-after-close"),
            verifyPeer = false,
            idleTimeout = 10.seconds,
            migration = MigrationPolicy.Manual,
        )

    private fun text(payload: String) =
        BufferFactory.deterministic().allocate(payload.length).also {
            it.writeString(payload, Charset.UTF8)
            it.resetForRead()
        }

    @Test
    fun aBlockReadsTheHandshakeOutcomeAfterTheServerRepliedAndClosed() =
        runBlocking {
            skipOnMissingNativeLib(HandshakeOutcomeSurvivesPeerCloseTests::class) {
                val tls = QuicTlsConfig(certChainPath = certPath("cert.crt"), privKeyPath = certPath("cert.key"))
                withQuicServer(port = 0, tlsConfig = tls, quicOptions = options) {
                    val serving =
                        launch {
                            connections {
                                val stream = acceptStream()
                                stream.read(5.seconds) { it.readString(it.remaining(), Charset.UTF8) }
                                stream.writeFully(text("reply"), 5.seconds)
                                stream.shutdownSend()
                            }
                        }
                    try {
                        val seen =
                            withQuicConnection("127.0.0.1", port, options, timeout = 10.seconds) {
                                val stream = openStream()
                                stream.writeFully(text("hello"), 5.seconds)
                                stream.shutdownSend()
                                // The reply is unread, so the block outlives the server's close.
                                val connection = this as QuicScope
                                withTimeout(10.seconds) { connection.state.first { it is QuicConnectionState.Closed } }
                                resumption to negotiatedAlpn
                            }
                        assertEquals(
                            QuicResumptionOutcome.FullHandshake(QuicFullHandshakeReason.NoTicketOffered) to "outcome-after-close",
                            seen,
                        )
                    } finally {
                        serving.cancel()
                    }
                }
            }
        }
}
