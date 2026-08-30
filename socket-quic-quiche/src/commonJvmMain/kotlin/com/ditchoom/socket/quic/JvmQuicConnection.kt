@file:OptIn(ExperimentalDatagramApi::class)

package com.ditchoom.socket.quic

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.PlatformBuffer
import com.ditchoom.buffer.flow.ConnectedDatagramChannel
import com.ditchoom.buffer.flow.ExperimentalDatagramApi
import com.ditchoom.buffer.flow.SocketAddress
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.ClosedSendChannelException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.consumeAsFlow
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
    override val remoteAddress: SocketAddress,
    private val scope: CoroutineScope,
    private val onRelease: (() -> Unit)? = null,
) : QuicConnection,
    QuicheBackedConnection,
    CoroutineScope by scope {
    override val state: StateFlow<QuicConnectionState> = driver.state

    override val quicheDriver: QuicheDriver get() = driver

    /**
     * Session id is cached by the driver (it never changes); the wire CID is re-read on every access
     * because it rotates — so this is rebuilt per read rather than stored.
     */
    override val identity: QuicConnectionIdentity
        get() = QuicConnectionIdentity(session = driver.sessionId, wire = driver.wireConnectionId)

    /** quiche reads raw addresses through FFM/JNI — the one answer every driver-backed connection gives. */
    override val capabilities: QuicCapabilities get() = QuicheDriver.capabilities

    private val closed = AtomicBoolean(false)

    private val datagramAdapter = DriverDatagramAdapter(driver, remoteAddress)

    internal fun start() {
        driver.start(scope)
    }

    internal suspend fun awaitEstablished(timeout: Duration) = driver.awaitEstablished(timeout)

    override suspend fun openStream(): QuicByteStream = open(unidirectional = false)

    override suspend fun openUniStream(): QuicByteStream = open(unidirectional = true)

    private suspend fun open(unidirectional: Boolean): QuicByteStream {
        try {
            val deferred = CompletableDeferred<StreamSlot>()
            driver.commands.send(QuicheCmd.OpenStream(deferred, unidirectional))
            val slot = deferred.await()
            val adapter = DriverStreamAdapter(driver, slot)
            return QuicByteStream(
                slot.id,
                QuicheStreamByteStream(
                    slot.id,
                    adapter,
                    driver.streamReadPool,
                    readPolicy = driver.streamReadPolicy,
                    writePolicy = driver.streamWritePolicy,
                ),
            )
        } catch (_: ClosedSendChannelException) {
            throw driver.connectionClosed()
        }
    }

    /**
     * Read the peer's leaf certificate DER into [der] (capacity [capacity]),
     * routed through the driver so the `quiche_conn_peer_cert` read is serialized with all other
     * conn access. Returns the DER length (snprintf-style: copied when <= [capacity], else re-read at
     * the returned size; 0 = no peer certificate). Used by the post-handshake serverCertificateHashes
     * verifier. Throws if the backend cannot read the peer certificate (e.g. JNI/Android until step 2).
     */
    internal suspend fun readPeerCertDer(
        der: PlatformBuffer,
        capacity: Int,
    ): Int {
        val deferred = CompletableDeferred<Int>()
        // The buffer travels with its address (#366): quiche writes the DER into it on the driver
        // loop, so what keeps that memory mapped has to reach the driver too. See [QuicheMemory].
        driver.commands.send(QuicheCmd.PeerCert(der.driverOwnedMemory(), capacity, deferred))
        return deferred.await()
    }

    override suspend fun acceptStream(): QuicByteStream = driver.acceptIncomingStream()

    override fun streams(): Flow<QuicByteStream> = driver.incomingStreams.consumeAsFlow()

    override fun datagramChannel(): ConnectedDatagramChannel = datagramAdapter

    override val pathState: StateFlow<QuicPathState> = driver.pathState

    override val networkAtClose: NetworkAtClose get() = driver.networkAtClose

    override suspend fun migrate(target: MigrationTarget): MigrationResult =
        try {
            val deferred = CompletableDeferred<MigrationResult>()
            driver.commands.send(QuicheCmd.Migrate(target, deferred))
            // Suspends until the path has validated and the active path has switched, or the attempt
            // has failed — the property the automatic reactor relies on instead of a quiet period.
            deferred.await()
        } catch (_: ClosedSendChannelException) {
            MigrationResult.Unmoved.Impossible.ConnectionClosed
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
