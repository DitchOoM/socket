package com.ditchoom.socket.quic

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.Charset
import com.ditchoom.buffer.deterministic
import com.ditchoom.buffer.flow.ReadResult
import com.ditchoom.buffer.flow.writeFully
import com.ditchoom.buffer.freeIfNeeded
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
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
 * - **a second read still contains the first** — `quiche_conn_source_ids` is a *read*, unlike
 *   `quiche_conn_retired_scid_iter`, which drains. A draining implementation reports a healthy set
 *   once and nothing ever after, so every reconciliation built on it silently compares against an
 *   empty world from its second use onward. Containment rather than equality on purpose: a live
 *   connection is still issuing spares between the two reads (`issueSpareCids`, capped per wake), so
 *   the set may legitimately grow — it just must never lose anything.
 * - **more than one** — the driver issues spare CIDs while established (`issueSpareCids`), so a live
 *   connection holds its active id plus spares. Exactly one would mean the spares are missing from
 *   quiche's view, which is what a peer needs in order to migrate at all (#448).
 *
 * This is the read-back half of the CID API. The write half — issue, retire — has been in use for
 * years; asking quiche what the live set *is* is new, and it is what makes a divergence between
 * quiche's table and our own routing map assertable instead of discoverable only downstream as a
 * dropped packet (#437) or a permanently pinned path slot (#395, #447). See #449.
 *
 * [theServersRoutingTableIsAProjectionOfQuichesSourceIds] is the other half of the same idea: the
 * first test proves the backend can *read* the live set; the second proves the routing table is
 * actually *equal* to it, end to end, across a real migration.
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
                            // A DRAIN is what this must catch, and a drain empties the set — so the
                            // property is that a second read still CONTAINS the first, not that it
                            // matches it exactly. The set legitimately grows between two reads on a
                            // live connection: `issueSpareCids` runs on every established wake and
                            // issues at most MAX_SPARE_SCIDS at a time, so with a deep
                            // QuicOptions.activeConnectionIdLimit it is still ramping up while this
                            // test runs, and an equality here fails on a healthy connection at whatever
                            // count the ramp happened to be passing through. Shrinking, by contrast,
                            // needs the peer to retire one of ours, which nothing in this scenario does.

                            fun List<ByteArray>.hex() =
                                map { id -> id.joinToString("") { b -> (b.toInt() and 0xFF).toString(16).padStart(2, '0') } }
                            val second = driver.sourceIds().hex()
                            assertTrue(
                                second.isNotEmpty(),
                                "the second read of quiche_conn_source_ids came back empty — it drained, " +
                                    "the way quiche_conn_retired_scid_iter legitimately does. Every " +
                                    "reconciliation built on this call would then be comparing against " +
                                    "nothing after its first use.",
                            )
                            assertTrue(
                                second.containsAll(ids.hex()),
                                "a source CID present in the first read is missing from the second, and " +
                                    "nothing in this scenario retires one — so the call is consuming what " +
                                    "it reports rather than reading it. First $ids, second $second.",
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

    /**
     * **The routing table equals `quiche_conn_source_ids` — including after the set shrinks** (#449,
     * and the standing regression guard for #437).
     *
     * The server's DCID→driver map decides whether a datagram reaches a connection at all, so it must
     * hold exactly the ids quiche recognises: an id quiche has forgotten but the map still routes hands
     * quiche a CID it will answer with PROTOCOL_VIOLATION (#437); an id quiche has issued but the map
     * has not learned makes a migrating peer's PATH_CHALLENGE miss the demux entirely.
     *
     * A client migration is what moves the set: RFC 9000 §9.5 has the client retire the CID it used on
     * the old path, and quiche drops that id from `source_ids` in the same call that queues it as
     * retired. So this migrates and then asserts the map followed — in **both** directions, because
     * only asserting the removal would let a map that had unrouted everything pass.
     *
     * Reading the server's table needs the server object rather than a `QuicScope`, which is why this
     * casts; both ends are this module's own server in this process.
     */
    @Test
    fun theServersRoutingTableIsAProjectionOfQuichesSourceIds() =
        runQuicTest(timeout = 60.seconds) {
            wrapTestBody {
                withQuicServer(port = 0, tlsConfig = testTlsConfig(), quicOptions = options) {
                    val server = this as SharedQuicheServer
                    val serverDriver = CompletableDeferred<QuicheDriver>()
                    val serverJob =
                        launch {
                            connections {
                                serverDriver.complete((this as QuicheBackedConnection).quicheDriver)
                                val stream = acceptStream()
                                while (true) {
                                    val data = stream.read(30.seconds)
                                    if (data !is ReadResult.Data) break
                                    try {
                                        stream.writeFully(data.buffer, 5.seconds)
                                    } finally {
                                        data.buffer.freeIfNeeded()
                                    }
                                }
                            }
                        }
                    try {
                        // The block runs INSIDE this timeout (withQuicConnection wraps it), so every
                        // poll below is bounded well under it and reports its own verdict — an opaque
                        // connection timeout would say nothing about which half of the projection failed.
                        withQuicConnection("127.0.0.1", port, options, timeout = 45.seconds) {
                            val stream = openStream()
                            assertEquals("before", stream.echo("before"), "the connection must be healthy to start")
                            val driver =
                                assertNotNull(
                                    withTimeoutOrNull(SYNC_BUDGET) { serverDriver.await() },
                                    "the server never accepted the connection",
                                )

                            val before =
                                assertNotNull(
                                    withTimeoutOrNull(SYNC_BUDGET) { driver.awaitRoutedSourceIds(server) },
                                    "the server never routed the ids quiche lists for this connection: a peer " +
                                        "migrating onto one of them would miss the demux entirely",
                                )
                            assertTrue(
                                before.size > 1,
                                "the server has only ${before.size} routed source CID, so the shrink below cannot " +
                                    "be distinguished from an empty table",
                            )

                            val migration = migrate()
                            assertTrue(migration is MigrationResult.Succeeded, "expected a migration, got $migration")
                            assertEquals("after", stream.echo("after"), "the stream must survive the migration")

                            // The client retired the CID it used on the old path; quiche removed it from
                            // source_ids the moment it processed the RETIRE_CONNECTION_ID frame.
                            val after =
                                assertNotNull(
                                    withTimeoutOrNull(SYNC_BUDGET) {
                                        var ids = driver.sourceIdKeys()
                                        while (ids.containsAll(before)) {
                                            delay(POLL_INTERVAL)
                                            ids = driver.sourceIdKeys()
                                        }
                                        ids
                                    },
                                    "quiche never dropped an id after the migration, so nothing is being measured " +
                                        "— RFC 9000 §9.5 has the client retire the CID it used on the old path",
                                )
                            val retired = before - after

                            assertNotNull(
                                withTimeoutOrNull(SYNC_BUDGET) {
                                    while (retired.any { server.routesConnectionIdForTest(it) }) delay(POLL_INTERVAL)
                                },
                                "the server is STILL routing $retired, which quiche has already forgotten. A " +
                                    "packet still in flight under that CID now reaches a quiche that does not " +
                                    "recognise it, and its InvalidState becomes a PROTOCOL_VIOLATION close (#437)",
                            )
                            for (id in after) {
                                assertTrue(
                                    server.routesConnectionIdForTest(id),
                                    "an id quiche still lists is not routed — a migrating peer switching to it " +
                                        "would have its packets dropped at the demux, so the path never validates",
                                )
                            }
                            stream.close()
                        }
                    } finally {
                        serverJob.cancel()
                    }
                }
            }
        }

    /** Every id quiche lists for [this], as the routing keys the server's demux would build. */
    private suspend fun QuicheDriver.sourceIdKeys(): Set<ConnectionIdKey> =
        sourceIds()
            .map { id ->
                val buf = BufferFactory.deterministic().allocate(id.size)
                id.forEach { buf.writeByte(it) }
                buf.resetForRead()
                ConnectionIdKey.from(buf, offset = 0, length = id.size)
            }.toSet()

    /**
     * quiche's ids for this connection once the server is routing all of them — the projection is
     * published on a driver wake and applied on the receive loop, so a read taken the instant the
     * handshake completes can legitimately be one hop early. Polling here rather than asserting
     * immediately keeps that ordinary lag out of the assertions that matter.
     */
    private suspend fun QuicheDriver.awaitRoutedSourceIds(server: SharedQuicheServer): Set<ConnectionIdKey> {
        while (true) {
            val ids = sourceIdKeys()
            if (ids.isNotEmpty() && ids.all { server.routesConnectionIdForTest(it) }) return ids
            delay(POLL_INTERVAL)
        }
    }

    private fun ReadResult.text(): String = if (this is ReadResult.Data) buffer.readString(buffer.remaining(), Charset.UTF8) else "no_data"

    private suspend fun QuicByteStream.echo(payload: String): String {
        val out = BufferFactory.deterministic().allocate(payload.length)
        out.writeString(payload, Charset.UTF8)
        out.resetForRead()
        write(out, 5.seconds)
        return read(30.seconds).text()
    }

    private companion object {
        /** Poll step while waiting for a projection to be published and applied. */
        val POLL_INTERVAL = 25.milliseconds

        /**
         * How long any one of the waits above may take. A projection crosses one coroutine hop on
         * loopback, so this is orders of magnitude more than enough — it exists to turn "never" into a
         * named failure, not to tolerate slowness.
         */
        val SYNC_BUDGET = 10.seconds
    }
}
