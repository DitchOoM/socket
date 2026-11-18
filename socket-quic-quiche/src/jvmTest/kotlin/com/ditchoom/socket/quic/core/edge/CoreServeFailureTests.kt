package com.ditchoom.socket.quic.core.edge

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.Default
import com.ditchoom.buffer.flow.ReadPolicy
import com.ditchoom.buffer.flow.WritePolicy
import com.ditchoom.socket.quic.NetShim
import com.ditchoom.socket.quic.QuicOptions
import com.ditchoom.socket.quic.QuicheApi
import com.ditchoom.socket.quic.QuicheConfig
import com.ditchoom.socket.quic.core.demux.AddressValidation
import com.ditchoom.socket.quic.core.demux.ServerConfig
import com.ditchoom.socket.quic.core.ready.NetStack
import com.ditchoom.socket.quic.core.ready.netStackOfThisRuntime
import com.ditchoom.socket.quic.core.testing.assumeNativeStack
import com.ditchoom.socket.quic.core.time.LoopClock
import com.ditchoom.socket.quic.core.time.MonotonicClock
import com.ditchoom.socket.quic.core.time.Nanos
import com.ditchoom.socket.quic.core.udp.DatagramBatchIo
import com.ditchoom.socket.quic.core.udp.SocketClosed
import com.ditchoom.socket.quic.core.udp.toPathKey
import com.ditchoom.socket.quic.loadQuicheApi
import kotlinx.coroutines.runBlocking
import java.net.InetSocketAddress
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/**
 * A serve that fails partway leaves nothing behind and frees nothing twice: whatever it opened (its memory, the poller,
 * the quiche config, the socket) has exactly one releaser, before the loop is built and after.
 */
class CoreServeFailureTests {
    @Test
    fun aServeFailingWhileItConfiguresQuicheReleasesThePollerConfigAndMemoryOnce() {
        val rig = Rig()
        rig.failConfiguring = true
        assertFailsWith<InjectedFailure> { rig.serve(wrapIo = { it }, clock = MonotonicClock) }
        assertEquals(1, rig.pollerCloses, "the poller is closed once")
        assertEquals(1, rig.configFrees, "the quiche config is freed once")
        assertEquals(0, rig.fdCloses, "no socket was opened")
        rig.assertMemoryFreedOnce()
    }

    /** The socket's io is wrapped after the server's memory hold is made: its address and token keys exist by then. */
    @Test
    fun aServeFailingBeforeItsLoopIsBuiltReleasesThePollerConfigAndSocketOnce() {
        val rig = Rig()
        assertFailsWith<InjectedFailure> { rig.serve(wrapIo = { throw InjectedFailure() }, clock = MonotonicClock) }
        rig.assertEachReleasedOnce()
    }

    @Test
    fun aCallerDrivenServeFailingAfterItsLoopIsBuiltReleasesThePollerConfigAndSocketOnce() {
        val rig = Rig()
        val clock = ArmedClock()
        assertFailsWith<InjectedFailure> {
            rig.serve(
                wrapIo = { io ->
                    // The loop is built right after this: the clock fails the serve's first pass.
                    clock.armed = true
                    rig.countingIo(io)
                },
                clock = clock,
            )
        }
        rig.assertEachReleasedOnce()
        assertEquals(1, rig.ioCloses, "the endpoint's socket is closed once")
    }

    private class Rig {
        private val shim: NetShim
        val api: QuicheApi = loadQuicheApi()
        var pollerCloses = 0
        var configFrees = 0
        var fdCloses = 0
        var ioCloses = 0
        var failConfiguring = false
        val factory =
            FreeCountingFactory(
                BufferFactory.Default,
                setOf(FreeCountingFactory.Site.EdgeMemory, FreeCountingFactory.Site.SockAddr, FreeCountingFactory.Site.AddressTokens),
            )

        init {
            assumeNativeStack()
            shim = (netStackOfThisRuntime as NetStack.Native).shim
        }

        private val countingShim =
            object : NetShim by shim {
                override fun pollerClose(poller: Long) {
                    pollerCloses++
                    shim.pollerClose(poller)
                }

                override fun close(fd: Int): Int {
                    fdCloses++
                    return shim.close(fd)
                }
            }

        private val countingApi =
            object : QuicheApi by api {
                override fun configFree(config: QuicheConfig) {
                    configFrees++
                    api.configFree(config)
                }

                override fun configSetApplicationProtos(
                    config: QuicheConfig,
                    protosAddr: Long,
                    protosLen: Int,
                ): Int = if (failConfiguring) throw InjectedFailure() else api.configSetApplicationProtos(config, protosAddr, protosLen)
            }

        fun countingIo(inner: DatagramBatchIo): DatagramBatchIo =
            object : DatagramBatchIo by inner {
                override fun close(): SocketClosed {
                    ioCloses++
                    return inner.close()
                }
            }

        fun serve(
            wrapIo: (DatagramBatchIo) -> DatagramBatchIo,
            clock: LoopClock,
        ) = runBlocking {
            coreServe(
                bind = InetSocketAddress("127.0.0.1", 0).toPathKey(),
                tls = coreTlsConfig(),
                options = options(),
                factory = factory,
                api = countingApi,
                readPolicy = ReadPolicy.Bounded(10.seconds),
                writePolicy = WritePolicy.Bounded(10.seconds),
                wrapIo = wrapIo,
                clock = clock,
                // Address validation gives the server its token keys, which its memory hold frees with the rest.
                config = ServerConfig(addressValidation = AddressValidation.Retry()),
                driver = LoopDriver.Caller,
                stack = NetStack.Native(countingShim, (netStackOfThisRuntime as NetStack.Native).waits),
            )
        }

        fun assertEachReleasedOnce() {
            assertEquals(1, pollerCloses, "the poller is closed once")
            assertEquals(1, configFrees, "the quiche config is freed once")
            assertEquals(1, fdCloses, "the socket's descriptor is closed once")
            assertMemoryFreedOnce()
        }

        fun assertMemoryFreedOnce() {
            val buffers = factory.edgeBuffers()
            assertTrue(buffers.isNotEmpty(), "the serve took its memory through the counting factory")
            val notOnce = buffers.filter { it.frees.get() != 1 }.map { it.toString() }.distinct()
            assertEquals(emptyList(), notOnce, "server buffers not freed exactly once")
        }

        private fun options() = QuicOptions(alpnProtocols = listOf("test"), verifyPeer = false, idleTimeout = 30.seconds)
    }

    /** A clock that fails every reading once armed. */
    private class ArmedClock : LoopClock {
        var armed = false

        override fun now(): Nanos = if (armed) throw InjectedFailure() else MonotonicClock.now()
    }

    private class InjectedFailure : RuntimeException("injected")
}
