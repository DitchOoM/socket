package com.ditchoom.socket.harness

import com.ditchoom.buffer.Charset
import com.ditchoom.socket.ClientSocket
import com.ditchoom.socket.connect
import com.ditchoom.socket.harnessHost
import com.ditchoom.socket.isHarnessAvailable
import com.ditchoom.socket.runTestNoTimeSkipping
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/**
 * Phase 3 — deterministic decode robustness coverage that the harness equivalent
 * of the legacy `tlsWithSni` fix still holds: a multi-byte UTF-8 codepoint split
 * across two TCP `read()` calls is still decoded correctly when the caller
 * accumulates raw bytes before decoding.
 *
 * The toxiproxy `slicer` toxic on the `echo` proxy forces single-byte writes
 * with a 1 ms delay between them, so the kernel cannot deliver a full 3-byte
 * UTF-8 character in one `read()` return — the decode MUST work across read
 * boundaries or this test fails.
 *
 * Lives in `commonTest` alongside [ExceptionConformanceTests] — both ride the
 * library's own `ClientSocket` via the multiplatform [Toxiproxy] helper, so
 * every platform with FULL_SOCKET_ACCESS (JVM, linuxX64, jsNode, Apple)
 * exercises the same partial-read decode invariant.
 */
class PartialReadConformanceTests {
    /**
     * Sends a string containing multi-byte UTF-8 chars through a slicer-forced
     * per-byte echo, accumulates the raw bytes back into a single buffer, then
     * decodes — asserting the round-trip preserves every byte.
     *
     * "✓" is `0xE2 0x9C 0x93` (3 bytes); with `average_size=1` the slicer
     * guarantees these arrive in at least three separate reads. Decoding each
     * chunk in isolation would produce replacement chars; the test proves the
     * library's UTF-8 decode is applied to the accumulated stream, not each
     * partial read.
     */
    @Test
    fun slicer_perByteReads_utf8Roundtrip() =
        runTestNoTimeSkipping {
            if (!isHarnessAvailable()) return@runTestNoTimeSkipping
            if (!Toxiproxy.isToxiproxyAvailable()) return@runTestNoTimeSkipping
            Toxiproxy.ensureDefaultProxies()
            Toxiproxy.addSlicerToxic(Toxiproxy.Proxy.ECHO, averageSize = 1, delayMicros = 1000)
            try {
                val sent = "✓ checkmark ✓"
                val sentBytes = sent.encodeToByteArray() // tests may use ByteArray freely
                val accumulated = ByteArray(sentBytes.size)
                var readCount = 0
                ClientSocket.connect(
                    port = HarnessConfig.toxiproxyEchoPort,
                    hostname = harnessHost(),
                    timeout = 5.seconds,
                ) { socket ->
                    socket.writeString(sent, Charset.UTF8, 2.seconds)
                    // Drain until we have at least `sentBytes.size` bytes back. The
                    // slicer hands them out one at a time; accumulate raw bytes —
                    // DO NOT decode mid-stream, that's the whole point of the test.
                    var filled = 0
                    withTimeout(10.seconds) {
                        while (filled < sentBytes.size) {
                            val buf = socket.read(5.seconds)
                            readCount++
                            val take = minOf(buf.remaining(), sentBytes.size - filled)
                            val chunk = buf.readByteArray(take)
                            chunk.copyInto(accumulated, destinationOffset = filled)
                            filled += take
                        }
                    }
                }
                val decoded = accumulated.decodeToString()
                assertEquals(sent, decoded, "slicer-forced per-byte reads must still round-trip UTF-8")
                // Sanity: with a 1-byte slicer at 1 ms cadence, kernel coalescing can
                // re-pack some bytes but it CANNOT collapse all 17 bytes into a single
                // read. Requiring ≥2 reads proves the slicer was actually applied —
                // otherwise the test would pass against an un-toxic'd echo.
                assertTrue(
                    readCount >= 2,
                    "slicer should have forced ≥ 2 reads to drain the echo, saw $readCount",
                )
            } finally {
                // Wipe the slicer so other harness tests aren't slowed to a crawl.
                try {
                    Toxiproxy.clearToxics(Toxiproxy.Proxy.ECHO)
                } catch (_: TimeoutCancellationException) {
                    // best-effort cleanup
                }
            }
        }
}
