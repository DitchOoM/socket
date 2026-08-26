package com.ditchoom.socket.transport

import com.ditchoom.buffer.flow.ReadPolicy
import com.ditchoom.buffer.flow.WritePolicy
import com.ditchoom.socket.TransportConfig
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.time.Duration.Companion.seconds

/**
 * Points [assertNothingSurvivesTeardown] at the two codec classes, the way
 * [ReconnectingConnectionTeardownProbeTests] points it at the reconnecting one.
 *
 * #471 proved these two run teardown exactly once, and [TeardownOnce] now guarantees it structurally.
 * Neither says anything about whether `close()` *stops what the class started* — a writer coroutine
 * on the connection's scope, and on the bidirectional side a reader as well. That is a separate
 * claim, and on [ReconnectingConnection] it turned out to be false in two distinct ways that the
 * teardown-once work had left untouched.
 *
 * Every coroutine these classes launch goes onto the `scope` they are constructed with, and
 * `testCodecConnection` builds that from the caller's context — so the probe's marker propagates and
 * anything left running is attributed here.
 */
class CodecTeardownProbeTests {
    private val config =
        TransportConfig(
            readPolicy = ReadPolicy.Bounded(5.seconds),
            writePolicy = WritePolicy.Bounded(5.seconds),
        )

    @Test
    fun closingACodecConnectionStopsItsWriterAndReader() =
        runBlocking(Dispatchers.Default) {
            withTimeout(60.seconds) {
                assertNothingSurvivesTeardown("codec-connection-close") { scope ->
                    val (aStream, bStream) = MemoryTransport.createPair(config)
                    val a = scope.testCodecConnection(aStream, TestStringCodec, config)
                    val b = scope.testCodecConnection(bStream, TestStringCodec, config)

                    // Drive both directions so the writer and the reader are genuinely running
                    // before teardown — a class that never started anything cannot leak anything,
                    // and would pass this vacuously.
                    val received = CompletableDeferred<String>()
                    scope.launch {
                        runCatching { b.receive().collect { received.complete(it) } }
                    }
                    a.send("hello")
                    received.await()

                    a.close()
                    b.close()
                }
            }
        }

    @Test
    fun closingACodecSenderStopsItsWriter() =
        runBlocking(Dispatchers.Default) {
            withTimeout(60.seconds) {
                assertNothingSurvivesTeardown("codec-sender-close") { scope ->
                    val (aStream, bStream) = MemoryTransport.createPair(config)
                    val sender = scope.testCodecSender(aStream, TestStringCodec, config)
                    val peer = scope.testCodecConnection(bStream, TestStringCodec, config)

                    val received = CompletableDeferred<String>()
                    scope.launch {
                        runCatching { peer.receive().collect { received.complete(it) } }
                    }
                    sender.send("hello")
                    received.await()

                    sender.close()
                    peer.close()
                }
            }
        }
}
