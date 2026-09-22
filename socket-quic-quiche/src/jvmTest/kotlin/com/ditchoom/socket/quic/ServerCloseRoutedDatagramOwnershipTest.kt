@file:OptIn(ExperimentalDatagramApi::class)

package com.ditchoom.socket.quic

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.PlatformBuffer
import com.ditchoom.buffer.ReadBuffer
import com.ditchoom.buffer.deterministic
import com.ditchoom.buffer.flow.AddressedDatagramChannel
import com.ditchoom.buffer.flow.Datagram
import com.ditchoom.buffer.flow.DatagramCapabilities
import com.ditchoom.buffer.flow.DatagramReadResult
import com.ditchoom.buffer.flow.DatagramSendOptions
import com.ditchoom.buffer.flow.ExperimentalDatagramApi
import com.ditchoom.buffer.flow.SocketAddress
import com.ditchoom.buffer.nativeMemoryAccess
import com.ditchoom.socket.udp.UdpSocket
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail
import kotlin.time.Duration.Companion.seconds

/**
 * A datagram a wildcard server has routed to a connection is that connection's to release — even
 * when the server closes in the instant after the datagram left the socket reader.
 *
 * The CI signature this reproduces: `IllegalStateException: Buffer has been freed and returned to
 * pool` from `driverOwnedNativeAddress` in the `RecvPacket` arm of a **server** driver's `execute`,
 * escaping uncaught as the server closed. The buffer's second owner is the wildcard composite's
 * member reader ([PerLocalAddressServerChannel]): its rendezvous hand-off to the server's reader had
 * completed, `close()` cancelled it before it resumed, and it freed a payload that was already on
 * its way to quiche.
 *
 * Every step is forced, none is timed:
 *  - the composite's member readers run on a [SteppedDispatcher], so the reader's resumption after the
 *    hand-off is held until the test releases it;
 *  - a [HeaderInfoGate] freezes the server's receive loop inside `quiche_header_info` — first to back
 *    the server's reader up so the member reader has to park in the hand-off, then on the victim
 *    datagram itself, *after* the loop's `closed` check, so it routes the datagram after the free.
 *
 * The connection is a real server-side [QuicheDriver] over [StubQuicheApi]; only header parsing is
 * real quiche, so the datagrams are real QUIC headers routed by real connection IDs.
 */
class ServerCloseRoutedDatagramOwnershipTest {
    @Test
    fun aDatagramRoutedAsTheServerClosesIsReleasedByItsConnectionOnly() =
        withRealHeaderParsing { real ->
            val rig = rig(real)
            // Allocated up front: a buffer released back to the pool is reissued at the same address,
            // and the gate tells datagrams apart by address.
            val initial = initialPacket()
            val backUp = shortPacket(marker = 1)
            val inFlight = shortPacket(marker = 2)
            val victim = shortPacket(marker = 3)

            rig.connect(initial)
            val backUpHold = rig.freezeTheLoopOn(backUp)

            // The server's reader takes the next datagram and parks handing it to the frozen loop...
            rig.dialled.deliver(inFlight)
            rig.readers.pumpUntil("the second short packet was handed to the server") { rig.dialled.entered.get() == 4 }

            // ...so the member reader parks in its own hand-off, holding the victim.
            rig.dialled.deliver(victim)
            rig.readers.runUntilIdle()
            assertEquals(4, rig.dialled.returned.get(), "premise: the member reader took the victim from its socket")
            assertEquals(4, rig.dialled.entered.get(), "premise: the member reader is parked handing the victim over, not reading again")

            // Unfreeze: the loop drains, the server's reader takes the victim — completing the member
            // reader's hand-off, whose resumption the stepped dispatcher now holds — and the loop freezes
            // on the victim's header, past its `closed` check.
            val victimHold = rig.api.holdAt(victim)
            backUpHold.release()
            victimHold.awaitEntered("the receive loop never reached header parsing for the victim")
            assertEquals(1, rig.readers.pendingCount, "premise: the member reader's resumption after the victim's hand-off is held")

            // Close: the composite cancels its readers before that resumption runs, then it runs.
            val closing = async(Dispatchers.IO) { runCatching { rig.server.close() } }
            rig.awaitSocketsClosed()
            rig.readers.runUntilIdle()
            val victimAfterReader = runCatching { victim.nativeMemoryAccess }.exceptionOrNull()

            // The loop routes the victim to its connection, and the server finishes closing.
            victimHold.release()
            rig.assertClosedCleanly(closing)

            assertEquals(
                emptyList(),
                rig.escaped.map {
                    it
                        .stackTraceToString()
                        .lines()
                        .take(8)
                        .joinToString("\n")
                },
                "a server driver died executing a datagram its connection owned. After the member reader " +
                    "resumed from a hand-off the server had already completed, the victim's buffer was " +
                    (victimAfterReader?.let { "already returned to its pool ($it)" } ?: "still live") +
                    " — the reader freed a payload it no longer owned.",
            )
            assertTrue(
                runCatching { victim.nativeMemoryAccess }.isFailure,
                "the victim's connection never released it: the driver must return it to its pool after connRecv",
            )
        }

