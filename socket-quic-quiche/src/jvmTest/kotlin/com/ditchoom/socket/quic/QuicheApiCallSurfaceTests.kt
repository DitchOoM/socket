package com.ditchoom.socket.quic

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.nativeMemoryAccess
import kotlinx.coroutines.test.runTest
import java.io.ByteArrayInputStream
import java.io.File
import java.nio.file.Files
import java.security.cert.CertificateFactory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/**
 * Every `QuicheApi` call that no other test reaches without the driver's cooperation, made directly over
 * one real client/server connection ([QuichePairByHand]) and checked against a value the connection
 * itself fixes. On the FFM backend each downcall's `FunctionDescriptor` has to match its call site, and a
 * mismatch only fails when the call runs; this is the test that runs them. It uses whichever backend
 * `-PquicheJvmBackend` selected, so it executes on both.
 */
class QuicheApiCallSurfaceTests {
    private val env = jvmMigrationSimEnv()
    private val api = env.api
    private val factory = BufferFactory.network()

    // Not the 120 s default: the assertion cannot pass against a zeroed struct.
    private val options = migrationSimOptions(idleTimeout = 77.seconds)

    private fun readNative(call: (address: Long, capacity: Int) -> Int): ByteArray {
        val buf = factory.allocate(CAPACITY)
        try {
            val n = call(buf.nativeMemoryAccess!!.nativeAddress.toLong(), CAPACITY)
            assertTrue(n in 0..CAPACITY, "the answer $n must be a length that fits the $CAPACITY-byte buffer")
            buf.position(0)
            return buf.readByteArray(n)
        } finally {
            buf.freeNativeMemory()
        }
    }

    private fun leafCertificate() =
        File(env.certChainPath).inputStream().use { CertificateFactory.getInstance("X.509").generateCertificate(it) }

    private fun der(bytes: ByteArray) = CertificateFactory.getInstance("X.509").generateCertificate(ByteArrayInputStream(bytes))

