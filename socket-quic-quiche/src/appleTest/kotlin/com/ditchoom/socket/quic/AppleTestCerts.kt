@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.ditchoom.socket.quic

import kotlinx.cinterop.toKString
import platform.posix.F_OK
import platform.posix.access
import platform.posix.fclose
import platform.posix.fopen
import platform.posix.fputs
import platform.posix.getenv

/**
 * Shared cert resolution for this module's Apple K/Native suites — the Apple counterpart of
 * `AndroidTestCerts`, extracted from [AppleQuicheEngineLoopbackTest] when [AppleQuicDatagramTests]
 * became the second consumer (issue #289).
 *
 * quiche loads its TLS chain + key from a **filesystem path**, so the resolution has two legs:
 *
 *  - **macOS K/N** runs with the repo/module dir as cwd, so the committed `testcerts/` files resolve
 *    directly and are used as-is.
 *  - **iOS/tvOS/watchOS simulators** run under `simctl spawn --standalone`, whose cwd has no
 *    `testcerts/`. Rather than skip there, the embedded PEM is materialized into the process's
 *    writable `TMPDIR`. That is what lets the full quiche handshake run **for real** on the simulator
 *    instead of self-skipping: the POSIX UDP datapath needs no Network.framework listener, so the only
 *    thing that ever blocked the sim was the missing cwd.
 */
internal object AppleTestCerts {
    /** Absolute-or-relative path to test cert [name], materializing it into TMPDIR when cwd has none. */
    fun path(name: String): String {
        listOf("testcerts/$name", "socket-quic-quiche/testcerts/$name")
            .firstOrNull { access(it, F_OK) == 0 }
            ?.let { return it }
        val pem = if (name.endsWith(".key")) TEST_KEY_PEM else TEST_CERT_PEM
        val dir = getenv("TMPDIR")?.toKString()?.trimEnd('/') ?: "/tmp"
        val path = "$dir/aqe-$name"
        val file = fopen(path, "w") ?: error("Cannot write temp cert to $path")
        try {
            fputs(pem, file)
        } finally {
            fclose(file)
        }
        return path
    }

    /** The committed `cert.crt` + `cert.key` identity every Apple quiche suite here uses. */
    val tlsConfig: QuicTlsConfig
        get() = QuicTlsConfig(certChainPath = path("cert.crt"), privKeyPath = path("cert.key"))

    // The committed socket-quic-quiche/testcerts/cert.{crt,key} (public test fixtures), embedded so
    // the suite is self-contained on an iOS simulator whose cwd lacks the repo testcerts/ dir.
    private const val TEST_CERT_PEM =
        "-----BEGIN CERTIFICATE-----\n" +
            "MIIC7TCCAdUCFDuGBhl3l5Z++VCLkvaav4yteBonMA0GCSqGSIb3DQEBCwUAMEUx\n" +
            "CzAJBgNVBAYTAkFVMRMwEQYDVQQIDApTb21lLVN0YXRlMSEwHwYDVQQKDBhJbnRl\n" +
            "cm5ldCBXaWRnaXRzIFB0eSBMdGQwHhcNMjAwMzIzMTYwNzU0WhcNNDcwODA5MTYw\n" +
            "NzU0WjAhMQswCQYDVQQGEwJHQjESMBAGA1UEAwwJcXVpYy50ZWNoMIIBIjANBgkq\n" +
            "hkiG9w0BAQEFAAOCAQ8AMIIBCgKCAQEAz5bOL7LD9kiIagcVrZqZ13ZcR0KhMuzs\n" +
            "brqULbZKyqC+uBRgINxYJ7LPnJ4LPYuCt/nAaQ7CLXfKgzAMFu8eIK6UEvZA6+7b\n" +
            "20E4rvOpPbTB/T4JbYZNQKyM9AEwr6j0P6vFgrWT7aBzhkmiqEe5vv/7ZOEGb+Ab\n" +
            "+cvMeszfBbk93nyzKdNaUuh95x7/p0Ow315np2PRuoT0QQnA9zE/9eZ3Jah3cNZn\n" +
            "NuQ6BDHlkegzTV5JhYYblRo/pmt2E9E0ha+NWsRLf3ZJUYhkYR3UqMltEKuLglCO\n" +
            "VWBbPmKd4IZUNIotpKMVQSVb9agNBF49hH9iBhN3fBm7Hp8KBpjJLwIDAQABMA0G\n" +
            "CSqGSIb3DQEBCwUAA4IBAQCo/Rn4spa5XFk0cCoKypP27DxePkGD9rQZk/CY4inV\n" +
            "JV16anZ1pr9yfO61+m3fRKTZq7yxtHRDWxDdROHx9LqV1dXLAmh1ecV9Kn6/796O\n" +
            "EHsOcVB0Lfi9Ili7//oUqlhGNploRuQbgWAXU+Eo1xJRWIXeedhzBSgEOMaQk3Zn\n" +
            "TdYFhP0/Ao/fEdI4VULv1A43ztnZIB2KXWgUQoFT32woL47eWge8LxxVmmH3STtz\n" +
            "nNcGnYxIorCQemDHDzMrvxRWgHxkpFGGqAhkFFyCmhKFPglKwt+yVTx26T8tShID\n" +
            "ISMj0rgVMptmtWKJfzNCvFG52gsuO4w3yGdjgjRRrBDm\n" +
            "-----END CERTIFICATE-----\n"

