package com.ditchoom.socket.quic.core.demux

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.flow.ReadPolicy
import com.ditchoom.buffer.flow.WritePolicy
import com.ditchoom.buffer.flow.writeFully
import com.ditchoom.socket.quic.PathKey
import com.ditchoom.socket.quic.QuicCloseException
import com.ditchoom.socket.quic.QuicOptions
import com.ditchoom.socket.quic.QuicheApi
import com.ditchoom.socket.quic.ScopedRead
import com.ditchoom.socket.quic.core.conn.MismatchResponse
import com.ditchoom.socket.quic.core.edge.CoreConnection
import com.ditchoom.socket.quic.core.edge.CoreServer
import com.ditchoom.socket.quic.core.edge.coreServe
import com.ditchoom.socket.quic.core.edge.coreTestOptions
import com.ditchoom.socket.quic.core.sched.SendRule
import com.ditchoom.socket.quic.core.sched.sendRuleOfThisRuntime
import com.ditchoom.socket.quic.core.testing.platformQuicheApi
import com.ditchoom.socket.quic.core.testing.platformTlsConfig
import com.ditchoom.socket.quic.core.udp.DatagramBatchIo
import com.ditchoom.socket.quic.network
import com.ditchoom.socket.quic.read
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlin.time.Duration.Companion.seconds

/** 127.0.0.1 at [port]; port 0 lets the system choose one. */
internal fun loopbackV4(port: Int = 0): PathKey = PathKey(4, port, 0L, LOOPBACK_V4)

/** The IPv4 wildcard at [port]: a server bound to it listens on both families. */
internal fun wildcardV4(port: Int = 0): PathKey = PathKey(4, port, 0L, 0L)

/**
 * Serves on a loopback port with [config], echoing every stream of every connection, for the length of [body]; the server
 * is closed afterwards. [body] gets the server.
 */
internal suspend fun withCoreEchoServer(
    config: ServerConfig = ServerConfig(),
    bind: PathKey = loopbackV4(),
    wrapIo: (DatagramBatchIo) -> DatagramBatchIo = { it },
    api: QuicheApi = platformQuicheApi(),
    onAccepted: (CoreConnection) -> Unit = {},
    factory: BufferFactory = BufferFactory.network(),
    options: QuicOptions = coreTestOptions,
    sends: SendRule = sendRuleOfThisRuntime,
    body: suspend CoroutineScope.(CoreServer) -> Unit,
) {
    val server =
        coreServe(
            mismatch = MismatchResponse.Fail,
            bind = bind,
            tls = platformTlsConfig(),
            options = options,
            factory = factory,
            api = api,
            readPolicy = ReadPolicy.Bounded(10.seconds),
            writePolicy = WritePolicy.Bounded(10.seconds),
            config = config,
            wrapIo = wrapIo,
            sends = sends,
        )
    try {
        coroutineScope {
            // The echo only suspends: on IO every resumption could wake another of its up to 64 workers.
            val serving = launch(Dispatchers.Default) { serveEchoes(server, onAccepted) }
            body(server)
            serving.cancel()
        }
    } finally {
        server.close()
    }
}

/** Echoes every stream of every connection [server] accepts until the server stops or the caller cancels. */
private suspend fun serveEchoes(
    server: CoreServer,
    onAccepted: (CoreConnection) -> Unit,
) {
    try {
        coroutineScope {
            server.connections().collect { connection ->
                onAccepted(connection)
                launch {
                    try {
                        connection.streams().collect { stream ->
                            launch {
                                // The peer closing the connection ends the echo; it is not a failure.
                                try {
                                    while (stream.read(10.seconds) { stream.writeFully(it, 10.seconds) } is ScopedRead.Data<*>) Unit
                                    stream.close()
                                } catch (closed: QuicCloseException) {
                                    Unit
                                }
                            }
                        }
                    } catch (closed: QuicCloseException) {
                        Unit
                    }
                }
            }
        }
    } catch (stopped: QuicCloseException) {
        Unit
    } catch (stopped: CancellationException) {
        throw stopped
    }
}

private const val LOOPBACK_V4 = 0x7f000001L
