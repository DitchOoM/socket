package com.ditchoom.socket.http3

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.Default
import com.ditchoom.buffer.codec.EncodeContext
import com.ditchoom.socket.http3.Http3FuzzGenerators.runDecoderEntryPoints
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.fail

/**
 * The **Kotlin/Native** (and js/apple) strengthening of the decoder fuzzing story. Per the conformance
 * plan, K/N can't be coverage-instrumented for libFuzzer, so instead of a black-box native libFuzzer this
 * makes the seeded, every-platform fuzzer carry the native runtime — a third runtime beyond JVM-Jazzer and
 * the JS/native invariant fuzzer — with three additions over [Http3DecoderInvariantFuzzTests]'s pure-random
 * inputs:
 *
 *  1. [replayJvmJazzerCorpus_onlyTypedErrors] — the committed JVM Jazzer seed corpus
 *     (`socket-http3/fuzz/corpus/http3-codec`, the Phase-0 crafted vectors + any fuzzer-found crashers) is
 *     embedded and replayed through every decoder entry point on EVERY platform, so a JVM-discovered input
 *     becomes a cross-platform regression (the decoder twin of [Http3RoundTripCorpusReplayTests]).
 *  2. [corpusSpliceMutation_onlyTypedErrors] — structure-aware mutation **seeded from that corpus**
 *     (bit/byte flips, truncation, extension, splicing two seeds, varint prefixing) reaches far deeper than
 *     uniform-random bytes, because it starts from inputs already shaped like valid frames / QPACK streams.
 *  3. [structuredFrameAndQpack_onlyTypedErrors] — generators that emit a real frame envelope
 *     (type varint + a deliberately (mis)matched length varint + body) and a real QPACK shape
 *     (prefix + field-line representations), hammering the framing bounds guards and field-line decoders.
 *
 * The invariant is identical across all three: every entry point returns a value or throws a typed
 * [Http3StreamException]; any other throwable is a finding. All run on jvm/js/linuxX64/linuxArm64/apple.
 */
class Http3DecoderCorpusFuzzTests {
    private fun hex(s: String): ByteArray =
        ByteArray(s.length / 2) { ((s[it * 2].digitToInt(16) shl 4) or s[it * 2 + 1].digitToInt(16)).toByte() }

    private fun ByteArray.toHex(): String = joinToString("") { (it.toInt() and 0xFF).toString(16).padStart(2, '0') }

    // Mirrors socket-http3/fuzz/corpus/http3-codec/* (the JVM Jazzer http3CodecFuzz seed corpus). Keep in
    // sync when a new crasher is committed there — append it here so it regresses on every platform too.
    private val jazzerCorpus: List<Pair<String, ByteArray>> =
        listOf(
            "frame_goaway_trailing" to hex("07020000"),
            "frame_grease_type" to hex("2101aa"),
            "frame_length_over_intmax" to hex("00c000000080000000"),
            "frame_reserved_h2_type" to hex("0200"),
            "frame_settings_valid" to hex("04060100074064"),
            "frame_total_size_int_overflow" to hex("00c00000007ffffff8"),
            "qpack_dec_instr_truncated" to hex("3fffff"),
            "qpack_dyn_empty_table" to hex("000080"),
            "qpack_enc_instr_len_overflow" to hex("5fffffffffff"),
            "qpack_huffman_invalid" to hex("000050811e"),
            "qpack_postbase_empty" to hex("000010"),
            "qpack_valid_static" to hex("0000d1"),
            "qpack_value_literal_truncated" to hex("0000500a4142"),
        )

    private suspend fun assertTypedOnly(
        label: String,
        wire: ByteArray,
    ) {
        try {
            runDecoderEntryPoints(wire)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Http3StreamException) {
            // Expected for malformed input — runDecoderEntryPoints swallows these, but belt-and-suspenders.
        } catch (t: Throwable) {
            fail("$label leaked ${t::class.simpleName} (not Http3StreamException) for wire=${wire.toHex()}: ${t.message}")
        }
    }

    @Test
    fun replayJvmJazzerCorpus_onlyTypedErrors() =
        runTest {
            for ((name, wire) in jazzerCorpus) assertTypedOnly("corpus[$name]", wire)
        }

