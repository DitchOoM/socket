package com.ditchoom.socket.testkit.echo

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.Charset
import com.ditchoom.buffer.Default
import com.ditchoom.buffer.ReadBuffer
import com.ditchoom.buffer.flow.ByteSink
import com.ditchoom.buffer.flow.BytesWritten
import com.ditchoom.buffer.flow.WritePolicy
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * [writeEcho] against a sink that takes a scripted number of bytes per call, the way a QUIC stream
 * takes only what its flow-control credit allows. The 10-26 walk's two integrity breaks were exactly
 * this: a record's head went out, the probe counted it whole, and its tail never did.
 */
class EchoWriteFullyTests {
    /** Takes [script]'s next count per call; once the script runs out, blocks until cancelled. */
    private class ScriptedSink(
        vararg script: Int,
    ) : ByteSink {
        private val script = ArrayDeque(script.toList())
        val received = StringBuilder()
        override val isOpen: Boolean = true
        override val writePolicy: WritePolicy get() = error("unused")

        override suspend fun write(
            buffer: ReadBuffer,
            deadline: Duration,
        ): BytesWritten {
            val take = script.removeFirstOrNull() ?: awaitCancellation()
            val n = minOf(take, buffer.remaining())
            received.append(buffer.readString(n, Charset.UTF8))
            return BytesWritten(n)
        }
    }

    private suspend fun ScriptedSink.echo(payload: String): EchoWrite {
        val out = BufferFactory.Default.allocate(payload.length)
        out.writeString(payload, Charset.UTF8)
        out.resetForRead()
        return writeEcho(out, 5.seconds)
    }

    @Test
    fun aPartialWriteIsResumedUntilTheRecordIsWhole() =
        runTest {
            val sink = ScriptedSink(8, 4)
            assertEquals(EchoWrite.Written, sink.echo("probe-23277;"))
            assertEquals("probe-23277;", sink.received.toString())
        }

    @Test
    fun aDeadlineAfterPartOfTheRecordWentOutIsTornNotTimedOut() =
        runTest {
            val sink = ScriptedSink(8)
            assertEquals(EchoWrite.Torn(accepted = 8, pending = 4), sink.echo("probe-23277;"))
            assertEquals("probe-23", sink.received.toString())
        }

    @Test
    fun aDeadlineBeforeAnyByteWentOutLeavesTheStreamIntact() =
        runTest {
            val sink = ScriptedSink()
            assertEquals(EchoWrite.TimedOut, sink.echo("probe-23277;"))
            assertEquals("", sink.received.toString())
        }

    @Test
    fun aTornWriteEndsTheSession() {
        val session = EchoSession(connectedAt = Duration.ZERO)
        val step = session.writeTorn(seq = 23277, torn = EchoWrite.Torn(accepted = 8, pending = 4), at = 1.seconds)
        assertEquals(
            SessionEnd.StreamGone.WriteTorn(accepted = 8, pending = 4),
            (step as EchoStep.Reconnect).end,
        )
    }
}
