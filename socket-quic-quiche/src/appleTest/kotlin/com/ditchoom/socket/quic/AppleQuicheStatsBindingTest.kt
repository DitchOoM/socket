@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.ditchoom.socket.quic

import kotlinx.cinterop.UByteVar
import kotlinx.cinterop.alloc
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.convert
import kotlinx.cinterop.cstr
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.rawValue
import kotlinx.cinterop.set
import kotlinx.cinterop.sizeOf
import platform.posix.AF_INET
import platform.posix.sockaddr_in
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Runtime smoke for the W3 cinterop stats bindings (`quiche_conn_stats` / `quiche_conn_path_stats`,
 * RFC_DETERMINISTIC_SIMULATION.md §5.1 item 5) on Apple: allocate a real quiche client connection
 * (no I/O, no handshake) and assert both calls return typed, sane snapshots — a fresh connection
 * reports zero traffic on its primary path, and an out-of-range path index maps to a typed `null`
 * rather than a crash. Exercises the cinterop struct mapping against the actual libquiche.a; the
 * traffic-value assertions (rtt > 0 after echo, counters > 0) run on the FFM lane
 * (`QuicheStatsBindingTests`), which drives the identical C structs.
 */
class AppleQuicheStatsBindingTest {
    @Test
    fun connStats_and_pathStats_bind_and_return_sane_values() =
        memScoped {
            val api: QuicheApi = CinteropQuicheApi
            val config = api.configNew(1)
            try {
                val scid = allocArray<UByteVar>(QUIC_MAX_CONN_ID_LEN)
                for (i in 0 until QUIC_MAX_CONN_ID_LEN) scid[i] = (i + 1).convert()
                val local = alloc<sockaddr_in>()
                val peer = alloc<sockaddr_in>()
                for (sa in listOf(local, peer)) {
                    sa.sin_len = sizeOf<sockaddr_in>().convert()
                    sa.sin_family = AF_INET.convert()
                    sa.sin_port = 0.convert()
                    sa.sin_addr.s_addr = 0x0100007Fu // 127.0.0.1 in network byte order (LE host)
                }
                val serverName = "localhost".cstr.ptr
                val conn =
                    api.connect(
                        serverName.rawValue.toLong(),
                        "localhost".length,
                        scid.rawValue.toLong(),
                        QUIC_MAX_CONN_ID_LEN,
                        local.ptr.rawValue.toLong(),
                        sizeOf<sockaddr_in>().toInt(),
                        peer.ptr.rawValue.toLong(),
                        sizeOf<sockaddr_in>().toInt(),
                        config,
                    )
                try {
                    val connStats = assertNotNull(api.connStats(conn), "connStats must be bound on cinterop")
                    assertEquals(0L, connStats.sent, "fresh connection has sent nothing: $connStats")
                    assertEquals(0L, connStats.recv, "fresh connection has received nothing: $connStats")
                    assertEquals(0L, connStats.lost, "$connStats")
                    assertTrue(connStats.pathsCount >= 1L, "quiche creates the primary path at connect: $connStats")

                    val pathStats = assertNotNull(api.connPathStats(conn, 0L), "path 0 must exist: $connStats")
                    assertTrue(pathStats.cwnd > 0L, "initial congestion window is never 0: $pathStats")
                    assertEquals(0L, pathStats.sentBytes, "$pathStats")
                    assertEquals(0L, pathStats.recvBytes, "$pathStats")
                    assertEquals(0L, pathStats.lost, "$pathStats")

                    assertNull(api.connPathStats(conn, 99L), "out-of-range path index must be a typed null")
                } finally {
                    api.connFree(conn)
                }
            } finally {
                api.configFree(config)
            }
        }
}
