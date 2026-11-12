package com.ditchoom.socket.udp

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.Default
import com.ditchoom.buffer.flow.ExperimentalDatagramApi
import com.ditchoom.buffer.flow.SocketAddress
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.promise
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertFalse

/**
 * `dgram.send` queues the Uint8Array view of the caller's payload when the socket cannot send inline
 * (EAGAIN), and Node reads it again when the socket drains. A send cancelled from outside returned
 * at once, so the caller could reuse the payload while Node still held a view of it.
 *
 * Whether the kernel pushes back cannot be forced from a test, so the socket here is a stand-in that
 * does what Node does in that case: it keeps the payload view and calls the send callback later.
 * The contract: when a cancelled send returns, Node's completion has fired (it no longer reads the
 * payload).
 */
@OptIn(DelicateCoroutinesApi::class, ExperimentalDatagramApi::class)
class CancelledSendKeepsPayloadStableTests {
    @Test
    fun aCancelledSendDoesNotReturnWhileNodeStillHoldsThePayload() =
        GlobalScope.promise {
            withTimeout(20_000) {
                var completeSend: ((Any?) -> Unit)? = null
                val fake = js("({})")
                fake.on = { _: String, _: dynamic -> }
                fake.removeAllListeners = { }
                fake.close = { _: dynamic -> }
                fake.unref = { }
                fake.address = { js("({address: '127.0.0.1', port: 9, family: 'IPv4'})") }
                fake.send = { _: dynamic, _: Int, _: Int, _: Int, _: String, callback: (Any?) -> Unit -> completeSend = callback }
                val channel = AddressedNodeDatagramChannel(fake.unsafeCast<DgramSocket>())

                val payload = BufferFactory.Default.allocate(64)
                repeat(64) { payload.writeByte(1) }
                payload.resetForRead()

                var returned = false
                val sender =
                    launch(start = CoroutineStart.UNDISPATCHED) {
                        try {
                            channel.send(payload, SocketAddress.ofLiteral("127.0.0.1", 9))
                        } finally {
                            returned = true
                        }
                    }
                check(completeSend != null) { "the stand-in socket never saw the send" }
                sender.cancel()
                delay(100) // a prompt cancel would have returned by now

                val returnedWhileQueued = returned
                // Release Node's hold so the coroutine can finish either way.
                completeSend?.invoke(null)
                sender.join()
                assertFalse(
                    returnedWhileQueued,
                    "a cancelled send returned while Node still held a view of the caller's payload: the caller may reuse it",
                )
            }
        }
}
