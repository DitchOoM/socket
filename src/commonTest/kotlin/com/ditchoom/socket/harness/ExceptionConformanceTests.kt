package com.ditchoom.socket.harness

import com.ditchoom.buffer.Charset
import com.ditchoom.socket.ClientSocket
import com.ditchoom.socket.SocketClosedException
import com.ditchoom.socket.connect
import com.ditchoom.socket.harnessHost
import com.ditchoom.socket.isHarnessAvailable
import com.ditchoom.socket.runTestNoTimeSkipping
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.yield
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * Phase 3 — deterministic regression for every platform's broken-pipe /
 * connection-reset wrapper. Drives the toxiproxy `echo` proxy on
 * `HarnessConfig.toxiproxyEchoPort` and forces three failure modes:
 *
 *  - **write after proxy down**  (`down` toxic) — write path: existing connection
 *    drops, write-after sees EPIPE/ECONNRESET → [SocketClosedException]
 *  - **write after peer reset**  (`reset_peer` toxic) — write path: write pattern
 *    surfaces EPIPE before ECONNRESET → [SocketClosedException]
 *  - **read parked during peer reset**  (`reset_peer` + concurrent writer) —
 *    read path: parked read sees connection-loss → [SocketClosedException]
 *
 * Together the three lock down both the read- and write-side wrappers
 * cross-platform. The parent-class assertion is intentional: the precise
 * sealed subtype (`BrokenPipe` vs `ConnectionReset` vs `EndOfStream`) is
 * kernel-scheduling- and toxiproxy-version-dependent and not deterministic
 * across runners (see individual test KDocs for the empirical findings).
 * The wrapper production code itself maps each errno correctly — what we
 * can't deterministically control via toxiproxy is *which* errno surfaces.
 *
 * Lives in `commonTest`: every platform with FULL_SOCKET_ACCESS (JVM, linuxX64,
 * jsNode, Apple) runs these same assertions against the same toxics. The
 * `Toxiproxy` helper rides the library's own multiplatform `ClientSocket`, so
 * no platform-specific HTTP client is needed.
 *
 * Replaces the inspection-only `ExceptionIntegrationTests.writeAfterPeerClose_…` /
 * `JvmExceptionSubtypeTests.brokenPipeOrReset_isSocketClosedSubtype` — those used
 * a local server `close()` with no control over what the kernel surfaces. Toxiproxy
 * gives the deterministic control we need at the boundary (drop/reset/slice) even
 * if the resulting subtype is kernel-controlled.
 *
 * All three tests skip silently when the harness or toxiproxy aren't reachable.
 */
class ExceptionConformanceTests {
    /**
     * Disable the proxy mid-flight; the existing TCP connection drops and the
     * next write surfaces as a [SocketClosedException].
     *
     * Asserts the parent class only — `down` (proxy disable) can land as either
     * [SocketClosedException.BrokenPipe] (write into a half-closed FD) or
     * [SocketClosedException.ConnectionReset] (kernel sees RST first) depending
     * on kernel scheduling, and we don't want to chase that race. The companion
     * `writeAfterPeerReset_*` test tightens the subtype assertion for the
     * deterministic `reset_peer` toxic.
     *
     * The single write often races the proxy-disable, so we loop a few writes
     * until the kernel notices the pipe is gone — same pattern the original
     * `writeAfterPeerClose_*` tests used, kept here so the test is robust on
     * busy CI runners.
     */
    @Test
    fun writeAfterProxyDown_producesSocketClosedException() =
        runTestNoTimeSkipping {
            if (!isHarnessAvailable()) return@runTestNoTimeSkipping
            if (!Toxiproxy.isToxiproxyAvailable()) return@runTestNoTimeSkipping
            Toxiproxy.ensureDefaultProxies()
            try {
                val socket =
                    ClientSocket.connect(
                        port = HarnessConfig.toxiproxyEchoPort,
                        hostname = harnessHost(),
                        timeout = 5.seconds,
                    )
                try {
                    // Prime the connection so we know traffic flows before the toxic.
                    socket.writeString("hello", Charset.UTF8, 2.seconds)
                    val echo = socket.read(2.seconds)
                    echo.readString(echo.remaining(), Charset.UTF8)

                    Toxiproxy.addDownToxic(Toxiproxy.Proxy.ECHO)

                    assertFailsWith<SocketClosedException> {
                        // The first write may succeed into the local kernel buffer;
                        // subsequent writes hit the dropped pipe. 20 × 50ms = ~1s,
                        // which is plenty for the JVM to surface EPIPE.
                        repeat(20) {
                            socket.writeString("post-down", Charset.UTF8, 2.seconds)
                            delay(50.milliseconds)
                        }
                    }
                } finally {
                    socket.close()
                }
            } finally {
                // ensureDefaultProxies() re-enables and clears in upsertProxy; calling
                // it from the *next* test's setup is sufficient. We still clear here
                // so concurrent tests in the same class don't see a downed proxy.
                Toxiproxy.ensureDefaultProxies()
            }
        }

