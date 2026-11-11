package com.ditchoom.socket

import com.ditchoom.buffer.flow.ReadResult
import com.ditchoom.data.readFlow
import com.ditchoom.socket.transport.CodecConnection
import com.ditchoom.socket.transport.TestStringCodec
import com.ditchoom.socket.transport.testCodecConnection
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.test.fail
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * A read reports [ReadResult.End] only for the peer's graceful close, the contract `ReadResult.End`
 * states. A peer's RST is [ReadResult.Reset], and a read that this side's own close cut short fails
 * with a [SocketClosedException]; neither may read as a clean end-of-stream, or the caller takes a
 * failure for a finished exchange.
 *
 * The peer is a [RawTcpPeer], made with the platform's own sockets, so it can end a connection with a
 * RST (`SO_LINGER` 0), which this library never does on purpose. The reads are on the server's side
 * of the connection.
 */
class ReadEndsTruthfullyTests {
    @Test
    fun aPeerResetWithNoDataReadsAsReset() =
        withAcceptedRawPeer { accepted, peer ->
            peer.reset()
            val (bytes, end) = readUntilEnd(accepted)
            assertEquals(0, bytes.size)
            assertEquals<Any>(ReadResult.Reset, end, "a RST from the peer must read as Reset")
        }

    @Test
    fun aPeerResetAfterDataReadsWhatArrivedThenReset() =
        withAcceptedRawPeer { accepted, peer ->
            peer.write(PAYLOAD)
            delay(50.milliseconds) // let the bytes land before the RST
            peer.reset()
            val (bytes, end) = readUntilEnd(accepted)
            assertTrue(PAYLOAD.copyOf(bytes.size).contentEquals(bytes), "read ${bytes.toList()}, not a prefix of the payload")
            assertEquals<Any>(ReadResult.Reset, end, "a RST from the peer must read as Reset, after ${bytes.size} bytes")
        }

    /** The graceful close stays [ReadResult.End], on Apple by way of NW's ENODATA on the read after the FIN. */
    @Test
    fun aPeerGracefulCloseAfterDataReadsTheDataThenEnd() =
        withAcceptedRawPeer { accepted, peer ->
            peer.write(PAYLOAD)
            peer.close()
            val (bytes, end) = readUntilEnd(accepted)
            assertTrue(PAYLOAD.contentEquals(bytes), "read ${bytes.toList()}")
            assertEquals<Any>(ReadResult.End, end)
        }

    @Test
    fun aReadThisSidesCloseCutsShortFailsClosedNotEnd() =
        withAcceptedRawPeer { accepted, _ ->
            val parked =
                async(start = CoroutineStart.UNDISPATCHED) {
                    runCatching { accepted.read(5.seconds) }
                }
            delay(100.milliseconds) // the read is outstanding in the platform
            accepted.close()
            val outcome = withTimeout(5.seconds) { parked.await() }
            val thrown = outcome.exceptionOrNull() ?: fail("a read cut short by this side's close returned ${outcome.getOrNull()}")
            assertIs<SocketClosedException>(thrown, "got ${thrown::class.simpleName}: ${thrown.message}")
            assertTrue(thrown !is SocketClosedException.EndOfStream, "this side's close is not the peer's EOF: $thrown")
        }

    /** After this side's close, a read fails closed instead of claiming the peer's EOF. */
    @Test
    fun aReadAfterThisSidesCloseFailsClosedNotEnd() =
        withAcceptedRawPeer { accepted, _ ->
            accepted.close()
            val outcome = runCatching { accepted.read(1.seconds) }
            val thrown = outcome.exceptionOrNull() ?: fail("a read after this side's close returned ${outcome.getOrNull()}")
            assertIs<SocketClosedException>(thrown)
            assertTrue(thrown !is SocketClosedException.EndOfStream, "$thrown")
        }

    /** [readFlow] stays quiet when its collector's owner closes the socket during a read. */
    @Test
    fun aReadFlowEndsQuietlyWhenThisSideClosesDuringARead() =
        withAcceptedRawPeer { accepted, _ ->
            val collected =
                async(start = CoroutineStart.UNDISPATCHED) {
                    runCatching { accepted.readFlow(5.seconds).toList() }
                }
            delay(100.milliseconds) // the read is outstanding in the platform
            accepted.close()
            val outcome = withTimeout(5.seconds) { collected.await() }
            assertEquals(0, outcome.getOrElse { fail("readFlow failed on this side's own close: $it") }.size)
        }

    /** A [CodecConnection]'s receive stays quiet when the connection itself is closed during a read. */
    @Test
    fun aCodecConnectionReceiveEndsQuietlyWhenTheConnectionIsClosedDuringARead() =
        withAcceptedRawPeer { accepted, _ ->
            val connection = testCodecConnection(accepted, TestStringCodec)
            val collected =
                async(start = CoroutineStart.UNDISPATCHED) {
                    runCatching { connection.receive().toList() }
                }
            delay(100.milliseconds) // the read is outstanding in the platform
            connection.close()
            val outcome = withTimeout(5.seconds) { collected.await() }
            assertEquals(0, outcome.getOrElse { fail("receive() failed on the connection's own close: $it") }.size)
        }

    private fun withAcceptedRawPeer(block: suspend kotlinx.coroutines.CoroutineScope.(ClientSocket, RawTcpPeer) -> Unit) =
        runTestNoTimeSkipping {
            if (!networkCapabilities().transports.contains(TransportKind.TCP)) return@runTestNoTimeSkipping
            val server = ServerSocket.allocate()
            val connections = server.bind()
            // Keep collecting: on some backends (Node) ending the accept flow's collection closes the
            // connections it accepted.
            val first = CompletableDeferred<ClientSocket>()
            val accepting = launch { connections.collect { first.complete(it) } }
            try {
                val peer = connectRawTcpPeer(server.port())
                val accepted = withTimeout(5.seconds) { first.await() }
                try {
                    block(accepted, peer)
                } finally {
                    accepted.close()
                    runCatching { peer.close() }
                }
            } finally {
                server.close()
                accepting.cancel()
            }
        }

    /** Reads until something other than data; the bytes read, and the [ReadResult] or exception it ended with. */
    private suspend fun readUntilEnd(socket: ClientSocket): Pair<ByteArray, Any> {
        var bytes = ByteArray(0)
        while (true) {
            val result =
                try {
                    socket.read(5.seconds)
                } catch (e: SocketException) {
                    return bytes to e
                }
            when (result) {
                is ReadResult.Data -> bytes += result.buffer.readByteArray(result.buffer.remaining())
                else -> return bytes to result
            }
        }
    }

    private companion object {
        val PAYLOAD = byteArrayOf(0x48, 0x00, 0x65, 0x00, 0x6C, 0x00, 0x6C, 0x00, 0x6F, 0x00)
    }
}
