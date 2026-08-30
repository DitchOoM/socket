package com.ditchoom.socket.quic

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.ByteOrder
import com.ditchoom.buffer.Default
import com.ditchoom.buffer.PlatformBuffer
import com.ditchoom.buffer.deterministic
import com.ditchoom.buffer.nativeMemoryAccess
import com.ditchoom.buffer.wrapNativeAddress
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.withTimeout
import java.lang.ref.WeakReference
import java.util.Collections
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/**
 * **The memory quiche is pointed at must still be mapped when quiche reads or writes it** — the
 * root cause of the "echo decodes bytes that were never sent" family (#366 / #401 / #415).
 *
 * ## The mechanism
 *
 * `BufferFactory.Default` on the JVM hands out buffers whose native memory is owned by the
 * *collector*: on JDK 21+ an `FfmAutoBuffer` over `Arena.ofAuto()` (whose `freeNativeMemory()` is a
 * documented no-op), on JDK 17 / Android a direct `ByteBuffer` reclaimed by its `Cleaner`. Both free
 * the memory when the **Kotlin object** stops being reachable, which no caller controls.
 *
 * Every echo site in this repo — and the natural way to use the public API — writes like this:
 *
 * ```kotlin
 * val out = BufferFactory.Default.allocate(payload.size)
 * …
 * stream.write(out, 5.seconds)   // `out` is dead from here on
 * ```
 *
 * The driver took `out`'s raw address, queued a `StreamSend` and suspended; nothing then referred to
 * `out`, so the collector could free the memory and the allocator hand the chunk to somebody else
 * before the driver loop ever called `quiche_conn_stream_send`. quiche copies whatever is at that
 * address into its send buffer, seals it and puts it on the wire — so the peer, and an echo peer's
 * reply, carry **bytes that were never sent**: right length, freed-chunk allocator metadata inside.
 * That is exactly the captured evidence (`… aa 7f 00 00 …` — the high half of an x86-64 heap
 * pointer, in an 11-byte "second-conn" echo), and exactly why this only ever fired on JVM/Android
 * lanes and never on Kotlin/Native, where a buffer's memory is freed explicitly.
 *
 * ## What these tests pin
 *
 * The stub backend stands where quiche stands — inside the FFI call, holding the address — and
 * forces collection there. The assertion is the property the whole family needed: *the object that
 * keeps this memory mapped is still reachable*, and (only once that holds, so no test ever reads
 * freed memory) *the bytes at the address are the bytes the caller wrote*.
 *
 * The `control` buffer is not decoration. `System.gc()` is advisory, and a run where nothing is
 * collected would pass these tests while proving nothing — the classic false green. So each test
 * first drops a buffer of its own and requires *that* one to be collected by the same loop; a
 * collector that did nothing fails the test instead of flattering it.
 *
 * **Mutation proof:** stop `QuicheMemory.Borrowed` from retaining its `owner` — the pre-fix state,
 * an address travelling alone — and [aWriteBufferTheCallerDroppedIsStillMappedWhenQuicheReadsIt]
 * fails on the first run: *"the buffer whose address quiche was handed had already been collected"*.
 *
 * Deleting only the `finally { cmd.buf.endBorrow() }` from `QuicheDriver.execute` does **not** move
 * these tests, and that is worth stating rather than discovering later: the retention is what makes
 * the buffer reachable, and the fence is what stops an optimizing JIT from dropping the command
 * early and undoing it. A test cannot force HotSpot to make that optimisation on demand, so the
 * fence is defended by reasoning (`Reference.reachabilityFence`'s own contract) rather than by this
 * suite — do not read its green as licence to remove it.
 *
 * ## Why JVM-only
 *
 * Android shares both the defect and the fix (the fence's actual lives in `commonJvmMain`), but a
 * device lane's `Runtime.gc()` gives weaker promptness guarantees than HotSpot's, and this suite's
 * control assertion turns "the collector did not run" into a *failure*. Keeping it here keeps the
 * device lane free of a timing-sensitive assertion about ART's collector while the fix still ships
 * to it.
 */
