@file:OptIn(com.ditchoom.buffer.flow.ExperimentalDatagramApi::class)

package com.ditchoom.socket.quic.core.edge

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.flow.AddressedDatagramChannel
import com.ditchoom.socket.TransportConfig
import com.ditchoom.socket.quic.QuicCandidateRace
import com.ditchoom.socket.quic.QuicCloseChoice
import com.ditchoom.socket.quic.QuicConnect
import com.ditchoom.socket.quic.QuicEndpoint
import com.ditchoom.socket.quic.QuicError
import com.ditchoom.socket.quic.QuicOptions
import com.ditchoom.socket.quic.QuicPeer
import com.ditchoom.socket.quic.QuicRefusal
import com.ditchoom.socket.quic.QuicServe
import com.ditchoom.socket.quic.QuicTlsConfig
import com.ditchoom.socket.quic.QuicheApi
import com.ditchoom.socket.quic.raceCandidates
import com.ditchoom.socket.quic.resolveStreamReadPolicy
import com.ditchoom.socket.quic.resolveStreamWritePolicy
import com.ditchoom.socket.quic.toPathKey
import com.ditchoom.socket.udp.UdpSocket
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

/**
 * Connects to [peer] over [channel], which the caller lent and keeps, as on a port it shares with ICE and STUN: one
 * attempt, no race. The connection never closes the channel and never moves off it. A channel that already carries a
 * QUIC connection or listener is refused ([QuicRefusal.ChannelInUse]); so is a connect whose options cannot be honoured,
 * by [coreConnect] before anything is sent.
 */
internal suspend fun connectOverLent(
    channel: AddressedDatagramChannel,
    peer: QuicEndpoint,
    serverName: String,
    options: QuicOptions,
    timeout: Duration,
    factory: BufferFactory,
): QuicConnect<CoreQuicConnection> {
    val lent =
        when (val claimed = LentChannels.claim(channel)) {
            is Claimed.Lent -> claimed.channel
            is Claimed.InUse -> return QuicConnect.Refused(QuicRefusal.ChannelInUse)
        }
    var handed = Handed.No
    try {
        val api = withContext(setupLane) { coreQuicheApi }
        val client =
            coreConnect(
                host = peer.address.ip,
                port = peer.port,
                serverName = serverName,
                options = options,
                factory = factory,
                api = api,
                timeout = timeout,
                readPolicy = resolveStreamReadPolicy(options),
                writePolicy = resolveStreamWritePolicy(options),
                ride = ClientRide.Lent(lent),
            )
        return QuicConnect.Connected(client.connection.public(), QuicCandidateRace.Unopposed(peer)).also { handed = Handed.Yes }
    } finally {
        // A claim the io took is the io's to give back, once the connection has let go of the channel.
        if (handed == Handed.No) lent.claim.entryFailed()
    }
}

/**
 * Serves on [channel], which the caller lent and keeps. A channel that already carries a QUIC connection or listener is
 * refused ([QuicRefusal.ChannelInUse]); so is a serve whose options cannot be honoured, by [coreServeLent].
 */
internal suspend fun serveOverLent(
    channel: AddressedDatagramChannel,
    tls: QuicTlsConfig,
    options: QuicOptions,
    factory: BufferFactory,
): QuicServe<CoreListener> {
    val lent =
        when (val claimed = LentChannels.claim(channel)) {
            is Claimed.Lent -> claimed.channel
            is Claimed.InUse -> return QuicServe.Refused(QuicRefusal.ChannelInUse)
        }
    var handed = Handed.No
    try {
        val api = withContext(setupLane) { coreQuicheApi }
        val server = coreServeLent(lent, tls, options, factory, api, resolveStreamReadPolicy(options), resolveStreamWritePolicy(options))
        return QuicServe.Serving(CoreListener(server)).also { handed = Handed.Yes }
    } finally {
        if (handed == Handed.No) lent.claim.entryFailed()
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

/** Serves on [host]:[port] with [pem] held in memory: its files are written for the bind only, and are gone once it returns. */
internal suspend fun serveOnTheCore(
    port: Int,
    host: String,
    pem: PemIdentity,
    options: QuicOptions,
    factory: BufferFactory,
): QuicServe<CoreListener> = pem.onFiles { tls -> serveOnTheCore(port, host, tls, options, factory) }
