package com.ditchoom.socket.harness

import com.ditchoom.buffer.Charset
import com.ditchoom.socket.ClientSocket
import com.ditchoom.socket.SocketClosedException
import com.ditchoom.socket.connect
import com.ditchoom.socket.harnessHost
import com.ditchoom.socket.isHarnessAvailable
import com.ditchoom.socket.runTestNoTimeSkipping
import kotlinx.coroutines.delay
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * Phase 3 — deterministic regression for the JVM broken-pipe / connection-reset
 * wrapper in `wrapJvmException` (commit `1561478`). Drives the toxiproxy `echo`
 * proxy on `HarnessConfig.toxiproxyEchoPort` and forces the two failure modes
 * the wrapper distinguishes:
 *
 *  - `down`        → proxy disabled; existing connection drops, write-after sees
 *                    `IOException("Broken pipe")` → [SocketClosedException.BrokenPipe]
 *  - `reset_peer`  → proxy sends RST on next packet; write-after sees
 *                    `IOException("Connection reset")` → [SocketClosedException.ConnectionReset]
 *
 * Replaces the inspection-only `ExceptionIntegrationTests.writeAfterPeerClose_…` /
 * `JvmExceptionSubtypeTests.brokenPipeOrReset_isSocketClosedSubtype` — those used
 * a local server `close()` which gives no control over whether the JVM sees EPIPE
 * or ECONNRESET. Toxiproxy gives the deterministic control we need.
 *
 * Lives in `commonJvmTest` (not `commonTest`): the assertions are JVM-specific to
 * the `wrapJvmException` mapping; linuxX64 has its own POSIX errno path
 * (`EPIPE` → `BrokenPipe`) exercised by `LinuxClientSocket` tests. We re-shape
 * this into `expect/actual` when toxiproxy interop becomes available on K/Native.
 *
 * Both tests skip silently when the harness or toxiproxy aren't reachable.
 */
class ExceptionConformanceTests {
    /**
     * Disable the proxy mid-flight; the existing TCP connection drops and the
     * next write surfaces as `IOException("Broken pipe")` from the JVM NIO2
     * channel, which `wrapJvmException` maps to [SocketClosedException.BrokenPipe].
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
     * Apply a `reset_peer` toxic with `timeout=0` so the next packet draws an
     * immediate RST from toxiproxy. The JVM NIO2 channel surfaces this as
     * `IOException("Connection reset by peer")`, which `wrapJvmException`
     * maps to [SocketClosedException.ConnectionReset].
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
}
