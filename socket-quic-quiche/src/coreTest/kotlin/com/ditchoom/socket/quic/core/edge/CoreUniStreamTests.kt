package com.ditchoom.socket.quic.core.edge

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.flow.ReadResult
import com.ditchoom.buffer.freeIfNeeded
import com.ditchoom.socket.quic.QuicError
import com.ditchoom.socket.quic.core.loop.LoopSet
import com.ditchoom.socket.quic.core.testing.coreTest
import com.ditchoom.socket.quic.network
import kotlinx.coroutines.awaitCancellation
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.time.Duration.Companion.seconds

/**
 * Unidirectional streams over a real loopback socket, on every platform: the client's send-only stream reaches the
 * server as receive-only with its bytes and FIN, and the server answers on a send-only stream of its own.
 */
class CoreUniStreamTests {
    private val loops = LoopSet(1)

    @AfterTest
    fun closeLoops() = loops.close()

    @Test
    fun eachSideSendsOnASendOnlyStreamThatThePeerReceivesAsReceiveOnly() =
        coreTest("CoreUniStreamTests.eachSideSendsOnASendOnlyStreamThatThePeerReceivesAsReceiveOnly") {
            withCoreServer(onConnection = { connection ->
                val request = assertIs<IncomingStream.ReceiveOnly>(connection.halves.accept()).receive
                val got = readToEnd(request)
                val reply = connection.halves.openSendOnly()
                sendAll(reply, got)
                awaitCancellation()
            }) { server ->
                val client = connectCore(server.port, loops = loops, options = acceptingClientOptions)
                val request = client.connection.halves.openSendOnly()
                assertEquals(2L, request.id.id, "the client's first unidirectional stream")
                sendAll(request, payload())
                val reply = assertIs<IncomingStream.ReceiveOnly>(client.connection.halves.accept()).receive
                assertEquals(3L, reply.id.id, "the server's first unidirectional stream")
                assertContentEquals(payload(), readToEnd(reply))
                client.connection.close(QuicError.NoError)
            }
        }

    private suspend fun sendAll(
        send: SendHalf,
        bytes: ByteArray,
    ) {
        val source = BufferFactory.network().allocate(bytes.size)
        source.writeBytes(bytes)
        source.resetForRead()
        while (source.remaining() > 0) send.write(source, 10.seconds)
        send.finish()
    }

    private suspend fun readToEnd(receive: ReceiveHalf): ByteArray {
        val out = ArrayList<Byte>()
        while (true) {
            when (val r = receive.read(10.seconds)) {
                is ReadResult.Data -> {
                    while (r.buffer.remaining() > 0) out += r.buffer.readByte()
                    r.buffer.freeIfNeeded()
                }
                else -> return out.toByteArray()
            }
        }
    }

    private fun payload() = ByteArray(PAYLOAD) { it.toByte() }

    private companion object {
        const val PAYLOAD = 5_000
    }
}
