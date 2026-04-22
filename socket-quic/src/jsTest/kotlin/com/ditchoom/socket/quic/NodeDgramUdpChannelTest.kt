package com.ditchoom.socket.quic

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.Default
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.promise
import kotlinx.coroutines.withTimeout
import kotlin.js.Promise
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * End-to-end round-trip over [NodeDgramUdpChannel] against a plain dgram echo socket.
 * Proves send + receive wiring before any quiche code sits on top.
 *
 * Browser: returns immediately (dgram is Node-only).
 */
class NodeDgramUdpChannelTest {
    @OptIn(DelicateCoroutinesApi::class)
    @Test
    fun clientSendReceiveRoundTripsAgainstEchoServer(): Promise<Unit> =
        GlobalScope.promise {
            if (!isNode) return@promise
            val payload = byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8, 9, 10)

            // Spin up a plain dgram echo server bound to an ephemeral port.
            val dgram = js("require('dgram')")
            val server = dgram.createSocket("udp4")
            val bound = CompletableDeferred<Int>()
            server.on("listening") {
                bound.complete(server.address().port.unsafeCast<Int>())
            }
            server.on("message") { msg: dynamic, rinfo: dynamic ->
                // Echo back to sender.
                server.send(msg, rinfo.port, rinfo.address)
            }
            server.bind(0, "127.0.0.1") // port 0 = kernel assigns
            val port = withTimeout(2_000) { bound.await() }

            try {
                val udp = NodeDgramUdpChannel.connect("127.0.0.1", port)
                try {
                    val sendBuf = BufferFactory.Default.allocate(payload.size)
                    sendBuf.writeBytes(payload)
                    sendBuf.resetForRead()
                    udp.send(sendBuf, payload.size)

                    val recvBuf = BufferFactory.Default.allocate(1500)
                    val n = withTimeout(2_000) { udp.receive(recvBuf) }
                    assertEquals(payload.size, n)
                    val got = recvBuf.readByteArray(n)
                    assertEquals(payload.toList(), got.toList())
                } finally {
                    udp.close()
                }
            } finally {
                // Give any pending dgram.send callbacks a tick to drain before closing the server.
                delay(10)
                val serverClosed = CompletableDeferred<Unit>()
                server.close { serverClosed.complete(Unit) }
                withTimeout(1_000) { serverClosed.await() }
            }
        }
}