    /**
     * The server's own reader-to-loop hand-off keeps the same rule from the other side: a datagram the
     * server's reader took from the socket and never handed over — the loop stopped taking as the server
     * closed, and cancelled the reader mid-hand-off — is freed by the hand-off, not lost with the reader.
     */
    @Test
    fun aDatagramTheServerReaderNeverHandedOverIsFreedWhenTheServerCloses() =
        withRealHeaderParsing { real ->
            val rig = rig(real)
            val initial = initialPacket()
            val backUp = shortPacket(marker = 1)
            val stranded = shortPacket(marker = 2)

            rig.connect(initial)
            val backUpHold = rig.freezeTheLoopOn(backUp)

            // The server's reader takes the stranded datagram and parks handing it to the frozen loop.
            rig.dialled.deliver(stranded)
            rig.readers.pumpUntil("the stranded packet was handed to the server's reader") { rig.dialled.entered.get() == 4 }

            // Close first, so the loop — once unfrozen — sees `closed` and never takes the stranded datagram.
            val closing = async(Dispatchers.IO) { runCatching { rig.server.close() } }
            rig.awaitSocketsClosed()
            rig.readers.runUntilIdle()
            backUpHold.release()
            rig.assertClosedCleanly(closing)

            assertTrue(
                runCatching { stranded.nativeMemoryAccess }.isFailure,
                "a datagram the server's reader took and never handed to the receive loop was never freed: " +
                    "the reader was cancelled mid-hand-off as the server closed and the payload went with it",
            )
            assertEquals(emptyList(), rig.escaped.map { "${it::class.simpleName}: ${it.message}" }, "a server driver died")
        }

    /** Real quiche for header parsing, or a recorded skip where the native library is absent. */
    private fun withRealHeaderParsing(body: suspend CoroutineScope.(QuicheApi) -> Unit): Unit =
        runBlocking {
            val real =
                try {
                    loadQuicheApi()
                } catch (e: UnsatisfiedLinkError) {
                    recordMissingNativeLib(ServerCloseRoutedDatagramOwnershipTest::class, e)
                    return@runBlocking
                }
            body(real)
        }

    /**
     * A wildcard server over two fake per-address sockets whose readers are [SteppedDispatcher]-held,
     * with a real server-side driver per connection over [StubQuicheApi].
     */
    private class Rig(
        val api: HeaderInfoGate,
        val readers: SteppedDispatcher,
        val dialled: FakeMember,
        val idle: FakeMember,
        val server: QuicServer,
        val escaped: List<Throwable>,
    )

    private suspend fun rig(real: QuicheApi): Rig {
        val api = HeaderInfoGate(StubQuicheApi(), real)
        val escaped = CopyOnWriteArrayList<Throwable>()
        val readers = SteppedDispatcher()
        // Resolved, as a real socket reports them: the server encodes both ends into sockaddrs.
        val peer = UdpSocket.resolve("198.51.100.7", 51000)
        val dialled = FakeMember(UdpSocket.resolve("127.0.0.1", 4433), peer)
        val idle = FakeMember(UdpSocket.resolve("127.0.0.2", 4433), peer)
        val server =
            buildJvmQuicServer(
                QuicPortBinding.Shared(PerLocalAddressServerChannel.of(listOf(dialled, idle), readers)),
                tlsConfig = QuicTlsConfig(certChainPath = "stub", privKeyPath = "stub"),
                requestedOptions = QuicOptions(alpnProtocols = listOf("test")),
                tuning =
                    QuicheDriverTuning(
                        driverContext = Dispatchers.Default + CoroutineExceptionHandler { _, t -> escaped += t },
                    ),
                api = api,
            )
        readers.runUntilIdle()
        return Rig(api, readers, dialled, idle, server, escaped)
    }

    /** The Initial is accepted, which routes [CONNECTION_ID] to a new connection's driver. */
    private fun Rig.connect(initial: PlatformBuffer) {
        dialled.deliver(initial)
        readers.pumpUntil("the Initial was handed to the server") { dialled.entered.get() == 2 }
    }

    /** Freeze the receive loop inside header parsing for [datagram]. */
    private fun Rig.freezeTheLoopOn(datagram: PlatformBuffer): Hold {
        val hold = api.holdAt(datagram)
        dialled.deliver(datagram)
        readers.pumpUntil("the datagram to freeze on was handed to the server") { dialled.entered.get() == 3 }
        hold.awaitEntered("the receive loop never reached header parsing for the datagram it was to freeze on")
        return hold
    }

    /** close() has cancelled the composite's readers: it closes the sockets right after. */
    private fun Rig.awaitSocketsClosed() {
        dialled.awaitClosed()
        idle.awaitClosed()
    }

