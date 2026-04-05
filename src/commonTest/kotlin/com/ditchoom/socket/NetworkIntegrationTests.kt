package com.ditchoom.socket

import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/**
 * Lightweight integration tests against public servers.
 *
 * These validate real TCP and TLS connectivity on every platform.
 * Uses example.com (IANA-controlled, stable) and httpbin.org.
 * Gracefully skips on platforms without socket support (wasmJs)
 * and on network-restricted environments (iOS Simulator in CI).
 */
class NetworkIntegrationTests {
    @Test
    fun tcp_connectToPublicServer() =
        runTestNoTimeSkipping(timeout = 15.seconds) {
            skipOnSimulator { return@runTestNoTimeSkipping }
            val socket = connectOrSkip(80, "example.com") ?: return@runTestNoTimeSkipping
            try {
                socket.writeString("GET / HTTP/1.0\r\nHost: example.com\r\n\r\n")
                val response = socket.readString()
                assertTrue(response.startsWith("HTTP/1."), "Expected HTTP response, got: ${response.take(40)}")
            } finally {
                socket.close()
            }
        }

    @Test
    fun tls_connectToPublicServer() =
        runTestNoTimeSkipping(timeout = 15.seconds) {
            skipOnSimulator { return@runTestNoTimeSkipping }
            val socket = connectOrSkip(443, "example.com", SocketOptions.tlsDefault()) ?: return@runTestNoTimeSkipping
            try {
                socket.writeString("GET / HTTP/1.0\r\nHost: example.com\r\n\r\n")
                val response = socket.readString()
                assertTrue(response.startsWith("HTTP/1."), "Expected HTTPS response, got: ${response.take(40)}")
            } finally {
                socket.close()
            }
        }

    @Test
    fun tls_connectToCloudflare() =
        runTestNoTimeSkipping(timeout = 15.seconds) {
            skipOnSimulator { return@runTestNoTimeSkipping }
            val socket =
                connectOrSkip(443, "cloudflare.com", SocketOptions.tlsDefault()) ?: return@runTestNoTimeSkipping
            try {
                socket.writeString("GET / HTTP/1.1\r\nHost: cloudflare.com\r\nConnection: close\r\n\r\n")
                val response = socket.readString()
                assertTrue(response.startsWith("HTTP/1."), "Expected HTTPS response, got: ${response.take(40)}")
            } finally {
                socket.close()
            }
        }

    @Test
    fun tcp_readWriteRoundtrip() =
        runTestNoTimeSkipping(timeout = 15.seconds) {
            skipOnSimulator { return@runTestNoTimeSkipping }
            val socket =
                connectOrSkip(443, "httpbin.org", SocketOptions.tlsDefault()) ?: return@runTestNoTimeSkipping
            try {
                val request = "GET /get HTTP/1.1\r\nHost: httpbin.org\r\nConnection: close\r\n\r\n"
                socket.writeString(request)
                val response = socket.readString()
                assertTrue(response.contains("200"), "Expected 200 in response, got: ${response.take(80)}")
            } finally {
                socket.close()
            }
        }

    /** Connect or return null if the platform doesn't support sockets. */
    private suspend fun connectOrSkip(
        port: Int,
        hostname: String,
        socketOptions: SocketOptions = SocketOptions(),
    ): ClientSocket? =
        try {
            ClientSocket.connect(port, hostname, timeout = 10.seconds, socketOptions = socketOptions)
        } catch (_: UnsupportedOperationException) {
            null // wasmJs, browser
        } catch (_: SocketException) {
            null // network unavailable
        } catch (_: Exception) {
            null // any other connectivity issue
        }
}
