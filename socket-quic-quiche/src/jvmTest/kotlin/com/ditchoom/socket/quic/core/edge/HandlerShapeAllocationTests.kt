package com.ditchoom.socket.quic.core.edge

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.PlatformBuffer
import com.ditchoom.buffer.ReadBuffer
import com.ditchoom.buffer.flow.ReadResult
import com.ditchoom.buffer.flow.writeFully
import com.ditchoom.buffer.freeIfNeeded
import com.ditchoom.socket.quic.QuicConnection
import com.ditchoom.socket.quic.QuicConnectionStats
import com.ditchoom.socket.quic.QuicError
import com.ditchoom.socket.quic.QuicReceiveStream
import com.ditchoom.socket.quic.QuicStatsRead
import com.ditchoom.socket.quic.QuicStream
import com.ditchoom.socket.quic.QuicStreamId
import com.ditchoom.socket.quic.ScopedRead
import com.ditchoom.socket.quic.StreamWrite
import com.ditchoom.socket.quic.core.conn.CallerPlace
import com.ditchoom.socket.quic.core.conn.Conn
import com.ditchoom.socket.quic.core.conn.ConnWork
import com.ditchoom.socket.quic.core.conn.Inside
import com.ditchoom.socket.quic.core.demux.SERVER_LOOP_THREAD
import com.ditchoom.socket.quic.core.life.ConnEnd
import com.ditchoom.socket.quic.core.loop.LoopSet
import com.ditchoom.socket.quic.core.sched.HandlerLane
import com.ditchoom.socket.quic.core.sched.HandlerSite
import com.ditchoom.socket.quic.core.stream.Retry
import com.ditchoom.socket.quic.core.stream.StreamTable
import com.ditchoom.socket.quic.core.stream.Waiter
import com.ditchoom.socket.quic.core.testing.withDescriptorsAbove127
import com.ditchoom.socket.quic.core.udp.NativeBytes
import com.ditchoom.socket.quic.core.udp.ReadAddress
import com.ditchoom.socket.quic.getOrElse
import com.ditchoom.socket.quic.network
import com.ditchoom.socket.quic.read
import com.ditchoom.socket.quic.readEach
import com.ditchoom.socket.quic.skipOnMissingNativeLib
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.Job
import kotlinx.coroutines.ThreadContextElement
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.lang.management.ManagementFactory
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.Continuation
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.resume
import kotlin.coroutines.startCoroutine
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.fail
import kotlin.time.Duration.Companion.seconds

/**
 * The tier-2 read shapes compared by counts, per operation: the bytes each thread allocates, coroutine resumes,
 * hand-offs to another thread, hand-backs to the loop, and packets on the wire. Rows: (a) the public suspend read;
 * (b) the `handleStreams` suspend block; (c) a handler registered once per stream, called off the loop on the
 * connection's handler lane for each read; (d) the same with everything read since its last run delivered at once, each
 * read in a buffer of its own; (e) the same batch lent as windows into one receive buffer the stream keeps; (f) the
 * handler called on the loop, for reference. Reported, not gated. Rows (c)-(f) are test-only: a waiter parked in the
 * connection's stream table drains quiche itself, and stops reading while a delivery is out, so a slow handler keeps
 * the stream's flow-control window shut.
 *
 * Row (b) is measured on the server, where the handlers run; the other rows on the client, which reads. Workloads: a
 * 64 B echo on one stream, a 16 MiB one-way transfer in 1 KiB writes (a download for the client rows, an upload for
 * (b)), and a 64 B echo on a new stream each time. Row (b)'s resumes come from a second pass with a resume counter in
 * the handler's context; its bytes from the first, without one.
 */
class HandlerShapeAllocationTests {
    private val loops = LoopSet(1)
    private val rows = ArrayList<String>()

    @AfterTest
    fun closeLoops() = loops.close()

    /** How a registered handler is handed what the loop read. */
    private enum class Delivery { PerRead, BatchOwned, BatchLent, OnLoop }

