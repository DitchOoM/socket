package com.ditchoom.socket.quic

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.deterministic
import com.ditchoom.buffer.flow.ReadResult
import com.ditchoom.buffer.freeIfNeeded
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/**
 * What the driver does with a stream's slot, in every way a stream can end: the slot goes only when both
 * directions are over, and nothing quiche reports afterwards makes it a stream to announce.
 */
class FinishedStreamHazardTests {
    private val bufferFactory = BufferFactory.deterministic()
    private val resetCode = QuicAppErrorCode(0x10c)

    @Test
    fun aFinishedPeerStreamReportedReadableAgainIsNotAnnouncedAgain() =
        withDriver(QuicRole.Server) { api, driver ->
            val stream = api.peerOpens(driver, PEER_BIDI)
            finishReceive(api, stream)
            finishSend(stream)
            assertEquals(emptyList(), held(driver), "both directions are over")

            api.readableStreams.addLast(PEER_BIDI)
            driver.stats()
            assertTrue(driver.incomingStreams.tryReceive().isFailure, "the stream is not announced a second time")
            assertEquals(emptyList(), held(driver), "no slot is made for it")
        }

    @Test
    fun aFinishedLocalStreamReportedReadableIsNotAnnounced() =
        withDriver(QuicRole.Client) { api, driver ->
            val slot = driver.openStreamSlot(unidirectional = false)
            val adapter = DriverStreamAdapter(driver, slot)
            api.streamRecvResult = StreamRecvResult.Data(bytesRead = 0, fin = true)
            adapter.streamClose(slot.id)
            assertEquals(listOf(slot.id.id), held(driver), "the receive side is still to be read")
            assertIs<ReadResult.End>(adapter.streamRead(slot.id, bufferFactory, READ_SIZE, TIMEOUT))
            assertEquals(emptyList(), held(driver), "both directions are over")

            api.readableStreams.addLast(slot.id.id)
            driver.stats()
            assertTrue(driver.incomingStreams.tryReceive().isFailure, "a stream this endpoint opened is never announced")
            assertEquals(emptyList(), held(driver))
        }

    @Test
    fun aPeerStreamIsKeptWhileItsReceiveSideHoldsUnreadBytes() =
        withDriver(QuicRole.Server) { api, driver ->
            api.readableStreams.addLast(PEER_BIDI)
            driver.stats()
            val stream = withTimeout(TIMEOUT) { driver.incomingStreams.receive() }
            api.readableStreams.clear()

            finishSend(stream)
            assertEquals(listOf(PEER_BIDI), held(driver), "the send side is over; the peer's bytes are still to come")

            api.streamRecvResult = StreamRecvResult.Data(bytesRead = 5, fin = false)
            val bytes = assertIs<ReadResult.Data>(stream.read(TIMEOUT))
            assertEquals(5, bytes.buffer.remaining())
            bytes.buffer.freeIfNeeded()
            assertEquals(listOf(PEER_BIDI), held(driver), "bytes delivered, the end is not")

            api.streamRecvResult = StreamRecvResult.Data(bytesRead = 0, fin = true)
            assertIs<ReadResult.End>(stream.read(TIMEOUT))
            assertEquals(emptyList(), held(driver))
        }

    @Test
    fun aPeerStreamWhoseFinArrivedWithItsLastBytesIsKeptUntilTheFinIsRead() =
        withDriver(QuicRole.Server) { api, driver ->
            val stream = api.peerOpens(driver, PEER_BIDI)
            finishSend(stream)

            api.streamRecvResult = StreamRecvResult.Data(bytesRead = 5, fin = true)
            assertIs<ReadResult.Data>(stream.read(TIMEOUT)).buffer.freeIfNeeded()
            assertEquals(listOf(PEER_BIDI), held(driver), "quiche has delivered the FIN; the application has not been given it")

            assertIs<ReadResult.End>(stream.read(TIMEOUT))
            assertEquals(emptyList(), held(driver))
        }

