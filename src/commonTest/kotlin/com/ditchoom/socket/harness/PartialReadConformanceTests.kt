package com.ditchoom.socket.harness

import com.ditchoom.buffer.Charset
import com.ditchoom.data.readBuffer
import com.ditchoom.data.writeString
import com.ditchoom.socket.ClientSocket
import com.ditchoom.socket.TransportConfig
import com.ditchoom.socket.connect
import com.ditchoom.socket.harnessHost
import com.ditchoom.socket.isHarnessAvailable
import com.ditchoom.socket.runTestNoTimeSkipping
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.seconds

/**
 * Phase 3 — deterministic decode robustness coverage that the harness equivalent
 * of the legacy `tlsWithSni` fix still holds: a multi-byte UTF-8 codepoint split
 * across two TCP `read()` calls is still decoded correctly when the caller
 * accumulates raw bytes before decoding.
 *
 * The toxiproxy `slicer` toxic on the `echo` proxy splits the response into
 * single-byte writes with a 1 ms delay between them, so a 3-byte UTF-8 character
 * is very likely to be delivered across multiple `read()` returns. How many
 * reads it actually takes is not asserted — kernel/Node socket buffering may
 * coalesce bytes — but the round-trip invariant must hold regardless of where
 * the boundaries fall.
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
     * "✓" is `0xE2 0x9C 0x93` (3 bytes); with `average_size=1` the slicer splits
     * the stream so these bytes are likely to span reads. Decoding each chunk in
     * isolation would produce replacement chars; the test proves decoding the
     * accumulated stream round-trips regardless of where the read boundaries land.
     */
    @Test
    fun slicer_perByteReads_utf8Roundtrip() =
        runTestNoTimeSkipping {
            if (!isHarnessAvailable()) return@runTestNoTimeSkipping
            if (!Toxiproxy.isToxiproxyAvailable()) return@runTestNoTimeSkipping
            Toxiproxy.ensureDefaultProxies()
            // A non-2xx response makes addSlicerToxic throw (see Toxiproxy.httpRequest),
            // so reaching the next line *guarantees* the slicer is installed on the echo
            // proxy — that is our deterministic anti-vacuous guard. We do NOT assert on
            // how many reads the fragmentation produces: whether 17 single-byte writes
            // arrive as 17 reads or get coalesced into 1 depends on kernel/Node socket
            // buffering and event-loop scheduling, which is exactly the kind of timing
            // the suite refuses to assert on. The invariant under test — raw bytes
            // round-trip faithfully across however many reads — holds either way.
            Toxiproxy.addSlicerToxic(Toxiproxy.Proxy.ECHO, averageSize = 1, delayMicros = 1000)
            try {
                val sent = "✓ checkmark ✓"
                val sentBytes = sent.encodeToByteArray() // tests may use ByteArray freely
                val accumulated = ByteArray(sentBytes.size)
                ClientSocket.connect(
                    port = HarnessConfig.toxiproxyEchoPort,
                    hostname = harnessHost(),
                    config = TransportConfig(connectTimeout = 5.seconds),
                ) { socket ->
                    socket.writeString(sent, Charset.UTF8, 2.seconds)
                    // Drain until we have at least `sentBytes.size` bytes back. The
                    // slicer hands them out one at a time; accumulate raw bytes —
                    // DO NOT decode mid-stream, that's the whole point of the test.
                    var filled = 0
                    withTimeout(10.seconds) {
                        while (filled < sentBytes.size) {
                            val buf = socket.readBuffer(5.seconds)
                            val take = minOf(buf.remaining(), sentBytes.size - filled)
                            val chunk = buf.readByteArray(take)
                            chunk.copyInto(accumulated, destinationOffset = filled)
                            filled += take
                        }
                    }
                }
                val decoded = accumulated.decodeToString()
                assertEquals(sent, decoded, "slicer-forced per-byte reads must still round-trip UTF-8")
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
