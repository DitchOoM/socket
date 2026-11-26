package com.ditchoom.socket.quic.core.edge

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.codec.DecodeContext
import com.ditchoom.buffer.flow.ReadResult
import com.ditchoom.buffer.freeIfNeeded
import com.ditchoom.socket.quic.QuicError
import com.ditchoom.socket.quic.ScopedReadResult
import com.ditchoom.socket.quic.core.loop.LoopSet
import com.ditchoom.socket.quic.core.testing.coreTest
import com.ditchoom.socket.quic.network
import kotlinx.coroutines.CompletableDeferred
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/**
 * Framed values on a bidirectional and on a unidirectional stream, over a real loopback socket on every platform: a typed
 * bidirectional stream carries a request and its reply, and a send-only stream carries two frames and its FIN, which its
 * receive-only peer reads to the end and decodes.
 */
class CoreFramedStreamTests {
    private val loops = LoopSet(1)

    @AfterTest
    fun closeLoops() = loops.close()

    @Test
    fun aBidirectionalFramedStreamCarriesARequestAndItsReply() =
        coreTest("CoreFramedStreamTests.aBidirectionalFramedStreamCarriesARequestAndItsReply") {
            withCoreServer(onConnection = { connection ->
                val stream = connection.acceptTypedStream(NoteCodec)
                val request = assertIs<ScopedReadResult.Data<Note>>(stream.receive()).value
                stream.send(Note(request.id, "echo: ${request.text}"))
                stream.close()
            }) { server ->
                val client = connectCore(server.port, loops = loops)
                val stream = client.connection.openTypedStream(NoteCodec)
                assertTrue(stream.id.id >= 0)
                stream.send(Note(1, "hello"))
                val reply = assertIs<ScopedReadResult.Data<Note>>(stream.receive()).value
                assertEquals(Note(1, "echo: hello"), reply)
                stream.close()
                client.connection.close(QuicError.NoError)
            }
        }

    @Test
    fun aUnidirectionalStreamCarriesFramedValuesAndItsFinToTheReceivingPeer() =
        coreTest("CoreFramedStreamTests.aUnidirectionalStreamCarriesFramedValuesAndItsFinToTheReceivingPeer") {
            val received = CompletableDeferred<List<Note>>()
            withCoreServer(onConnection = { connection ->
                val receive = assertIs<IncomingStream.ReceiveOnly>(connection.halves.accept()).receive
                val bytes = ReceivedBytes(FRAMES_MAX)
                while (true) {
                    when (val read = receive.read(10.seconds)) {
                        is ReadResult.Data -> {
                            bytes.add(read.buffer)
                            read.buffer.freeIfNeeded()
                        }
                        else -> break
                    }
                }
                val all = bytes.toByteArray()
                val buffer = BufferFactory.network().allocate(all.size)
                buffer.writeBytes(all)
                buffer.resetForRead()
                val notes = ArrayList<Note>()
                while (buffer.remaining() > 0) notes += NoteCodec.decode(buffer, DecodeContext.Empty)
                buffer.freeIfNeeded()
                received.complete(notes)
            }) { server ->
                val client = connectCore(server.port, loops = loops)
                val send = client.connection.halves.openSendOnly()
                assertTrue(send.id.isUnidirectional)
                for (note in SENT) {
                    val frame = frameOf(note)
                    while (frame.remaining() > 0) send.write(frame, 10.seconds)
                }
                send.finish()
                assertEquals(SENT, received.await(), "both frames, and the stream ended at its FIN")
                client.connection.close(QuicError.NoError)
            }
        }

    private companion object {
        val SENT = listOf(Note(1, "uni-1"), Note(2, "uni-2"))
        const val FRAMES_MAX = 256
    }
}