    @Test
    fun aStreamTheApplicationFinishedReadingStaysUntilItsSendSideIsOver() =
        withDriver(QuicRole.Server) { api, driver ->
            val stream = api.peerOpens(driver, PEER_BIDI)
            finishReceive(api, stream)
            assertEquals(listOf(PEER_BIDI), held(driver), "the reply is still to be sent")
            finishSend(stream)
            assertEquals(emptyList(), held(driver))
        }

    @Test
    fun aUnidirectionalStreamThisEndpointOpenedIsReleasedWhenItsFinIsAccepted() =
        withDriver(QuicRole.Client) { _, driver ->
            val slot = driver.openStreamSlot(unidirectional = true)
            assertEquals(listOf(slot.id.id), held(driver))
            DriverStreamAdapter(driver, slot).streamClose(slot.id)
            assertEquals(emptyList(), held(driver), "it has no receive side")
        }

    @Test
    fun aUnidirectionalStreamThePeerOpenedIsReleasedWhenItsEndIsRead() =
        withDriver(QuicRole.Client) { api, driver ->
            val stream = api.peerOpens(driver, PEER_UNI)
            assertEquals(listOf(PEER_UNI), held(driver))
            finishReceive(api, stream)
            assertEquals(emptyList(), held(driver), "it has no send side")

            api.readableStreams.addLast(PEER_UNI)
            driver.stats()
            assertTrue(driver.incomingStreams.tryReceive().isFailure)
        }

    @Test
    fun aStreamThePeerStoppedAndResetIsReleasedOnceItsResetIsRead() =
        withDriver(QuicRole.Client) { api, driver ->
            val slot = driver.openStreamSlot(unidirectional = false)
            val adapter = DriverStreamAdapter(driver, slot)
            api.connStreamSendResult = QuicheDriver.QUICHE_ERR_STREAM_STOPPED
            api.connStreamSendErrorCode = resetCode
            adapter.streamClose(slot.id)
            assertEquals(listOf(slot.id.id), held(driver), "the peer's reset is still to be read")

            api.streamRecvResult = StreamRecvResult.Reset(resetCode)
            assertIs<ReadResult.Reset>(adapter.streamRead(slot.id, bufferFactory, READ_SIZE, TIMEOUT))
            assertEquals(emptyList(), held(driver))
        }

    @Test
    fun aStreamTheApplicationResetIsReleased() =
        withDriver(QuicRole.Server) { api, driver ->
            val stream = api.peerOpens(driver, PEER_BIDI)
            stream.reset(resetCode.value)
            assertEquals(emptyList(), held(driver), "RESET_STREAM and STOP_SENDING were both sent")
        }

    @Test
    fun aStreamOpenedForACallerWhoLeftIsKeptForTheNextOpen() =
        withDriver(QuicRole.Client) { _, driver ->
            val open = QuicheCmd.OpenStream(CompletableDeferred())
            driver.commands.send(open)
            val slot = withTimeout(TIMEOUT) { open.result.await() }
            driver.commands.send(QuicheCmd.AbandonOpen(open))
            assertEquals(listOf(slot.id.id), held(driver))
            assertEquals(slot, driver.openStreamSlot(unidirectional = false), "the next open takes it")
        }

    @Test
    fun streamsAreOnlyLifecycleFinishedOnceBothDirectionsAreOver() {
        assertEquals(StreamLifecycle.SendOver, StreamLifecycle.Open.after(StreamDirection.Send))
        assertEquals(StreamLifecycle.ReceiveOver, StreamLifecycle.Open.after(StreamDirection.Receive))
        assertEquals(StreamLifecycle.Finished, StreamLifecycle.SendOver.after(StreamDirection.Receive))
        assertEquals(StreamLifecycle.Finished, StreamLifecycle.ReceiveOver.after(StreamDirection.Send))
        assertEquals(StreamLifecycle.SendOver, StreamLifecycle.SendOver.after(StreamDirection.Send))
        assertEquals(StreamLifecycle.Finished, StreamLifecycle.Finished.after(StreamDirection.Send))
        assertEquals(StreamLifecycle.Open, StreamLifecycle.of(QuicStreamId(0), QuicRole.Client))
        assertEquals(StreamLifecycle.ReceiveOver, StreamLifecycle.of(QuicStreamId(2), QuicRole.Client))
        assertEquals(StreamLifecycle.SendOver, StreamLifecycle.of(QuicStreamId(2), QuicRole.Server))
        assertEquals(StreamLifecycle.ReceiveOver, StreamLifecycle.of(QuicStreamId(3), QuicRole.Server))
        assertEquals(StreamLifecycle.SendOver, StreamLifecycle.of(QuicStreamId(3), QuicRole.Client))
    }

