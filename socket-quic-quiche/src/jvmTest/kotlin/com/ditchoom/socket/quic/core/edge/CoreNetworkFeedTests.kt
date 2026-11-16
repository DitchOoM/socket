package com.ditchoom.socket.quic.core.edge

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.Default
import com.ditchoom.buffer.flow.ReadPolicy
import com.ditchoom.buffer.flow.WritePolicy
import com.ditchoom.socket.InternetAccess
import com.ditchoom.socket.NetworkMonitor
import com.ditchoom.socket.NetworkState
import com.ditchoom.socket.quic.NetworkMonitorSource
import com.ditchoom.socket.quic.QuicError
import com.ditchoom.socket.quic.loadQuicheApi
import com.ditchoom.socket.quic.skipOnMissingNativeLib
import com.ditchoom.socket.transport.NetworkId
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertIs
import kotlin.time.Duration.Companion.seconds

/**
 * The network monitor's news reaches a real core connection as connection work: no usable network keeps it (the idle
 * timeout bounds an outage), and a usable network back has it send at once.
 */
class CoreNetworkFeedTests {
    private class FakeMonitor : NetworkMonitor {
        override val state = MutableStateFlow<NetworkState>(UP)

        override fun close() = Unit
    }

    private suspend fun connectWatched(
        port: Int,
        monitor: NetworkMonitor,
    ): CoreClient =
        onIo {
            coreConnect(
                host = "127.0.0.1",
                port = port,
                serverName = "localhost",
                options = coreTestOptions.copy(networkMonitor = NetworkMonitorSource.Supplied(monitor)),
                factory = BufferFactory.Default,
                api = loadQuicheApi(),
                timeout = 10.seconds,
                readPolicy = ReadPolicy.Bounded(10.seconds),
                writePolicy = WritePolicy.Bounded(10.seconds),
            )
        }

    @Test
    fun anOutageOfSeveralSecondsKeepsTheConnectionAndItEchoesOnceTheNetworkReturns() =
        runBlocking {
            skipOnMissingNativeLib(CoreNetworkFeedTests::class) {
                withEchoServer { port ->
                    val monitor = FakeMonitor()
                    val client = connectWatched(port, monitor)
                    monitor.state.value = NetworkState.Offline
                    delay(4_000)
                    assertIs<EdgeEnd.Open>(client.connection.end(), "no usable network does not end the connection")
                    monitor.state.value = UP
                    val payload = Random(7).nextBytes(2_000)
                    assertContentEquals(payload, echoOver(client.connection.openStream(), payload))
                    client.connection.close(QuicError.NoError)
                }
            }
        }

    private companion object {
        val UP: NetworkState = NetworkState.Routable(NetworkId.Unidentified, InternetAccess.Unobserved)
    }
}