    @Test
    fun corpusSpliceMutation_onlyTypedErrors() =
        runTest {
            val rng = Random(0x77_3030_0001)
            val seeds = jazzerCorpus.map { it.second }
            repeat(6000) { i ->
                assertTypedOnly("splice#$i", mutate(rng, seeds))
            }
        }

    @Test
    fun structuredFrameAndQpack_onlyTypedErrors() =
        runTest {
            val rng = Random(0x77_3030_0002)
            repeat(6000) { i ->
                assertTypedOnly("structuredFrame#$i", structuredFrame(rng))
                assertTypedOnly("structuredQpack#$i", structuredQpack(rng))
            }
        }

    // ---- mutators / structured generators -----------------------------------------------------------

    /** Structure-aware mutation of a corpus seed: the input stays frame-/QPACK-shaped, so deep paths recur. */
    private fun mutate(
        rng: Random,
        seeds: List<ByteArray>,
    ): ByteArray {
        val base = seeds[rng.nextInt(seeds.size)].copyOf()
        return when (rng.nextInt(6)) {
            0 -> base.also { if (it.isNotEmpty()) repeat(1 + rng.nextInt(3)) { _ -> it[rng.nextInt(it.size)] = rng.nextInt(256).toByte() } }
            1 -> if (base.isEmpty()) base else base.copyOf(rng.nextInt(base.size + 1)) // truncate
            2 -> base + ByteArray(rng.nextInt(8)) { rng.nextInt(256).toByte() } // extend
            3 -> base + seeds[rng.nextInt(seeds.size)] // splice two seeds
            4 -> byteArrayOf(rng.nextInt(256).toByte()) + base // varint/type prefix
            else -> base.also { if (it.isNotEmpty()) it[0] = rng.nextInt(256).toByte() } // flip the first (type) byte
        }
    }

    private fun varint(
        rng: Random,
        value: Long,
    ): ByteArray {
        val buf = BufferFactory.Default.allocate(8)
        VarIntCodec.encode(buf, value and ((1L shl 62) - 1), EncodeContext.Empty)
        buf.resetForRead()
        return ByteArray(buf.remaining()) { buf.readByte() }
    }

    private val frameTypes = longArrayOf(0x00, 0x01, 0x03, 0x04, 0x05, 0x07, 0x0d, 0x02, 0x06, 0x21, 0x1f * 0x40 + 0x21)

    /** A frame envelope: type varint + a length varint that often LIES about the body, then a body. */
    private fun structuredFrame(rng: Random): ByteArray {
        val type = frameTypes[rng.nextInt(frameTypes.size)]
        val body = ByteArray(rng.nextInt(24)) { rng.nextInt(256).toByte() }
        val declared =
            when (rng.nextInt(4)) {
                0 -> body.size.toLong() // honest
                1 -> (body.size + 1 + rng.nextInt(8)).toLong() // claims more than present (truncation)
                2 -> rng.nextInt(body.size + 1).toLong() // claims less (trailing bytes)
                else -> 0x7fff_ffffL + rng.nextInt(8) // near/over Int.MAX
            }
        return varint(rng, type) + varint(rng, declared) + body
    }

    /** A QPACK field section: a 2-byte prefix then random field-line representation bytes. */
    private fun structuredQpack(rng: Random): ByteArray {
        val prefix = byteArrayOf(rng.nextInt(256).toByte(), rng.nextInt(256).toByte())
        val lines =
            ByteArray(rng.nextInt(20)) {
                // Bias the leading bits toward the four representation patterns (indexed / dynamic / literal-name-ref / literal).
                when (rng.nextInt(4)) {
                    0 -> (0x80 or rng.nextInt(0x40)).toByte() // indexed (static when 0x40 bit set)
                    1 -> (0x20 or rng.nextInt(0x20)).toByte() // literal with name ref
                    2 -> (0x10 or rng.nextInt(0x10)).toByte() // indexed post-base / literal post-base
                    else -> rng.nextInt(256).toByte()
                }
            }
        return prefix + lines
    }
}
