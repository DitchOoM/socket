package com.ditchoom.socket.http3

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
import kotlinx.coroutines.test.runTest
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.fail
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * Phase 1 of the H3/QPACK conformance plan, in its **deterministic, every-platform** form: a seeded
 * in-process fuzzer over the hand-rolled decoder surface. Where the JVM-only Jazzer target
 * (`Http3CodecFuzzer` + the `http3CodecFuzz` Gradle task) uses libFuzzer coverage feedback to mine new
 * inputs, this drives the same entry points with a seeded [Random] so the **invariant** is checked on
 * jvm / js / linuxX64 / linuxArm64 / apple in ordinary CI, and every failure is reproducible from its
 * printed seed + hex.
 *
 * The invariant for every decoder entry point: on ANY input it either produces a value or throws a
 * **typed [Http3StreamException]** — never a raw buffer-underflow, [IllegalArgumentException],
 * [IndexOutOfBoundsException], null-pointer, hang, or other untyped failure (errors stay typed). This is
 * the contract the Phase-0 boundary hardening establishes; this fuzzer is its scale check.
 *
 * Seeds are fixed; a regression is pinned by copying the printed hex into [Http3ConformanceCorpusTests].
 */
class Http3DecoderInvariantFuzzTests {
    private fun pool() =
        BufferPool(
            threadingMode = ThreadingMode.SingleThreaded,
            maxPoolSize = 8,
            defaultBufferSize = 256,
            factory = BufferFactory.Default,
        )

    private fun bufferOf(bytes: ByteArray): PlatformBuffer {
        val buf = BufferFactory.Default.allocate(bytes.size.coerceAtLeast(1))
        for (b in bytes) buf.writeByte(b)
        buf.resetForRead()
        return buf
    }

    private fun ByteArray.toHex(): String = joinToString("") { (it.toInt() and 0xFF).toString(16).padStart(2, '0') }

    private class ScriptedByteStream(
        results: List<ReadResult>,
    ) : ByteStream {
        private val queue = ArrayDeque(results)
        override val isOpen: Boolean get() = queue.isNotEmpty()
        override val readPolicy: ReadPolicy = ReadPolicy.Bounded(15.seconds)
        override val writePolicy: WritePolicy = WritePolicy.Bounded(15.seconds)

        override suspend fun read(deadline: Duration): ReadResult = if (queue.isEmpty()) ReadResult.End else queue.removeFirst()

        override suspend fun write(
            buffer: ReadBuffer,
            deadline: Duration,
        ): BytesWritten = throw UnsupportedOperationException("read-only test stream")

        override suspend fun close() = Unit
    }

    private fun streamOf(bytes: ByteArray): ByteStream {
        val buf = BufferFactory.Default.allocate(bytes.size.coerceAtLeast(1))
        for (b in bytes) buf.writeByte(b)
        buf.resetForRead()
        return ScriptedByteStream(listOf(ReadResult.Data(buf), ReadResult.End))
    }

    /** Asserts [block] either returns or throws ONLY [Http3StreamException]; any other throwable fails. */
    private inline fun assertTypedOnly(
        label: String,
        wire: ByteArray,
        block: () -> Unit,
    ) {
        try {
            block()
        } catch (e: Http3StreamException) {
            // The expected, typed outcome for malformed input.
        } catch (t: Throwable) {
            fail("$label leaked ${t::class.simpleName} (not Http3StreamException) for wire=${wire.toHex()}: ${t.message}")
        }
    }

    private fun Random.bytes(maxLen: Int): ByteArray = ByteArray(nextInt(0, maxLen)) { nextInt(256).toByte() }

    private val knownFrameTypes =
        intArrayOf(0x00, 0x01, 0x03, 0x04, 0x05, 0x07, 0x0d, 0x02, 0x06, 0x08, 0x09, 0x21)

    // =====================================================================================

    @Test
    fun fuzz_streamReader_onlyTypedErrors() =
        runTest {
            val rng = Random(0x77_3010_0001)
            repeat(4000) { i ->
                val wire = rng.bytes(48)
                // Half the time aim the first byte at a known frame type so structured/bounded decode
                // paths (SETTINGS entry runs, single-varint frames, reserved-H2 rejection) get hammered.
                if (wire.isNotEmpty() && rng.nextBoolean()) wire[0] = knownFrameTypes[rng.nextInt(knownFrameTypes.size)].toByte()
                assertTypedOnly("streamReader#$i", wire) {
                    val reader = Http3StreamReader(streamOf(wire), StreamProcessor.create(pool(), ByteOrder.BIG_ENDIAN))
                    while (reader.nextFrame() != null) Unit
                }
            }
        }

    @Test
    fun fuzz_qpackDecodeSection_onlyTypedErrors() =
        runTest {
            val rng = Random(0x77_3010_0002)
            repeat(4000) { i ->
                val wire = rng.bytes(48)
                // A capacity-0 (static-only) decoder: any non-zero Required Insert Count is rejected as
                // QPACK_DECOMPRESSION_FAILED rather than blocking on dynamic inserts — so this never hangs
                // on the documented §2.2.1 blocking path while still exercising all field-line decoders.
                // Bias the prefix to 0x00,0x00 (RIC 0, Base 0) half the time to dig into field-line decode.
                if (wire.size >= 2 && rng.nextBoolean()) {
                    wire[0] = 0x00
                    wire[1] = 0x00
                }
                assertTypedOnly("qpackDecodeSection#$i", wire) {
                    QpackDecoder(maxCapacity = 0) {}.decodeSection(bufferOf(wire), streamId = 0, scratchPool = null)
                }
            }
        }

    @Test
    fun fuzz_qpackEncoderInstructions_onlyTypedErrors() =
        runTest {
            val rng = Random(0x77_3010_0003)
            repeat(4000) { i ->
                val wire = rng.bytes(48)
                assertTypedOnly("qpackEncoderInstr#$i", wire) {
                    val reader = QpackInstructionReader.encoder(streamOf(wire), pool())
                    while (reader.next() != null) Unit
                }
            }
        }

    @Test
    fun fuzz_qpackDecoderInstructions_onlyTypedErrors() =
        runTest {
            val rng = Random(0x77_3010_0004)
            repeat(4000) { i ->
                val wire = rng.bytes(48)
                assertTypedOnly("qpackDecoderInstr#$i", wire) {
                    val reader = QpackInstructionReader.decoder(streamOf(wire), pool())
                    while (reader.next() != null) Unit
                }
            }
        }
}
