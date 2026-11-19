package com.ditchoom.socket.quic

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.UnsafeMemory
import com.ditchoom.buffer.nativeMemoryAccess
import com.ditchoom.socket.quic.core.life.QuicheConnRef
import com.ditchoom.socket.quic.core.stream.ReadableList
import com.ditchoom.socket.quic.core.stream.StreamsBlock
import com.ditchoom.socket.quic.core.stream.WritableTail
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * [QuicheApi.connStreams] on a real quiche pair, through the backend under test: the block lists what quiche's readable
 * iterator and writable marks say, in the layout this code reads, and a turn with more readable streams than fit is
 * told so instead of handed part of them. Each backend's own native entry runs here, so a stale or misbound one fails.
 */
abstract class StreamsBlockTestSuite {
    internal abstract fun simEnv(): MigrationSimEnv

    protected open suspend fun wrapTestBody(block: suspend () -> Unit): Unit = block()

    @Test
    fun theBackendWritesTheLayoutThisCodeReads() =
        runTest {
            wrapTestBody {
                assertEquals(STREAMS_LAYOUT, simEnv().api.connStreamsLayout(), "QN_STREAMS_LAYOUT of the loaded library")
            }
        }

    @Test
    fun aTurnReadsEveryReadableStreamAndTakesTheWritableOnes() =
        runTest {
            wrapTestBody {
                withServerHolding(streams = 5) { block, _ ->
                    block.take()
                    assertEquals(ReadableList.Listed, block.readableList())
                    assertEquals(clientStreams(5), (0 until block.readable()).map { block.id(it).id }.toSet(), "readable")
                    val writable = (block.readable() until block.readable() + block.writable()).map { block.id(it).id }.toSet()
                    assertEquals(clientStreams(5), writable, "the streams the peer opened are writable")
                    assertEquals(WritableTail.Taken, block.writableTail())

                    block.take()
                    assertEquals(clientStreams(5), (0 until block.readable()).map { block.id(it).id }.toSet(), "still readable")
                    assertEquals(0, block.writable(), "writable marks are taken once")
                }
            }
        }

    @Test
    fun moreReadableStreamsThanFitAreSpilledWholeAndTheWritableOnesStillTaken() =
        runTest {
            wrapTestBody {
                val streams = StreamsBlock.IDS + 6
                withServerHolding(streams) { block, writableNext ->
                    block.take()
                    assertEquals(ReadableList.Spilled, block.readableList(), "more than the block holds")
                    assertEquals(0, block.readable(), "a spilled list writes none of its ids")
                    val first = (0 until block.writable()).map { block.id(it).id }
                    assertEquals(StreamsBlock.IDS, first.size, "the writable ids fill the block")
                    assertEquals(WritableTail.MaybeMore, block.writableTail())
                    val rest = generateSequence { writableNext().takeIf { it >= 0L } }.toList()
                    assertEquals(streams - StreamsBlock.IDS, rest.size, "the rest stay marked in quiche")
                    assertEquals(clientStreams(streams), (first + rest).toSet(), "every stream the peer opened, each once")
                }
            }
        }

    private fun clientStreams(count: Int): Set<Long> = (0 until count).map { it * 4L }.toSet()

    /**
     * The server of an established pair after the client opened [streams] streams with a byte each; [body] gets its block,
     * filled with a pattern first so nothing passes on memory that happened to be zero, and its writable-next call.
     */
    private suspend fun withServerHolding(
        streams: Int,
        body: (StreamsBlock, () -> Long) -> Unit,
    ) {
        val env = simEnv()
        val pair = QuichePairByHand.open(env)
        val memory = BufferFactory.network().allocate(StreamsBlock.bytesFor(StreamsBlock.IDS))
        try {
            pair.settle()
            check(pair.established(pair.client) && pair.established(pair.server)) { "the handshake must complete" }
            repeat(streams) { assertTrue(pair.streamSend(pair.client, QuicStreamId(it * 4L), "x", fin = false) > 0, "stream ${it * 4}") }
            pair.deliver(pair.sends(pair.client), pair.server)
            val address = memory.nativeMemoryAccess?.nativeAddress?.toLong() ?: fail("the network factory makes native memory")
            val size = StreamsBlock.bytesFor(StreamsBlock.IDS)
            for (at in 0 until size step 8) UnsafeMemory.putLong(address + at, POISON)
            body(StreamsBlock(QuicheConnRef(env.api, pair.server), address, size)) { env.api.connStreamWritableNext(pair.server) }
        } finally {
            memory.freeNativeMemory()
            pair.close()
        }
    }

    private companion object {
        const val POISON = 0x5A5A5A5A5A5A5A5AL
    }
}
