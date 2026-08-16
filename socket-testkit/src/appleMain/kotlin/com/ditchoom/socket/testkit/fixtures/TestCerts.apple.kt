@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.ditchoom.socket.testkit.fixtures

import com.ditchoom.socket.testkit.skip.SkipReason
import kotlinx.cinterop.toKString
import platform.posix.F_OK
import platform.posix.access
import platform.posix.getenv

/**
 * Absolute path to a module's committed `testcerts/` directory, exported by the Gradle build.
 *
 * ## Why an environment variable and not a relative path
 *
 * KGP launches every simulator test binary with `simctl spawn --standalone`, and that spawn does not
 * inherit the invoking process's working directory — it starts in the *device's data container*.
 * Measured on an iPhone 17 Pro simulator, `--standalone /bin/pwd` prints:
 *
 * ```
 * /Users/<user>/Library/Developer/CoreSimulator/Devices/<UDID>/data
 * ```
 *
 * There is no `testcerts/` under there and never will be, so the cwd-relative probe every native
 * suite uses resolves nothing and the loopback server has no cert+key to bind with. That is the
 * whole of issue #359: 93 test invocations across three simulator lanes, each of which reported as
 * a PASS before #357 made skips visible.
 *
 * The fix works because an iOS simulator is **not** a virtual machine. It runs on the host kernel
 * and sees the host filesystem, so an absolute host path is directly readable from inside the
 * sandbox — also measured, before writing any of this:
 *
 * ```
 * $ xcrun simctl spawn --standalone <UDID> /bin/cat .../socket-http3/testcerts/cert.crt
 * -----BEGIN CERTIFICATE-----
 * ```
 *
 * So the build hands the sandbox an absolute path rather than trying to relocate the fixtures.
 *
 * ⚠️ The variable reaches the binary as `SIMCTL_CHILD_SOCKET_TESTCERTS_DIR`, not under this name —
 * `simctl spawn` passes only `SIMCTL_CHILD_`-prefixed variables to its child, stripping the prefix
 * on the way in. The root `build.gradle.kts` does that prefixing; by the time `getenv` runs here,
 * the name is the bare one below. Setting the unprefixed variable in a CI step does nothing at all,
 * which is exactly how the `QUIC_SIM_BOOTED` wiring managed to be documented for years while never
 * existing.
 */
const val TESTCERTS_DIR_ENV: String = "SOCKET_TESTCERTS_DIR"

/**
 * Where a suite's server cert+key came from, or what it looked for and did not find.
 *
 * Sealed rather than a nullable path so a caller cannot accidentally treat "not found" as a usable
 * value, and so the failure carries the candidate list — a skip that says only "fixtures missing"
 * cannot be told apart from a build that stopped exporting the path.
 */
sealed interface TestCerts {
    /** Both files resolved. Paths are whatever probe succeeded — absolute or cwd-relative. */
    data class Available(
        val certChainPath: String,
        val privKeyPath: String,
    ) : TestCerts

    /** Neither the exported directory nor any relative fallback held the pair. */
    data class Unavailable(
        val tried: List<String>,
    ) : TestCerts {
        /**
         * The typed skip for this failure.
         *
         * Note what decides whether reporting it is *tolerable*: nothing here. Lanes that provision
         * their own filesystem (the macOS K/N shards) set `SOCKET_REQUIRE_ALL_TESTS=1`, so this skip
         * goes red there — a macOS checkout missing its committed `testcerts/` is a broken lane, not
         * an accommodation. The simulator lanes leave it unset and the skip is recorded. One
         * mechanism, two outcomes, no second predicate to keep in sync.
         */
        fun asSkipReason(): SkipReason.SimulatorLacksFixtures =
            SkipReason.SimulatorLacksFixtures(
                "no cert+key pair found; tried $tried. If this is a simulator lane, the Gradle build " +
                    "should be exporting SIMCTL_CHILD_$TESTCERTS_DIR_ENV (see the root build.gradle.kts); " +
                    "if it is macOS, the committed testcerts/ are missing from the checkout",
            )
    }
}

/**
 * Locate a suite's `cert.crt` / `cert.key`, preferring the path the build exported.
 *
 * [moduleDir] is the repository-relative module directory (e.g. `socket-http3`), used for the
 * historical fallback that lets the suite run when Gradle is invoked from the repo root rather than
 * the module. Both fallbacks are kept: macOS K/N resolves through them today and this must not
 * change how that lane behaves.
 *
 * Probes for **both** files rather than one. Resolving `cert.crt` from the exported directory and
 * `cert.key` from a stale relative one would hand quiche a mismatched pair, which fails deep inside
 * the TLS handshake with an error that says nothing about fixtures.
 */
fun locateTestCerts(moduleDir: String): TestCerts {
    val roots =
        buildList {
            getenv(TESTCERTS_DIR_ENV)?.toKString()?.takeIf { it.isNotEmpty() }?.let { add(it) }
            add("testcerts")
            add("$moduleDir/testcerts")
        }
    for (root in roots) {
        val cert = "$root/cert.crt"
        val key = "$root/cert.key"
        if (access(cert, F_OK) == 0 && access(key, F_OK) == 0) {
            return TestCerts.Available(certChainPath = cert, privKeyPath = key)
        }
    }
    return TestCerts.Unavailable(roots.flatMap { listOf("$it/cert.crt", "$it/cert.key") })
}
