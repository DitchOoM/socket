package com.ditchoom.socket.quic

/**
 * The sim's [QuicConfigCalls], adapting whichever [QuicheApi] the platform supplied.
 *
 * A verbatim twin of `commonJvmMain`'s `CommonJvmQuicConfigCalls`, which the sims used while they were
 * JVM-only. Every call here goes through the [QuicheApi] abstraction rather than a cinterop config
 * pointer, so unlike the Apple and Linux adapters — which take their native config directly and are
 * therefore per-platform — this one is genuinely common: it works against the JNI, FFM and cinterop
 * backends alike. It exists as a copy because the original is `internal` to a JVM-only source set.
 */
internal class SimQuicConfigCalls(
    private val api: QuicheApi,
    private val cfg: QuicheConfig,
) : QuicConfigCalls {
    override fun setMaxIdleTimeout(ms: Long) = api.configSetMaxIdleTimeout(cfg, ms)

    override fun setMaxRecvUdpPayloadSize(size: Long) = api.configSetMaxRecvUdpPayloadSize(cfg, size)

    override fun setMaxSendUdpPayloadSize(size: Long) = api.configSetMaxSendUdpPayloadSize(cfg, size)

    override fun setInitialMaxData(v: Long) = api.configSetInitialMaxData(cfg, v)

    override fun setInitialMaxStreamDataBidiLocal(v: Long) = api.configSetInitialMaxStreamDataBidiLocal(cfg, v)

    override fun setInitialMaxStreamDataBidiRemote(v: Long) = api.configSetInitialMaxStreamDataBidiRemote(cfg, v)

    override fun setInitialMaxStreamDataUni(v: Long) = api.configSetInitialMaxStreamDataUni(cfg, v)

    override fun setInitialMaxStreamsBidi(v: Long) = api.configSetInitialMaxStreamsBidi(cfg, v)

    override fun setInitialMaxStreamsUni(v: Long) = api.configSetInitialMaxStreamsUni(cfg, v)

    override fun setMaxConnectionWindow(v: Long) = api.configSetMaxConnectionWindow(cfg, v)

    override fun setMaxStreamWindow(v: Long) = api.configSetMaxStreamWindow(cfg, v)

    override fun setDisableActiveMigration(v: Boolean) = api.configSetDisableActiveMigration(cfg, v)

    override fun setActiveConnectionIdLimit(v: Long) = api.configSetActiveConnectionIdLimit(cfg, v)

    override fun verifyPeer(v: Boolean) = api.configVerifyPeer(cfg, v)

    override fun setCcAlgorithm(algo: Int) = api.configSetCcAlgorithm(cfg, algo)

    override fun enableHystart(v: Boolean) = api.configEnableHystart(cfg, v)

    override fun setInitialCongestionWindowPackets(packets: Long) = api.configSetInitialCongestionWindowPackets(cfg, packets)

    override fun enablePacing(v: Boolean) = api.configEnablePacing(cfg, v)

    override fun setMaxPacingRate(v: Long) = api.configSetMaxPacingRate(cfg, v)

    override fun discoverPmtu(v: Boolean) = api.configDiscoverPmtu(cfg, v)

    override fun enableEarlyData() = api.configEnableEarlyData(cfg)

    override fun grease(v: Boolean) = api.configGrease(cfg, v)

    override fun enableDgram(
        recvQueueLen: Long,
        sendQueueLen: Long,
    ) = api.configEnableDgram(cfg, true, recvQueueLen, sendQueueLen)
}
