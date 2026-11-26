package com.ditchoom.socket.quic.core.edge

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.Default
import com.ditchoom.buffer.PlatformBuffer
import com.ditchoom.buffer.flow.BytesWritten
import com.ditchoom.buffer.flow.ReadResult
import com.ditchoom.buffer.freeIfNeeded
import com.ditchoom.socket.quic.QuicAppErrorCode
import com.ditchoom.socket.quic.QuicCloseException
import com.ditchoom.socket.quic.QuicOptions
import com.ditchoom.socket.quic.QuicStreamAbort
import com.ditchoom.socket.quic.QuicStreamException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * How a write ends, on a real core client and server on virtual time: an empty write returns at once and sends nothing,
 * and a write on a stream the peer stopped fails with that stream's STOP_SENDING code while the connection and its other
 * streams carry on.
 */
class CoreStreamWriteEndsVirtualTests {
    private val options =
        QuicOptions(alpnProtocols = listOf("test"), verifyPeer = false, idleTimeout = 30.seconds, enablePmtuDiscovery = false)

    @Test
    fun anEmptyWriteReturnsZeroAtOnceAndNeitherSendsNorFinishesTheStream() =
        VirtualPair.with(options) { pair ->
            val server = pair.accept()
            val stream = pair.client.connection.openStream()
            var wrote: BytesWritten? = null
            val asked = pair.now()
            launch { wrote = stream.write(BufferFactory.Default.allocate(0).also { it.resetForRead() }, 1.hours) }
            pair.runUntil { wrote != null }
            assertEquals(BytesWritten(0), wrote)
            assertTrue(pair.now() == asked, "an empty write took no virtual time: ${pair.now() - asked} ns")

            // Nothing of it reached the peer: the stream is still open, and what is written next arrives alone.
            launch { stream.write(bytes(3), 1.hours) }
            var got = -1
            launch {
                val peer = server.acceptStream()
                val read = peer.read(1.hours)
                got = (read as? ReadResult.Data)?.buffer?.remaining() ?: -2
                (read as? ReadResult.Data)?.buffer?.freeIfNeeded()
            }
            pair.runUntil { got != -1 }
            assertEquals(3, got, "the three bytes written after the empty write, and no end before them")
        }

    @Test
    fun aWriteOnAStreamThePeerStoppedFailsWithThePeersCodeAndTheConnectionStaysUsable() =
        VirtualPair.with(options) { pair ->
            val server = pair.accept()
            val client = pair.client.connection
            val stopped = client.openStream()
            written(pair, stopped)
            val incoming = assertIs<IncomingStream.Bidirectional>(opened(pair) { server.halves.accept() })
            assertEquals(Stopping.Now, incoming.stream.receive.stop(CODE))

            var failure: Throwable? = null
            launch { failure = runCatching { while (true) stopped.write(bytes(64), 1.hours) }.exceptionOrNull() }
            pair.runUntil { failure != null }
            val error = assertIs<QuicStreamException>(failure, "a stopped stream is not a closed connection: $failure")
            assertEquals(stopped.streamId.id, error.streamId, "the failure names the affected stream")
            assertEquals(QuicStreamAbort.StopSending(CODE), error.abort)
            assertEquals(CODE, error.abort.applicationErrorCode)
            assertTrue((failure as Any) !is QuicCloseException)

            // A different stream of the same connection still goes through.
            val healthy = client.openStream()
            written(pair, healthy)
            var got = -1
            launch {
                val peer = server.acceptStream()
                val read = peer.read(1.hours)
                got = (read as? ReadResult.Data)?.buffer?.remaining() ?: -2
                (read as? ReadResult.Data)?.buffer?.freeIfNeeded()
            }
            pair.runUntil { got != -1 }
            assertEquals(1, got, "the connection carried another stream after one was stopped")
            assertIs<EdgeEnd.Open>(client.end())
        }

    /** Runs [open] to its answer, passing the pair's loops meanwhile. */
    private suspend fun <S : Any> CoroutineScope.opened(
        pair: VirtualPair,
        open: suspend () -> S,
    ): S {
        var made: S? = null
        launch { made = open() }
        pair.runUntil { made != null }
        return checkNotNull(made)
    }

    /** One byte written on [stream], so the peer learns of it. */
    private suspend fun CoroutineScope.written(
        pair: VirtualPair,
        stream: com.ditchoom.socket.quic.QuicByteStream,
    ) {
        var done = false
        launch {
            stream.write(bytes(1), 1.hours)
            done = true
        }
        pair.runUntil { done }
        pair.runFor(100.milliseconds)
    }

    private fun bytes(size: Int): PlatformBuffer =
        BufferFactory.Default.allocate(size).apply {
            repeat(size) { writeByte(it.toByte()) }
            resetForRead()
        }

    private companion object {
        val CODE = QuicAppErrorCode(0x10c)
    }
}
