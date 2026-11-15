package com.ditchoom.socket.quic.core.demux

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.Default
import com.ditchoom.buffer.flow.ReadPolicy
import com.ditchoom.buffer.flow.WritePolicy
import com.ditchoom.buffer.flow.writeFully
import com.ditchoom.socket.quic.QuicCloseException
import com.ditchoom.socket.quic.QuicheApi
import com.ditchoom.socket.quic.ScopedRead
import com.ditchoom.socket.quic.core.edge.CoreConnection
import com.ditchoom.socket.quic.core.edge.CoreServer
import com.ditchoom.socket.quic.core.edge.coreServe
import com.ditchoom.socket.quic.core.edge.coreTestOptions
import com.ditchoom.socket.quic.core.edge.coreTlsConfig
import com.ditchoom.socket.quic.core.udp.DatagramBatchIo
import com.ditchoom.socket.quic.loadQuicheApi
import com.ditchoom.socket.quic.read
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import java.net.InetSocketAddress
import kotlin.time.Duration.Companion.seconds

/**
 * Serves on a loopback port with [config], echoing every stream of every connection, for the length of [body]; the server
 * is closed afterwards. [body] gets the server.
 */
internal suspend fun withCoreEchoServer(
    config: ServerConfig = ServerConfig(),
    bind: InetSocketAddress = InetSocketAddress("127.0.0.1", 0),
    wrapIo: (DatagramBatchIo) -> DatagramBatchIo = { it },
    api: QuicheApi = loadQuicheApi(),
    onAccepted: (CoreConnection) -> Unit = {},
    factory: BufferFactory = BufferFactory.Default,
    body: suspend CoroutineScope.(CoreServer) -> Unit,
) {
    val server =
        coreServe(
            bind = bind,
            tls = coreTlsConfig(),
            options = coreTestOptions,
            factory = factory,
            api = api,
            readPolicy = ReadPolicy.Bounded(10.seconds),
            writePolicy = WritePolicy.Bounded(10.seconds),
            config = config,
            wrapIo = wrapIo,
        )
    try {
        coroutineScope {
            val serving =
                launch(Dispatchers.IO) {
                    try {
                        server.connections().collect { connection ->
                            onAccepted(connection)
                            launch {
                                try {
                                    connection.streams().collect { stream ->
                                        launch {
                                            // The peer closing the connection ends the echo; it is not a failure.
                                            try {
                                                while (stream.read(
                                                        10.seconds,
                                                    ) { stream.writeFully(it, 10.seconds) } is ScopedRead.Data<*>
                                                ) {
                                                    Unit
                                                }
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
                    } catch (stopped: QuicCloseException) {
                        Unit
                    } catch (stopped: CancellationException) {
                        throw stopped
                    }
                }
            body(server)
            serving.cancel()
        }
    } finally {
        server.close()
    }
}

/** What the server's own loop thread is called. */
internal const val SERVER_LOOP_THREAD = com.ditchoom.socket.quic.core.edge.SERVER_THREAD