class QuicNativeBufferLifetimeTests {
    /** What the driver itself allocates from — the production choice (`BufferFactory.network()`). */
    private val driverBufferFactory = BufferFactory.deterministic()

    /** How long the probe insists on trying before it concludes the collector will not run. */
    private val gcAttempts = 40
    private val gcPauseMillis = 5L

    /** Everything the in-FFI probe learned, read back after the call completes. */
    private class Probe {
        @Volatile var controlCollected: Boolean = false

        /** Starts pessimistic so a probe that never ran cannot pass by default. */
        @Volatile var ownerCollected: Boolean = true

        @Volatile var bytesAtAddress: ByteArray? = null
    }

    /**
     * A buffer allocated and dropped in a frame that has already returned — unreachable by
     * construction, so a collector that runs at all must clear this reference.
     */
    private fun droppedControl(): WeakReference<PlatformBuffer> = WeakReference(BufferFactory.Default.allocate(64))

    /** Collect until [control] clears (or we give up), and report whether it did. */
    private fun collectUntil(control: WeakReference<PlatformBuffer>): Boolean {
        repeat(gcAttempts) {
            System.gc()
            Thread.sleep(gcPauseMillis)
            if (control.get() == null) return true
        }
        return control.get() == null
    }

    private fun createTestDriver(
        api: QuicheApi,
        bufferFactory: BufferFactory = driverBufferFactory,
    ): QuicheDriver =
        QuicheDriver(
            // Test double: nothing here moves a path.
            migration = MigrationCapability.BackendCannotMigrate,
            rawApi = api,
            conn = QuicheConn(1L),
            bufferFactory = bufferFactory,
            recvInfo = QuicheRecvInfo(1L),
            sendInfo = QuicheSendInfo(1L),
            udpChannel = StubUdpChannel(),
            clientMode = false,
            isServer = false,
        )

    /**
     * Allocate, fill, write, never touch again — the shape of every echo site in this repo and of
     * the two CI captures this suite exists for. `sendBuf` is dead the instant `streamWrite` has its
     * address, and it lives in a frame of its own so no enclosing coroutine keeps it reachable
     * either.
     */
    private suspend fun writeThenDropTheBuffer(
        adapter: DriverStreamAdapter,
        payload: ByteArray,
        published: AtomicReference<WeakReference<PlatformBuffer>?>,
    ) {
        val sendBuf = BufferFactory.Default.allocate(payload.size)
        for (b in payload) sendBuf.writeByte(b)
        sendBuf.resetForRead()
        published.set(WeakReference(sendBuf))
        adapter.streamWrite(QuicStreamId(0L), sendBuf, 5.seconds)
    }

    @Test
    fun aWriteBufferTheCallerDroppedIsStillMappedWhenQuicheReadsIt() =
        runQuicTest {
            val payload = "second-conn".encodeToByteArray()
            val published = AtomicReference<WeakReference<PlatformBuffer>?>(null)
            val probe = Probe()
            val api =
                object : QuicheApi by StubQuicheApi() {
                    override fun connStreamSend(
                        conn: QuicheConn,
                        streamId: QuicStreamId,
                        buf: Long,
                        bufLen: Int,
                        fin: Boolean,
                    ): StreamSendResult {
                        probe.controlCollected = collectUntil(droppedControl())
                        val owner = published.get()
                        probe.ownerCollected = owner != null && owner.get() == null
                        // Only when the owner survived: reading the address otherwise would BE the
                        // use-after-free this test is about.
                        if (!probe.ownerCollected) {
                            val view = PlatformBuffer.wrapNativeAddress(buf, bufLen)
                            probe.bytesAtAddress = ByteArray(bufLen) { view.readByte() }
                        }
                        return StreamSendResult(bufLen, null)
                    }
                }
            val driver = createTestDriver(api)
            val driverScope = CoroutineScope(SupervisorJob() + CoroutineExceptionHandler { _, _ -> })
            driver.start(driverScope)
            val adapter = DriverStreamAdapter(driver, StreamSlot(QuicStreamId(0L)))
            try {
                withTimeout(30.seconds) { writeThenDropTheBuffer(adapter, payload, published) }
            } finally {
                driver.destroy()
                driverScope.cancel()
            }
            assertTrue(
                probe.controlCollected,
                "no collection happened during the send, so this run proves nothing about buffer lifetime",
            )
            assertTrue(
                !probe.ownerCollected,
                "the buffer whose address quiche was handed had already been collected — quiche would " +
                    "have copied freed memory onto the wire (#366)",
            )
            assertContentEquals(
                payload,
                probe.bytesAtAddress,
                "the bytes at the address quiche was handed are not the bytes the caller wrote",
            )
        }

