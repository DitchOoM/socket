package com.ditchoom.socket

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.Default
import com.ditchoom.data.readBuffer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.seconds

/**
 * Pins the Writer contract: `write(buffer)` must send exactly `buffer.remaining()` bytes
 * (honouring `position()` and `limit()`) and leave the buffer's position advanced by that
 * many bytes when it returns.
 *
 * Regression guard for the K/N Apple path: [NWSocketWrapper.write] used to send the full
 * backing NSMutableData (256 bytes in the test — the entire allocation) because
 * `ReadBuffer.toNSData()` ignored position/limit on the MutableDataBuffer zero-copy path,
 * and also failed to advance `buffer.position()` afterward. The ws frame encoder
 * reserves a 14-byte prefix inside a 256-byte scratch and positions the buffer at
 * `[8, 22)` for the wire window; on the old path the server received `[0, 256)`,
 * which started with a zero byte (opcode=continuation), tripping Ktor's
 * "Can't continue finished frames" protocol violation. Symptom at the caller was
 * `ClassCastException: Close cannot be cast to Binary` on the first receive.
 */
class PartialBufferWriteTests {
    @Test
    fun write_usesPositionAndLimit_advancesPosition() =
        runTestNoTimeSkipping {
            if (!networkCapabilities().transports.contains(TransportKind.TCP)) return@runTestNoTimeSkipping

            val server = ServerSocket.allocate()
            val serverFlow = server.bind()
            val dataReady = Mutex(locked = true)
            var collected = ByteArray(0)

            val serverJob =
                launch(Dispatchers.Default) {
                    serverFlow.collect { serverClient ->
                        while (collected.size < 14) {
                            val buf = serverClient.readBuffer(5.seconds)
                            val remaining = buf.remaining()
                            if (remaining == 0) break
                            val tmp = ByteArray(remaining)
                            repeat(remaining) { i -> tmp[i] = buf.readByte() }
                            collected += tmp
                        }
                        dataReady.unlock()
                        serverClient.close()
                    }
                }

            val client = ClientSocket.allocate()
            client.open(server.port(), "127.0.0.1", TransportConfig(connectTimeout = 5.seconds))

            // Allocate a 256-byte buffer and write 14 bytes into offsets [8, 22), matching the
            // websocket frame encoder's reserved-prefix layout.
            val scratch = BufferFactory.Default.allocate(256)
            repeat(256) { scratch.writeByte(0xFF.toByte()) } // 0xFF sentinel — would be visible on the wire if sent
            scratch.resetForWrite()
            scratch.position(8)
            val payload = byteArrayOf(0x82.toByte(), 0x88.toByte(), 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12)
            repeat(payload.size) { i -> scratch.writeByte(payload[i]) }
            // Expose the [8, 22) window.
            scratch.position(8)
            scratch.setLimit(22)

            val bytesWritten = client.write(scratch, 5.seconds).count
            assertEquals(14, bytesWritten, "write() should report bytes written = remaining() = 14")
            assertEquals(22, scratch.position(), "write() should advance position() by bytesWritten")

            dataReady.lockWithTimeout()
            client.close()
            server.close()
            serverJob.cancel()

            assertEquals(14, collected.size, "server should receive exactly remaining() bytes")
            for (i in payload.indices) {
                assertEquals(payload[i], collected[i], "byte $i mismatch")
            }
        }

    @Test
    fun write_pooledStyleOffsetBuffer_sendsOnlyWindow() =
        runTestNoTimeSkipping {
            if (!networkCapabilities().transports.contains(TransportKind.TCP)) return@runTestNoTimeSkipping

            val server = ServerSocket.allocate()
            val serverFlow = server.bind()
            val dataReady = Mutex(locked = true)
            var collected = ByteArray(0)

            val serverJob =
                launch(Dispatchers.Default) {
                    serverFlow.collect { serverClient ->
                        while (collected.size < 7) {
                            val buf = serverClient.readBuffer(5.seconds)
                            val remaining = buf.remaining()
                            if (remaining == 0) break
                            val tmp = ByteArray(remaining)
                            repeat(remaining) { i -> tmp[i] = buf.readByte() }
                            collected += tmp
                        }
                        dataReady.unlock()
                        serverClient.close()
                    }
                }

            val client = ClientSocket.allocate()
            client.open(server.port(), "127.0.0.1", TransportConfig(connectTimeout = 5.seconds))

            // Two sequential sub-window writes from the same backing buffer — catches the case
            // where send ignores position on every call (would duplicate the first window).
            val scratch = BufferFactory.Default.allocate(64)
            repeat(64) { scratch.writeByte(0xAA.toByte()) }

            scratch.position(10)
            scratch.setLimit(14)
            client.write(scratch, 5.seconds)

            // Reset the window before advancing past the previous limit — JVM's NIO-backed
            // buffer enforces `newPosition <= limit`, so widen the limit first.
            scratch.setLimit(64)
            scratch.position(30)
            scratch.setLimit(33)
            client.write(scratch, 5.seconds)

            dataReady.lockWithTimeout()
            client.close()
            server.close()
            serverJob.cancel()

            val expected = ByteArray(7) { 0xAA.toByte() } // 4 + 3 bytes, all sentinel
            assertEquals(expected.size, collected.size, "server should see exactly 4 + 3 = 7 bytes")
            for (i in expected.indices) {
                assertEquals(expected[i], collected[i], "byte $i mismatch")
            }
        }
}
