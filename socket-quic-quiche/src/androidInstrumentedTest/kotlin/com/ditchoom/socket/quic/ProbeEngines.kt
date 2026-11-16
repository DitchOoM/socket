package com.ditchoom.socket.quic

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.Default
import com.ditchoom.buffer.flow.ReadPolicy
import com.ditchoom.buffer.flow.WritePolicy
import com.ditchoom.socket.quic.core.edge.EdgeEnd
import com.ditchoom.socket.quic.core.edge.coreConnect
import com.ditchoom.socket.quic.core.life.ConnEnd
import com.ditchoom.socket.quic.trace.QuicTraceCapture
import com.ditchoom.socket.testkit.trace.TraceSink
import com.ditchoom.socket.testkit.walk.LaneEngine
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * What a walk lane needs of a client stack, and nothing more: connect, run [block] for as long as the connection
 * lives, close. [DeviceHandoffProbe] talks only to this, so re-targeting a lane at another stack is one more
 * implementation here.
 *
 * The block runs on a [QuicScope], which is all the probe reads: `identity`, `negotiatedAlpn`, `pathState`,
 * `openStream()` and `launch`. A connection that dies under the block surfaces as a [QuicCloseException] carrying the
 * typed [QuicCloseReason], exactly as `withQuicConnection` does.
 */
internal interface ProbeEngine {
    suspend fun <R> connect(
        host: String,
        port: Int,
        options: QuicOptions,
        timeout: Duration,
        block: suspend QuicScope.(EndNote) -> R,
    ): R
}

/**
 * How the lane's connection ended, as the engine names it: empty while it is open, and always empty for the 4.x
 * client, whose [QuicCloseReason] already is the whole story. The probe puts it on the `CONNECTION-DEAD` line.
 */
internal fun interface EndNote {
    fun now(): String
}

internal fun LaneEngine.probeEngine(): ProbeEngine =
    when (this) {
        LaneEngine.Quiche4 -> Quiche4Engine
        LaneEngine.Core -> CoreEngine
    }

/** The 4.x client: `withQuicConnection`, which owns the whole lifecycle. */
private object Quiche4Engine : ProbeEngine {
    override suspend fun <R> connect(
        host: String,
        port: Int,
        options: QuicOptions,
        timeout: Duration,
        block: suspend QuicScope.(EndNote) -> R,
    ): R = withQuicConnection(host, port, options, timeout = timeout) { block(EndNote { "" }) }
}

/**
 * The run-to-completion core client. It has no connection migration yet, so a handoff ends the connection: the
 * block is cancelled and this throws the [QuicCloseException] the core ended it with, which the lane logs as
 * `CONNECTION-DEAD reason=[end=<ConnEnd>] …` and reconnects from.
 *
 * The core edge this depends on (package `com.ditchoom.socket.quic.core.edge`):
 * - `coreConnect(host, port, serverName, options, factory, api, timeout, readPolicy, writePolicy)`, whose
 *   `.connection` is a [QuicConnection]
 * - `CoreConnection.end()`, an `EdgeEnd.Ended(ConnEnd)` once the connection is over, and `dumpFlight(sink)`
 * - the streams' `read` / `write` through [QuicByteStream], unchanged
 *
 * `options.trace` goes to the core as given: the core records its connection's trace through the same capture the 4.x
 * lanes use, so the walk's per-connection trace files and budget need no second path.
 */
private object CoreEngine : ProbeEngine {
    override suspend fun <R> connect(
        host: String,
        port: Int,
        options: QuicOptions,
        timeout: Duration,
        block: suspend QuicScope.(EndNote) -> R,
    ): R {
        // The sink the core records this connection into, kept so a connection that is still open when the walk
        // gives up on it can be asked for its flight recording too.
        var recording: TraceSink? = null
        val trace =
            options.trace?.let { capture ->
                QuicTraceCapture(
                    {
                        capture.captureFor().also { recording = it.sink }
                    },
                    capture.recordNetworkObservations,
                )
            }
        // Only what the core applies: migration and standby options belong to the 4.x driver.
        val coreOptions =
            QuicOptions(
                alpnProtocols = options.alpnProtocols,
                verifyPeer = options.verifyPeer,
                idleTimeout = options.idleTimeout,
                keepAliveInterval = options.keepAliveInterval,
                trace = trace,
            )
        val client =
            coreConnect(
                host = host,
                port = port,
                serverName = host,
                options = coreOptions,
                factory = BufferFactory.Default,
                api = loadQuicheApi(),
                timeout = HANDSHAKE_TIMEOUT,
                readPolicy = ReadPolicy.Bounded(READ_BOUND),
                writePolicy = WritePolicy.Bounded(READ_BOUND),
            )
        val connection = client.connection
        val note =
            EndNote {
                when (val end = connection.end()) {
                    EdgeEnd.Open -> ""
                    is EdgeEnd.Ended -> end.end.label()
                }
            }
        try {
            return connection.runUntilClosed(coreOptions.idleTimeout) { block(note) }
        } catch (e: Throwable) {
            // A connection that ended already recorded its tail into the trace; one still open (the scope failed
            // under it) has not, so ask for the recording before it is closed.
            val sink = recording
            if (sink != null && connection.end() == EdgeEnd.Open) {
                withContext(NonCancellable) { connection.dumpFlight(sink) }
            }
            throw e
        } finally {
            withContext(NonCancellable) { connection.close(QuicError.NoError) }
        }
    }

    private val HANDSHAKE_TIMEOUT = 15.seconds
    private val READ_BOUND = 30.seconds
}

/** The name analyze.py counts deaths by: the [ConnEnd] variant, nothing that varies per connection. */
private fun ConnEnd.label(): String = this::class.simpleName ?: "ConnEnd"