    private sealed interface Shape {
        data object Suspend : Shape

        class Registered(
            val delivery: Delivery,
        ) : Shape
    }

    @Test
    fun theReadShapesAreCounted() =
        runBlocking {
            withDescriptorsAbove127 {
                skipOnMissingNativeLib(HandlerShapeAllocationTests::class) {
                    withCoreServer(onConnection = { serveRawStreams(it) }) { server ->
                        clientRows("(a) suspend read", server.port, Shape.Suspend)
                        clientRows("(c) handler on the lane, per read", server.port, Shape.Registered(Delivery.PerRead))
                        clientRows("(d) handler on the lane, batched, owned buffers", server.port, Shape.Registered(Delivery.BatchOwned))
                        clientRows("(e) handler on the lane, batched, lent buffer", server.port, Shape.Registered(Delivery.BatchLent))
                        clientRows("(f) handler on the loop (reference)", server.port, Shape.Registered(Delivery.OnLoop))
                    }
                    handleStreamsRows("(b) handleStreams block (server)", CountResumes.No)
                    handleStreamsRows("(b) handleStreams block (server), resumes pass", CountResumes.Yes)
                    println(
                        rows.joinToString(
                            "\n",
                            prefix =
                                "[handler shape] row | workload | caller B/op | lane B/op | loop B/op | resumes/op | " +
                                    "dispatches/op | loop hand-backs/op | wire packets/op | ops\n",
                        ) { "[handler shape] $it" },
                    )
                    assertEquals(21, rows.size, "every row was measured")
                }
            }
        }

    // ---- the server the client rows read from ----------------------------------------------------------------------

    /** A stream whose first read is the single byte [DOWNLOAD] gets [CHUNKS] KiB back; any other is echoed until its FIN. */
    private suspend fun serveRawStreams(connection: CoreConnection) {
        val chunk = payloadOf(CHUNK)
        connection.handleStreams { stream ->
            when (val first = stream.read(10.seconds)) {
                is ReadResult.Data -> {
                    val buffer = first.buffer
                    if (buffer.remaining() == 1 && buffer.get(buffer.position()) == DOWNLOAD) {
                        buffer.freeIfNeeded()
                        repeat(CHUNKS) {
                            chunk.position(0)
                            stream.writeFully(chunk, 10.seconds)
                        }
                    } else {
                        stream.writeFully(buffer, 10.seconds)
                        buffer.freeIfNeeded()
                        while (true) {
                            when (val next = stream.read(10.seconds)) {
                                is ReadResult.Data -> {
                                    stream.writeFully(next.buffer, 10.seconds)
                                    next.buffer.freeIfNeeded()
                                }
                                else -> break
                            }
                        }
                    }
                }
                else -> Unit
            }
            stream.close()
        }
    }

    // ---- the client rows --------------------------------------------------------------------------------------------

    private suspend fun clientRows(
        row: String,
        port: Int,
        shape: Shape,
    ) {
        val client = connectCore(port, loops = loops, factory = BufferFactory.network())
        val connection = client.connection.public()
        val dispatcher = CountingDispatcher(CALLER)
        val lane =
            when (val entry = client.connection.handlers.enter()) {
                is LaneEntry.Entered -> entry.lane
                is LaneEntry.Refused -> fail("the client's handler pool is closed")
            }
        val run = ClientRun(client, connection, CONN_FIELD.get(client.connection) as Conn, lane, shape, dispatcher)
        val done = CompletableFuture<kotlin.Result<Unit>>()
        suspend {
            run.echoes("$row | echo 64 B")
            run.download("$row | 16 MiB one way")
            run.newStreams("$row | new stream per echo")
        }.startCoroutine(Continuation(dispatcher + Job()) { done.complete(it) })
        done.get(300, TimeUnit.SECONDS).getOrThrow()
        client.connection.handlers.left()
        client.connection.close(QuicError.NoError)
        dispatcher.close()
    }