    private const val TEST_KEY_PEM =
        "-----BEGIN PRIVATE KEY-----\n" +
            "MIIEvgIBADANBgkqhkiG9w0BAQEFAASCBKgwggSkAgEAAoIBAQDPls4vssP2SIhq\n" +
            "BxWtmpnXdlxHQqEy7OxuupQttkrKoL64FGAg3Fgnss+cngs9i4K3+cBpDsItd8qD\n" +
            "MAwW7x4grpQS9kDr7tvbQTiu86k9tMH9Pglthk1ArIz0ATCvqPQ/q8WCtZPtoHOG\n" +
            "SaKoR7m+//tk4QZv4Bv5y8x6zN8FuT3efLMp01pS6H3nHv+nQ7DfXmenY9G6hPRB\n" +
            "CcD3MT/15nclqHdw1mc25DoEMeWR6DNNXkmFhhuVGj+ma3YT0TSFr41axEt/dklR\n" +
            "iGRhHdSoyW0Qq4uCUI5VYFs+Yp3ghlQ0ii2koxVBJVv1qA0EXj2Ef2IGE3d8Gbse\n" +
            "nwoGmMkvAgMBAAECggEBAMtFkpUmablKgTnBwjqCvs47OlUVK6AgW8x5qwuwC0Cr\n" +
            "ctXyLcc/vJry/1UPdVZIvDHGv+Cf8Qhw2r7nV49FiqzaBmki9aOR+3uRPB4kvr6L\n" +
            "t8Fw8+5pqlAAJu3wFGqN+M44N2mswDPaAAWpKTu7MGmVY+f+aT03qG1MYOiGoISK\n" +
            "gP6DHiinddD38spM2muyCUyFZk9a+aBEfaQzZoU3gc0yB6R/qBOWZ7NIoIUMicku\n" +
            "Zf3L6/06uunyZp+ueR83j1YWbg3JoYKlGAuQtDRF709+MQrim8lKTnfuHiBeZKYZ\n" +
            "GNLSo7lGjrp6ccSyfXmlA36hSfdlrWtZJ4+utZShftECgYEA+NNOFNa1BLfDw3ot\n" +
            "a6L4W6FE45B32bLbnBdg8foyEYrwzHLPFCbws1Z60pNr7NaCHDIMiKVOXvKQa78d\n" +
            "qdWuPUVJ83uVs9GI8tAo00RAvBn6ut9yaaLa8mIv6ZpfU20IgE5sDjB7IBY9tTVd\n" +
            "EDyJcDuKQXzQ48qmEw86wINQMd0CgYEA1ZMdt7yLnpDiYa6M/BuKjp7PWKcRlzVM\n" +
            "BcCEYHA4LJ6xEOH4y9DEx2y5ljwOcXgJhXAfAyGQr7s1xiP/nXurqfmdP8u7bawp\n" +
            "VwuWJ8Vv0ZXITaU0isezG2Dpnseuion3qSraWlmWUlWLVVgKETZmk7cF7VIXa0NT\n" +
            "LFREdObI5HsCgYBUbm8KRyi5Zxm4VNbgtTYM8ZYMmdLxPe2i85PjyAABT+IRncuC\n" +
            "jQwT7n5Swc9XWBpiMuFp5J3JPgmfZgRMwsMS61YClqbfk3Qi4FtaBMjqiu43Rubt\n" +
            "zWL56DNV0xoRlufRkcq8rdq5spJR0L+5aLFCMhHh0taW1QaxZPOMq4IkyQKBgQC3\n" +
            "GetubGzewqPyzuz77ri5URm+jW0dT4ofnE9hRpRCXMK9EJ52TkOGHYZ2cIKJcTno\n" +
            "dpl/27Tpk/ykJJSu9SnVDbVszkOf4OuIPty6uCAHdPxG5Q3ItTCulkVz5QmUqHf1\n" +
            "RlHxB8FCUSilQFdRLmx+03h3X9vID+4soQoXlwxAJQKBgE5SQpN+TG5V+E4zHgNd\n" +
            "6cy6gA5dGDJ0KbsgxJwlKTFA9nIcs2ssBxLY9U4x75EGuqpeVNmq6xwwmPtBs0rp\n" +
            "M3W4zdFrZQ3BneFRW7WbSBbsUSprkJW/p4GXa17GzGUq/MDXlGhNlApP1nknzFvE\n" +
            "xGaH0/H/TZxpLCogVP9npUkj\n" +
            "-----END PRIVATE KEY-----\n"
}
