package com.ditchoom.socket.quic

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds

/**
 * A bulk download over a 50 ms round trip with no packet cap, run on virtual time: the receiver's flow
 * control window must open as the sender's congestion window does, so bytes delivered per round trip
 * grow past the initial stream window instead of staying at it.
 */
class DownloadWindowTests {
    private val env = jvmMigrationSimEnv()

    @Test
    fun aDownloadOverAFiftyMillisecondPathDeliversMoreThanTheInitialStreamWindowPerRoundTrip() =
        runTest {
            val options = migrationSimOptions()
            val initialWindow = options.flowControl.initialMaxStreamDataBidiLocal
            val pair = QuichePairByHand.open(env, options)
            try {
                val stream = QuicStreamId(0)
                pair.handshakeUpToTheClientsFinished(ONE_WAY)
                pair.streamSend(pair.client, stream, "request", fin = true)
                val chunk = "x".repeat(CHUNK)
                val delivered = ArrayList<Long>()
                repeat(ROUNDS) {
                    // The server fills what its windows allow and the datagrams cross; the client reads at
                    // once and answers in the same instant, so a window update costs one more crossing.
                    pair.streamSend(pair.server, stream, chunk, fin = false)
                    val toClient = pair.sends(pair.server)
                    pair.elapse(ONE_WAY)
                    pair.deliver(toClient, pair.client)
                    delivered += pair.readAll(pair.client, stream).first.toLong()
                    val toServer = pair.sends(pair.client)
                    pair.elapse(ONE_WAY)
                    pair.deliver(toServer, pair.server)
                }
                val best = delivered.drop(ROUNDS / 2).max()
                assertTrue(
                    best > 2 * initialWindow,
                    "the best late round trip delivered $best bytes (window $initialWindow); per round trip: $delivered",
                )
            } finally {
                pair.close()
            }
        }

    private companion object {
        val ONE_WAY = 25.milliseconds
        const val ROUNDS = 80
        const val CHUNK = 4 * 1024 * 1024
    }
}
