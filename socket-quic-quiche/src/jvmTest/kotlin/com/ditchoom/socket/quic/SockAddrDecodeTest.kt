package com.ditchoom.socket.quic

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.Default
import java.net.InetSocketAddress
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

/**
 * Round-trips [SockAddrUtil.toNativeSockAddr] (encode) against the slice-3
 * sockaddr decode binding ([QuicheApi.decodePathKey]). The routing-critical
 * property: distinct local addresses decode to distinct [PathKey]s and identical
 * addresses to equal keys — that's what lets the multi-socket driver map
 * `sendInfo.from` to the right path socket.
 *
 * Uses the real loaded [QuicheApi] (FFM on JDK 21 / JNI shim in CI); the decode
 * functions are pure memory reads, no quiche connection required.
 */
class SockAddrDecodeTest {
    private val api: QuicheApi = loadQuicheApi()

    private fun key(
        host: String,
        port: Int,
    ): PathKey {
        val sa = InetSocketAddress(host, port).toNativeSockAddr(BufferFactory.Default)
        try {
            return api.decodePathKey(sa.address)
        } finally {
            sa.free()
        }
    }

    @Test
    fun decodesIpv4FamilyAndPort() {
        val k = key("127.0.0.1", 4433)
        assertEquals(4, k.family)
        assertEquals(4433, k.port)
    }

    @Test
    fun decodesIpv6FamilyAndPort() {
        val k = key("::1", 8443)
        assertEquals(6, k.family)
        assertEquals(8443, k.port)
    }

    @Test
    fun distinctLocalAddressesDecodeToDistinctKeys() {
        // The two loopback aliases the slice-3 active-migration harness uses.
        assertNotEquals(key("127.0.0.1", 4433), key("127.0.0.2", 4433))
    }

    @Test
    fun samePortDifferentValueDiffers() {
        assertNotEquals(key("127.0.0.1", 4433), key("127.0.0.1", 4434))
    }

    @Test
    fun identicalAddressesDecodeToEqualKeys() {
        assertEquals(key("127.0.0.1", 4433), key("127.0.0.1", 4433))
        assertEquals(key("::1", 8443), key("::1", 8443))
    }
}
