package com.ditchoom.socket.quic.core.edge

import com.ditchoom.socket.quic.QuicOptions
import com.ditchoom.socket.quic.QuicStreamId
import com.ditchoom.socket.quic.QuicStreamReadError
import com.ditchoom.socket.quic.QuicStreamReadException
import com.ditchoom.socket.quic.QuicheApi
import com.ditchoom.socket.quic.QuicheConn
import com.ditchoom.socket.quic.StreamRecvStep
import kotlinx.coroutines.launch
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.seconds

/**
 * A quiche stream error that is not a peer reset fails the read as a typed failure carrying the stream and quiche's code,
 * on a real core client whose quiche answers one stream's reads with an error: never an end, never a reset.
 */
class CoreStreamReadErrorVirtualTests {
    private val options =
        QuicOptions(alpnProtocols = listOf("test"), verifyPeer = false, idleTimeout = 30.seconds, enablePmtuDiscovery = false)

    /** The client's quiche, answering reads of [failing] with [code] while [code] is not zero. */
    private class FailingReads {
        var failing = QuicStreamId(0L)
        var code = 0

        fun around(real: QuicheApi): QuicheApi =
            object : QuicheApi by real {
                override fun connStreamRecvStep(
                    conn: QuicheConn,
                    streamId: QuicStreamId,
                    buf: Long,
                    bufLen: Int,
                ): StreamRecvStep =
                    if (code != 0 && streamId == failing) {
                        StreamRecvStep.error(code)
                    } else {
                        real.connStreamRecvStep(conn, streamId, buf, bufLen)
                    }
            }
    }

    @Test
    fun aQuicheStreamErrorFailsTheReadTypedNotAsAnEnd() =
        VirtualPair.with(options, clientApi = FailingReads().also { faults = it }::around) { pair ->
            val stream = pair.client.connection.openStream()
            faults.failing = stream.streamId
            faults.code = QUICHE_ERR_INVALID_STREAM_STATE
            var failure: Throwable? = null
            launch { failure = runCatching { stream.read(1.hours) }.exceptionOrNull() }
            pair.runUntil { failure != null }
            val read = assertIs<QuicStreamReadException>(failure, "a quiche stream error must not read as a clean end: $failure")
            assertEquals(QuicStreamReadError.InvalidStreamState, read.error)
            assertEquals(stream.streamId.id, read.streamId)
        }

    @Test
    fun anUnknownQuicheCodeKeepsItsRawValue() =
        VirtualPair.with(options, clientApi = FailingReads().also { faults = it }::around) { pair ->
            val stream = pair.client.connection.openStream()
            faults.failing = stream.streamId
            faults.code = FLOW_CONTROL
            var failure: Throwable? = null
            launch { failure = runCatching { stream.read(1.hours) }.exceptionOrNull() }
            pair.runUntil { failure != null }
            assertEquals(QuicStreamReadError.Quiche(FLOW_CONTROL), assertIs<QuicStreamReadException>(failure).error)
        }

    private lateinit var faults: FailingReads

    private companion object {
        /** quiche's INVALID_STREAM_STATE. */
        const val QUICHE_ERR_INVALID_STREAM_STATE = -7

        /** FLOW_CONTROL: not a documented stream_recv code. */
        const val FLOW_CONTROL = -11
    }
}
