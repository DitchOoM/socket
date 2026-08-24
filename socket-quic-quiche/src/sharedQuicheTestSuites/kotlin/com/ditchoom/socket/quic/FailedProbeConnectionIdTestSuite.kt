@file:OptIn(ExperimentalDatagramApi::class)

package com.ditchoom.socket.quic

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.Charset
import com.ditchoom.buffer.deterministic
import com.ditchoom.buffer.flow.ExperimentalDatagramApi
import com.ditchoom.buffer.flow.ReadResult
import com.ditchoom.buffer.flow.writeFully
import com.ditchoom.buffer.freeIfNeeded
import com.ditchoom.socket.udp.UdpSocket
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * Shared regression guard for #447: **a migration whose probe is never answered must give its
 * destination connection ID back**, so a run of failed handoffs cannot disable migration for the
 * rest of the connection's life.
 *
 * ## The defect
 * `quiche_conn_probe_path` is not a passive question. `create_path_on_client` takes
 * `lowest_available_dcid_seq()` and links it to the path it creates, so `available_dcids()` drops the
 * instant a probe is armed. If the peer never answers the PATH_CHALLENGE,
 * `Path::on_failed_validation()` sets the path to `Failed` and leaves `active_dcid_seq` exactly where
 * it was — quiche never returns the id, and the path is not even evictable, because `Path::unused()`
 * requires `active_dcid_seq.is_none()`.
 *
 * The driver used to write that sequence number into native scratch and never read it, so its three
 * failure exits (`FailedValidation`, the RFC 9000 §8.2.4 abandon timer, and a `quiche_conn_migrate`
 * that refuses an already-validated path) had **no value to forget**. Each unanswered probe cost one
 * spare CID permanently; once the pool was gone the peer had no reason to issue more (its
 * `scids_left` stays 0 while nothing is retired), and every later `migrate()` answered
 * [MigrationResult.Unmoved.Failed.NoSpareConnectionId] — forever. Observed live against Google on
 * 2026-08-22: `PathNotValidated` → `NoSpareConnectionId` for the remainder of the connection.
 *
 * An unanswered PATH_CHALLENGE is *routine* on real cellular, which is what makes this the highest
 * user-impact half of the CID/path family: one bad handoff parks migration for good, and the next
 * handoff — the one that matters, where the old path is genuinely dead — cannot happen.
 *
 * ## Why the test is built this way
 * On loopback every probe succeeds, so there is no natural failed validation to observe.
 * [UnansweredProbeDatagramChannel] supplies the missing network condition through the production
 * [QuicPortBinding.Shared] seam: after the connection is established it drops every datagram from a
 * source the server has not already heard from, which is precisely what a probe from a fresh local
 * port is.
 *
 * The decisive assertion is deliberately **not** "attempt N reports PathNotValidated". Retiring a CID
 * and receiving its replacement is a round trip, so which failure a given attempt reports is timing —
 * whereas whether the connection can *ever* migrate again is not. So: exhaust the pool with
 * [FAILED_ATTEMPTS] unanswerable probes (one more than [PINNED_CID_LIMIT] allows to be
 * outstanding), lift the block, and require a migration to succeed and the stream to keep running.
 * Unpatched, that last migration finds an empty pool that nothing will ever refill.
 *
 * ## Why it is a *shared* suite
 * Same reason as [RetiredCidInFlightPacketTestSuite]: the behaviour depends on `libquiche`'s own CID
 * accounting, and each target links a `libquiche` built separately. A JVM-only guard proves the
 * driver is right; it proves nothing about the archive Apple embeds or the `.so` Android ships.
 */
abstract class FailedProbeConnectionIdTestSuite {
    abstract fun testTlsConfig(): QuicTlsConfig

    /**
     * Build a server on [binding]. Every platform has the seam; only the function that carries it
     * differs (`buildJvmQuicServer` / `buildAppleQuicServer` / `buildLinuxQuicServer`), and none of
     * them is visible from common code.
     */
    internal abstract suspend fun buildServer(
        binding: QuicPortBinding,
        tlsConfig: QuicTlsConfig,
        options: QuicOptions,
    ): SharedQuicheServer

    /** Same hook as every other suite: JVM/Android members turn a missing native into a typed skip. */
    protected open suspend fun wrapTestBody(block: suspend () -> Unit): Unit = block()

