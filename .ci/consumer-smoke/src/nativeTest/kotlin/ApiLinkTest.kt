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

    /**
     * The same link gate for network awareness, which since issue #269 comes from the separate
     * `com.ditchoom:network-monitor` artifact re-exported over socket's `api` edge (this project never
     * names it — see [Smoke.networkAwareness]).
     *
     * Reaching it here forces the linker to resolve that module's own cinterop symbols out of the
     * PUBLISHED klib: on Apple the `nm_path_monitor_*` / `nm_enumerate_interfaces` helpers behind
     * `NWPathMonitor` and `getifaddrs`, on Linux the netlink bind shim. If a helper were left out of the
     * published klib, `ld64` fails here with an undefined symbol — the failure resolution cannot see.
     *
     * `runCatching` because this only needs to LINK: the Apple/Linux gates link without running, and a
     * sandboxed environment that denies netlink is not this test's concern.
     */
    @Test
    fun nativeNetworkMonitorLinks() {
        runCatching { Smoke.networkAwareness() }
    }
}
