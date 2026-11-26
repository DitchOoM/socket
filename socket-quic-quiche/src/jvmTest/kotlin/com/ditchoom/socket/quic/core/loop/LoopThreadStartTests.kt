package com.ditchoom.socket.quic.core.loop

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.flow.ReadPolicy
import com.ditchoom.buffer.flow.WritePolicy
import com.ditchoom.socket.quic.QuicError
import com.ditchoom.socket.quic.core.conn.MismatchResponse
import com.ditchoom.socket.quic.core.demux.loopbackV4
import com.ditchoom.socket.quic.core.demux.withCoreEchoServer
import com.ditchoom.socket.quic.core.edge.CoreLoopThreadRefused
import com.ditchoom.socket.quic.core.edge.connectCore
import com.ditchoom.socket.quic.core.edge.coreServe
import com.ditchoom.socket.quic.core.edge.coreTestOptions
import com.ditchoom.socket.quic.core.edge.echoOver
import com.ditchoom.socket.quic.core.testing.platformQuicheApi
import com.ditchoom.socket.quic.core.testing.platformTlsConfig
import com.ditchoom.socket.quic.core.udp.Thrown
import com.ditchoom.socket.quic.network
import com.ditchoom.socket.quic.skipOnMissingNativeLib
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/**
 * A loop whose thread the platform cannot create is refused as a typed failure, with nothing left open, and the next
 * attempt starts a loop: a connect or a serve never waits on a loop that will not run.
 */
class LoopThreadStartTests {
    /** The first start is refused as HotSpot refuses a native thread; every later one starts the thread. */
    private class RefusesOnce {
        val attempts = AtomicInteger()

        fun start(
            loop: Loop,
            name: String,
        ): LoopStart =
            if (attempts.incrementAndGet() == 1) {
                LoopStart.Refused(
                    Thrown(OutOfMemoryError("unable to create native thread: possibly out of memory or process/resource limits reached")),
                )
            } else {
                startLoop(loop, name)
            }
    }

    @Test
    fun aConnectWhoseLoopThreadCannotStartFailsTypedAndTheNextConnectStartsALoop() =
        runBlocking {
            skipOnMissingNativeLib(LoopThreadStartTests::class) {
                withCoreEchoServer { server ->
                    val starts = RefusesOnce()
                    val loops = LoopSet(1, starter = starts::start)
                    try {
                        val refused =
                            withTimeout(WITHIN) { assertFailsWith<CoreLoopThreadRefused> { connectCore(server.port, loops = loops) } }
                        assertIs<OutOfMemoryError>(refused.rootCause(), "the refusal carries what the thread start threw")
                        assertEquals(emptyList(), loops.countsOfStarted(), "the loop that never ran is not left in the set")

                        val client = withTimeout(WITHIN) { connectCore(server.port, loops = loops) }
                        val payload = ByteArray(PAYLOAD) { it.toByte() }
                        assertContentEquals(payload, echoOver(client.connection.openStream(), payload))
                        client.connection.close(QuicError.NoError)
                        assertEquals(2, starts.attempts.get(), "one refused start and one that started")
                    } finally {
                        loops.close()
                    }
                }
            }
        }

    @Test
    fun aServeWhoseLoopThreadCannotStartFailsTypedAndTheNextServeBinds() =
        runBlocking {
            skipOnMissingNativeLib(LoopThreadStartTests::class) {
                val starts = RefusesOnce()
                val refused =
                    withTimeout(WITHIN) {
                        assertFailsWith<CoreLoopThreadRefused> {
                            coreServe(
                                mismatch = MismatchResponse.Fail,
                                bind = loopbackV4(),
                                tls = platformTlsConfig(),
                                options = coreTestOptions,
                                factory = BufferFactory.network(),
                                api = platformQuicheApi(),
                                readPolicy = ReadPolicy.Bounded(10.seconds),
                                writePolicy = WritePolicy.Bounded(10.seconds),
                                starter = starts::start,
                            )
                        }
                    }
                assertIs<OutOfMemoryError>(refused.rootCause(), "the refusal carries what the thread start threw")

                val served =
                    coreServe(
                        mismatch = MismatchResponse.Fail,
                        bind = loopbackV4(),
                        tls = platformTlsConfig(),
                        options = coreTestOptions,
                        factory = BufferFactory.network(),
                        api = platformQuicheApi(),
                        readPolicy = ReadPolicy.Bounded(10.seconds),
                        writePolicy = WritePolicy.Bounded(10.seconds),
                        starter = starts::start,
                    )
                assertTrue(served.port > 0, "the second serve bound a port")
                withTimeout(WITHIN) { served.close() }
                assertEquals(2, starts.attempts.get(), "one refused start and one that started")
            }
        }

    /** The first cause of the chain: coroutine stack recovery wraps a copy of the exception around the one thrown. */
    private fun Throwable.rootCause(): Throwable = generateSequence(this) { it.cause }.last()

    private companion object {
        const val PAYLOAD = 200
        val WITHIN = 20.seconds
    }
}
