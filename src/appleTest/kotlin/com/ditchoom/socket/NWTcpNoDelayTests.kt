package com.ditchoom.socket

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.Default
import com.ditchoom.data.readBuffer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

/**
 * Regression coverage for [IoTuning.tcpNoDelay] on the Network.framework path.
 *
 * Before the fix, `nw_helper_create_tcp_connection` never called
 * `nw_tcp_options_set_no_delay`, so `tcpNoDelay = true` was silently ignored on Apple
 * targets while the JVM/Node/Linux implementations honored it. Sequential
 * request/response traffic (WebSocket echo, MQTT ping) then stalled on Nagle +
 * delayed-ACK — observed in CI as ~12-35ms per Autobahn echo round-trip on macOS vs
 * 1-8ms on Linux for the same Kotlin/Native code.
 *
 * Loopback timing on shared CI runners is too noisy for a strict latency assertion, so
 * this test asserts correctness through the no-delay code path end-to-end and logs the
 * measured round-trip latency for eyeballing in CI output.
 */
class NWTcpNoDelayTests {
    @Test
    fun sequentialEchoRoundTripsWithTcpNoDelay() =
        runTestNoTimeSkipping(timeout = 60.seconds) {
            val rounds = 100
            val payloadSize = 1024
            val server = ServerSocket.allocate()
            val serverFlow = server.bind()

            val serverJob =
                launch(Dispatchers.Default) {
                    serverFlow.collect { connected ->
                        launch(Dispatchers.Default) {
                            try {
                                while (true) {
                                    val buffer = connected.readBuffer(10.seconds)
                                    connected.write(buffer, 10.seconds)
                                }
                            } catch (_: Exception) {
                                // client closed the connection — echo loop is done
                            } finally {
                                connected.close()
                            }
                        }
                    }
                }

            val client = ClientSocket.allocate()
            client.open(
                server.port(),
                hostname = "127.0.0.1",
                config =
                    TransportConfig(
                        io = IoTuning(tcpNoDelay = true),
                        connectTimeout = 5.seconds,
                    ),
            )

            val payload = ByteArray(payloadSize) { (it % 251).toByte() }
            var totalReceived = 0
            val mark = TimeSource.Monotonic.markNow()
            repeat(rounds) {
                val sendBuffer = BufferFactory.Default.allocate(payloadSize)
                sendBuffer.writeBytes(payload)
                sendBuffer.resetForRead()
                client.write(sendBuffer, 10.seconds)

                var receivedThisRound = 0
                while (receivedThisRound < payloadSize) {
                    val echoed = client.readBuffer(10.seconds)
                    receivedThisRound += echoed.remaining()
                }
                totalReceived += receivedThisRound
            }
            val elapsed = mark.elapsedNow()
            println(
                "NWTcpNoDelay: $rounds x ${payloadSize}B echo round-trips in $elapsed " +
                    "(avg ${elapsed / rounds} per round-trip)",
            )

            assertEquals(rounds * payloadSize, totalReceived, "every echoed byte should arrive")

            client.close()
            server.close()
            serverJob.cancel()
        }
}
