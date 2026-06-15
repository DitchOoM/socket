package com.ditchoom.socket.quic

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.ReadBuffer
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.ClosedSendChannelException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.consumeAsFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeout
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.time.Duration

/**
 * JVM QUIC connection backed by [QuicheDriver].
 *
 * Implements [QuicConnection] (the engine-SPI return type) which extends [QuicScope] (public).
 * Users only see [QuicScope] inside the [withQuicConnection] block.
 *
 * [onRelease] is the per-connection lifecycle teardown the engine wires in (cancel the connection
 * scope, close the UDP channel, free the quiche config) — invoked once by [close]. It is null for
 * the unit-test constructor, which owns those resources externally and only wants the driver torn
 * down.
 */
internal class JvmQuicConnection(
    private val driver: QuicheDriver,
    override val bufferFactory: BufferFactory,
    private val scope: CoroutineScope,
    private val onRelease: (() -> Unit)? = null,
) : QuicConnection,
    CoroutineScope by scope {
    override val state: StateFlow<QuicConnectionState> = driver.state

    private val closed = AtomicBoolean(false)

    private val datagramAdapter = DriverDatagramAdapter(driver, bufferFactory)

    internal fun start() {
        driver.start(scope)
    }

    internal suspend fun awaitEstablished(timeout: Duration) {
        withTimeout(timeout) {
            state.first { it !is QuicConnectionState.Handshaking }
        }
    }

    override suspend fun openStream(): QuicByteStream = open(unidirectional = false)

    override suspend fun openUniStream(): QuicByteStream = open(unidirectional = true)

    private suspend fun open(unidirectional: Boolean): QuicByteStream {
        try {
            val deferred = CompletableDeferred<StreamSlot>()
            driver.commands.send(QuicheCmd.OpenStream(deferred, unidirectional))
            val slot = deferred.await()
            val adapter = DriverStreamAdapter(driver, slot)
            return QuicByteStream(slot.id, QuicheStreamByteStream(slot.id, adapter, bufferFactory))
        } catch (_: ClosedSendChannelException) {
            throw QuicCloseException(driver.closeReasonOr(QuicError.NoError), "connection closed")
        }
    }

    override suspend fun acceptStream(): QuicByteStream = driver.incomingStreams.receive()

    override fun streams(): Flow<QuicByteStream> = driver.incomingStreams.consumeAsFlow()

    override suspend fun sendDatagram(buffer: ReadBuffer) = datagramAdapter.sendDatagram(buffer)

    override suspend fun receiveDatagram(): DatagramReceiveResult = datagramAdapter.receiveDatagram()

    override fun datagrams(): Flow<ReadBuffer> = datagramAdapter.datagrams()

    override fun maxDatagramSize(): MaxDatagramSize = datagramAdapter.maxDatagramSize()

    override val pathState: StateFlow<PathInfo> = driver.pathState

    override suspend fun migrate(
        localHost: String?,
        localPort: Int,
    ): MigrationResult =
        try {
            val deferred = CompletableDeferred<MigrationResult>()
            driver.commands.send(QuicheCmd.Migrate(localHost, localPort, deferred))
            deferred.await()
        } catch (_: ClosedSendChannelException) {
            MigrationResult.Failed("connection closed")
        }

    /**
     * Driver-level terminal close — sends CONNECTION_CLOSE([error]) and destroys the driver, but
     * does NOT run [onRelease]. This is the user-callable mid-block path ([closeWithError]): the
     * caller's block is still running on [scope], so the connection scope must NOT be cancelled here.
     */
    private suspend fun driverClose(error: QuicError) {
        try {
            val deferred = CompletableDeferred<Unit>()
            driver.commands.send(QuicheCmd.Close(error, deferred))
            deferred.await()
        } catch (_: ClosedSendChannelException) {
            // Already closed
        }
        driver.destroy()
    }

    /**
     * Application-coded close (RFC 9000 §19.19), user-callable from inside the block. Driver-only —
     * the lifecycle teardown ([onRelease]) runs later when the [withQuicConnection] wrapper calls
     * [close] after the block returns. Overrides the [QuicConnection] default (which would route
     * through [close] and prematurely tear down the running scope).
     */
    override suspend fun closeWithError(errorCode: Long) = driverClose(QuicError.ApplicationError(errorCode))

    /**
     * Full lifecycle teardown — the [withQuicConnection] wrapper's `finally` calls this after the
     * block returns: driver close, then [onRelease] (scope cancel + UDP close + config free).
     * Idempotent; a no-op once already torn down (e.g. after a mid-block [closeWithError]).
     */
    override suspend fun close(error: QuicError) {
        if (!closed.compareAndSet(false, true)) return
        try {
            driverClose(error)
        } finally {
            onRelease?.invoke()
        }
    }
}
