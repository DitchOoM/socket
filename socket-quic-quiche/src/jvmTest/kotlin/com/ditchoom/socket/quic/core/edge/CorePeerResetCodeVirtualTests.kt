package com.ditchoom.socket.quic.core.edge

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.Default
import com.ditchoom.buffer.flow.ReadResult
import com.ditchoom.buffer.flow.writeFully
import com.ditchoom.socket.quic.QuicAppErrorCode
import com.ditchoom.socket.quic.QuicByteStream
import com.ditchoom.socket.quic.QuicError
import com.ditchoom.socket.quic.QuicOptions
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.seconds

/**
 * The peer's RESET_STREAM code reaches the reader, on a real core client and server on virtual time: a read of a live
 * stream the peer reset returns the reset and the stream's reads record its code, and so does a read made after the
 * connection ended, from what the end held for the stream.
 */
class CorePeerResetCodeVirtualTests {
    private val options =
        QuicOptions(alpnProtocols = listOf("test"), verifyPeer = false, idleTimeout = 30.seconds, enablePmtuDiscovery = false)

    @Test
    fun aReadOfALiveStreamThePeerResetCarriesThePeersCode() =
        VirtualPair.with(options) { pair ->
            val serverSide = pair.accept()
            val stream = pair.client.connection.openStream()
            written(pair, stream)
            resetByServer(pair, serverSide)
            val reads = StreamReads()
            assertIs<PeerReset.NotMet>(reads.reset.value(), "no read met the reset yet")
            assertIs<ReadResult.Reset>(readOnce(pair, stream, reads))
            assertEquals(QuicAppErrorCode(CODE), assertIs<PeerReset.Reset>(reads.reset.value()).code)
        }

    @Test
    fun aReadAfterTheConnectionEndedCarriesTheCodeOfTheResetTheEndHeld() =
        VirtualPair.with(options) { pair ->
            val serverSide = pair.accept()
            val stream = pair.client.connection.openStream()
            written(pair, stream)
            resetByServer(pair, serverSide)
            var closed = false
            launch {
                serverSide.close(QuicError.ApplicationError(1L))
                closed = true
            }
            pair.runUntil { closed && pair.client.connection.end() is EdgeEnd.Ended }
            val reads = StreamReads()
            assertIs<ReadResult.Reset>(readOnce(pair, stream, reads), "the reset the end held for the stream")
            assertEquals(QuicAppErrorCode(CODE), assertIs<PeerReset.Reset>(reads.reset.value()).code)
        }

    /** The server accepts the client's stream and resets it with [CODE]; the pair runs until the reset left. */
    private suspend fun CoroutineScope.resetByServer(
        pair: VirtualPair,
        serverSide: CoreConnection,
    ) {
        var reset = false
        launch {
            serverSide.acceptStream().reset(CODE)
            reset = true
        }
        pair.runUntil { reset }
        pair.runFor(pair.oneWay * 4)
    }

    /** One read of [stream] as its own reads make it, so [reads] records what it met. */
    private suspend fun CoroutineScope.readOnce(
        pair: VirtualPair,
        stream: QuicByteStream,
        reads: StreamReads,
    ): ReadResult {
        var result: ReadResult? = null
        launch { result = pair.client.connection.read(stream.streamId, 1.hours, reads) }
        pair.runUntil { result != null }
        return checkNotNull(result)
    }

    private suspend fun CoroutineScope.written(
        pair: VirtualPair,
        stream: QuicByteStream,
    ) {
        var done = false
        launch {
            val one =
                BufferFactory.Default
                    .allocate(1)
                    .apply { writeByte(1) }
                    .also { it.resetForRead() }
            stream.writeFully(one, 1.hours)
            done = true
        }
        pair.runUntil { done }
    }

    private companion object {
        const val CODE = 0x10cL
    }
}
