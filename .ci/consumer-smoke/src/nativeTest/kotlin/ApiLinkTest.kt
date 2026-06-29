package consumer.smoke

import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.test.Test
import kotlin.time.Duration.Companion.seconds

/**
 * Kotlin/Native LINK gate (the Gap-A catcher). Building this test binary forces the linker to resolve
 * the full QUIC/H3 native stack reachable from [Smoke.connectAndGet] — including socket-quic-quiche's
 * cinterop `quiche_*` symbols. If the published artifacts don't carry the static archive those symbols
 * live in, this fails at LINK with undefined symbols (exactly the failure `resolveAll` cannot see).
 *
 * It is a LINK check, not a behavioural one: the connection is given a short timeout and any failure is
 * swallowed — reaching the call site (so it links) is the whole point; a loopback/public exchange is the
 * JVM smoke's job.
 */
class ApiLinkTest {
    @Test
    fun nativeQuicStackLinks() =
        runTest(timeout = 30.seconds) {
            // Localhost:1 — nothing listens; we only need the symbol reachable so the binary links.
            withTimeoutOrNull(2.seconds) {
                runCatching { Smoke.connectAndGet("127.0.0.1", 1) }
            }
        }
}
