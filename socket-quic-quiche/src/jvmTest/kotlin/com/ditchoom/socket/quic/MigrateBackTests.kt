package com.ditchoom.socket.quic

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds

/**
 * A client that migrated to a validated path and kept the old path's destination id can migrate straight back to the
 * old path: quiche takes it at once, on the id the old path held, and the connection carries on there. A client core
 * whose new socket is lost before the loop promoted it migrates back this way. A real quiche pair.
 */
class MigrateBackTests {
    private val env = jvmMigrationSimEnv()

    /** Back in the same call sequence as the migration, before either path sent anything. */
    @Test
    fun aClientMigratesStraightBackToThePathItLeftOnTheIdThatPathHeld() =
        runTest {
            val pair = QuichePairByHand.open(env)
            try {
                pair.establishWithSpareIds()
                val validated = pair.validate(MOVED_TO)
                assertTrue("Validated(endpoint $MOVED_TO)" in validated, "the new path validated: $validated")
                val moved = assertIs<MigrateOutcome.Migrated>(pair.migrate(MOVED_TO), "the validated path takes the connection")
                assertTrue(moved.dcidSeq > 0, "the move took a new id")
                val back =
                    assertIs<MigrateOutcome.Migrated>(pair.migrate(ORIGINAL), "quiche refused the path it just left: ${pair.pathTable(pair.client)}")
                assertEquals(0L, back.dcidSeq, "the old path's own id carries it again")
                assertEquals(0, pair.retireDcid(moved.dcidSeq), "the new path's id is retired")
                assertEquals(SENT, pair.echoThrough(), "the old path carries the connection")
                assertEquals("open", pair.closed(pair.client))
                assertEquals("open", pair.closed(pair.server))
            } finally {
                pair.close()
            }
        }

    /** Back after the migrated path already carried datagrams: the peer saw the migration, and follows the client back. */
    @Test
    fun aClientMigratesBackAfterTheNewPathCarriedDatagrams() =
        runTest {
            val pair = QuichePairByHand.open(env)
            try {
                pair.establishWithSpareIds()
                pair.validate(MOVED_TO)
                val moved = assertIs<MigrateOutcome.Migrated>(pair.migrate(MOVED_TO))
                pair.exchange(ONE_WAY) { true }
                val back = assertIs<MigrateOutcome.Migrated>(pair.migrate(ORIGINAL), "paths: ${pair.pathTable(pair.client)}")
                assertEquals(0L, back.dcidSeq)
                assertEquals(0, pair.retireDcid(moved.dcidSeq))
                assertEquals(SENT, pair.echoThrough(), "the old path carries the connection")
                assertEquals("open", pair.closed(pair.client))
            } finally {
                pair.close()
            }
        }

    private fun QuichePairByHand.establishWithSpareIds() {
        handshakeUpToTheClientsFinished(ONE_WAY)
        exchange(ONE_WAY) { true }
        issueSpareCids(server)
        issueSpareCids(client)
        exchange(ONE_WAY) { true }
        assertTrue(confirmed(client), "the client's handshake is confirmed")
    }

    /** Probes the path from [endpoint] and exchanges until its validation is answered: the client's path events. */
    private fun QuichePairByHand.validate(endpoint: Int): List<String> {
        assertIs<ProbeOutcome.Probed>(probe(endpoint), "a spare id is held for the probe")
        exchange(ONE_WAY) { true }
        return pathEvents(client)
    }

    /** The client sends a request on a new stream and the server reads all of it: what the server read. */
    private fun QuichePairByHand.echoThrough(): Int {
        val stream = QuicStreamId(0)
        streamSend(client, stream, "x".repeat(SENT), fin = true)
        exchange(ONE_WAY) { true }
        return readAll(server, stream).first
    }

    private companion object {
        const val ORIGINAL = 0
        const val MOVED_TO = 1
        const val SENT = 100
        val ONE_WAY = 30.milliseconds
    }
}
