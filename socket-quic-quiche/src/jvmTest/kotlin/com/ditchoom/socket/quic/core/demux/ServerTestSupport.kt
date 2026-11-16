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
import com.ditchoom.socket.quic.core.udp.toPathKey
import com.ditchoom.socket.quic.loadQuicheApi
import com.ditchoom.socket.quic.read
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import org.junit.rules.TestRule
import org.junit.runner.Description
import org.junit.runners.model.Statement
import java.net.InetSocketAddress
import kotlin.time.Duration
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
            bind = bind.toPathKey(),
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

/** The live threads running a server's loop. */
private fun serverLoopThreads(): Set<Thread> =
    Thread
        .getAllStackTraces()
        .keys
        .filter { it.name == SERVER_LOOP_THREAD && it.isAlive }
        .toSet()

/**
 * Waits for the server loop threads that are running, which end moments after a close returns (the close is told from
 * the loop's own thread), and returns how many are still alive after [patience]: those never end.
 */
internal fun liveServerLoops(patience: Duration = 5.seconds): Int {
    val running = serverLoopThreads()
    running.forEach { it.join(patience.inWholeMilliseconds) }
    return running.count { it.isAlive }
}

/** Fails the test that leaves a server loop running, rather than a later test that counts loops. */
internal class NoServerLoopLeft : TestRule {
    override fun apply(
        base: Statement,
        description: Description,
    ): Statement =
        object : Statement() {
            override fun evaluate() {
                val before = serverLoopThreads()
                try {
                    base.evaluate()
                } catch (failed: Throwable) {
                    leaked(before)?.let(failed::addSuppressed)
                    throw failed
                }
                leaked(before)?.let { throw it }
            }

            private fun leaked(before: Set<Thread>): AssertionError? {
                val started = serverLoopThreads() - before
                started.forEach { it.join(LOOP_ENDS_WITHIN.inWholeMilliseconds) }
                val alive = started.filter { it.isAlive }
                return if (alive.isEmpty()) null else AssertionError("${description.displayName} left ${alive.size} server loop(s) running")
            }
        }

    private companion object {
        val LOOP_ENDS_WITHIN = 5.seconds
    }
}
