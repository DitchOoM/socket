package com.ditchoom.socket.quic.core.edge

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.flow.ReadPolicy
import com.ditchoom.buffer.flow.WritePolicy
import com.ditchoom.socket.quic.EarlyDataServerTestSuite
import com.ditchoom.socket.quic.HandshakeHold
import com.ditchoom.socket.quic.HandshakeHoldApi
import com.ditchoom.socket.quic.HeldServer
import com.ditchoom.socket.quic.QuicConnectionState
import com.ditchoom.socket.quic.QuicError
import com.ditchoom.socket.quic.QuicOptions
import com.ditchoom.socket.quic.QuicScope
import com.ditchoom.socket.quic.QuicTlsConfig
import com.ditchoom.socket.quic.core.conn.MismatchResponse
import com.ditchoom.socket.quic.core.demux.loopbackV4
import com.ditchoom.socket.quic.core.testing.platformQuicheApi
import com.ditchoom.socket.quic.core.testing.platformTlsConfig
import com.ditchoom.socket.quic.network
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlin.time.Duration.Companion.seconds

/**
 * [EarlyDataServerTestSuite] against the run-to-completion core's server: the handler starts when the client's 0-RTT is
 * accepted, an early stream's reads wait for the handshake unless it is marked replay-safe, and a handshake that fails
 * after the handler started ends that handler typed while the server serves the next connection. The client is the
 * production one; the hold drops Handshake datagrams at the server's quiche.
 */
class CoreEarlyDataServerTests : EarlyDataServerTestSuite() {
    override fun testTlsConfig(): QuicTlsConfig = platformTlsConfig()

    internal override suspend fun heldServer(options: QuicOptions): HeldServer {
        val api = HandshakeHoldApi(platformQuicheApi())
        val server =
            coreServe(
                mismatch = MismatchResponse.Fail,
                bind = loopbackV4(),
                tls = testTlsConfig(),
                options = options,
                factory = BufferFactory.network(),
                api = api,
                readPolicy = ReadPolicy.Bounded(DEADLINE),
                writePolicy = WritePolicy.Bounded(DEADLINE),
            )
        return CoreHeldServer(server, api)
    }

    private class CoreHeldServer(
        private val server: CoreServer,
        override val hold: HandshakeHold,
    ) : HeldServer {
        override val port: Int get() = server.port

        /** Each connection's handler runs on its own; the connection closes when its handler returns or fails. */
        override fun serve(
            scope: CoroutineScope,
            handler: suspend QuicScope.() -> Unit,
        ): Job =
            scope.launch {
                val serving = this
                server.connections().collect { connection ->
                    serving.launch {
                        try {
                            connection.handler()
                        } catch (ended: Exception) {
                            // The suite asserts on what its handler saw; the handler's end is the connection's.
                        } finally {
                            connection.closeNow(QuicError.NoError)
                        }
                    }
                }
            }

        override fun stateOf(connection: QuicScope): QuicConnectionState =
            when (connection) {
                is CoreConnection -> connection.state.value
                else -> error("not a core connection: $connection")
            }

        override suspend fun close() = server.close()
    }

    private companion object {
        val DEADLINE = 20.seconds
    }
}
