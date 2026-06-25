package com.ditchoom.socket.http3

import kotlinx.coroutines.test.runTest
import kotlin.test.Test

/**
 * Every-platform replay of the committed round-trip fuzz corpus (`socket-http3/fuzz/corpus/http3-roundtrip`)
 * and the home for any Jazzer crash repro. The JVM Jazzer target `Http3RoundTripFuzzer` and this test both
 * route a raw `byte[]` through [Http3FuzzGenerators.replay], so a `crash-*` artifact found on the JVM is
 * pinned as a regression that reproduces byte-identically on jvm/js/native/apple — the round-trip analogue
 * of how [Http3ConformanceCorpusTests] pins decoder crashers.
 *
 * [SEED_CORPUS] mirrors the committed seed files (their leading bytes select the surface + frame variant /
 * QPACK table regime). When the fuzzer discovers a crasher, append its hex here with a descriptive name.
 */
class Http3RoundTripCorpusReplayTests {
    private fun hex(s: String): ByteArray =
        ByteArray(s.length / 2) { ((s[it * 2].digitToInt(16) shl 4) or s[it * 2 + 1].digitToInt(16)).toByte() }

    // Shared tail of entropy bytes the seed files carry after their surface/variant selector prefix.
    private val tail = "112233445566778899aabbccddeeff0110203040050607084142434461626364"

    private val seedCorpus: List<kotlin.Pair<String, ByteArray>> =
        listOf(
            "frame_data" to hex("01" + "00000000" + tail),
            "frame_settings" to hex("01" + "00000002" + tail),
            "frame_goaway" to hex("01" + "00000003" + tail),
            "frame_pushpromise" to hex("01" + "00000006" + tail),
            "frame_unknown_grease" to hex("01" + "00000007" + tail),
            "qpack_static_only" to hex("00" + "00000000" + tail),
            "qpack_small_table" to hex("00" + "00000001" + tail),
            "qpack_dynamic_table" to hex("00" + "00000002" + tail),
        )

    @Test
    fun replayCorpus_everyEntryRoundTripsOnEveryPlatform() =
        runTest {
            for ((name, bytes) in seedCorpus) {
                try {
                    Http3FuzzGenerators.replay(bytes)
                } catch (t: Throwable) {
                    throw AssertionError("round-trip corpus entry '$name' failed to replay cleanly: ${t.message}", t)
                }
            }
        }
}