    private suspend fun Rig.assertClosedCleanly(closing: Deferred<Result<Unit>>) {
        val closed = withTimeout(20.seconds) { closing.await() }
        assertTrue(closed.isSuccess, "server.close() failed: ${closed.exceptionOrNull()}")
    }

    private val pool = QuicheDriver.newRecvBufPool(BufferFactory.deterministic())

    /** A long-header Initial carrying [CONNECTION_ID], padded to RFC 9000 §14.1's 1200 bytes. */
    private fun initialPacket(): PlatformBuffer =
        pool.allocate(MIN_INITIAL).apply {
            writeByte(0xC0.toByte()) // long header, fixed bit, type Initial
            byteArrayOf(0, 0, 0, 1).forEach { writeByte(it) } // QUIC v1
            writeByte(CONNECTION_ID.size.toByte())
            CONNECTION_ID.forEach { writeByte(it) }
            writeByte(8)
            repeat(8) { writeByte(0x5C) }
            writeByte(0) // token length
            while (position() < MIN_INITIAL) writeByte(0)
            resetForRead()
        }

    /** A short-header packet to [CONNECTION_ID]; [marker] only makes the bytes of each distinct. */
    private fun shortPacket(marker: Int): PlatformBuffer =
        pool.allocate(SHORT_LEN).apply {
            writeByte(0x40) // short header, fixed bit
            CONNECTION_ID.forEach { writeByte(it) }
            while (position() < SHORT_LEN) writeByte(marker.toByte())
            resetForRead()
        }

    private fun SteppedDispatcher.pumpUntil(
        what: String,
        condition: () -> Boolean,
    ) {
        runUntilIdle()
        while (!condition()) {
            if (!awaitThenRunUntilIdle(10.seconds)) fail("premise never held: $what")
        }
    }

    /** Freezes `quiche_header_info` on the datagrams it is told to hold; real quiche parses every header. */
    private class HeaderInfoGate(
        stub: StubQuicheApi,
        private val real: QuicheApi,
    ) : QuicheApi by stub {
        private val holds = ConcurrentHashMap<Long, Hold>()

        fun holdAt(datagram: PlatformBuffer): Hold = Hold().also { holds[datagram.nativeMemoryAccess!!.nativeAddress.toLong()] = it }

        override fun headerInfo(
            buf: Long,
            bufLen: Int,
            dcil: Int,
            versionOut: Long,
            typeOut: Long,
            scidOut: Long,
            scidLenOut: Long,
            dcidOut: Long,
            dcidLenOut: Long,
            tokenOut: Long,
            tokenLenOut: Long,
        ): Int {
            holds.remove(buf)?.freeze()
            return real.headerInfo(buf, bufLen, dcil, versionOut, typeOut, scidOut, scidLenOut, dcidOut, dcidLenOut, tokenOut, tokenLenOut)
        }
    }

    private class Hold {
        private val entered = CountDownLatch(1)
        private val released = CountDownLatch(1)

        fun freeze() {
            entered.countDown()
            check(released.await(HOLD_SECONDS, TimeUnit.SECONDS)) { "a held header parse was never released" }
        }

        fun awaitEntered(failure: String) {
            if (!entered.await(HOLD_SECONDS, TimeUnit.SECONDS)) fail(failure)
        }

        fun release() = released.countDown()
    }

    /** One of the wildcard server's per-address sockets, fed by the test. */
    private class FakeMember(
        override val localAddress: SocketAddress,
        private val peer: SocketAddress,
    ) : AddressedDatagramChannel {
        private val queue = Channel<DatagramReadResult>(Channel.UNLIMITED)
        private val closed = CountDownLatch(1)

        /** `receive()` calls begun, and those that returned a datagram. */
        val entered = AtomicInteger()
        val returned = AtomicInteger()

        fun deliver(payload: PlatformBuffer) {
            queue.trySend(DatagramReadResult.Received(Datagram(payload = payload, peer = peer)))
        }

        fun awaitClosed() {
            if (!closed.await(HOLD_SECONDS, TimeUnit.SECONDS)) fail("server.close() never closed the socket $localAddress")
        }

        override val isOpen: Boolean get() = closed.count > 0
        override val maxWritableSize: Int = 1350
        override val capabilities: DatagramCapabilities = DatagramCapabilities()

        override suspend fun receive(): DatagramReadResult {
            entered.incrementAndGet()
            return queue.receive().also { returned.incrementAndGet() }
        }

        override suspend fun send(
            payload: ReadBuffer,
            to: SocketAddress,
            options: DatagramSendOptions,
        ) = Unit

        override fun close() = closed.countDown()
    }

    private companion object {
        val CONNECTION_ID = ByteArray(QUIC_MAX_CONN_ID_LEN) { (0xA0 + it).toByte() }
        const val MIN_INITIAL = SharedQuicheServer.MIN_INITIAL_DATAGRAM_SIZE
        const val SHORT_LEN = 64
        const val HOLD_SECONDS = 20L
    }
}