    @Test
    fun everyReadbackAnswersWhatTheConnectionFixed() =
        runTest {
            val dir = Files.createTempDirectory("quiche-call-surface").toFile()
            val qlog = File(dir, "client.sqlog")
            val keylog = File(dir, "client.keys")
            val pair = QuichePairByHand.open(env, options, configureClient = { api.configLogKeys(it) })
            try {
                try {
                    assertTrue(api.connSetQlogPath(pair.client, qlog.path, "call-surface", "FFM descriptor shapes"), "qlog path accepted")
                    assertTrue(api.connSetKeylogPath(pair.client, keylog.path), "keylog path accepted")

                    pair.exchange(DEFAULT_PATH_LATENCY) { true }
                    assertTrue(pair.established(pair.client) && pair.established(pair.server), "the handshake must complete")

                    // ALPN: the one both sides configured.
                    assertEquals(
                        "migsim",
                        readNative { a, c -> api.connApplicationProto(pair.client, a, c) }.decodeToString(),
                        "negotiated ALPN",
                    )

                    // Peer certificate: the client sees the server's leaf, as DER, equal to the test fixture.
                    val leaf = leafCertificate()
                    assertEquals(leaf, der(readNative { a, c -> api.connPeerCert(pair.client, a, c) }), "peer leaf certificate")
                    assertEquals(leaf, der(readNative { a, c -> api.connPeerCertChainAt(pair.client, 0, a, c) }), "chain[0] is the leaf")
                    assertEquals(0, api.connPeerCertChainAt(pair.client, 99, 0L, 0), "an index past the chain answers 0")

                    // Identifiers: quiche's own, never empty.
                    assertTrue(readNative { a, c -> api.connTraceId(pair.client, a, c) }.isNotEmpty(), "trace id")
                    assertEquals(
                        QUIC_ISSUED_CONN_ID_LEN,
                        readNative { a, c -> api.connSourceId(pair.client, a, c) }.size,
                        "source id length is the one the pair issued",
                    )

                    // Stats: the connection has sent and received packets.
                    val stats = assertIs<QuicConnStats>(api.connStats(pair.client), "connection stats")
                    assertTrue(stats.sent > 0 && stats.recv > 0, "sent=${stats.sent} recv=${stats.recv}")
                    val path = assertIs<QuicPathStats>(api.connPathStats(pair.client, 0L), "path 0 stats")
                    assertTrue(path.sent > 0 && path.recv > 0, "path sent=${path.sent} recv=${path.recv}")

                    // Peer transport parameters: what the server configured.
                    val params = assertIs<PeerTransportParams.Negotiated>(api.connPeerTransportParams(pair.client))
                    assertEquals(77_000L, params.maxIdleTimeoutMillis, "server max_idle_timeout")
                    assertEquals(options.flowControl.initialMaxData, params.initialMaxData, "server initial_max_data")
                    assertEquals(options.flowControl.initialMaxStreamsBidi, params.initialMaxStreamsBidi, "server initial_max_streams_bidi")
                    assertEquals(options.activeConnectionIdLimit, params.activeConnIdLimit, "server active_connection_id_limit")
                    assertEquals(false, params.disableActiveMigration, "Manual migration policy permits migration")
                    assertEquals(-1L, params.maxDatagramFrameSize, "datagrams are off")

                    // Session: the server's ticket reached the client, and a fresh connection accepts it.
                    val session = readNative { a, c -> api.connSession(pair.client, a, c) }
                    assertTrue(session.isNotEmpty(), "the client holds a session ticket after the handshake")
                    val resumed = QuichePairByHand.open(env, options)
                    try {
                        val buf = factory.allocate(session.size)
                        try {
                            buf.writeBytes(session)
                            assertEquals(
                                0,
                                api.connSetSession(resumed.client, buf.nativeMemoryAccess!!.nativeAddress.toLong(), session.size),
                            )
                        } finally {
                            buf.freeNativeMemory()
                        }
                    } finally {
                        resumed.close()
                    }

                    // Connection ids and paths: the server issues spares, the client probes a second local path,
                    // validates it and migrates, then retires the id it left; the server sees that retirement.
                    assertTrue(
                        api.connReadSourceIds(pair.server, 0L, 0) >= 1,
                        "the server's source id set is readable (count only)",
                    )
                    pair.issueSpareCids(pair.server)
                    pair.issueSpareCids(pair.client)
                    pair.exchange(DEFAULT_PATH_LATENCY) { true }
                    val probe = pair.probe(1)
                    assertIs<ProbeOutcome.Probed>(
                        probe,
                        "probe: ${(probe as? ProbeOutcome.Rejected)?.code}, client spare ids ${pair.spareDcids(pair.client)}",
                    )
                    pair.exchange(DEFAULT_PATH_LATENCY) { true }
                    assertTrue(pair.pathEvents(pair.client).isNotEmpty(), "the probe produced path events on the client")
                    assertIs<MigrateOutcome.Migrated>(pair.migrate(1))
                    pair.exchange(DEFAULT_PATH_LATENCY) { true }
                    assertEquals(0, pair.retireDcid(0L), "the client retires the id it left")
                    pair.exchange(DEFAULT_PATH_LATENCY) { true }
                    val retired = factory.allocate(RETIRED_SCID_SLOT_BYTES * 8)
                    try {
                        val count = api.connDrainRetiredScids(pair.server, retired.nativeMemoryAccess!!.nativeAddress.toLong(), 8)
                        assertTrue(count >= 1, "the server learns the client retired one of its ids, count=$count")
                    } finally {
                        retired.freeNativeMemory()
                    }

                    // Close with a known application error: both ends read it back, typed.
                    val code = 0x2AL
                    assertEquals(0, pair.closeWith(pair.client, QuicError.ApplicationError(code)))
                    pair.exchange(DEFAULT_PATH_LATENCY) { true }
                    assertEquals(QuicError.ApplicationError(code), api.connLocalError(pair.client), "the closer's own error")
                    assertEquals(QuicError.ApplicationError(code), api.connPeerError(pair.server), "the error the peer received")
                } finally {
                    pair.close()
                }
                // quiche flushes its qlog and keylog writers when the connection is freed.
                assertTrue(qlog.length() > 0, "the qlog file is non-empty")
                assertTrue(keylog.length() > 0, "the keylog file is non-empty")
            } finally {
                dir.deleteRecursively()
            }
        }

    private companion object {
        const val CAPACITY = 8192
    }
}
