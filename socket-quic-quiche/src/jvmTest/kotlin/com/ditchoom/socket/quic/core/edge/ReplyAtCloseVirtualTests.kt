package com.ditchoom.socket.quic.core.edge

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.Default
import com.ditchoom.buffer.flow.writeFully
import com.ditchoom.socket.quic.QuicCloseException
import com.ditchoom.socket.quic.QuicError
import com.ditchoom.socket.quic.QuicOptions
import com.ditchoom.socket.quic.QuicUnreadAtClose
import com.ditchoom.socket.quic.ScopedRead
import com.ditchoom.socket.quic.core.life.ConnEnd
import com.ditchoom.socket.quic.read
import kotlinx.coroutines.launch
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.seconds

/**
 * A reply the server wrote just before it closed is read by a client that reads only after the server's close
 * arrived: the connection's end does not discard bytes it received and acknowledged (RFC 9000 §10.2).
 */
class ReplyAtCloseVirtualTests {
    private val options =
        QuicOptions(alpnProtocols = listOf("test"), verifyPeer = false, idleTimeout = 30.seconds, enablePmtuDiscovery = false)

    @Test
    fun aReplyReceivedBeforeThePeersCloseIsReadAfterIt() =
        VirtualPair.with(options) { pair ->
            val serverSide = pair.accept()
            launch {
                serverSide.openStream().writeFully(bytes(REPLY), 1.hours)
                serverSide.close(QuicError.NoError)
            }
            val client = pair.client.connection
            pair.runUntil { client.end() is EdgeEnd.Ended }
            assertIs<ConnEnd.ByPeer>(assertIs<EdgeEnd.Ended>(client.end()).end, "the server's close ended the client")
            assertIs<QuicUnreadAtClose.Unread>(client.unreadAtClose.value, "the reply is still the client's to read")
            var read = ByteArray(0)
            var done = false
            launch {
                val stream = client.acceptStream()
                stream.read(1.hours) { read = it.readByteArray(it.remaining()) }
                done = true
            }
            pair.runUntil { done }
            assertEquals(REPLY.toList(), read.toList(), "the reply the server wrote before its close")
            assertEquals<QuicUnreadAtClose>(QuicUnreadAtClose.AllRead, client.unreadAtClose.value)
        }

    @Test
    fun aReplyWithItsFinIsReadToTheEndAndTheNextReadAfterAnUnfinishedOneRaisesTheClose() =
        VirtualPair.with(options) { pair ->
            val serverSide = pair.accept()
            launch {
                serverSide.openStream().apply {
                    writeFully(bytes(REPLY), 1.hours)
                    shutdownSend()
                }
                serverSide.openStream().writeFully(bytes(REPLY), 1.hours)
                serverSide.close(QuicError.NoError)
            }
            val client = pair.client.connection
            pair.runUntil { client.end() is EdgeEnd.Ended }
            val unread = assertIs<QuicUnreadAtClose.Unread>(client.unreadAtClose.value)
            assertEquals(2, unread.streams.size, "both streams still hold the reply: $unread")
            var finished: ScopedRead<Int> = ScopedRead.Reset
            var unfinished: Throwable = AssertionError("not read")
            var done = false
            launch {
                val first = client.acceptStream()
                assertIs<ScopedRead.Data<*>>(first.read(1.hours) { it.remaining() })
                finished = first.read(1.hours) { it.remaining() }
                val second = client.acceptStream()
                assertIs<ScopedRead.Data<*>>(second.read(1.hours) { it.remaining() })
                unfinished = assertFailsWith<QuicCloseException> { second.read(1.hours) { it.remaining() } }
                done = true
            }
            pair.runUntil { done }
            assertEquals<ScopedRead<Int>>(ScopedRead.End, finished, "the FIN that came with the reply ends its stream")
            assertIs<QuicCloseException>(unfinished)
            assertEquals<QuicUnreadAtClose>(QuicUnreadAtClose.AllRead, client.unreadAtClose.value)
        }

    private fun bytes(content: ByteArray) =
        BufferFactory.Default.allocate(content.size).apply {
            writeBytes(content)
            resetForRead()
        }

    private companion object {
        val REPLY = byteArrayOf(7, 8, 9)
    }
}
