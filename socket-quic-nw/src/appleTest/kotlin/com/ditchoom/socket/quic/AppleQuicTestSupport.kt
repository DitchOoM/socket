@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.ditchoom.socket.quic

import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.convert
import kotlinx.cinterop.get
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.set
import kotlinx.cinterop.toKString
import platform.posix.F_OK
import platform.posix.access
import platform.posix.fclose
import platform.posix.fopen
import platform.posix.fread

// Shared test fixtures for the Apple (Network.framework) runs of the cross-platform QUIC suites
// (issue #112). The Apple server's TLS identity comes from a PKCS#12 bundle (see
// QuicTlsConfig.pkcs12Path) generated from the committed PEM by the build's `generateTestP12` task,
// rather than the PEM paths the JVM/Linux servers use.

/** Resolve a file under `testcerts/`, relative to either the module dir or the repo root. */
internal fun appleTestCertPath(name: String): String =
    listOf("testcerts/$name", "socket-quic/testcerts/$name")
        .firstOrNull { access(it, F_OK) == 0 }
        ?: error("Test cert not found: $name (cwd-relative testcerts/ — did generateTestP12 run?)")

/** The TLS config every Apple QUIC server test uses: PEM (for parity) + the PKCS#12 identity. */
internal fun appleQuicTestTlsConfig() =
    QuicTlsConfig(
        certChainPath = appleTestCertPath("cert.crt"),
        privKeyPath = appleTestCertPath("cert.key"),
        pkcs12Path = appleTestCertPath("cert.p12"),
        pkcs12Password = APPLE_TEST_P12_PASSWORD,
    )

/**
 * A TLS identity from a named W3C `serverCertificateHashes` constraint fixture (`pinned`,
 * `pinned-expired`, `pinned-toolong`, `pinned-rsa`) — the build's `generateTestP12` packages each into
 * a `.p12` the Network.framework listener presents. The compliant `pinned` drives the accept test; the
 * violators drive the per-constraint reject tests.
 */
internal fun appleQuicPinnedTlsConfig(name: String = "pinned") =
    QuicTlsConfig(
        certChainPath = appleTestCertPath("$name.crt"),
        privKeyPath = appleTestCertPath("$name.key"),
        pkcs12Path = appleTestCertPath("$name.p12"),
        pkcs12Password = APPLE_TEST_P12_PASSWORD,
    )

/**
 * The self-signed `localhost` identity (SAN DNS:localhost,IP:127.0.0.1) the CA-pinning tests need
 * — distinct from [appleQuicTestTlsConfig] so the chain both anchors to a pinnable cert AND matches
 * the "localhost" connect hostname. Backed by `localhost.p12`, which `generateTestP12` produces
 * alongside `cert.p12`.
 */
internal fun appleQuicLocalhostTlsConfig() =
    QuicTlsConfig(
        certChainPath = appleTestCertPath("localhost.crt"),
        privKeyPath = appleTestCertPath("localhost.key"),
        pkcs12Path = appleTestCertPath("localhost.p12"),
        pkcs12Password = APPLE_TEST_P12_PASSWORD,
    )

/** Read a (text) cert file into a String via posix — test-only, no buffer-lib dependency needed. */
internal fun appleReadFileText(path: String): String =
    memScoped {
        val fp = fopen(path, "r") ?: error("Cannot open $path")
        try {
            val sb = StringBuilder()
            val bufSize = 4096
            val buf = allocArray<ByteVar>(bufSize)
            while (true) {
                val n = fread(buf, 1.convert(), (bufSize - 1).convert(), fp).toInt()
                if (n <= 0) break
                buf[n] = 0
                sb.append(buf.toKString())
            }
            sb.toString()
        } finally {
            fclose(fp)
        }
    }

/** Must match the build's `generateTestP12` task passphrase. */
internal const val APPLE_TEST_P12_PASSWORD = "testpass"
