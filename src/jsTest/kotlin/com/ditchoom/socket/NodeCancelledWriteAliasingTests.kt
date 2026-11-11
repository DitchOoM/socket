package com.ditchoom.socket

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.Default
import com.ditchoom.buffer.flow.WritePolicy
import com.ditchoom.socket.harness.NonDrainingPeer
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.khronos.webgl.Uint8Array
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * `socket.write(view)` queues the view; Node does not copy it. A `write` cancelled from outside
 * (not the bounded timeout, which already destroys the socket) used to return with the bytes still
 * queued in Node, so a caller that got its buffer back and reused it put the NEW bytes on the wire
 * inside the OLD message.
 *
 * The peer here is a raw `net` server that does not read until told to, so the client's write is
 * genuinely parked on back-pressure when it is cancelled. After the cancel the caller overwrites the
 * buffer, then the peer drains. Every byte the peer ever receives must be one the caller wrote
 * (0xAA); a single 0xBB is the aliasing defect.
 */
class NodeCancelledWriteAliasingTests {
    private val original: Byte = 0xAA.toByte()
    private val overwritten: Byte = 0xBB.toByte()

    @Test
    fun aCancelledWriteNeverPutsTheCallersLaterBytesOnTheWire() =
        runTestNoTimeSkipping {
            val peerSocket = CompletableDeferred<Socket>()
            val portReady = CompletableDeferred<Int>()
            val server =
                Net.createServer { socket ->
                    socket.on("error") { _ -> }
                    peerSocket.complete(socket) // paused: no 'data' listener yet, so it does not drain
                }
            server.listen(port = 0, host = "127.0.0.1") { portReady.complete(server.address()!!.port) }
            val port = portReady.await()

            val client =
                ClientSocket.connect(
                    port,
                    config =
                        TransportConfig(
                            writePolicy = WritePolicy.UntilClosed,
                            connectTimeout = 5.seconds,
                            io = IoTuning(sendBuffer = NonDrainingPeer.SMALL_SOCKET_BUFFER),
                        ),
                )
            try {
                val size = 8 * 1024 * 1024 // far more than the loopback socket buffers can absorb
                val buffer = BufferFactory.Default.allocate(size)
                repeat(size) { buffer.writeByte(original) }
                buffer.resetForRead()

                val writer = launch { client.write(buffer, kotlin.time.Duration.INFINITE) }
                delay(500.milliseconds) // the write is now parked: its tail is queued inside Node
                assertTrue(writer.isActive, "the write must still be parked on back-pressure when it is cancelled")
                writer.cancel()
                writer.join()

                // The caller owns the buffer again and reuses it.
                buffer.resetForWrite()
                repeat(size) { buffer.writeByte(overwritten) }

                var received = 0
                var foreign = 0
                val drained = CompletableDeferred<Unit>()
                val peer = peerSocket.await()
                peer.on("data") { chunk ->
                    val bytes = chunk.unsafeCast<Uint8Array>()
                    for (i in 0 until bytes.length) {
                        if (bytes.asDynamic()[i].unsafeCast<Int>().toByte() != original) foreign++
                    }
                    received += bytes.length
                }
                peer.on("end") { _ -> drained.complete(Unit) }
                peer.on("close") { _ -> drained.complete(Unit) }
                peer.resume()
                // If the connection survives (main), the whole 8 MiB arrives and no 'end' comes: bound the wait.
                val settle =
                    launch {
                        delay(3.seconds)
                        drained.complete(Unit)
                    }
                drained.await()
                settle.cancel()

                assertEquals(
                    0,
                    foreign,
                    "the peer received $foreign byte(s) the caller wrote AFTER cancelling the write ($received bytes total): " +
                        "Node kept reading the caller's reused buffer",
                )
            } finally {
                client.close()
                server.close {}
            }
        }
}