    @Test
    fun aRunOfUnansweredProbesLeavesTheConnectionAbleToMigrate() =
        runQuicTest(timeout = 180.seconds) {
            wrapTestBody {
                // activeConnectionIdLimit is PINNED at the RFC-minimum-plus-two rather than inherited
                // from QuicOptions' default. This suite is the standing per-platform guard for #447 and
                // it runs on real sockets and real time: every unanswered probe costs its full RFC 9000
                // §8.2.4 abandon budget (~3s), on every target. Pinning the smallest limit that still
                // exhausts the pool keeps [FAILED_ATTEMPTS] meaning "one past exhaustion" and keeps each
                // target's run bounded, whatever the shipped default becomes. The defect this guards is
                // in the mechanism, not in the size of the pool.
                val opts =
                    QuicOptions(
                        alpnProtocols = listOf("test"),
                        verifyPeer = false,
                        idleTimeout = 60.seconds,
                        activeConnectionIdLimit = PINNED_CID_LIMIT,
                    )
                val socket =
                    UdpSocket.bind(
                        "127.0.0.1",
                        0,
                        receiveBufferSize = QuicheDriver.MAX_DATAGRAM_SIZE,
                        bufferFactory = BufferFactory.network(),
                    )
                val channel = UnansweredProbeDatagramChannel(socket)
                val server = buildServer(QuicPortBinding.Shared(channel), testTlsConfig(), opts)
                val serverPort = server.port
                val serverJob =
                    launch {
                        server.connections {
                            val stream = acceptStream()
                            while (true) {
                                val data = stream.read(60.seconds)
                                if (data is ReadResult.Data) {
                                    try {
                                        stream.writeFully(data.buffer, 5.seconds)
                                    } finally {
                                        // read transfers ownership; write is zero-copy and takes none.
                                        data.buffer.freeIfNeeded()
                                    }
                                } else {
                                    break
                                }
                            }
                            stream.close()
                        }
                    }
                try {
                    withQuicConnection("127.0.0.1", serverPort, opts, timeout = 30.seconds) {
                        val stream = openStream()
                        assertEquals("before", stream.echo("before"), "the connection must be healthy before any probe")

                        // From here every datagram from a source the server has not already heard is
                        // dropped — i.e. every migration probe's PATH_CHALLENGE.
                        channel.dropSourcesNotYetSeen()

                        repeat(FAILED_ATTEMPTS) { attempt ->
                            val result = migrate()
                            assertTrue(
                                result is MigrationResult.Unmoved,
                                "attempt ${attempt + 1} of $FAILED_ATTEMPTS reported $result, but its " +
                                    "PATH_CHALLENGE could not have been answered — the block is not working " +
                                    "and the rest of this test would prove nothing",
                            )
                        }
                        assertTrue(
                            channel.dropped > 0,
                            "no datagram was ever dropped, so no probe actually went unanswered — this test " +
                                "would pass with the leak fully present",
                        )
                        assertEquals(
                            "still-here",
                            stream.echo("still-here"),
                            "the failed migrations must leave the ORIGINAL path working — a connection that " +
                                "died here would make the recovery assertion below meaningless",
                        )

                        // The network comes good again. Post-fix the pool has been refilled by the
                        // retirements; unpatched it is empty and nothing will ever refill it, because a
                        // peer only issues a replacement CID when one is retired.
                        channel.allowEverySource()
                        val recovered = migrateAllowingReplenishRoundTrip()
                        assertTrue(
                            recovered is MigrationResult.Succeeded,
                            "after $FAILED_ATTEMPTS unanswered probes the connection can no longer migrate " +
                                "at all: $recovered. Each failed probe consumed a destination CID that was " +
                                "never retired, so quiche's spare pool is permanently empty and the peer has " +
                                "no reason to issue more — one bad handoff on real cellular disables " +
                                "migration for the life of the connection (#447)",
                        )
                        assertEquals(
                            "after",
                            stream.echo("after"),
                            "the stream did not survive the migration that followed the failed ones",
                        )
                        stream.close()
                    }
                } finally {
                    serverJob.cancel()
                    server.close()
                }
            }
        }

    /**
     * Migrate, tolerating the one round trip a freshly-retired CID needs to be replaced.
     *
     * Retiring sends RETIRE_CONNECTION_ID; the peer answers with NEW_CONNECTION_ID; only then is
     * `available_dcids()` non-zero again. That is a real, bounded delay and retrying through it is
     * correct. What it cannot paper over is the defect: an unpatched connection has *nothing*
     * outstanding to be replaced, so every one of these retries answers NoSpareConnectionId.
     */
    private suspend fun QuicScope.migrateAllowingReplenishRoundTrip(): MigrationResult {
        var result = migrate()
        var retries = 0
        while (result is MigrationResult.Unmoved.Failed.NoSpareConnectionId && retries < REPLENISH_RETRIES) {
            delay(REPLENISH_BACKOFF)
            result = migrate()
            retries++
        }
        return result
    }

    private fun ReadResult.text(): String = if (this is ReadResult.Data) buffer.readString(buffer.remaining(), Charset.UTF8) else NO_DATA

    private suspend fun QuicByteStream.writeString(
        payload: String,
        timeout: Duration,
    ) {
        val out = BufferFactory.deterministic().allocate(payload.length)
        out.writeString(payload, Charset.UTF8)
        out.resetForRead()
        write(out, timeout)
    }

    private suspend fun QuicByteStream.echo(payload: String): String {
        writeString(payload, 5.seconds)
        return read(30.seconds).text()
    }

    private companion object {
        /**
         * The `active_connection_id_limit` this suite pins, deliberately below the shipped default —
         * see the comment at its use. quiche sizes both the CID table and `max_concurrent_paths` from
         * it, so at most `PINNED_CID_LIMIT - 1` spare destination CIDs can be outstanding at once.
         */
        const val PINNED_CID_LIMIT = 4L

        /**
         * Unanswerable probes to run before lifting the block: one past exhaustion of the pinned pool.
         * Unpatched, the pool is empty and the path table is full of un-evictable `Failed` paths before
         * the block is lifted.
         */
        const val FAILED_ATTEMPTS = PINNED_CID_LIMIT.toInt()

        /** Bounded retries for the RETIRE → NEW_CONNECTION_ID round trip. 2s total, on loopback. */
        const val REPLENISH_RETRIES = 40
        val REPLENISH_BACKOFF = 50.milliseconds

        /** A read that ended or timed out, kept distinct from any payload the test actually sends. */
        const val NO_DATA = "no_data"
    }
}
