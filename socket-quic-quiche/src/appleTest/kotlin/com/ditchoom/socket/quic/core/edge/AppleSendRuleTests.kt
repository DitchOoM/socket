package com.ditchoom.socket.quic.core.edge

import com.ditchoom.socket.quic.QuicError
import com.ditchoom.socket.quic.core.demux.withCoreEchoServer
import com.ditchoom.socket.quic.core.loop.LoopSet
import com.ditchoom.socket.quic.core.sched.SendRule
import com.ditchoom.socket.quic.core.sched.sendRuleOfThisRuntime
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlin.random.Random
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs

/** On Apple only the loop sends: callers on any dispatcher hand their work over, client and server alike. */
class AppleSendRuleTests {
    private val loops = LoopSet(1)

    @AfterTest
    fun closeLoops() = loops.close()

    @Test
    fun everySendOnAppleLeavesFromTheLoop() =
        runBlocking {
            assertIs<SendRule.ByLoop>(sendRuleOfThisRuntime)
            withCoreEchoServer { server ->
                val client = connectCore(server.port, loops = loops)
                try {
                    withContext(Dispatchers.Default) {
                        (0 until STREAMS)
                            .map { n ->
                                async {
                                    val payload = Random(n).nextBytes(BYTES)
                                    assertContentEquals(payload, echoOver(client.connection.openStream(), payload), "stream $n")
                                }
                            }.awaitAll()
                    }
                    assertEquals(0L, client.conn.counts.enteredInPlace, "callers entered the client connection")
                    assertEquals(0L, client.loop.callerCounts.sendsInPlace, "callers sent in place on the client")
                    assertEquals(0L, server.loop.callerCounts.sendsInPlace, "callers sent in place on the server")
                } finally {
                    client.connection.close(QuicError.NoError)
                }
            }
        }

    private companion object {
        const val STREAMS = 4
        const val BYTES = 2 shl 20
    }
}
