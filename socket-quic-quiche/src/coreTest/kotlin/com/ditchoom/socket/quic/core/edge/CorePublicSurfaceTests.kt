package com.ditchoom.socket.quic.core.edge

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.ReadBuffer
import com.ditchoom.buffer.flow.ReadPolicy
import com.ditchoom.socket.quic.QuicAbortCodes
import com.ditchoom.socket.quic.QuicAppErrorCode
import com.ditchoom.socket.quic.QuicCloseChoice
import com.ditchoom.socket.quic.QuicCloseException
import com.ditchoom.socket.quic.QuicCloseOutcome
import com.ditchoom.socket.quic.QuicConnection
import com.ditchoom.socket.quic.QuicConnectionEnd
import com.ditchoom.socket.quic.QuicConnectionLife
import com.ditchoom.socket.quic.QuicError
import com.ditchoom.socket.quic.QuicIncomingStream
import com.ditchoom.socket.quic.QuicReceiveStream
import com.ditchoom.socket.quic.QuicStream
import com.ditchoom.socket.quic.QuicStreamsAlreadyHandledException
import com.ditchoom.socket.quic.ScopedRead
import com.ditchoom.socket.quic.StreamWrite
import com.ditchoom.socket.quic.core.loop.LoopSet
import com.ditchoom.socket.quic.core.testing.coreTest
import com.ditchoom.socket.quic.getOrElse
import com.ditchoom.socket.quic.network
import com.ditchoom.socket.quic.readEach
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertSame
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * The core through its public types, over a real loopback socket on every platform: streams by direction, reads and
 * writes whose every outcome is a value, the handler forms, and the close choice.
 */
class CorePublicSurfaceTests {
    private val loops = LoopSet(1)

    @AfterTest
    fun closeLoops() = loops.close()

    @Test
    fun aBlockFormOpenIsEchoedByAHandlerAndBothEndWithValues() =
        coreTest("CorePublicSurfaceTests.aBlockFormOpenIsEchoedByAHandlerAndBothEndWithValues") {
            val serverEnd = CompletableDeferred<QuicConnectionEnd>()
            withCoreServer(onConnection = { core -> serverEnd.complete(core.public().handleStreams(::echo)) }) { server ->
                val client = connect(server.port)
                assertEquals("test", client.handshake.negotiatedAlpn)
                val reply =
                    client.openStream { (send, receive) ->
                        assertIs<StreamWrite.Written>(send.writeAndFinish(bytes(payload())))
                        assertIs<StreamWrite.Written>(send.finish(), "finishing twice answers the same")
                        collect(receive, ScopedRead.End.PeerFinished)
                    }
                assertContentEquals(payload(), reply)
                assertIs<QuicConnectionLife.Open>(client.life())
                assertIs<QuicCloseOutcome.Drained>(client.close())
                assertIs<QuicConnectionEnd.ByLocal>(assertIs<QuicConnectionLife.Ended>(client.life()).end)
                assertIs<QuicConnectionEnd.ByPeer>(withTimeout(WAIT) { serverEnd.await() }, "handleStreams returns the end")
            }
        }

    @Test
    fun aSendOnlyStreamReachesThePeersHandlerAsAReceiveStream() =
        coreTest("CorePublicSurfaceTests.aSendOnlyStreamReachesThePeersHandlerAsAReceiveStream") {
            val got = CompletableDeferred<ByteArray>()
            val shape = CompletableDeferred<QuicIncomingStream>()
            withCoreServer(onConnection = { core ->
                core.public().handleStreams { incoming ->
                    shape.complete(incoming)
                    when (incoming) {
                        is QuicStream -> Unit
                        is QuicReceiveStream -> got.complete(collect(incoming, ScopedRead.End.PeerFinished))
                    }
                }
            }) { server ->
                val client = connect(server.port)
                client.openSendStream { send -> assertIs<StreamWrite.Written>(send.write(bytes(payload()))) }
                assertContentEquals(payload(), withTimeout(WAIT) { got.await() }, "the block's return finished the stream")
                assertIs<QuicReceiveStream>(shape.await())
                client.close(QuicCloseChoice.Immediate())
            }
        }

