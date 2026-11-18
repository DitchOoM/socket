@file:OptIn(kotlin.concurrent.atomics.ExperimentalAtomicApi::class)

package com.ditchoom.socket.quic.core.edge

import com.ditchoom.socket.quic.QuicError
import com.ditchoom.socket.quic.QuicheApi
import com.ditchoom.socket.quic.QuicheConfig
import com.ditchoom.socket.quic.core.demux.withCoreEchoServer
import com.ditchoom.socket.quic.core.testing.platformQuicheApi
import kotlinx.coroutines.runBlocking
import kotlin.concurrent.atomics.AtomicInt
import kotlin.concurrent.atomics.AtomicReference
import kotlin.concurrent.atomics.fetchAndIncrement
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/**
 * Client connects share one quiche config per plan: connects with equal options build it once, connects that differ in
 * a config input build their own, and a connection outlives a config pushed out by newer plans.
 */
class SharedClientConfigTests {
    @Test
    fun connectsWithTheSameOptionsBuildOneConfig() =
        runBlocking {
            val api = ConfigCountingApi(platformQuicheApi())
            withCoreEchoServer { server ->
                repeat(CONNECTS) { connectCore(server.port, api = api).connection.close(QuicError.NoError) }
            }
            assertEquals(1, api.made.load(), "configs built for $CONNECTS connects with the same options")
        }

    @Test
    fun connectsThatDifferInAConfigInputBuildTheirOwn() =
        runBlocking {
            val api = ConfigCountingApi(platformQuicheApi())
            withCoreEchoServer { server ->
                repeat(2) {
                    for (seconds in IDLE_SECONDS) {
                        val options = coreTestOptions.copy(idleTimeout = seconds.seconds)
                        connectCore(server.port, api = api, options = options).connection.close(QuicError.NoError)
                    }
                }
            }
            assertEquals(IDLE_SECONDS.size, api.made.load(), "configs built for ${IDLE_SECONDS.size} idle timeouts, twice each")
        }

    @Test
    fun aConnectionOutlivesTheConfigItWasMadeFrom() =
        runBlocking {
            val api = ConfigCountingApi(platformQuicheApi())
            withCoreEchoServer { server ->
                val first = connectCore(server.port, api = api)
                val firstConfig = api.last.load()
                for (i in 1..OnSetupLane.CONFIGS_KEPT) {
                    val options = coreTestOptions.copy(idleTimeout = (FIRST_PUSHING_SECONDS + i).seconds)
                    connectCore(server.port, api = api, options = options).connection.close(QuicError.NoError)
                }
                assertTrue(firstConfig in api.freed.load(), "the first connection's config was freed by newer plans")
                val payload = ByteArray(PAYLOAD) { it.toByte() }
                val stream = first.connection.openStream()
                assertContentEquals(payload, echoOver(stream, payload), "the echo after its config was freed")
                first.connection.close(QuicError.NoError)
            }
        }

    /** Counts the configs built and records the ones freed. */
    private class ConfigCountingApi(
        private val real: QuicheApi,
    ) : QuicheApi by real {
        val made = AtomicInt(0)
        val last = AtomicReference(0L)
        val freed = AtomicReference(emptySet<Long>())

        override fun configNew(version: Int): QuicheConfig =
            real.configNew(version).also {
                made.fetchAndIncrement()
                last.store(it.handle)
            }

        override fun configFree(config: QuicheConfig) {
            freed.store(freed.load() + config.handle)
            real.configFree(config)
        }
    }

    private companion object {
        const val CONNECTS = 8
        val IDLE_SECONDS = listOf(11, 12, 13)
        const val FIRST_PUSHING_SECONDS = 20
        const val PAYLOAD = 4096
    }
}