    /** A [BufferFactory] that remembers what it handed out, and at which address. */
    private class TrackingFactory(
        private val delegate: BufferFactory,
    ) : BufferFactory {
        val handedOut: MutableList<Pair<WeakReference<PlatformBuffer>, Long>> =
            Collections.synchronizedList(mutableListOf())

        override fun allocate(
            size: Int,
            byteOrder: ByteOrder,
        ): PlatformBuffer =
            delegate.allocate(size, byteOrder).also {
                handedOut += WeakReference(it) to (it.nativeMemoryAccess?.nativeAddress ?: 0L)
            }

        override fun wrap(
            array: ByteArray,
            byteOrder: ByteOrder,
        ): PlatformBuffer = delegate.wrap(array, byteOrder)

        /** The buffer living at [address], or null if this factory never handed one out there. */
        fun ownerOf(address: Long): WeakReference<PlatformBuffer>? =
            synchronized(handedOut) { handedOut.toList() }.firstOrNull { it.second == address }?.first
    }

    /** `streamRead` allocates its own buffer; nobody outside the driver holds it. */
    private suspend fun readWithADriverOwnedBuffer(
        adapter: DriverStreamAdapter,
        factory: BufferFactory,
    ) {
        adapter.streamRead(QuicStreamId(0L), factory, 64, 5.seconds)
    }

    @Test
    fun aReadBufferIsStillMappedWhenQuicheWritesIt() =
        runQuicTest {
            val probe = Probe()
            val factory = TrackingFactory(driverBufferFactory)
            val api =
                object : QuicheApi by StubQuicheApi() {
                    override fun connStreamRecv(
                        conn: QuicheConn,
                        streamId: QuicStreamId,
                        buf: Long,
                        bufLen: Int,
                    ): StreamRecvResult {
                        probe.controlCollected = collectUntil(droppedControl())
                        // ONLY the buffer this recv is pointed at. Flagging *any* collected pool
                        // buffer would flag one that was explicitly freed first, which is not this
                        // hazard — the loose version of this probe reported a defect that was not there.
                        val owner = factory.ownerOf(buf)
                        probe.ownerCollected = owner != null && owner.get() == null
                        return StreamRecvResult.Data(4, false)
                    }
                }
            val driver = createTestDriver(api, factory)
            val driverScope = CoroutineScope(SupervisorJob() + CoroutineExceptionHandler { _, _ -> })
            driver.start(driverScope)
            val adapter = DriverStreamAdapter(driver, StreamSlot(QuicStreamId(0L)))
            try {
                withTimeout(30.seconds) { readWithADriverOwnedBuffer(adapter, factory) }
            } finally {
                driver.destroy()
                driverScope.cancel()
            }
            assertTrue(
                probe.controlCollected,
                "no collection happened during the recv, so this run proves nothing about buffer lifetime",
            )
            assertTrue(
                !probe.ownerCollected,
                "the buffer quiche was about to WRITE into had already been collected — quiche would " +
                    "have written into freed memory (#366)",
            )
        }
}
