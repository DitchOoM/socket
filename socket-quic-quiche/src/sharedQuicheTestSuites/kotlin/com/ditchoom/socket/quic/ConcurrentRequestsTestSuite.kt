package com.ditchoom.socket.quic

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.Charset
import com.ditchoom.buffer.flow.ReadResult
import com.ditchoom.buffer.flow.writeFully
import com.ditchoom.buffer.freeIfNeeded
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * A server reading many request streams at once hands every request's bytes to the read waiting for them.
 *
 * The shape of the interop runner's `multiplexing` case: the client opens [STREAMS] streams at once and
 * writes each request line in two parts a few milliseconds apart, then its FIN; the server reads each
 * stream until the line ends and answers with a FIN.
 */
abstract class ConcurrentRequestsTestSuite {
    /** The platform's `libquiche` binding, certificate fixtures and `sockaddr` layout. */
    internal abstract fun simEnv(): MigrationSimEnv

    /** A JVM/Android member turns a missing native into a typed skip; Kotlin/Native members keep this default. */
    protected open suspend fun wrapTestBody(block: suspend () -> Unit): Unit = block()

    @Test
    fun everyRequestOfManyConcurrentStreamsReachesTheServersRead() =
        runTest {
            wrapTestBody {
                val options =
                    migrationSimOptions().copy(
                        flowControl = FlowControl(initialMaxStreamsBidi = STREAMS.toLong()),
                    )
                withMigrationSim(simEnv(), seed = 3_001L, quicOptions = options) {
                    val served = mutableListOf<String>()
                    val timedOut = mutableListOf<Long>()
                    val answered = mutableListOf<Int>()
                    coroutineScope {
                        launch {
                            coroutineScope {
                                repeat(STREAMS) {
                                    val stream = server.acceptStream()
                                    launch {
                                        try {
                                            served += serve(stream)
                                        } catch (e: TimeoutCancellationException) {
                                            timedOut += stream.streamId.id
                                        }
                                    }
                                }
                            }
                        }
                        val random = Random(7)
                        repeat(STREAMS) { index ->
                            val gap = random.nextInt(1, 40).milliseconds
                            launch {
                                val stream = client.openStream()
                                stream.writeText("GET /", 5.seconds)
                                delay(gap)
                                stream.writeText("file-$index\r\n", 5.seconds)
                                stream.shutdownSend()
                                if (stream.readAll().startsWith("ok")) answered += index
                                stream.close()
                            }
                        }
                    }
                    assertEquals(
                        emptyList(),
                        timedOut,
                        "the server's read of these streams never got the request bytes quiche had taken in " +
                            "(${served.size} served, ${answered.size} answered of $STREAMS)",
                    )
                    assertEquals(STREAMS, answered.size, "every request must be answered")
                }
            }
        }

    /** Reads the request line, then answers it with a FIN. */
    private suspend fun serve(stream: QuicByteStream): String {
        val request = StringBuilder()
        while ('\n' !in request) {
            when (val read = stream.read(READ_BOUND)) {
                is ReadResult.Data ->
                    try {
                        request.append(read.buffer.readString(read.buffer.remaining(), Charset.UTF8))
                    } finally {
                        read.buffer.freeIfNeeded()
                    }
                ReadResult.End, ReadResult.Reset -> break
            }
        }
        val reply = BufferFactory.network().allocate(2)
        reply.writeString("ok", Charset.UTF8)
        reply.resetForRead()
        stream.writeFullyAndShutdownSend(reply, 5.seconds)
        reply.freeNativeMemory()
        return request.toString()
    }

    private suspend fun QuicByteStream.writeText(
        text: String,
        bound: kotlin.time.Duration,
    ) {
        val buffer = BufferFactory.network().allocate(text.length)
        buffer.writeString(text, Charset.UTF8)
        buffer.resetForRead()
        writeFully(buffer, bound)
        buffer.freeNativeMemory()
    }

    private suspend fun QuicByteStream.readAll(): String {
        val text = StringBuilder()
        while (true) {
            when (val read = read(READ_BOUND)) {
                is ReadResult.Data ->
                    try {
                        text.append(read.buffer.readString(read.buffer.remaining(), Charset.UTF8))
                    } finally {
                        read.buffer.freeIfNeeded()
                    }
                ReadResult.End, ReadResult.Reset -> return text.toString()
            }
        }
    }

    private companion object {
        const val STREAMS = 1000
        val READ_BOUND = 30.seconds
    }
}