    private inner class ClientRun(
        private val client: CoreClient,
        private val connection: QuicConnection,
        private val conn: Conn,
        private val lane: HandlerLane,
        private val shape: Shape,
        private val dispatcher: CountingDispatcher,
    ) {
        private val payload = payloadOf(ECHO)
        private val signal = Signal()
        private val handBacks = AtomicLong()
        private val laneDispatches = AtomicLong()
        private lateinit var threads: Threads

        suspend fun echoes(label: String) {
            threads = Threads(threadId(client.loop.name), Thread.currentThread().id, threadsNamed(CLIENT_LANE_PREFIX))
            val stream = connection.openStream().getOrElse { fail("not opened: $it") }
            val reader = register(stream, EchoCount(ECHO, signal))
            repeat(ECHO_WARM_UP) { echo(stream) }
            // The handler lane's threads start with the first handler: counted from the warm-up's end.
            threads = Threads(threads.loop, threads.caller, threadsNamed(CLIENT_LANE_PREFIX))
            val before = stats()
            val from = snapshot()
            repeat(ECHOES) { echo(stream) }
            rows += from.row(label, before, stats(), ECHOES.toLong())
            stream.send.finish()
            reader.awaitEnd()
        }

        private suspend fun echo(stream: QuicStream) {
            payload.position(0)
            if (stream.send.write(payload) !== StreamWrite.Written) fail("the echo's write was not written")
            when (shape) {
                is Shape.Suspend -> {
                    var got = 0
                    while (got < ECHO) got += stream.receive.read { it.remaining() }.getOrElse { fail("the echo ended: $it") }
                }
                is Shape.Registered -> signal.await()
            }
        }

        suspend fun download(label: String) {
            val stream = connection.openStream().getOrElse { fail("not opened: $it") }
            val before = stats()
            val count = ReadCount(WARM_READS) { snapshot() }
            if (stream.send.writeAndFinish(byteOf(DOWNLOAD)) !== StreamWrite.Written) fail("not written")
            when (shape) {
                is Shape.Suspend -> stream.receive.readEach { count.onData(it) }
                is Shape.Registered -> register(stream, count).awaitEnd()
            }
            // Bytes and hand-offs are counted past the warm-up reads; the wire over the whole transfer.
            rows += count.from.row(label, before, stats(), count.reads - WARM_READS, wireOps = count.reads)
        }

        suspend fun newStreams(label: String) {
            repeat(NEW_WARM_UP) { newStreamEcho() }
            val before = stats()
            val from = snapshot()
            repeat(NEW_STREAMS) { newStreamEcho() }
            rows += from.row(label, before, stats(), NEW_STREAMS.toLong())
        }

        private suspend fun newStreamEcho() {
            val stream = connection.openStream().getOrElse { fail("not opened: $it") }
            payload.position(0)
            when (shape) {
                is Shape.Suspend -> {
                    if (stream.send.writeAndFinish(payload) !== StreamWrite.Written) fail("not written")
                    var got = 0
                    while (true) {
                        got +=
                            stream.receive.read { it.remaining() }.getOrElse { end ->
                                if (end !== ScopedRead.End.PeerFinished || got != ECHO) fail("the echo ended after $got B: $end")
                                return
                            }
                    }
                }
                is Shape.Registered -> {
                    val reader = register(stream, EchoCount(Int.MAX_VALUE, signal))
                    if (stream.send.writeAndFinish(payload) !== StreamWrite.Written) fail("not written")
                    reader.awaitEnd()
                }
            }
        }

        private suspend fun stats(): QuicConnectionStats = (connection.stats() as QuicStatsRead.Read).stats

        fun snapshot(): Snapshot {
            val resumes = { dispatcher.dispatches.get() }
            val dispatches = { dispatcher.dispatches.get() + laneDispatches.get() }
            val backs = { handBacks.get() }
            return Snapshot(threads, resumes, dispatches, backs)
        }

        private fun register(
            stream: QuicStream,
            handler: Handler,
        ): Registration =
            when (shape) {
                is Shape.Suspend -> Registration.Inline
                is Shape.Registered -> {
                    val reader = RegisteredReader(stream.id, conn, lane, shape.delivery, handler, handBacks, laneDispatches)
                    conn.submit(reader, CallerPlace.HandToLoop)
                    Registration.Reader(reader)
                }
            }
    }

