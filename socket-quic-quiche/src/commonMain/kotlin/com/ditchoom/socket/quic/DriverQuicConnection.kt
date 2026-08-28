@file:OptIn(ExperimentalDatagramApi::class)

package com.ditchoom.socket.quic

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.flow.ConnectedDatagramChannel
import com.ditchoom.buffer.flow.ExperimentalDatagramApi
import com.ditchoom.buffer.flow.SocketAddress
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.ClosedSendChannelException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.consumeAsFlow

/**
 * Server-side QUIC connection backed by a [QuicheDriver]. Minted per accepted connection by
 * [SharedQuicheServer.connections]; the driver handles the platform differences, so this wrapper is
 * common across JVM/Android, Linux, and Apple (it replaced three byte-identical per-platform copies).
 */
internal class DriverQuicConnection(
    private val driver: QuicheDriver,
    override val bufferFactory: BufferFactory,
    override val remoteAddress: SocketAddress,
    connectionScope: CoroutineScope,
) : QuicConnection,
    QuicheBackedConnection,
    CoroutineScope by connectionScope {
    override val state: StateFlow<QuicConnectionState> = driver.state

    override val quicheDriver: QuicheDriver get() = driver

    /**
     * Session id is cached by the driver (it never changes); the wire CID is re-read on every access
     * because it rotates — so this is rebuilt per read rather than stored.
     */
    override val identity: QuicConnectionIdentity
        get() = QuicConnectionIdentity(session = driver.sessionId, wire = driver.wireConnectionId)

    private val datagramAdapter = DriverDatagramAdapter(driver, remoteAddress)

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

    override suspend fun acceptStream(): QuicByteStream = driver.acceptIncomingStream()

    override fun streams(): Flow<QuicByteStream> = driver.incomingStreams.consumeAsFlow()

    override fun datagramChannel(): ConnectedDatagramChannel = datagramAdapter

    /**
     * RFC 9000 §9 is client-only in QUIC v1, and this is the server-accepted side — stated here rather
     * than inherited from [QuicScope]'s default, which would answer
     * [MigrationResult.Unmoved.Impossible.BackendCannotMigrate] and blame the backend for a role
     * constraint. The driver behind this connection is built with
     * [MigrationCapability.ServerConnection] and would answer the same; overriding here means a server
     * connection never has to reach the driver to learn it.
     *
     * ([networkAtClose] is deliberately left at the [QuicConnection] default: a server has no local
     * client network path to correlate against, which is the same reason the connectivity tap is never
     * wired from `bind`.)
     */
    override suspend fun migrate(target: MigrationTarget): MigrationResult = MigrationResult.Unmoved.Impossible.ServerConnection

    override suspend fun close(error: QuicError) {
        try {
            val deferred = CompletableDeferred<Unit>()
            driver.commands.send(QuicheCmd.Close(error, deferred))
            deferred.await()
        } catch (_: ClosedSendChannelException) {
            // Already closed
        }
        driver.destroy()
    }
}
