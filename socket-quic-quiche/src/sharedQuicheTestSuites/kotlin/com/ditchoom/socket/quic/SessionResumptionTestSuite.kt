package com.ditchoom.socket.quic

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.Charset
import com.ditchoom.buffer.ReadBuffer
import com.ditchoom.buffer.deterministic
import com.ditchoom.buffer.flow.writeFully
import com.ditchoom.socket.IpFamily
import com.ditchoom.socket.ResolvedAddress
import com.ditchoom.socket.TransportConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlin.concurrent.atomics.AtomicInt
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.concurrent.atomics.incrementAndFetch
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * A 0-RTT early-data block belongs to a connection attempt, not to a connect: a connect that races its candidates runs
 * the caller's block once per candidate it reaches, on a real quiche client and server over loopback. A suite with
 * per-platform members because each backend binds the session FFI separately: JVM (JNI by default, FFM with
 * `-PquicheJvmBackend=ffm`), Apple cinterop, Linux cinterop and Android JNI.
 */
abstract class SessionResumptionTestSuite {
    abstract fun testTlsConfig(): QuicTlsConfig

    /** Same hook as the other suites: the JVM/Android members turn a missing native into a typed skip. */
    protected open suspend fun wrapTestBody(block: suspend () -> Unit): Unit = block()

    private val clientOptions =
        QuicOptions(
            alpnProtocols = listOf(PROTOCOL),
            verifyPeer = false,
            idleTimeout = 10.seconds,
            migration = MigrationPolicy.Manual,
        )

    private val acceptsEarlyData = clientOptions.copy(enableEarlyData = true)

    // ---- the exchange ---------------------------------------------------------------------------

    /** What the server application saw on one accepted connection. */
    private data class ServerSeen(
        val resumption: QuicResumptionOutcome,
        val earlyStreamData: QuicheDriver.EarlyStreamData,
        val payload: String,
    )

    private class Served(
        val job: kotlinx.coroutines.Job,
        private val observations: Channel<ServerSeen>,
    ) {
        suspend fun next(): ServerSeen = withTimeout(15.seconds) { observations.receive() }
    }

    /**
     * Serve every connection: read one stream to its end, reply `ack:<payload>`, and ship what was seen
     * out of the handler — an assertion thrown inside a launched handler would cancel its siblings and
     * hang the client instead of reporting.
     */
    private fun CoroutineScope.serveEach(server: QuicServer): Served {
        val observations = Channel<ServerSeen>(Channel.UNLIMITED)
        val job =
            launch {
                server.connections {
                    val stream = acceptStream()
                    val payload = stream.readToEnd()
                    stream.writeText("ack:$payload")
                    stream.shutdownSend()
                    val driver = (this as QuicheBackedConnection).quicheDriver
                    observations.send(ServerSeen(resumption, driver.earlyStreamData, payload))
                }
            }
        return Served(job, observations)
    }

    @Test
    @OptIn(ExperimentalAtomicApi::class)
    fun anEarlyDataBlockRunsOncePerCandidateTheRaceReaches() =
        runQuicTest(timeout = 60.seconds) {
            wrapTestBody {
                withQuicServer(port = 0, tlsConfig = testTlsConfig(), quicOptions = acceptsEarlyData) {
                    val seen = serveEach(this)
                    try {
                        val ticket = firstConnection(port)
                        seen.next()

                        val runs = AtomicInt(0)
                        val options =
                            clientOptions.copy(
                                resumption =
                                    QuicResumption.ResumeWithEarlyData(ticket) {
                                        runs.incrementAndFetch()
                                        val stream = openStream()
                                        stream.writeText(EARLY_PAYLOAD)
                                        stream.shutdownSend()
                                    },
                            )
                        // A blackhole first, so the race really reaches both candidates: the block runs at
                        // the top of every attempt, before that attempt has heard anything from the network,
                        // and the second candidate starts one attempt delay later and wins.
                        val peer =
                            QuicPeer.Candidates(
                                listOf(
                                    QuicEndpoint(ResolvedAddress(BLACKHOLE, IpFamily.V4), port),
                                    QuicEndpoint(ResolvedAddress("127.0.0.1", IpFamily.V4), port),
                                ),
                                serverName = "127.0.0.1",
                            )
                        val race = withQuicConnection(peer, options, TransportConfig(), timeout = 30.seconds) { it }

                        assertIs<QuicCandidateRace.Raced>(race, "two candidates were offered, so this was a race")
                        assertEquals(
                            2,
                            runs.load(),
                            "a 0-RTT block belongs to a connection ATTEMPT, not to a connect: each attempt is " +
                                "its own connection with its own first flight, so a raced connect runs the " +
                                "caller's block once per candidate it reaches. Its documentation is what tells " +
                                "callers to write it so that is safe; this is what makes the claim true. " +
                                "Race: $race",
                        )
                    } finally {
                        seen.job.cancel()
                    }
                }
            }
        }

    /** A full-handshake connection that exchanges once and returns the ticket the server issued it. */
    private suspend fun firstConnection(port: Int): QuicSessionTicket =
        withQuicConnection("127.0.0.1", port, clientOptions, timeout = 10.seconds) {
            assertEquals(QuicResumptionOutcome.FullHandshake(QuicFullHandshakeReason.NoTicketOffered), resumption, "no ticket was offered")
            openStream().exchange(FIRST_PAYLOAD)
            val issued = withTimeout(10.seconds) { sessionTicket.first { it is QuicSessionTicketState.Issued } }
            assertIs<QuicSessionTicketState.Issued>(issued).ticket
        }

    private suspend fun QuicByteStream.exchange(payload: String): String {
        writeText(payload)
        shutdownSend()
        return readToEnd()
    }

    private suspend fun QuicByteStream.writeText(payload: String) {
        writeFully(text(payload), STREAM_DEADLINE)
    }

    private suspend fun QuicByteStream.readToEnd(): String {
        val sb = StringBuilder()
        while (true) {
            when (val chunk = read(STREAM_DEADLINE) { it.readString(it.remaining(), Charset.UTF8) }) {
                is ScopedReadResult.Data -> sb.append(chunk.value)
                ScopedReadResult.End, ScopedReadResult.Reset -> return sb.toString()
            }
        }
    }

    private fun text(payload: String): ReadBuffer {
        val out = BufferFactory.deterministic().allocate(payload.length)
        out.writeString(payload, Charset.UTF8)
        out.resetForRead()
        return out
    }

    private companion object {
        const val PROTOCOL = "resume-test"

        /** RFC 5737 documentation address: nothing routes it, so a candidate there never answers. */
        const val BLACKHOLE = "192.0.2.1"
        const val FIRST_PAYLOAD = "first"
        const val EARLY_PAYLOAD = "early-hello"

        val STREAM_DEADLINE: Duration = 10.seconds
    }
}
