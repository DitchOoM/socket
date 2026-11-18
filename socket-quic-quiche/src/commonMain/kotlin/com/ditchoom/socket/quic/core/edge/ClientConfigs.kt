package com.ditchoom.socket.quic.core.edge

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.use
import com.ditchoom.socket.quic.ApiQuicConfigCalls
import com.ditchoom.socket.quic.QuicConfigCalls
import com.ditchoom.socket.quic.QuicOptions
import com.ditchoom.socket.quic.QuicRole
import com.ditchoom.socket.quic.QuicheApi
import com.ditchoom.socket.quic.QuicheConfig
import com.ditchoom.socket.quic.applyQuicOptions
import com.ditchoom.socket.quic.clientAlpnOffer
import com.ditchoom.socket.quic.core.conn.HandshakeRule
import com.ditchoom.socket.quic.encodeAlpnList
import com.ditchoom.socket.quic.loadTrustAnchors

/** A quiche config call a client config is built with, by its name. */
internal enum class ConfigCallName {
    MaxIdleTimeout,
    MaxRecvUdpPayloadSize,
    MaxSendUdpPayloadSize,
    InitialMaxData,
    InitialMaxStreamDataBidiLocal,
    InitialMaxStreamDataBidiRemote,
    InitialMaxStreamDataUni,
    InitialMaxStreamsBidi,
    InitialMaxStreamsUni,
    MaxConnectionWindow,
    MaxStreamWindow,
    DisableActiveMigration,
    ActiveConnectionIdLimit,
    VerifyPeer,
    CcAlgorithm,
    Hystart,
    InitialCongestionWindowPackets,
    Pacing,
    MaxPacingRate,
    DiscoverPmtu,
    EarlyData,
    Grease,
    DatagramRecvQueue,
    DatagramSendQueue,
    KeyLog,
}

/** One config call and its argument; a switch's argument is [ON] or [OFF]. */
internal data class ConfigCall(
    val name: ConfigCallName,
    val value: Long,
) {
    companion object {
        const val ON = 1L
        const val OFF = 0L
    }
}

/**
 * Everything a client's quiche config is built from, as a value: the config calls the options make, the protocols
 * offered, the pinned trust anchors and the initial RTT. Connects with equal plans on one api share one config.
 */
internal data class ClientConfigPlan(
    val calls: List<ConfigCall>,
    val alpn: List<String>,
    val pinnedCas: List<String>,
    val initialRttNanos: Long,
)

/** The plan of the config [buildClientConfig] makes from [options] and [handshake]. */
internal fun clientConfigPlan(
    options: QuicOptions,
    handshake: HandshakeRule,
): ClientConfigPlan {
    val recording = RecordedCalls()
    applyQuicOptions(options, recording, QuicRole.Client)
    return ClientConfigPlan(recording.calls(), clientAlpnOffer(options), options.trustedCaCertificatesPem, handshake.priorNanos())
}

/** A new client config from [options] and [handshake]; freed again if a step fails. */
internal fun buildClientConfig(
    api: QuicheApi,
    options: QuicOptions,
    handshake: HandshakeRule,
    factory: BufferFactory,
): QuicheConfig {
    val config = api.configNew(QUIC_V1)
    return runCatching {
        encodeAlpnList(clientAlpnOffer(options), factory).use { api.configSetApplicationProtos(config, addressOf(it), it.remaining()) }
        applyQuicOptions(options, ApiQuicConfigCalls(api, config), QuicRole.Client)
        handshake.applyTo(api, config)
        loadTrustAnchors(api, config, options, factory)
        config
    }.onFailure { api.configFree(config) }.getOrThrow()
}

/** The calls [applyQuicOptions] makes, in order, as values. */
private class RecordedCalls : QuicConfigCalls {
    private val made = ArrayList<ConfigCall>(ConfigCallName.entries.size)

    fun calls(): List<ConfigCall> = made

    private fun call(
        name: ConfigCallName,
        value: Long,
    ) {
        made.add(ConfigCall(name, value))
    }

    private fun switch(
        name: ConfigCallName,
        on: Boolean,
    ) = call(name, if (on) ConfigCall.ON else ConfigCall.OFF)

    override fun setMaxIdleTimeout(ms: Long) = call(ConfigCallName.MaxIdleTimeout, ms)

    override fun setMaxRecvUdpPayloadSize(size: Long) = call(ConfigCallName.MaxRecvUdpPayloadSize, size)

    override fun setMaxSendUdpPayloadSize(size: Long) = call(ConfigCallName.MaxSendUdpPayloadSize, size)

    override fun setInitialMaxData(v: Long) = call(ConfigCallName.InitialMaxData, v)

    override fun setInitialMaxStreamDataBidiLocal(v: Long) = call(ConfigCallName.InitialMaxStreamDataBidiLocal, v)

    override fun setInitialMaxStreamDataBidiRemote(v: Long) = call(ConfigCallName.InitialMaxStreamDataBidiRemote, v)

    override fun setInitialMaxStreamDataUni(v: Long) = call(ConfigCallName.InitialMaxStreamDataUni, v)

    override fun setInitialMaxStreamsBidi(v: Long) = call(ConfigCallName.InitialMaxStreamsBidi, v)

    override fun setInitialMaxStreamsUni(v: Long) = call(ConfigCallName.InitialMaxStreamsUni, v)

    override fun setMaxConnectionWindow(v: Long) = call(ConfigCallName.MaxConnectionWindow, v)

    override fun setMaxStreamWindow(v: Long) = call(ConfigCallName.MaxStreamWindow, v)

    override fun setDisableActiveMigration(v: Boolean) = switch(ConfigCallName.DisableActiveMigration, v)

    override fun setActiveConnectionIdLimit(v: Long) = call(ConfigCallName.ActiveConnectionIdLimit, v)

    override fun verifyPeer(v: Boolean) = switch(ConfigCallName.VerifyPeer, v)

    override fun setCcAlgorithm(algo: Int) = call(ConfigCallName.CcAlgorithm, algo.toLong())

    override fun enableHystart(v: Boolean) = switch(ConfigCallName.Hystart, v)

    override fun setInitialCongestionWindowPackets(packets: Long) = call(ConfigCallName.InitialCongestionWindowPackets, packets)

    override fun enablePacing(v: Boolean) = switch(ConfigCallName.Pacing, v)

    override fun setMaxPacingRate(v: Long) = call(ConfigCallName.MaxPacingRate, v)

    override fun discoverPmtu(v: Boolean) = switch(ConfigCallName.DiscoverPmtu, v)

    override fun enableEarlyData() = call(ConfigCallName.EarlyData, ConfigCall.ON)

    override fun grease(v: Boolean) = switch(ConfigCallName.Grease, v)

    override fun enableDgram(
        recvQueueLen: Long,
        sendQueueLen: Long,
    ) {
        call(ConfigCallName.DatagramRecvQueue, recvQueueLen)
        call(ConfigCallName.DatagramSendQueue, sendQueueLen)
    }

    override fun logKeys() = call(ConfigCallName.KeyLog, ConfigCall.ON)
}

private const val QUIC_V1 = 1
