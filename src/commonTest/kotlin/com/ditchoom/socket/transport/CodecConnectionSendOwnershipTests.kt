package com.ditchoom.socket.transport

import com.ditchoom.buffer.ExperimentalFanoutApi
import com.ditchoom.buffer.ReadBuffer
import com.ditchoom.buffer.WriteBuffer
import com.ditchoom.buffer.codec.ContextFreeCodec
import com.ditchoom.buffer.codec.DecodeContext
import com.ditchoom.buffer.codec.EncodeContext
import com.ditchoom.buffer.codec.PeekResult
import com.ditchoom.buffer.codec.WireSize
import com.ditchoom.buffer.codec.encodeShared
import com.ditchoom.buffer.flow.ByteStream
import com.ditchoom.buffer.flow.BytesWritten
import com.ditchoom.buffer.flow.CapacityBehavior
import com.ditchoom.buffer.flow.CloseCause
import com.ditchoom.buffer.flow.ConnectionPhase
import com.ditchoom.buffer.flow.Linger
import com.ditchoom.buffer.flow.NotSentReason
import com.ditchoom.buffer.flow.OutboundCapacity
import com.ditchoom.buffer.flow.ReadPolicy
import com.ditchoom.buffer.flow.ReadResult
import com.ditchoom.buffer.flow.SendMode
import com.ditchoom.buffer.flow.WritePolicy
import com.ditchoom.buffer.stream.StreamProcessor
import com.ditchoom.socket.ClientSocket
import com.ditchoom.socket.IoTuning
import com.ditchoom.socket.ServerSocket
import com.ditchoom.socket.SocketClosedException
import com.ditchoom.socket.TransportConfig
import com.ditchoom.socket.TransportKind
import com.ditchoom.socket.allocate
import com.ditchoom.socket.harness.NonDrainingPeer
import com.ditchoom.socket.networkCapabilities
import com.ditchoom.socket.nonDrainingPeerIsReliable
import com.ditchoom.socket.runTestNoTimeSkipping
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * The connection owns its writer (issue #382): callers hand a message off and never run the write
 * themselves, so a cancelled caller cannot truncate a frame and two concurrent callers cannot
 * interleave their bytes.
 *
 * The suite is deliberately split by what each property needs to be *observable*:
 *  - atomicity, serialization and send-then-close ordering are asserted over **real TCP sockets**,
 *    because those are the conditions under which the defect was measured;
 *  - capacity, linger and abort are asserted over a [StalledPeer] — a peer that genuinely never
 *    accepts a byte. A real never-reading TCP peer only back-pressures once two kernels' buffers
 *    fill, which is platform-dependent and on Node never happens at all
 *    ([nonDrainingPeerIsReliable]), so the stub is the *more* honest fixture for those, not the
 *    weaker one. The real-socket variant of the parked-write case is kept as well, gated on the
 *    platforms where back-pressure is real.
 */
@OptIn(ExperimentalFanoutApi::class)
class CodecConnectionSendOwnershipTests {
    // ── fixtures ───────────────────────────────────────────────────────────────────────────────

    private val config =
        TransportConfig(
            readPolicy = ReadPolicy.Bounded(20.seconds),
            writePolicy = WritePolicy.UntilClosed,
        )

    private fun tcpAvailable() = networkCapabilities().transports.contains(TransportKind.TCP)

    /** One connected TCP pair, both ends already wrapped in a [CodecConnection]. */
    private class TcpPair(
        val client: CodecConnection<String>,
        val peer: CodecConnection<String>,
        private val server: ServerSocket,
        private val acceptJob: Job,
    ) {
        suspend fun close() {
            runCatching { client.close() }
            runCatching { peer.close() }
            runCatching { server.close() }
            acceptJob.cancel()
        }
    }

    /**
     * Binds an ephemeral loopback server, connects one client, and returns both ends.
     *
     * The accept is awaited from the *test's* coroutine, never from inside a client connect block —
     * awaiting the server from within `ClientSocket.connect { … }` deadlocks single-threaded targets.
     */
    private suspend fun CoroutineScope.tcpPair(
        clientSendMode: SendMode<String> = SendMode.AwaitWritten,
        connectionConfig: TransportConfig = config,
    ): TcpPair {
        val server = ServerSocket.allocate(connectionConfig)
        val flow = server.bind()
        val accepted = CompletableDeferred<ClientSocket>()
        val acceptJob =
            launch(Dispatchers.Default) {
                flow.collect { serverToClient ->
                    if (!accepted.isCompleted) accepted.complete(serverToClient)
                }
            }
        val client = ClientSocket.allocate(connectionConfig)
        client.open(server.port())
        val serverSide = withTimeout(10.seconds) { accepted.await() }
        return TcpPair(
            client = CodecConnection(client, BigFrameCodec, connectionConfig, sendMode = clientSendMode),
            peer = CodecConnection(serverSide, BigFrameCodec, connectionConfig),
            server = server,
            acceptJob = acceptJob,
        )
    }

    // ── 1. a cancelled sender never truncates ──────────────────────────────────────────────────

    /**
     * The defect, over a real socket: cancelling a caller mid-`send` used to leave a partial frame
     * under a length prefix declaring the whole thing, and the peer then consumed every later frame
     * as the missing tail.
     *
     * The fixture pins the cancel to a moment when the write is genuinely *in flight*: small socket
     * buffers on both ends, a 2 MiB frame, and a peer that does not start reading until after the
     * cancel — so the writer is parked in the transport, holding a half-written frame, at exactly the
     * instant its caller dies.
     *
     * The general assertion is two-sided, because both outcomes are correct and which one happens is
     * a race the test must not pretend to control: the cancelled frame is either *absent* (cancelled
     * before the writer took it) or *whole* (the writer had it and finished it — "cancelled" does not
     * imply "not sent"). What must never appear is a third outcome: a short frame, or a follow-up
     * frame that fails to decode because the stream desynchronized. Where back-pressure is real, the
     * stronger half is asserted too — the frame must be there, whole.
     */
    @Test
    fun cancelledSenderNeverTruncatesTheFrame() =
        runTestNoTimeSkipping(timeout = 60.seconds) {
            if (!tcpAvailable()) return@runTestNoTimeSkipping
            val backPressured =
                config.copy(
                    io =
                        IoTuning(
                            sendBuffer = NonDrainingPeer.SMALL_SOCKET_BUFFER,
                            receiveBuffer = NonDrainingPeer.SMALL_SOCKET_BUFFER,
                        ),
                )
            val pair = tcpPair(connectionConfig = backPressured)
            try {
                val big = "A".repeat(2 * 1024 * 1024)
                val cancelledSender = launch(Dispatchers.Default) { pair.client.send(big) }
                // Nobody is reading yet, so by now the writer is parked inside the transport with a
                // partially written frame. Pull the rug out from under the *caller*.
                delay(500)
                cancelledSender.cancel()
                cancelledSender.join()

                val received = mutableListOf<String>()
                val readerDone = Mutex(locked = true)
                val reader =
                    launch(Dispatchers.Default) {
                        try {
                            pair.peer.receive().collect { received += it }
                        } finally {
                            readerDone.unlock()
                        }
                    }

                // The very next frame must arrive intact — this is the assertion that fails when the
                // stream has been desynchronized by a truncated predecessor.
                pair.client.send("second")
                pair.client.close()
                withTimeout(45.seconds) { readerDone.lock() }
                reader.join()

                assertTrue(received.isNotEmpty(), "the peer decoded nothing at all")
                assertTrue(
                    received.all { it == big || it == "second" },
                    "a frame arrived that is neither of the two sent — the stream desynchronized",
                )
                assertEquals("second", received.last(), "the frame after the cancelled one must arrive whole")
                assertEquals(received.size, received.distinct().size, "a frame was decoded twice")
                if (nonDrainingPeerIsReliable()) {
                    // Back-pressure is real here, so the write WAS parked when the caller died: the
                    // writer owed the frame and finished it. Cancelled is not the same as not sent.
                    assertEquals(listOf(big, "second"), received, "the writer abandoned a frame it had already taken")
                }
            } finally {
                pair.close()
            }
        }

    // ── 2. concurrent senders never interleave ─────────────────────────────────────────────────

    /**
     * Red before the writer moved onto the connection: nothing serialized two `send`s, so two
     * callers' bytes could interleave inside one frame.
     */
    @Test
    fun concurrentSendersNeverInterleave() =
        runTestNoTimeSkipping(timeout = 60.seconds) {
            if (!tcpAvailable()) return@runTestNoTimeSkipping
            val pair = tcpPair()
            try {
                val count = 16
                // Big enough that a single frame cannot reach the wire in one syscall — interleaving
                // needs a window, and a 40 KiB frame guarantees one.
                val messages = (0 until count).map { "m$it-" + ('a' + (it % 26)).toString().repeat(40_000) }
                val received = CompletableDeferred<List<String>>()
                val reader =
                    launch(Dispatchers.Default) {
                        received.complete(
                            pair.peer
                                .receive()
                                .take(count)
                                .toList(),
                        )
                    }

                val senders = messages.map { message -> launch(Dispatchers.Default) { pair.client.send(message) } }
                senders.joinAll()

                val decoded = withTimeout(30.seconds) { received.await() }
                assertEquals(messages.toSet(), decoded.toSet(), "every frame must arrive whole and exactly once")
                reader.join()
            } finally {
                pair.close()
            }
        }

    // ── 3. Handoff: a stalled peer does not block send ─────────────────────────────────────────

    /**
     * The fan-out mode's whole point: a peer that never accepts a byte must cost the producer
     * O(enqueue), not O(peer). Beyond capacity the oldest queued message is evicted and reported —
     * degrade, do not flap — and the connection stays [ConnectionPhase.Open] throughout.
     */
    @Test
    fun handoffSendStaysPromptWhileThePeerNeverReads() =
        runTestNoTimeSkipping(timeout = 60.seconds) {
            val peer = StalledPeer()
            val notSent = mutableListOf<Pair<String, NotSentReason>>()
            val notSentLock = Mutex()
            val connection =
                CodecConnection(
                    peer,
                    BigFrameCodec,
                    config,
                    sendMode =
                        SendMode.Handoff(
                            capacity = OutboundCapacity(4),
                            onCapacity = CapacityBehavior.DropOldest,
                            linger = Linger.Bounded(250.milliseconds),
                            onNotSent = { message, reason -> notSentLock.withLock { notSent += message to reason } },
                        ),
                )

            // The writer takes the first message and parks inside the peer's write; everything after
            // it queues, and past capacity the head is evicted.
            connection.send("m0")
            peer.awaitWriteEntered()

            repeat(40) { index ->
                val returned = withTimeoutOrNull(2.seconds) { connection.send("q$index") }
                assertNotNull(returned, "send #$index waited on the peer — the whole point of Handoff is that it cannot")
            }
            assertEquals(ConnectionPhase.Open, connection.sendPhase.value, "eviction must not close the connection")

            notSentLock.withLock {
                assertTrue(notSent.isNotEmpty(), "40 messages into a queue of 4 must report drops")
                assertTrue(
                    notSent.all { it.second == NotSentReason.CapacityExceeded },
                    "over-capacity drops must be reported as CapacityExceeded, got ${notSent.map { it.second }}",
                )
                // DropOldest evicts the head, so the *earliest* queued messages are the victims and
                // the message itself comes back — never a buffer someone must free.
                assertEquals("q0", notSent.first().first)
            }

            // The peer is still stalled, so a graceful close must escalate at the linger bound rather
            // than hang. 250 ms linger; 10 s is a watchdog, not the assertion.
            val closed = withTimeoutOrNull(10.seconds) { connection.close() }
            assertNotNull(closed, "close() hung on a stalled peer despite a bounded linger")
            assertEquals(ConnectionPhase.Closed(CloseCause.Aborted), connection.sendPhase.value)
        }

    // ── 4. send-then-close keeps its order ─────────────────────────────────────────────────────

    /**
     * The compat guarantee of the default mode: `send` returns only after the frame is on the wire,
     * so the send-goodbye-then-close pattern needs no drain contract and no `flush()`.
     */
    @Test
    fun sendThenCloseStillDeliversTheGoodbyeFrame() =
        runTestNoTimeSkipping(timeout = 60.seconds) {
            if (!tcpAvailable()) return@runTestNoTimeSkipping
            val pair = tcpPair()
            try {
                pair.client.send("goodbye")
                pair.client.close()
                val decoded = withTimeout(30.seconds) { pair.peer.receive().toList() }
                assertEquals(listOf("goodbye"), decoded)
            } finally {
                pair.close()
            }
        }

    // ── 5. overflow engages at exactly capacity ────────────────────────────────────────────────

    /**
     * Deterministic on every platform, because the [StalledPeer] pins where the writer is: it holds
     * exactly one message (the one it is writing), so the queue depth is knowable and the policy can
     * be observed engaging at the boundary rather than "somewhere around" it.
     */
    @Test
    fun overflowEngagesAtExactlyCapacity() =
        runTestNoTimeSkipping(timeout = 60.seconds) {
            val peer = StalledPeer()
            val notSent = mutableListOf<Pair<String, NotSentReason>>()
            val notSentLock = Mutex()
            val capacity = 3
            val connection =
                CodecConnection(
                    peer,
                    BigFrameCodec,
                    config,
                    sendMode =
                        SendMode.Handoff(
                            capacity = OutboundCapacity(capacity),
                            onCapacity = CapacityBehavior.DropNewest,
                            linger = Linger.UntilDrained,
                            onNotSent = { message, reason -> notSentLock.withLock { notSent += message to reason } },
                        ),
                )

            connection.send("in-flight")
            peer.awaitWriteEntered()

            repeat(capacity) { index -> connection.send("q$index") }
            notSentLock.withLock { assertTrue(notSent.isEmpty(), "the policy engaged BELOW capacity: ${notSent.map { it.first }}") }

            connection.send("one-too-many")
            val dropped: List<Pair<String, NotSentReason>> = notSentLock.withLock { notSent.toList() }
            assertEquals(1, dropped.size, "exactly one message crossed the boundary, got $dropped")
            assertEquals("one-too-many", dropped.single().first, "DropNewest rejects the arrival, not the queue")
            assertEquals(NotSentReason.CapacityExceeded, dropped.single().second)

            // The connection survives its overflow: let the peer drain and everything accepted lands,
            // in order, with the rejected message absent.
            peer.release()
            withTimeout(20.seconds) { connection.close() }
            assertEquals(listOf("in-flight", "q0", "q1", "q2"), peer.decodeWire())
            assertEquals(ConnectionPhase.Closed(CloseCause.Graceful), connection.sendPhase.value)
        }

    // ── 6. send after close maps into the socket error family ──────────────────────────────────

    /**
     * Buffer raises an `IllegalStateException`; this library promises consumers that everything a
     * socket API throws is a [com.ditchoom.socket.SocketException] (an `IOException` on JVM). The
     * `catch (e: SocketClosedException)` a consumer already writes for "connection lost" must cover
     * a send refused by a closed writer, with the sealed cause available for anyone who wants it.
     */
    @Test
    fun sendAfterCloseThrowsOutboundClosedWithAGracefulCause() =
        runTestNoTimeSkipping(timeout = 30.seconds) {
            val (stream, _) = MemoryTransport.createPair(config)
            val connection = CodecConnection(stream, BigFrameCodec, config)
            connection.close()

            val failure =
                try {
                    connection.send("after close")
                    null
                } catch (e: SocketClosedException) {
                    e
                }
            val closed = assertNotNull(failure, "send after close must throw")
            assertTrue(closed is SocketClosedException.OutboundClosed, "expected OutboundClosed, got ${closed::class.simpleName}")
            assertEquals(CloseCause.Graceful, closed.closeCause)
        }

    /** The abort ladder's version of the same mapping: the cause distinguishes it from a graceful close. */
    @Test
    fun sendAfterAbortThrowsOutboundClosedWithAnAbortedCause() =
        runTestNoTimeSkipping(timeout = 30.seconds) {
            val (stream, _) = MemoryTransport.createPair(config)
            val connection = CodecConnection(stream, BigFrameCodec, config)
            connection.abort()

            val failure = assertFailsWith<SocketClosedException.OutboundClosed> { connection.send("after abort") }
            assertEquals(CloseCause.Aborted, failure.closeCause)
        }

    // ── 7. abort() unblocks a parked write ─────────────────────────────────────────────────────

    /**
     * `close()` on the default mode waits for the in-flight frame; `abort()` does not. A frame
     * truncated on a connection being torn down harms nobody, which is exactly what makes cancelling
     * the writer safe — and it is the escape hatch that keeps a stalled peer from turning teardown
     * into a hang.
     */
    @Test
    fun abortUnblocksAWriteParkedInTheTransport() =
        runTestNoTimeSkipping(timeout = 60.seconds) {
            val peer = StalledPeer()
            val connection = CodecConnection(peer, BigFrameCodec, config)
            var senderFailure: Throwable? = null
            val sender =
                launch(Dispatchers.Default) {
                    try {
                        connection.send("parked")
                    } catch (t: Throwable) {
                        senderFailure = t
                    }
                }
            peer.awaitWriteEntered()

            val aborted = withTimeoutOrNull(10.seconds) { connection.abort() }
            assertNotNull(aborted, "abort() did not unblock a write parked in the transport")
            assertEquals(ConnectionPhase.Closed(CloseCause.Aborted), connection.sendPhase.value)

            sender.join()
            val failure = assertNotNull(senderFailure, "the parked sender must be told its frame did not land")
            assertTrue(failure is SocketClosedException.OutboundClosed, "got ${failure::class.simpleName}")
            assertEquals(CloseCause.Aborted, failure.closeCause)
        }

    /**
     * The same property against a real kernel: a peer that never `recv()`s closes its window, the
     * client's writes park inside the platform transport, and `abort()` must still return promptly.
     *
     * Gated on [nonDrainingPeerIsReliable] — on Node a `net.Socket` write is fire-and-forget into an
     * unbounded channel, so the write never parks and there is nothing here to unblock (the contract
     * itself is covered on every platform by [abortUnblocksAWriteParkedInTheTransport] above).
     */
    @Test
    fun abortUnblocksAWriteParkedInARealSocket() =
        runTestNoTimeSkipping(timeout = 60.seconds) {
            if (!tcpAvailable() || !nonDrainingPeerIsReliable()) return@runTestNoTimeSkipping
            val peer = NonDrainingPeer.start()
            val clientConfig =
                config.copy(io = IoTuning(sendBuffer = NonDrainingPeer.SMALL_SOCKET_BUFFER))
            val socket = ClientSocket.allocate(clientConfig)
            socket.open(peer.port)
            val connection = CodecConnection(socket, BigFrameCodec, clientConfig)
            try {
                peer.awaitAccepted()
                // Far more than any autotuned socket buffer pair will hold, so the write is genuinely
                // parked in the transport rather than merely queued.
                val sender = launch(Dispatchers.Default) { runCatching { connection.send("B".repeat(16 * 1024 * 1024)) } }
                delay(500)

                val aborted = withTimeoutOrNull(15.seconds) { connection.abort() }
                assertNotNull(aborted, "abort() did not unblock a write parked on a full send buffer")
                assertEquals(ConnectionPhase.Closed(CloseCause.Aborted), connection.sendPhase.value)
                withTimeout(15.seconds) { sender.join() }
            } finally {
                runCatching { connection.abort() }
                peer.close()
            }
        }

    // ── 8. encode-once fan-out over real connections ───────────────────────────────────────────

    /**
     * One encode, two connections, one refcount. Each `send` retains for the duration of its own
     * transfer and the writer releases exactly once; the creator's reference is dropped by
     * `frame.close()` after the loop. Over-retaining a freed frame is an accounting bug and must fail
     * loudly rather than hand out a view onto storage somebody else now owns.
     */
    @Test
    fun aSharedFrameFansOutToTwoConnectionsAndIsFreedExactlyOnce() =
        runTestNoTimeSkipping(timeout = 60.seconds) {
            if (!tcpAvailable()) return@runTestNoTimeSkipping
            val first = tcpPair()
            val second = tcpPair()
            try {
                val message = "fan-out payload " + "z".repeat(5_000)
                val frame = BigFrameCodec.encodeShared(message)

                val firstDecoded = CompletableDeferred<String>()
                val secondDecoded = CompletableDeferred<String>()
                val readers =
                    listOf(
                        launch(Dispatchers.Default) {
                            firstDecoded.complete(
                                first.peer
                                    .receive()
                                    .take(1)
                                    .toList()
                                    .single(),
                            )
                        },
                        launch(Dispatchers.Default) {
                            secondDecoded.complete(
                                second.peer
                                    .receive()
                                    .take(1)
                                    .toList()
                                    .single(),
                            )
                        },
                    )

                first.client.send(frame)
                second.client.send(frame)
                assertEquals(message, withTimeout(30.seconds) { firstDecoded.await() })
                assertEquals(message, withTimeout(30.seconds) { secondDecoded.await() })
                readers.joinAll()

                // Both sends released their transferred reference before returning, so this drops the
                // last one. Retaining afterwards must throw rather than resurrect freed storage.
                frame.close()
                assertFailsWith<IllegalStateException> { frame.bytes.retain() }
            } finally {
                first.close()
                second.close()
            }
        }

    // ── CodecSender: the mux leaf gets the same ownership ──────────────────────────────────────

    /** [CodecSender] carries the same writer, so it inherits the same send-after-close mapping. */
    @Test
    fun codecSenderSendAfterCloseThrowsOutboundClosed() =
        runTestNoTimeSkipping(timeout = 30.seconds) {
            val peer = StalledPeer()
            peer.release()
            val sender = CodecSender(peer, BigFrameCodec, config)
            sender.send("before close")
            sender.close()

            val failure = assertFailsWith<SocketClosedException.OutboundClosed> { sender.send("after close") }
            assertEquals(CloseCause.Graceful, failure.closeCause)
            assertEquals(listOf("before close"), peer.decodeWire())
        }

    /**
     * The leaf's escape hatch. `close()` drains, and a drain against a peer that never accepts a byte
     * waits with it — so the unidirectional sender needs the same `abort()` the bidirectional
     * connection has, or a stalled peer would have no way out of teardown.
     */
    @Test
    fun codecSenderAbortUnblocksAParkedWrite() =
        runTestNoTimeSkipping(timeout = 60.seconds) {
            val peer = StalledPeer()
            val sender = CodecSender(peer, BigFrameCodec, config)
            var senderFailure: Throwable? = null
            val parked =
                launch(Dispatchers.Default) {
                    try {
                        sender.send("parked")
                    } catch (t: Throwable) {
                        senderFailure = t
                    }
                }
            peer.awaitWriteEntered()

            val aborted = withTimeoutOrNull(10.seconds) { sender.abort() }
            assertNotNull(aborted, "abort() did not unblock a write parked in the sink")
            assertEquals(ConnectionPhase.Closed(CloseCause.Aborted), sender.sendPhase.value)

            parked.join()
            val failure = assertNotNull(senderFailure, "the parked sender must be told its frame did not land")
            assertTrue(failure is SocketClosedException.OutboundClosed, "got ${failure::class.simpleName}")
            assertEquals(CloseCause.Aborted, failure.closeCause)
        }

    /** Concurrent senders on one mux leaf must not interleave either — same writer, same guarantee. */
    @Test
    fun codecSenderSerializesConcurrentSenders() =
        runTestNoTimeSkipping(timeout = 60.seconds) {
            // A sink that accepts a bounded slice per call is what gives interleaving its window.
            val peer = ChunkedSink(acceptPerWrite = 64)
            val sender = CodecSender(peer, BigFrameCodec, config)
            val messages = (0 until 12).map { "s$it-" + ('a' + it).toString().repeat(2_000) }
            messages.map { message -> launch(Dispatchers.Default) { sender.send(message) } }.joinAll()
            sender.close()

            assertEquals(messages.toSet(), peer.decodeWire().toSet(), "frames must be whole and unspliced")
        }
}

// ── test doubles ───────────────────────────────────────────────────────────────────────────────

/**
 * A 4-byte-length-prefixed UTF-8 string codec.
 *
 * Two reasons it exists next to `TestStringCodec`: that one's 2-byte prefix caps a frame at 64 KiB,
 * and the cancellation test needs a frame far larger than one write chunk; and this one is a
 * [ContextFreeCodec] — its encoding reads no [EncodeContext] at all — which is the capability
 * declaration that makes `encodeShared` legal for it.
 */
object BigFrameCodec : ContextFreeCodec<String> {
    override fun decode(
        buffer: ReadBuffer,
        context: DecodeContext,
    ): String {
        val length = buffer.readInt()
        return buffer.readString(length)
    }

    override fun encode(
        buffer: WriteBuffer,
        value: String,
        context: EncodeContext,
    ) {
        val bytes = value.encodeToByteArray()
        buffer.writeInt(bytes.size)
        buffer.writeBytes(bytes)
    }

    override fun wireSize(
        value: String,
        context: EncodeContext,
    ): WireSize = WireSize.Exact(4 + value.encodeToByteArray().size)

    override fun peekFrameSize(
        stream: StreamProcessor,
        baseOffset: Int,
    ): PeekResult {
        if (stream.available() < baseOffset + 4) return PeekResult.NeedsMoreData
        return PeekResult.Complete(4 + stream.peekInt(baseOffset))
    }
}

/** Decodes a captured wire image back into whole [BigFrameCodec] frames, failing on a short tail. */
private fun List<Byte>.decodeFrames(): List<String> {
    val bytes = toByteArray()
    val decoded = mutableListOf<String>()
    var offset = 0
    while (offset < bytes.size) {
        check(offset + 4 <= bytes.size) { "truncated length prefix at $offset of ${bytes.size}" }
        var length = 0
        for (i in 0 until 4) length = (length shl 8) or (bytes[offset + i].toInt() and 0xFF)
        check(offset + 4 + length <= bytes.size) { "frame at $offset declares $length bytes, only ${bytes.size - offset - 4} present" }
        decoded += bytes.decodeToString(offset + 4, offset + 4 + length)
        offset += 4 + length
    }
    return decoded
}

/**
 * A peer whose receive window is full and stays full: every write parks until [release].
 *
 * The deterministic stand-in for a never-draining peer. A real one only back-pressures once two
 * kernels' buffers fill — platform-dependent, and on Node it never happens at all — so for the
 * capacity/linger/abort assertions this is the fixture that actually pins the writer where the test
 * says it is.
 */
private class StalledPeer : ByteStream {
    private val gate = CompletableDeferred<Unit>()
    private val writesEntered = Channel<Unit>(Channel.UNLIMITED)
    private val closeSignal = CompletableDeferred<Unit>()
    private val wire = mutableListOf<Byte>()
    private val wireLock = Mutex()

    override val isOpen: Boolean get() = !closeSignal.isCompleted
    override val readPolicy: ReadPolicy = ReadPolicy.UntilClosed
    override val writePolicy: WritePolicy = WritePolicy.UntilClosed

    override suspend fun read(deadline: Duration): ReadResult {
        closeSignal.await()
        return ReadResult.End
    }

    override suspend fun write(
        buffer: ReadBuffer,
        deadline: Duration,
    ): BytesWritten {
        writesEntered.trySend(Unit)
        gate.await()
        val take = buffer.remaining()
        val chunk = ArrayList<Byte>(take)
        repeat(take) { chunk += buffer.readByte() }
        wireLock.withLock { wire += chunk }
        return BytesWritten(take)
    }

    override suspend fun close() {
        closeSignal.complete(Unit)
        // A close must not leave the writer parked forever: the real transport's close is what
        // unblocks a stalled write, and the stub owes the same.
        gate.complete(Unit)
    }

    /** Suspends until a write has actually entered this peer — the writer is now demonstrably busy. */
    suspend fun awaitWriteEntered(timeout: Duration = 10.seconds) {
        assertNotNull(withTimeoutOrNull(timeout) { writesEntered.receive() }, "no write ever reached the peer")
    }

    /** Lets every parked and future write through. */
    fun release() {
        gate.complete(Unit)
    }

    suspend fun decodeWire(): List<String> = wireLock.withLock { wire.toList() }.decodeFrames()
}

/**
 * A sink that accepts at most [acceptPerWrite] bytes per call — the shape that gives an unserialized
 * writer a window to interleave in, and the same shape `CodecConnectionPartialWriteTests` uses to
 * prove `writeFully` completes a frame.
 */
private class ChunkedSink(
    private val acceptPerWrite: Int,
) : ByteStream {
    private val wire = mutableListOf<Byte>()
    private val wireLock = Mutex()

    override val isOpen: Boolean get() = true
    override val readPolicy: ReadPolicy = ReadPolicy.UntilClosed
    override val writePolicy: WritePolicy = WritePolicy.UntilClosed

    override suspend fun read(deadline: Duration): ReadResult = ReadResult.End

    override suspend fun write(
        buffer: ReadBuffer,
        deadline: Duration,
    ): BytesWritten {
        val take = minOf(acceptPerWrite, buffer.remaining())
        val chunk = ArrayList<Byte>(take)
        repeat(take) { chunk += buffer.readByte() }
        wireLock.withLock { wire += chunk }
        return BytesWritten(take)
    }

    override suspend fun close() = Unit

    suspend fun decodeWire(): List<String> = wireLock.withLock { wire.toList() }.decodeFrames()
}