    @Test
    fun aPassedReadDeadlineIsAValueAndTheStreamStillReads() =
        coreTest("CorePublicSurfaceTests.aPassedReadDeadlineIsAValueAndTheStreamStillReads") {
            withCoreServer(onConnection = { core -> core.public().handleStreams(::echo) }) { server ->
                val client = connect(server.port)
                val (send, receive) = client.openStream()
                val early = receive.read(ReadPolicy.Bounded(150.milliseconds)) { it.remaining() }
                assertSame<Any>(ScopedRead.DeadlinePassed, early.fold({ it }, { it }))
                assertIs<StreamWrite.Written>(send.write(bytes(payload())))
                assertEquals(true, receive.read { it.remaining() }.getOrElse { 0 } > 0, "the stream reads after its deadline passed")
                client.close(QuicCloseChoice.Immediate())
            }
        }

    @Test
    fun thePeersResetReachesTheReaderWithItsCode() =
        coreTest("CorePublicSurfaceTests.thePeersResetReachesTheReaderWithItsCode") {
            withCoreServer(onConnection = { core ->
                core.public().handleStreams { incoming ->
                    when (incoming) {
                        is QuicStream -> incoming.send.reset(QuicAppErrorCode(RESET_CODE))
                        is QuicReceiveStream -> Unit
                    }
                }
            }) { server ->
                val client = connect(server.port)
                val (send, receive) = client.openStream()
                assertIs<StreamWrite.Written>(send.write(bytes(payload())))
                val why = withTimeout(WAIT) { receive.readEach { } }
                assertEquals(QuicAppErrorCode(RESET_CODE), assertIs<ScopedRead.End.PeerReset>(why).code)
                client.close(QuicCloseChoice.Immediate())
            }
        }

    @Test
    fun aHandlerThatThrowsResetsItsStreamWithTheApplicationsCodeAndTheConnectionGoesOn() =
        coreTest("CorePublicSurfaceTests.aHandlerThatThrowsResetsItsStreamWithTheApplicationsCodeAndTheConnectionGoesOn") {
            val codes = QuicAbortCodes(handlerFailed = QuicAppErrorCode(HANDLER_CODE))
            withCoreServer(onConnection = { core ->
                core.public(codes).handleStreams { incoming ->
                    when (incoming.id.id) {
                        0L -> throw IllegalStateException("the first stream's handler fails")
                        else -> echo(incoming)
                    }
                }
            }) { server ->
                val client = connect(server.port)
                val (send, receive) = client.openStream()
                send.write(bytes(payload()))
                val why = withTimeout(WAIT) { receive.readEach { } }
                assertEquals(QuicAppErrorCode(HANDLER_CODE), assertIs<ScopedRead.End.PeerReset>(why).code)
                val reply =
                    client.openStream { (again, echoed) ->
                        again.writeAndFinish(bytes(payload()))
                        collect(echoed, ScopedRead.End.PeerFinished)
                    }
                assertContentEquals(payload(), reply, "the next stream is served")
                client.close(QuicCloseChoice.Immediate())
            }
        }

    @Test
    fun thePeersStopReachesTheWriterWithItsCodeAndStays() =
        coreTest("CorePublicSurfaceTests.thePeersStopReachesTheWriterWithItsCodeAndStays") {
            withCoreServer(onConnection = { core ->
                core.public().handleStreams { incoming ->
                    when (incoming) {
                        is QuicStream -> incoming.receive.stop(QuicAppErrorCode(STOP_CODE))
                        is QuicReceiveStream -> Unit
                    }
                }
            }) { server ->
                val client = connect(server.port)
                val (send, _) = client.openStream()
                val stopped =
                    withTimeout(WAIT) {
                        var result = send.write(bytes(payload()))
                        while (result is StreamWrite.Written) {
                            delay(10.milliseconds)
                            result = send.write(bytes(payload()))
                        }
                        result
                    }
                assertEquals(QuicAppErrorCode(STOP_CODE), assertIs<StreamWrite.NotWritten.PeerStopped>(stopped).code)
                assertSame(stopped, send.write(bytes(payload())), "a later write answers the same failure at once")
                assertSame(stopped, send.finish(), "and finish gives it as the verdict")
                client.close(QuicCloseChoice.Immediate())
            }
        }

