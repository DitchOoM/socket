package com.ditchoom.socket.quic

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.Default
import com.ditchoom.buffer.flow.ReadPolicy
import com.ditchoom.buffer.flow.WritePolicy
import com.ditchoom.socket.quic.core.edge.coreConnect
import com.ditchoom.socket.testkit.walk.LaneEngine
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
        block: suspend QuicScope.() -> R,
    ): R
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
        block: suspend QuicScope.() -> R,
    ): R = withQuicConnection(host, port, options, timeout = timeout) { block() }
}

/**
 * The run-to-completion core client. It has no connection migration yet, so a handoff ends the connection: the
 * block is cancelled and this throws the [QuicCloseException] the core ended it with, which the lane logs as
 * `CONNECTION-DEAD reason=…` and reconnects from.
 *
 * The core API this depends on (package `com.ditchoom.socket.quic.core.edge`), all of it here:
 * - `coreConnect(host, port, serverName, options, factory, api, timeout, readPolicy, writePolicy)` returning a
 *   client whose `.connection` is a [QuicConnection]
 * - `QuicConnection.openStream()`, `.identity`, `.negotiatedAlpn`, `.pathState`, `.state`, `.close(QuicError)`
 * - the streams' `read` / `write` through [QuicByteStream], unchanged
 * The first one is the only name that is not public API; the rest are the [QuicConnection] contract.
 */
private object CoreEngine : ProbeEngine {
    override suspend fun <R> connect(
        host: String,
        port: Int,
        options: QuicOptions,
        timeout: Duration,
        block: suspend QuicScope.() -> R,
    ): R {
        // Only what the core applies: the trace, migration and standby options belong to the 4.x driver.
        val coreOptions =
            QuicOptions(
                alpnProtocols = options.alpnProtocols,
                verifyPeer = options.verifyPeer,
                idleTimeout = options.idleTimeout,
                keepAliveInterval = options.keepAliveInterval,
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
        try {
            return connection.runUntilClosed(coreOptions.idleTimeout) { block() }
        } finally {
            connection.close(QuicError.NoError)
        }
    }

    private val HANDSHAKE_TIMEOUT = 15.seconds
    private val READ_BOUND = 30.seconds
}