    /**
     * Apply a `reset_peer` toxic with `timeout=0` so toxiproxy sends RST on
     * the next packet through the proxy. The write loop must surface a
     * [SocketClosedException] (parent class only).
     *
     * NOTE on subtype-tightening: an earlier spec assumed `reset_peer` would
     * deterministically yield [SocketClosedException.ConnectionReset] on every
     * platform. Empirically (3-of-3 deterministic JVM runs) toxiproxy 2.12's
     * `reset_peer` toxic produces `Broken pipe` (EPIPE) on the *write-after*
     * pattern, not `Connection reset` (ECONNRESET). The proxy closes the
     * upstream socket; subsequent local writes go into the kernel send buffer,
     * and only the *next* write hits the half-closed pipe → EPIPE. ECONNRESET
     * would only surface for a *read* pending when the RST arrives.
     *
     * The platform mappings themselves ARE correct (JVM `wrapJvmException`,
     * linuxX64 `IoUringUtils.kt`, jsNode `NodeSocketExtensions`, Apple error
     * helpers all map ECONNRESET → ConnectionReset and EPIPE → BrokenPipe).
     * The non-determinism is at the toxic / kernel layer, not in our mapping.
     * Keeping the parent-class assertion so the test stays portable; a
     * tighter `ConnectionReset`-specific test would need a different shape
     * (e.g. a parked `read()` while the proxy is reset).
     */
    @Test
    fun writeAfterPeerReset_producesSocketClosedException() =
        runTestNoTimeSkipping {
            if (!isHarnessAvailable()) return@runTestNoTimeSkipping
            if (!Toxiproxy.isToxiproxyAvailable()) return@runTestNoTimeSkipping
            Toxiproxy.ensureDefaultProxies()
            try {
                val socket =
                    ClientSocket.connect(
                        port = HarnessConfig.toxiproxyEchoPort,
                        hostname = harnessHost(),
                        timeout = 5.seconds,
                    )
                try {
                    socket.writeString("hello", Charset.UTF8, 2.seconds)
                    val echo = socket.read(2.seconds)
                    echo.readString(echo.remaining(), Charset.UTF8)

                    Toxiproxy.addResetPeerToxic(Toxiproxy.Proxy.ECHO, timeoutMs = 0)

                    // Parent-class assertion: see KDoc — `reset_peer` lands as
                    // EPIPE→BrokenPipe on the write-after pattern, not ECONNRESET.
                    assertFailsWith<SocketClosedException> {
                        repeat(20) {
                            socket.writeString("post-reset", Charset.UTF8, 2.seconds)
                            delay(50.milliseconds)
                        }
                    }
                } finally {
                    socket.close()
                }
            } finally {
                // upsertProxy() wipes toxics + re-enables on the next ensureDefaultProxies.
                Toxiproxy.ensureDefaultProxies()
            }
        }

