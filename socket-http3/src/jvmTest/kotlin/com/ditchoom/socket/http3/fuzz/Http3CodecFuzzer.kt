package com.ditchoom.socket.http3.fuzz

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.ByteOrder
import com.ditchoom.buffer.Default
import com.ditchoom.buffer.PlatformBuffer
import com.ditchoom.buffer.ReadBuffer
import com.ditchoom.buffer.flow.ByteStream
import com.ditchoom.buffer.flow.BytesWritten
import com.ditchoom.buffer.flow.ReadPolicy
import com.ditchoom.buffer.flow.ReadResult
import com.ditchoom.buffer.flow.WritePolicy
import com.ditchoom.buffer.pool.BufferPool
import com.ditchoom.buffer.pool.ThreadingMode
import com.ditchoom.buffer.stream.StreamProcessor
import com.ditchoom.socket.http3.Http3StreamException
import com.ditchoom.socket.http3.Http3StreamReader
import com.ditchoom.socket.http3.QpackDecoder
import com.ditchoom.socket.http3.QpackInstructionReader
import kotlinx.coroutines.runBlocking
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * Coverage-guided **Jazzer** fuzz target over the hand-rolled HTTP/3 + QPACK *decoder* — the module's
 * real risk surface (RFC 9114 / RFC 9204), as opposed to quiche's interop-proven transport. Unlike the
 * native `quiche_header_info` fuzzer in `:socket-quic-quiche`, the code under test here is **pure
 * Kotlin**, so libFuzzer (which Jazzer wraps) gets genuine edge coverage of the parser via Jazzer's JVM
 * instrumentation — this is a true coverage-guided fuzzer, not just a crash harness.
 *
 * Jazzer's driver ABI hands each input in as a `byte[]`; that array is converted to a [PlatformBuffer]
 * **once** at the entry point and never threaded further — every decoder entry point downstream is fed a
 * buffer ([fresh] makes an independent, owned copy per consumer, since each reader consumes/recycles its
 * input). The entry points: the frame reassembler ([Http3StreamReader]), the QPACK field-section decoder
 * ([QpackDecoder.decodeSection], on a capacity-0 decoder so a non-zero Required Insert Count is rejected
 * rather than blocking on dynamic inserts — no hang), and the QPACK encoder/decoder instruction readers
 * ([QpackInstructionReader]).
 *
 * **Invariant** (the bug being hunted): every entry point either returns a value or throws a typed
 * [Http3StreamException]. [tolerate] swallows exactly that; ANY other `Throwable` — a raw
 * buffer-underflow, [IndexOutOfBoundsException], NPE, [IllegalArgumentException], OOM, or a hang — bubbles
 * out of [fuzzerTestOneInput] and Jazzer records it as a finding with a `crash-*` repro. This is the
 * dynamic counterpart to the deterministic `Http3DecoderInvariantFuzzTests` (which pins the same
 * invariant under a seeded RNG on every platform) and the hand-crafted `Http3ConformanceCorpusTests`.
 *
 * **Run it** via the `http3CodecFuzz` Gradle task. The target uses the `byte[]` entry-point form, so it
 * has no compile-time dependency on Jazzer — Jazzer is only on the runtime classpath of that task.
 *
 * Intentionally NOT a `@Test`: a JUnit run would invoke it once with no input. It is reachable only
 * through the Jazzer driver, which calls [fuzzerTestOneInput] in a tight loop.
 */
object Http3CodecFuzzer {
    private const val INPUT_CAP = 4096
    private val factory = BufferFactory.Default

    private fun pool() =
        BufferPool(
            threadingMode = ThreadingMode.SingleThreaded,
            maxPoolSize = 8,
            defaultBufferSize = 256,
            factory = factory,
        )

    /** A read-only [ByteStream] yielding [source]'s bytes in one chunk, then End. */
    private class OneShotByteStream(
        private val source: ReadBuffer,
    ) : ByteStream {
        private var delivered = false
        override val isOpen: Boolean get() = !delivered
        override val readPolicy: ReadPolicy = ReadPolicy.Bounded(5.seconds)
        override val writePolicy: WritePolicy = WritePolicy.Bounded(5.seconds)

        override suspend fun read(deadline: Duration): ReadResult {
            if (delivered) return ReadResult.End
            delivered = true
            return ReadResult.Data(source)
        }

        override suspend fun write(
            buffer: ReadBuffer,
            deadline: Duration,
        ): BytesWritten = throw UnsupportedOperationException("read-only fuzz stream")

        override suspend fun close() = Unit
    }

    /**
     * An independent, read-positioned copy of [source]'s bytes (buffer→buffer, no array): each decoder
     * entry point consumes — and a stream reader recycles — its own input, so they can't share one buffer.
     */
    private fun fresh(source: ReadBuffer): PlatformBuffer {
        val len = source.remaining()
        val copy = factory.allocate(len.coerceAtLeast(1))
        val base = source.position()
        for (i in 0 until len) copy.writeByte(source[base + i])
        copy.resetForRead()
        return copy
    }

    @JvmStatic
    fun fuzzerTestOneInput(data: ByteArray) {
        val len = if (data.size > INPUT_CAP) INPUT_CAP else data.size
        if (len == 0) return
        // The single byte[] → buffer conversion at the driver ABI boundary; everything below is buffers.
        val source =
            factory.allocate(len).apply {
                writeBytes(data, 0, len)
                resetForRead()
            }
        runBlocking {
            tolerate {
                val reader = Http3StreamReader(OneShotByteStream(fresh(source)), StreamProcessor.create(pool(), ByteOrder.BIG_ENDIAN))
                while (reader.nextFrame() != null) Unit
            }
            tolerate {
                QpackDecoder(maxCapacity = 0) {}.decodeSection(fresh(source), streamId = 0, scratchPool = null)
            }
            tolerate {
                val reader = QpackInstructionReader.encoder(OneShotByteStream(fresh(source)), pool())
                while (reader.next() != null) Unit
            }
            tolerate {
                val reader = QpackInstructionReader.decoder(OneShotByteStream(fresh(source)), pool())
                while (reader.next() != null) Unit
            }
        }
    }

    /** Run [block]; a typed [Http3StreamException] is the expected outcome for malformed input and is
     *  swallowed. Every other throwable propagates to the Jazzer driver as a finding. */
    private inline fun tolerate(block: () -> Unit) {
        try {
            block()
        } catch (e: Http3StreamException) {
            // Expected: the codec rejected malformed input with a typed error. Not a finding.
        }
    }
}