    @Test
    fun aSlotReportsItsFinishOnce() {
        val slot = StreamSlot(QuicStreamId(0))
        assertEquals(false, slot.finish(StreamDirection.Send))
        assertEquals(true, slot.finish(StreamDirection.Receive))
        assertEquals(false, slot.finish(StreamDirection.Receive))
        assertEquals(false, slot.finish(StreamDirection.Send))
    }

    @Test
    fun releasedPeerStreamsAreRememberedWhateverTheOrderTheyWereReleasedIn() {
        val released = ReleasedPeerStreams()
        val bidi = { index: Long -> QuicStreamId(index * 4) }
        val uni = { index: Long -> QuicStreamId(index * 4 + 2) }
        released.add(bidi(2))
        assertTrue(bidi(2) in released)
        assertTrue(bidi(0) !in released && bidi(1) !in released, "streams below it may still be unseen")
        released.add(bidi(0))
        released.add(bidi(1))
        assertTrue((0L..2L).all { bidi(it) in released })
        assertTrue(bidi(3) !in released)
        assertTrue(uni(0) !in released, "bidirectional and unidirectional streams are numbered apart")
        released.add(uni(0))
        assertTrue(uni(0) in released)
    }

    private fun withDriver(
        role: QuicRole,
        block: suspend CoroutineScope.(StubQuicheApi, QuicheDriver) -> Unit,
    ) = runQuicTest {
        val api = StubQuicheApi()
        val driver =
            QuicheDriver(
                // Test double: these tests never move a path.
                migration = MigrationCapability.BackendCannotMigrate,
                rawApi = api,
                conn = QuicheConn(1L),
                bufferFactory = bufferFactory,
                recvInfo = QuicheRecvInfo(1L),
                sendInfo = QuicheSendInfo(1L),
                udpChannel = StubUdpChannel(),
                role = role,
                ingress = DatagramIngress.ExternalPump,
            )
        driver.start(this)
        try {
            block(api, driver)
        } finally {
            driver.destroy()
        }
    }

    /** The peer opens [id]: quiche reports it readable once, and the driver announces it. */
    private suspend fun StubQuicheApi.peerOpens(
        driver: QuicheDriver,
        id: Long,
    ): QuicByteStream {
        readableStreams.addLast(id)
        driver.stats()
        val stream = withTimeout(TIMEOUT) { driver.incomingStreams.receive() }
        readableStreams.clear()
        return stream
    }

    /** The application reads [stream] to the peer's FIN. */
    private suspend fun finishReceive(
        api: StubQuicheApi,
        stream: QuicByteStream,
    ) {
        api.streamRecvResult = StreamRecvResult.Data(bytesRead = 0, fin = true)
        assertIs<ReadResult.End>(stream.read(TIMEOUT))
    }

    /** The application finishes [stream]'s send side with no more bytes. */
    private suspend fun finishSend(stream: QuicByteStream) {
        val nothing = bufferFactory.allocate(1)
        nothing.resetForRead()
        stream.writeFullyAndShutdownSend(nothing, TIMEOUT)
        nothing.freeNativeMemory()
    }

    /** The streams the driver holds a slot for, after a turn of its loop. */
    private suspend fun held(driver: QuicheDriver): List<Long> {
        driver.stats()
        return assertIs<Inspected.Read<DriverStallSnapshot>>(driver.stallSnapshot()).value.streams.map { it.id }
    }

    private companion object {
        const val READ_SIZE = 1024
        val TIMEOUT = 5.seconds

        /** Client-opened bidirectional stream 0, as the server sees it. */
        const val PEER_BIDI = 0L

        /** A server-opened unidirectional stream, as the client sees it. */
        const val PEER_UNI = 3L
    }
}