    /** What the caller waits on once a stream's reads are under way. */
    private sealed interface Registration {
        suspend fun awaitEnd()

        /** The caller reads itself: nothing to wait for. */
        data object Inline : Registration {
            override suspend fun awaitEnd() = Unit
        }

        class Reader(
            private val reader: RegisteredReader,
        ) : Registration {
            override suspend fun awaitEnd() = reader.ended.await()
        }
    }

    // ---- the handlers -----------------------------------------------------------------------------------------------

    /** A registered handler: called with each read's bytes, valid only during the call, then once at the end. */
    private interface Handler {
        fun onData(bytes: ReadBuffer)

        fun onEnd()
    }

    /** Fires [signal] each time another [every] bytes arrived. */
    private class EchoCount(
        private val every: Int,
        private val signal: Signal,
    ) : Handler {
        private var got = 0

        override fun onData(bytes: ReadBuffer) {
            got += bytes.remaining()
            if (got >= every) {
                got -= every
                signal.fire()
            }
        }

        override fun onEnd() = Unit
    }

    /** Counts reads, and takes the counters' snapshot once [warm] reads went by. */
    private class ReadCount(
        private val warm: Long,
        private val take: () -> Snapshot,
    ) : Handler {
        var reads = 0L
        lateinit var from: Snapshot

        override fun onData(bytes: ReadBuffer) {
            reads++
            if (reads == warm) from = take()
        }

        override fun onEnd() = Unit
    }

