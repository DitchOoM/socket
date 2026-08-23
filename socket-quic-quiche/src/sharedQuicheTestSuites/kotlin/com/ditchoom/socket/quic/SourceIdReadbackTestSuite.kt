package com.ditchoom.socket.quic

import kotlinx.coroutines.launch
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/**
 * Proves each platform's backend really reads quiche's **live** source connection IDs
 * (`quiche_conn_active_scids` + `quiche_conn_source_ids`), rather than answering the interface
 * default.
 *
 * ## Why the default makes this test necessary
 * [QuicheApi.connActiveScids] and [QuicheApi.connReadSourceIds] both default to "nothing", following
 * the [QuicheApi.connStats]/[QuicheApi.connPeerError] convention that lets a test double skip an
 * accessor it does not model. That default is indistinguishable from a real backend that forgot to
 * override — a backend with no binding at all reports a healthy-looking "0 active CIDs" forever, and
 * every reconciliation check built on top of it passes by describing an empty world. So this suite
 * asserts the numbers are *non-empty* and *cross-checked*, never merely well-formed.
 *
 * ## What each assertion catches
 * - **non-empty** — the unbound-backend case above, which is the whole reason for a per-platform member.
 * - **contains the connection's own wire CID** — the reading is of *this* connection's real table and
 *   not some other buffer that happens to hold plausible bytes. [QuicheDriver.wireConnectionId] comes
 *   from a different quiche call (`quiche_conn_source_id`), so agreement between the two is genuine
 *   corroboration rather than one value restated.
 * - **count agrees with the read** — `connActiveScids` sizes the buffer `connReadSourceIds` fills.
 *   Both run on the driver coroutine, the only thread allowed to touch the connection, so a
 *   disagreement means that confinement has broken and every count-then-read pair in the driver
 *   (including [QuicheDriver.drainRetiredScids]) is unsound.
 * - **more than one** — the driver issues spare CIDs while established (`issueSpareCids`), so a live
 *   connection holds its active id plus spares. Exactly one would mean the spares are missing from
 *   quiche's view, which is what a peer needs in order to migrate at all (#448).
 *
 * This is the read-back half of the CID API. The write half — issue, retire — has been in use for
 * years; asking quiche what the live set *is* is new, and it is what makes a divergence between
 * quiche's table and our own routing map assertable instead of discoverable only downstream as a
 * dropped packet (#437) or a permanently pinned path slot (#395, #447). See #449.
 */
abstract class SourceIdReadbackTestSuite {
    abstract fun testTlsConfig(): QuicTlsConfig

    protected open suspend fun wrapTestBody(block: suspend () -> Unit): Unit = block()

    private val options = QuicOptions(alpnProtocols = listOf("test"), verifyPeer = false, idleTimeout = 10.seconds)

    @Test
    fun theBackendReadsQuichesLiveSourceConnectionIds() =
        runQuicTest(timeout = 30.seconds) {
            wrapTestBody {
                withQuicServer(port = 0, tlsConfig = testTlsConfig(), quicOptions = options) {
                    val serverJob = launch { connections { acceptStream() } }
                    try {
                        withQuicConnection("127.0.0.1", port, options, timeout = 10.seconds) {
                            openStream()
                            val driver = (this as QuicheBackedConnection).quicheDriver
                            val ids = driver.sourceIds()

                            assertTrue(
                                ids.isNotEmpty(),
                                "connReadSourceIds returned nothing on an established connection. Either this " +
                                    "platform's backend never bound quiche_conn_source_ids and is answering the " +
                                    "QuicheApi default, or quiche has no active source CID at all — the first is " +
                                    "a silent gap that makes every CID reconciliation vacuously true.",
                            )
                            // Known, not Unavailable: a backend that never bound quiche's
                            // current-source-CID accessor reports Unavailable, and comparing against
                            // that would make the corroboration below vacuous in exactly the same way
                            // the QuicheApi default would.
                            val wire =
                                assertIs<QuicWireConnectionId.Known>(
                                    driver.wireConnectionId,
                                    "this backend does not report quiche's current source CID, so the readback " +
                                        "cannot be corroborated against an independent call",
                                )
                            assertContains(
                                ids.map { id -> id.joinToString("") { b -> (b.toInt() and 0xFF).toString(16).padStart(2, '0') } },
                                wire.hex,
                                "the connection's own wire CID is absent from quiche's active source ids. It is " +
                                    "read through a different call (quiche_conn_source_id), so this is the check " +
                                    "that the readback describes THIS connection's table.",
                            )
                            assertEquals(
                                ids.size,
                                driver.sourceIds().size,
                                "two consecutive reads disagreed — quiche_conn_source_ids must be a pure read " +
                                    "(unlike quiche_conn_retired_scid_iter, which drains)",
                            )
                            assertTrue(
                                ids.size > 1,
                                "only ${ids.size} active source CID: the driver issues spares while established " +
                                    "(issueSpareCids), and without them in quiche's view the peer has nothing to " +
                                    "migrate onto. Got ${ids.size}.",
                            )
                        }
                    } finally {
                        serverJob.cancel()
                    }
                }
            }
        }
}