    @Test
    fun aConnectionThePeerClosedEndsItsReadsAndWritesWithTheEndAsAValue() =
        coreTest("CorePublicSurfaceTests.aConnectionThePeerClosedEndsItsReadsAndWritesWithTheEndAsAValue") {
            withCoreServer(onConnection = { core ->
                val connection = core.public()
                connection.handleStreams { connection.close(QuicCloseChoice.Immediate(QuicError.ApplicationError(CLOSE_CODE))) }
            }) { server ->
                val client = connect(server.port)
                val (send, receive) = client.openStream()
                send.write(bytes(payload()))
                val why = withTimeout(WAIT) { receive.readEach { } }
                val end = assertIs<ScopedRead.End.ConnectionEnded>(why).end
                assertEquals(QuicError.ApplicationError(CLOSE_CODE), assertIs<QuicConnectionEnd.ByPeer>(end).error)
                val refused = assertIs<StreamWrite.NotWritten.ConnectionEnded>(send.write(bytes(payload())))
                assertIs<QuicConnectionEnd.ByPeer>(refused.end)
                assertSame<StreamWrite>(refused, send.finish())
                assertIs<QuicConnectionEnd.ByPeer>(withTimeout(WAIT) { client.awaitEnd() })
                assertIs<QuicCloseOutcome.EndedFirst>(client.close())
                assertFailsWith<QuicCloseException> { client.openStream() }
            }
        }

    @Test
    fun aSecondHandleStreamsIsATypedMisuseWhileTheFirstHandles() =
        coreTest("CorePublicSurfaceTests.aSecondHandleStreamsIsATypedMisuseWhileTheFirstHandles") {
            withCoreServer(onConnection = { core -> core.public().handleStreams(::echo) }) { server ->
                val client = connect(server.port)
                val first = CompletableDeferred<QuicConnectionEnd>()
                launch(start = CoroutineStart.UNDISPATCHED) { first.complete(client.handleStreams { }) }
                assertFailsWith<QuicStreamsAlreadyHandledException> { client.handleStreams { } }
                assertIs<QuicCloseOutcome.Immediate>(client.close(QuicCloseChoice.Immediate()))
                assertIs<QuicConnectionEnd.ByLocal>(withTimeout(WAIT) { first.await() })
            }
        }

    private suspend fun connect(port: Int): QuicConnection = connectCore(port, loops = loops).connection.public()

    /** Echoes a bidirectional stream until its peer finishes; a receive-only one is read to its end. */
    private suspend fun echo(incoming: QuicIncomingStream) {
        when (incoming) {
            is QuicStream -> incoming.receive.readEach { incoming.send.write(it) }
            is QuicReceiveStream -> incoming.readEach { }
        }
    }

    /** Everything [receive] gives until it stops, which must be for [expected]. */
    private suspend fun collect(
        receive: QuicReceiveStream,
        expected: ScopedRead.NoData,
    ): ByteArray {
        val got = ReceivedBytes(PAYLOAD)
        assertEquals(expected, withTimeout(WAIT) { receive.readEach { got.add(it) } })
        return got.toByteArray()
    }

    private fun bytes(content: ByteArray): ReadBuffer =
        BufferFactory.network().allocate(content.size).apply {
            writeBytes(content)
            resetForRead()
        }

    private fun payload() = ByteArray(PAYLOAD) { it.toByte() }

    private companion object {
        const val PAYLOAD = 5_000
        const val RESET_CODE = 0x33L
        const val HANDLER_CODE = 0x55L
        const val STOP_CODE = 0x42L
        const val CLOSE_CODE = 0x77L
        val WAIT = 10.seconds
    }
}