    /**
     * Registered once on [stream]: the loop drains quiche into the reader's buffers and hands them to [handler] on
     * [lane], or calls it in place for [Delivery.OnLoop]. While a delivery is out it reads nothing more, so quiche keeps
     * the bytes and the stream's flow-control window stays shut: a handler slow to return holds the peer back.
     */
    private class RegisteredReader(
        private val stream: QuicStreamId,
        private val conn: Conn,
        private val lane: HandlerLane,
        private val delivery: Delivery,
        private val handler: Handler,
        private val handBacks: AtomicLong,
        private val laneDispatches: AtomicLong,
    ) : ConnWork(),
        Waiter {
        val ended = Signal()
        private val out = AtomicInteger(IDLE)
        private val lent = BufferFactory.network().allocate(BATCH_ROOM)
        private val lentAddress = addressOf(lent)
        private val owned = arrayOfNulls<PlatformBuffer>(MAX_BATCH)
        private val lengths = IntArray(MAX_BATCH)
        private var count = 0
        private var finished = Finished.No
        private val deliver = Runnable { deliverOnLane() }
        private val again = Again()

        private enum class Finished { No, Yes }

        override fun run(inside: Inside) {
            val entry = inside.streams.entry(stream)
            when (drain(inside.streams)) {
                Retry.Again -> entry.parkReader(this)
                Retry.Settled -> inside.streams.collect(entry)
            }
        }

        override fun retry(table: StreamTable): Retry = drain(table)

        /** The loop: reads what quiche holds, unless a delivery is still out. */
        private fun drain(table: StreamTable): Retry {
            if (out.get() == OUT) return Retry.Again
            count = 0
            var offset = 0
            while (count < MAX_BATCH && finished == Finished.No) {
                val into =
                    when (delivery) {
                        Delivery.BatchLent, Delivery.OnLoop -> lent
                        Delivery.PerRead, Delivery.BatchOwned -> BufferFactory.network().allocate(ROOM)
                    }
                val at =
                    when (delivery) {
                        Delivery.BatchLent -> offset
                        Delivery.OnLoop, Delivery.PerRead, Delivery.BatchOwned -> 0
                    }
                val room =
                    when (delivery) {
                        Delivery.BatchLent -> BATCH_ROOM - offset
                        Delivery.OnLoop, Delivery.PerRead, Delivery.BatchOwned -> ROOM
                    }
                if (room < CHUNK) break
                val address = if (into === lent) lentAddress + at else addressOf(into)
                val read =
                    table.io.recv(stream, address, room).fold(
                        more = { it },
                        last = {
                            table.entry(stream).finished()
                            finished = Finished.Yes
                            it
                        },
                        nothingYet = { 0 },
                        gone = { 0 },
                        reset = { 0 },
                        error = { 0 },
                    )
                if (read == 0) {
                    if (into !== lent) into.freeIfNeeded()
                    break
                }
                when (delivery) {
                    Delivery.OnLoop -> {
                        lent.position(0)
                        lent.setLimit(read)
                        handler.onData(lent)
                    }
                    Delivery.BatchLent -> {
                        lengths[count++] = read
                        offset += read
                    }
                    Delivery.PerRead, Delivery.BatchOwned -> {
                        into.position(0)
                        into.setLimit(read)
                        owned[count++] = into
                    }
                }
                if (delivery == Delivery.PerRead) break
            }
            when (delivery) {
                Delivery.OnLoop -> if (finished == Finished.Yes) end()
                Delivery.PerRead, Delivery.BatchOwned, Delivery.BatchLent ->
                    if (count > 0 || finished == Finished.Yes) {
                        out.set(OUT)
                        laneDispatches.incrementAndGet()
                        lane.dispatch(HandlerSite.Unnamed, deliver)
                    }
            }
            return if (finished == Finished.Yes) Retry.Settled else Retry.Again
        }

        /** The lane: hands the batch to the handler, then gives the loop the stream back. */
        private fun deliverOnLane() {
            var offset = 0
            for (i in 0 until count) {
                when (delivery) {
                    Delivery.BatchLent -> {
                        lent.position(offset)
                        lent.setLimit(offset + lengths[i])
                        offset += lengths[i]
                        handler.onData(lent)
                    }
                    Delivery.OnLoop, Delivery.PerRead, Delivery.BatchOwned -> {
                        val buffer = owned[i] ?: fail("no buffer was read into slot $i")
                        handler.onData(buffer)
                        buffer.freeIfNeeded()
                        owned[i] = null
                    }
                }
            }
            if (finished == Finished.Yes) return end()
            out.set(IDLE)
            handBacks.incrementAndGet()
            conn.submit(again, CallerPlace.HandToLoop)
        }

        private fun end() {
            handler.onEnd()
            lent.freeIfNeeded()
            ended.fire()
        }

        override fun connectionEnded(end: ConnEnd) = ended.fire()

        override fun gone(end: ConnEnd) = connectionEnded(end)

        /** Back inside after a delivery: reads what quiche held meanwhile, since no new datagram may come to wake the reader. */
        private inner class Again : ConnWork() {
            override fun run(inside: Inside) {
                if (drain(inside.streams) == Retry.Settled) inside.streams.unpark(stream, this@RegisteredReader)
            }

            override fun gone(end: ConnEnd) = Unit
        }

        private companion object {
            const val IDLE = 0
            const val OUT = 1
        }
    }

    /** One waiter and one firer at a time; a fire before the wait is kept for it. Lock-free; a park allocates its continuation. */
    private class Signal {
        private val state = AtomicInteger(NONE)

        @Volatile private var waiting: CancellableContinuation<Unit>? = null

        suspend fun await() {
            if (state.compareAndSet(FIRED, NONE)) return
            suspendCancellableCoroutine { c ->
                waiting = c
                if (!state.compareAndSet(NONE, WAITING)) {
                    state.set(NONE)
                    c.resume(Unit)
                }
            }
        }

        fun fire() {
            if (state.compareAndSet(NONE, FIRED)) return
            if (state.compareAndSet(WAITING, NONE)) (waiting ?: fail("a waiter parked without its continuation")).resume(Unit)
        }