    /**
     * Park a `read()` in a background coroutine, write one byte to the
     * deterministic peer-close sidecar, and confirm the parked read
     * surfaces a [SocketClosedException] — the **read path** counterpart
     * to the two preceding write-path tests.
     *
     * Why a dedicated sidecar instead of toxiproxy `reset_peer`:
     *
     * - toxiproxy 2.12's `reset_peer` SO_LINGER=0+close()s on the *proxy*
     *   side, but the toxic activates on *data flow*, not connection
     *   liveness. On busy GH `ubuntu-24.04` runners the parked read often
     *   surfaced a [kotlinx.coroutines.TimeoutCancellationException]
     *   before the toxic fired — flaked badly enough that commit `421a676`
     *   `@Ignore`'d this case.
     * - The `rst` service in docker-compose.yml is a Python listener that
     *   calls `setsockopt(SO_LINGER, on=1, linger=0)` then `close()` on
     *   the accepted FD after reading exactly one byte. The single-byte
     *   trigger is what's deterministic: the test controls *when* the
     *   server closes by *when* it writes the byte.
     *
     * Networking: the rst service runs with `network_mode: host`, so the
     * Python listener binds directly in the host's network namespace —
     * no docker bridge, no docker-proxy, no NAT. Loopback to loopback.
     * The RST that SO_LINGER+close puts on the wire reaches the client
     * unmodified, and the read-path wrapper surfaces
     * [SocketClosedException.ConnectionReset] (subclass of
     * `SocketClosedException`). Two earlier topologies (bridge-IP pin,
     * 127.0.0.1 port publish) both failed on GH ubuntu-24.04 runners
     * while passing locally — see `test-harness/docker-compose.yml`
     * `rst:` for the postmortem.
     *
     * The assertion stays at the parent class: jsNode's stream-shape and
     * Apple's `NWConnection` sometimes surface a generic close path even
     * when the wire saw RST, and we don't want to chase that subtype
     * variation across platforms. What matters here is that *all*
     * platforms' read-path wrappers translate the connection loss into a
     * `SocketClosedException` — that's the contract.
     *
     * The single-byte trigger lets the test order things precisely:
     *   1. connect to the sidecar
     *   2. park `read()` (CoroutineStart.UNDISPATCHED → up to first suspend)
     *   3. `delay(100ms)` to give the read time to actually block on the
     *      kernel syscall
     *   4. write one byte → sidecar exits → close arrives → parked read fails.
     */
    @Test
    fun pendingReadDuringPeerReset_producesSocketClosedException() =
        runTestNoTimeSkipping {
            if (!isHarnessAvailable()) return@runTestNoTimeSkipping
            val socket =
                ClientSocket.connect(
                    port = HarnessConfig.rstPort,
                    hostname = harnessHost(),
                    timeout = 5.seconds,
                )
            try {
                coroutineScope {
                    // Park the read in a child coroutine. CoroutineStart.UNDISPATCHED
                    // guarantees the body runs up to its first non-trivial suspend
                    // before async() returns. In our case that's NIO2's
                    // `AsynchronousSocketChannel.read()` (JVM) / io_uring submit
                    // (linuxX64) / `socket.on('data')` registration (jsNode) —
                    // i.e. the kernel-side read is *already* registered.
                    val readResult =
                        async<Throwable?>(start = CoroutineStart.UNDISPATCHED) {
                            try {
                                socket.read(5.seconds)
                                null
                            } catch (t: Throwable) {
                                t
                            }
                        }
                    // Trigger the close in a retry loop instead of a fixed delay.
                    // The previous shape `delay(100ms); writeString("x")` worked
                    // on idle dev boxes but was a tax on busy CI runners where
                    // 100ms doesn't always cover the syscall round-trip
                    // (kernel ack → server recv → server close → RST → client).
                    // Reactive form: keep writing 1-byte triggers until the read
                    // observes the close (success path), or until our own write
                    // hits EPIPE (already-closed path) — both terminate the loop.
                    // `yield()` returns to the dispatcher between iterations so
                    // the read coroutine gets to consume the RST when it arrives.
                    while (!readResult.isCompleted) {
                        try {
                            socket.writeString("x", Charset.UTF8, 1.seconds)
                        } catch (_: SocketClosedException) {
                            // RST already in our send buffer's face. The parked
                            // read will see the same close; just wait for it.
                            break
                        }
                        yield()
                    }
                    val thrown = readResult.await()
                    assertNotNull(thrown, "expected the parked read to throw")
                    assertTrue(
                        thrown is SocketClosedException,
                        "expected SocketClosedException, got " +
                            "${thrown::class.simpleName}(${thrown.message})",
                    )
                }
            } finally {
                socket.close()
            }
        }
}
