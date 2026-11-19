package com.ditchoom.socket.quic.core.edge

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.flow.ReadPolicy
import com.ditchoom.buffer.flow.WritePolicy
import com.ditchoom.socket.quic.QuicError
import com.ditchoom.socket.quic.RouteSource
import com.ditchoom.socket.quic.UdpSocketChannelFactory
import com.ditchoom.socket.quic.core.conn.MismatchResponse
import com.ditchoom.socket.quic.core.demux.withCoreEchoServer
import com.ditchoom.socket.quic.core.loop.LoopSet
import com.ditchoom.socket.quic.core.testing.platformQuicheApi
import com.ditchoom.socket.quic.network
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.seconds

/**
 * Where a connect's setup runs: off the caller's thread, and one connect at a time on a shared setup lane, so a herd of
 * connects occupies one dispatcher worker and not one each.
 */
class CoreSetupLaneTests {
    private val loops = LoopSet(2)

    @AfterTest
    fun closeLoops() = loops.close()

    @Test
    fun concurrentConnectsRunTheirSetupOneAtATime() =
        runBlocking {
            withCoreEchoServer { server ->
                val inSetup = AtomicInteger()
                val most = AtomicInteger()
                val clients =
                    List(CONNECTS) {
                        async(Dispatchers.Default) {
                            connectWith(server.port) { route ->
                                most.accumulateAndGet(inSetup.incrementAndGet(), ::maxOf)
                                // Long enough that setups which may overlap do.
                                Thread.sleep(SETUP_MILLIS)
                                inSetup.decrementAndGet()
                                route.routeSourceAddress()
                            }
                        }
                    }.awaitAll()
                clients.forEach { it.connection.close(QuicError.NoError) }
                assertEquals(1, most.get(), "connect setups that ran at once, of $CONNECTS concurrent connects")
            }
        }

    @Test
    fun aCallerOnAMainDispatcherIsNeverBlockedByTheSetupOfItsConnects() =
        runBlocking {
            withCoreEchoServer { server ->
                val mainThreads = ConcurrentLinkedQueue<Thread>()
                val main = Executors.newSingleThreadExecutor { Thread(it, "core-main").also(mainThreads::add) }
                val onMain = ConcurrentLinkedQueue<String>()
                try {
                    val clients =
                        withContext(main.asCoroutineDispatcher()) {
                            List(MAIN_CONNECTS) {
                                async {
                                    connectWith(server.port) { route ->
                                        if (Thread.currentThread() in mainThreads) onMain.add("route probe")
                                        // The main thread must be free to run other work while a setup blocks.
                                        val ran = CompletableFuture<Unit>()
                                        main.execute { ran.complete(Unit) }
                                        ran.get(MAIN_FREE_SECONDS, TimeUnit.SECONDS)
                                        route.routeSourceAddress()
                                    }
                                }
                            }.awaitAll()
                        }
                    clients.forEach { it.connection.close(QuicError.NoError) }
                } finally {
                    main.shutdownNow()
                }
                assertEquals(emptyList(), onMain.toList(), "setup that ran on the main thread")
            }
        }

    private suspend fun connectWith(
        port: Int,
        routeSource: suspend (UdpSocketChannelFactory) -> RouteSource,
    ): CoreClient =
        coreConnect(
            mismatch = MismatchResponse.Fail,
            host = "127.0.0.1",
            port = port,
            serverName = "localhost",
            options = coreTestOptions,
            factory = BufferFactory.network(),
            api = platformQuicheApi(),
            timeout = 10.seconds,
            readPolicy = ReadPolicy.Bounded(10.seconds),
            writePolicy = WritePolicy.Bounded(10.seconds),
            loops = loops,
            flight = TEST_FLIGHT,
            routeSource = routeSource,
        )

    private companion object {
        const val CONNECTS = 16
        const val MAIN_CONNECTS = 4
        const val SETUP_MILLIS = 20L
        const val MAIN_FREE_SECONDS = 5L
    }
}
