package com.ditchoom.socket

import com.ditchoom.buffer.flow.WritePolicy
import com.ditchoom.socket.harness.NonDrainingPeer
import com.ditchoom.socket.harness.WriteOutcome
import com.ditchoom.socket.harness.writeOutcome
import kotlin.test.Test
import kotlin.test.fail
import kotlin.time.Duration.Companion.seconds

// TEMPORARY diagnostic for the Windows lane; removed before merge.
class TempSelectorWriteTimeoutProbe {
    @Test
    fun probe() =
        runTestNoTimeSkipping(timeout = 300.seconds) {
            val savedAsync = useAsyncChannels
            val savedBlocking = useNioBlocking
            useAsyncChannels = false
            useNioBlocking = false
            val lines = mutableListOf<String>()
            try {
                repeat(25) { i ->
                    val peer = NonDrainingPeer.start()
                    val client =
                        ClientSocket.connect(
                            peer.port,
                            config =
                                TransportConfig(
                                    writePolicy = WritePolicy.Bounded(1.seconds),
                                    connectTimeout = 5.seconds,
                                    io = IoTuning(sendBuffer = NonDrainingPeer.SMALL_SOCKET_BUFFER),
                                ),
                        )
                    try {
                        peer.awaitAccepted()
                        val o = client.writeOutcome(1.seconds, 6.seconds)
                        val open = client.isOpen
                        val line =
                            when (o) {
                                is WriteOutcome.Threw ->
                                    "#$i open=$open ${o.error::class.qualifiedName}(${o.error.message}) cause=${o.error.cause} " +
                                        "elapsed=${o.elapsed} bytes=${o.bytesBefore} blocked=${o.blockedFor} " +
                                        "stack=${o.error.stackTrace.take(6).joinToString(" | ")}"
                                else -> "#$i open=$open $o"
                            }
                        lines += line
                    } finally {
                        client.close()
                        peer.close()
                    }
                }
            } finally {
                useAsyncChannels = savedAsync
                useNioBlocking = savedBlocking
            }
            println("PROBE-RESULTS\n" + lines.joinToString("\n"))
            if (System.getProperty("os.name").startsWith("Windows")) fail("PROBE-RESULTS\n" + lines.joinToString("\n"))
        }
}
