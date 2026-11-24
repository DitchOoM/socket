package com.ditchoom.socket.quic.core.edge

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.socket.TransportConfig
import com.ditchoom.socket.quic.QuicCloseChoice
import com.ditchoom.socket.quic.QuicConnect
import com.ditchoom.socket.quic.QuicConnection
import com.ditchoom.socket.quic.QuicEndpoint
import com.ditchoom.socket.quic.QuicError
import com.ditchoom.socket.quic.QuicListener
import com.ditchoom.socket.quic.QuicOptions
import com.ditchoom.socket.quic.QuicPeer
import com.ditchoom.socket.quic.QuicServe
import com.ditchoom.socket.quic.QuicTlsConfig
import com.ditchoom.socket.quic.QuicheApi
import com.ditchoom.socket.quic.raceCandidates
import com.ditchoom.socket.quic.resolveStreamReadPolicy
import com.ditchoom.socket.quic.resolveStreamWritePolicy
import com.ditchoom.socket.quic.toPathKey
import com.ditchoom.socket.udp.UdpSocket
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlin.time.Duration

/** The quiche binding of this platform, loaded where the setup lane runs it. */
internal expect val coreQuicheApi: QuicheApi

/**
 * Connects to [peer] on the core, racing its candidates; a connect whose options cannot be honoured is refused before
 * any name is resolved or socket opened. Each candidate is one core connect; a candidate that completes after the race
 * was decided is closed at once.
 */
internal suspend fun connectOnTheCore(
    peer: QuicPeer,
    options: QuicOptions,
    transport: TransportConfig,
    timeout: Duration,
    factory: BufferFactory,
): QuicConnect<CoreQuicConnection> {
    when (val verdict = verdictOn(options)) {
        is OptionVerdict.Refused -> return QuicConnect.Refused(verdict.refusal)
        is OptionVerdict.Honoured -> Unit
    }
    val api = withContext(setupLane) { coreQuicheApi }
    val raced =
        peer.raceCandidates(
            transport,
            close = { late: CoreQuicConnection -> late.close(QuicCloseChoice.Immediate()) },
            attempt = { endpoint -> connectOne(endpoint, peer.serverName, options, api, timeout, factory) },
        )
    return QuicConnect.Connected(raced.connection, raced.candidateRace)
}

/** [connectOnTheCore], then [block] as the connection's block; see [CoreQuicConnection.asBlock]. */
internal suspend fun <R> connectOnTheCore(
    peer: QuicPeer,
    options: QuicOptions,
    transport: TransportConfig,
    timeout: Duration,
    factory: BufferFactory,
    block: suspend (QuicConnection) -> R,
): QuicConnect<R> =
    when (val connected = connectOnTheCore(peer, options, transport, timeout, factory)) {
        is QuicConnect.Refused -> connected
        is QuicConnect.Connected -> QuicConnect.Connected(connected.value.asBlock(block), connected.race)
    }

/** One core connect to [endpoint], handed out once its handshake completed; a cancelled attempt leaves nothing open. */
private suspend fun connectOne(
    endpoint: QuicEndpoint,
    serverName: String,
    options: QuicOptions,
    api: QuicheApi,
    timeout: Duration,
    factory: BufferFactory,
): CoreQuicConnection {
    val client =
        coreConnect(
            host = endpoint.address.ip,
            port = endpoint.port,
            serverName = serverName,
            options = options,
            factory = factory,
            api = api,
            timeout = timeout,
            readPolicy = resolveStreamReadPolicy(options),
            writePolicy = resolveStreamWritePolicy(options),
        )
    var handed = Handed.No
    try {
        return client.connection.public().also { handed = Handed.Yes }
    } finally {
        when (handed) {
            Handed.Yes -> Unit
            Handed.No -> client.connection.closeNow(QuicError.NoError)
        }
    }
}

/** Whether a connected attempt reached its caller. */
private enum class Handed { No, Yes }

/** Serves on [host]:[port] on the core; a serve whose options cannot be honoured is refused before any socket opens. */
internal suspend fun serveOnTheCore(
    port: Int,
    host: String,
    tls: QuicTlsConfig,
    options: QuicOptions,
    factory: BufferFactory,
): QuicServe<CoreListener> {
    when (val verdict = verdictOn(options)) {
        is OptionVerdict.Refused -> return QuicServe.Refused(verdict.refusal)
        is OptionVerdict.Honoured -> Unit
    }
    val (api, bind) = withContext(setupLane) { coreQuicheApi to UdpSocket.resolve(host, port).toPathKey() }
    val server =
        coreServe(
            bind = bind,
            tls = tls,
            options = options,
            factory = factory,
            api = api,
            readPolicy = resolveStreamReadPolicy(options),
            writePolicy = resolveStreamWritePolicy(options),
        )
    return QuicServe.Serving(CoreListener(server))
}

/** [serveOnTheCore], then [block] with the listener, which closes when [block] ends. */
internal suspend fun <R> serveOnTheCore(
    port: Int,
    host: String,
    tls: QuicTlsConfig,
    options: QuicOptions,
    factory: BufferFactory,
    block: suspend (QuicListener) -> R,
): QuicServe<R> =
    when (val serving = serveOnTheCore(port, host, tls, options, factory)) {
        is QuicServe.Refused -> serving
        is QuicServe.Serving -> {
            val listener = serving.value
            try {
                QuicServe.Serving(block(listener))
            } finally {
                withContext(NonCancellable) { listener.close() }
            }
        }
    }
