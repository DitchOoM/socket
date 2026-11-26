package com.ditchoom.socket.quic.core.edge

import com.ditchoom.socket.quic.NssKeyLog
import com.ditchoom.socket.quic.QuicError
import com.ditchoom.socket.quic.core.demux.withCoreEchoServer
import com.ditchoom.socket.quic.core.testing.coreTest
import com.ditchoom.socket.quic.core.testing.filesByName
import com.ditchoom.socket.quic.core.testing.scratchDirectory
import com.ditchoom.socket.quic.trace.QlogDeclaration
import com.ditchoom.socket.quic.trace.QlogTarget
import com.ditchoom.socket.quic.trace.QuicConnectionCapture
import com.ditchoom.socket.quic.trace.QuicTraceCapture
import com.ditchoom.socket.quic.trace.TrafficSecretsLog
import com.ditchoom.socket.testkit.trace.TraceEvent
import com.ditchoom.socket.testkit.trace.TraceSink
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.job
import kotlinx.coroutines.withTimeout
import kotlin.concurrent.atomics.AtomicReference
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail
import kotlin.time.Duration.Companion.seconds

/**
 * A core connection whose capture names a [TrafficSecretsLog.File] leaves the TLS secrets that decrypt it there, in the NSS
 * key log format Wireshark reads; one that names none leaves nothing, and one whose file cannot be opened carries on and
 * says so in its trace.
 *
 * Every backend binds `quiche_conn_set_keylog_path` and `quiche_config_log_keys` separately (JNI shim, FFM, two cinterops),
 * so these run on each. The JVM-only suite beside them reads the environment door and decrypts the recorded datagrams.
 */
@OptIn(ExperimentalAtomicApi::class)
class CoreKeyLogTests {
    private class Recording : TraceSink {
        private val recorded = AtomicReference(emptyList<TraceEvent>())

        val events: List<TraceEvent> get() = recorded.load()

        override fun emit(event: TraceEvent) {
            while (true) {
                val current = recorded.load()
                if (recorded.compareAndSet(current, current + event)) return
            }
        }
    }

    /** One client connection to a core server that echoes [PAYLOAD]; both connections are freed when this returns. */
    private suspend fun echoOnce(capture: QuicConnectionCapture): String {
        val options = coreTestOptions.copy(trace = QuicTraceCapture(captureFor = { capture }, qlog = QlogDeclaration.NotBudgeted))
        val accepted = CompletableDeferred<CoreConnection>()
        var reply = ""
        withCoreEchoServer(onAccepted = { accepted.complete(it) }) { server ->
            val client = connectCore(server.port, options = options)
            reply = echoOver(client.connection.openStream(), PAYLOAD.encodeToByteArray()).decodeToString()
            client.connection.close(QuicError.NoError)
            freed(client.connection)
        }
        freed(accepted.await())
        return reply
    }

    /** quiche flushes its files when it frees the connection, which the connection's scope ends after. */
    private suspend fun freed(connection: CoreConnection) = withTimeout(10.seconds) { connection.coroutineContext.job.join() }

    private val required =
        listOf("CLIENT_HANDSHAKE_TRAFFIC_SECRET", "SERVER_HANDSHAKE_TRAFFIC_SECRET", "CLIENT_TRAFFIC_SECRET_0", "SERVER_TRAFFIC_SECRET_0")

    @Test
    fun aNamedTrafficSecretsLogHoldsTheConnectionsTlsSecretsInNssFormat() =
        coreTest("CoreKeyLogTests.aNamedTrafficSecretsLogHoldsTheConnectionsTlsSecretsInNssFormat") {
            val dir = scratchDirectory("core-keylog")
            val sink = Recording()
            assertEquals(PAYLOAD, echoOnce(QuicConnectionCapture(sink, trafficSecrets = TrafficSecretsLog.File("$dir/conn-0001.keys"))))
            val keyLog = filesByName(dir)["conn-0001.keys"] ?: fail("no key log; the directory holds ${filesByName(dir).keys}")
            val entries = NssKeyLog.parse(keyLog)
            assertTrue(entries.map { it.label }.containsAll(required), "key log carries ${entries.map { it.label }}:\n$keyLog")
            assertEquals(1, entries.map { it.clientRandom }.toSet().size, "one client random:\n$keyLog")
            assertTrue(sink.events.none { it is TraceEvent.TrafficSecretsRefused }, "an opened key log is not refused")
        }

    @Test
    fun noTrafficSecretsAreWrittenUnlessAFileIsNamed() =
        coreTest("CoreKeyLogTests.noTrafficSecretsAreWrittenUnlessAFileIsNamed") {
            val dir = scratchDirectory("core-keylog")
            val capture = QuicConnectionCapture(Recording(), qlog = QlogTarget.File("$dir/conn-0001.sqlog"))
            assertEquals(TrafficSecretsLog.Off, capture.trafficSecrets)
            assertEquals(PAYLOAD, echoOnce(capture))
            val files = filesByName(dir)
            assertEquals(setOf("conn-0001.sqlog"), files.keys)
            assertTrue(files.values.none { body -> required.any { it in body } }, "no file may carry TLS secrets: ${files.keys}")
        }

    @Test
    fun aKeyLogThatCannotBeOpenedIsReportedInTheTraceAndTheConnectionCarriesOn() =
        coreTest("CoreKeyLogTests.aKeyLogThatCannotBeOpenedIsReportedInTheTraceAndTheConnectionCarriesOn") {
            val path = "${scratchDirectory("core-keylog")}/no-such-directory/conn-0001.keys"
            val sink = Recording()
            val reply = echoOnce(QuicConnectionCapture(sink, trafficSecrets = TrafficSecretsLog.File(path)))
            assertEquals(PAYLOAD, reply, "a key log that cannot be opened must never cost the connection")
            assertEquals(listOf(path), sink.events.filterIsInstance<TraceEvent.TrafficSecretsRefused>().map { it.path })
        }

    private companion object {
        const val PAYLOAD = "decrypt me offline"
    }
}