        private companion object {
            const val NONE = 0
            const val FIRED = 1
            const val WAITING = 2
        }
    }

    // ---- row (b): handleStreams on the server -----------------------------------------------------------------------

    private enum class CountResumes { No, Yes }

    private suspend fun handleStreamsRows(
        row: String,
        counting: CountResumes,
    ) {
        val resumes = AtomicLong()
        val reads = AtomicLong()
        withCoreServer(onConnection = { core ->
            core.public().handleStreams { incoming ->
                suspend fun serve() {
                    when (incoming) {
                        is QuicStream -> {
                            var upload = UploadMode.Unknown
                            incoming.receive.readEach { bytes ->
                                reads.incrementAndGet()
                                if (upload == UploadMode.Unknown) {
                                    upload = if (bytes.get(bytes.position()) == UPLOAD) UploadMode.Yes else UploadMode.No
                                }
                                if (upload == UploadMode.No) incoming.send.write(bytes)
                            }
                        }
                        is QuicReceiveStream -> incoming.readEach { }
                    }
                }
                when (counting) {
                    CountResumes.Yes -> withContext(ResumeCounter(resumes)) { serve() }
                    CountResumes.No -> serve()
                }
            }
        }) { server ->
            val client = connectCore(server.port, loops = loops, factory = BufferFactory.network())
            val connection = client.connection.public()
            val payload = payloadOf(ECHO)
            val chunk = payloadOf(CHUNK)

            suspend fun stats(): QuicConnectionStats = (connection.stats() as QuicStatsRead.Read).stats

            fun snapshot() =
                Snapshot(Threads(threadId(SERVER_LOOP_THREAD), NO_THREAD, threadsNamed(SERVER_HANDLER_THREAD)), {
                    resumes.get()
                }, { resumes.get() }, { 0L })

            val stream = connection.openStream().getOrElse { fail("not opened: $it") }

            suspend fun echo() {
                payload.position(0)
                stream.send.write(payload)
                var got = 0
                while (got < ECHO) got += stream.receive.read { it.remaining() }.getOrElse { fail("the echo ended: $it") }
            }
            repeat(ECHO_WARM_UP) { echo() }
            var before = stats()
            var from = snapshot()
            repeat(ECHOES) { echo() }
            rows += from.row("$row | echo 64 B", before, stats(), ECHOES.toLong())
            stream.send.finish()

            val upload = connection.openStream().getOrElse { fail("not opened: $it") }
            upload.send.write(byteOf(UPLOAD))
            repeat(WARM_READS.toInt()) {
                chunk.position(0)
                upload.send.write(chunk)
            }
            before = stats()
            from = snapshot()
            val readsFrom = reads.get()
            repeat(CHUNKS - WARM_READS.toInt()) {
                chunk.position(0)
                upload.send.write(chunk)
            }
            upload.send.finish()
            upload.receive.read { Unit }
            rows += from.row("$row | 16 MiB one way", before, stats(), reads.get() - readsFrom)

            suspend fun one() {
                val s = connection.openStream().getOrElse { fail("not opened: $it") }
                payload.position(0)
                s.send.writeAndFinish(payload)
                while (true) s.receive.read { it.remaining() }.getOrElse { return }
            }
            repeat(NEW_WARM_UP) { one() }
            before = stats()
            from = snapshot()
            repeat(NEW_STREAMS) { one() }
            rows += from.row("$row | new stream per echo", before, stats(), NEW_STREAMS.toLong())
            client.connection.close(QuicError.NoError)
        }
    }

    private enum class UploadMode { Unknown, Yes, No }

    /** Counts every resume of the coroutine it is in: each one installs the thread context. */
    private class ResumeCounter(
        private val count: AtomicLong,
    ) : AbstractCoroutineContextElement(Key),
        ThreadContextElement<Unit> {
        companion object Key : CoroutineContext.Key<ResumeCounter>

        override fun updateThreadContext(context: CoroutineContext) {
            count.incrementAndGet()
        }

        override fun restoreThreadContext(
            context: CoroutineContext,
            oldState: Unit,
        ) = Unit
    }

    // ---- counting ---------------------------------------------------------------------------------------------------

    /** The threads a row's bytes are counted on; [caller] is [NO_THREAD] where no caller reads. */
    private class Threads(
        val loop: Long,
        val caller: Long,
        val lanes: List<Long>,
    )

    /** The threads' allocation counters and the hand-off counts at one moment. */
    private class Snapshot(
        private val on: Threads,
        private val resumes: () -> Long,
        private val dispatches: () -> Long,
        private val handBacks: () -> Long,
    ) {
        private val mx = ManagementFactory.getThreadMXBean() as com.sun.management.ThreadMXBean
        private val loopFrom = mx.getThreadAllocatedBytes(on.loop)
        private val callerFrom = callerBytes()
        private val lanesFrom = laneBytes()
        private val resumesFrom = resumes()
        private val dispatchesFrom = dispatches()
        private val handBacksFrom = handBacks()

        private fun callerBytes() = if (on.caller == NO_THREAD) 0L else mx.getThreadAllocatedBytes(on.caller)

        private fun laneBytes() = on.lanes.sumOf { mx.getThreadAllocatedBytes(it) }

        fun row(
            label: String,
            before: QuicConnectionStats,
            after: QuicConnectionStats,
            ops: Long,
            wireOps: Long = ops,
        ): String {
            val n = ops.toDouble()
            val wire = (after.packetsSent + after.packetsReceived - before.packetsSent - before.packetsReceived).toDouble() / wireOps
            return "%s | %.1f | %.1f | %.1f | %.3f | %.3f | %.3f | %.2f | %d".format(
                label,
                (callerBytes() - callerFrom) / n,
                (laneBytes() - lanesFrom) / n,
                (mx.getThreadAllocatedBytes(on.loop) - loopFrom) / n,
                (resumes() - resumesFrom) / n,
                (dispatches() - dispatchesFrom) / n,
                (handBacks() - handBacksFrom) / n,
                wire,
                ops,
            )
        }
    }

    private fun threadId(name: String): Long =
        Thread
            .getAllStackTraces()
            .keys
            .first { it.name == name }
            .id

    private fun threadsNamed(prefix: String): List<Long> =
        Thread
            .getAllStackTraces()
            .keys
            .filter { it.name.startsWith(prefix) }
            .map { it.id }

    private companion object {
        const val CALLER = "handler-shape-caller"
        const val CLIENT_LANE_PREFIX = "quic-core-client-handler"
        const val NO_THREAD = -1L
        const val DOWNLOAD: Byte = 'D'.code.toByte()
        const val UPLOAD: Byte = 'U'.code.toByte()
        const val ECHO = 64
        const val CHUNK = 1024
        const val CHUNKS = 16_384
        const val ROOM = 16_384
        const val BATCH_ROOM = 65_536
        const val MAX_BATCH = 64
        const val ECHO_WARM_UP = 1_000
        const val ECHOES = 3_000
        const val WARM_READS = 1_000L
        const val NEW_WARM_UP = 500
        const val NEW_STREAMS = 2_000
        val CONN_FIELD = CoreConnection::class.java.getDeclaredField("conn").apply { isAccessible = true }

        fun payloadOf(size: Int) =
            BufferFactory.network().allocate(size).apply {
                repeat(size) { writeByte(it.toByte()) }
                resetForRead()
            }

        fun byteOf(value: Byte) =
            BufferFactory.network().allocate(1).apply {
                writeByte(value)
                resetForRead()
            }

        fun addressOf(buffer: PlatformBuffer): Long =
            when (val at = NativeBytes.addressOf(buffer)) {
                is ReadAddress.At -> at.address
                is ReadAddress.NotAddressable -> 0L
            }
    }
}
