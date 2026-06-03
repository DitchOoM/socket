package com.ditchoom.socket.quic

/**
 * Platform-neutral bindings for quiche config calls.
 *
 * Each platform implements this with its native API (JNI/FFM on JVM, cinterop on Linux).
 * The shared [applyQuicOptions] function drives the config sequence — platforms only supply
 * the leaf-level native calls.
 */
internal interface QuicConfigCalls {
    fun setMaxIdleTimeout(ms: Long)

    fun setMaxRecvUdpPayloadSize(size: Long)

    fun setMaxSendUdpPayloadSize(size: Long)

    fun setInitialMaxData(v: Long)

    fun setInitialMaxStreamDataBidiLocal(v: Long)

    fun setInitialMaxStreamDataBidiRemote(v: Long)

    fun setInitialMaxStreamDataUni(v: Long)

    fun setInitialMaxStreamsBidi(v: Long)

    fun setInitialMaxStreamsUni(v: Long)

    fun setMaxConnectionWindow(v: Long)

    fun setMaxStreamWindow(v: Long)

    fun setDisableActiveMigration(v: Boolean)

    fun setActiveConnectionIdLimit(v: Long)

    fun verifyPeer(v: Boolean)

    fun setCcAlgorithm(algo: Int)

    fun enableHystart(v: Boolean)

    fun setInitialCongestionWindowPackets(packets: Long)

    fun enablePacing(v: Boolean)

    fun setMaxPacingRate(v: Long)

    fun discoverPmtu(v: Boolean)

    fun enableEarlyData()

    fun grease(v: Boolean)

    /** Enable unreliable DATAGRAM frames (RFC 9221) with the given receive/send queue lengths. */
    fun enableDgram(
        recvQueueLen: Long,
        sendQueueLen: Long,
    )
}

/**
 * Apply all [QuicOptions] to a quiche config via platform-specific [calls].
 *
 * This is the single source of truth for config sequencing — JVM and Linux
 * both delegate here instead of duplicating the logic.
 */
internal fun applyQuicOptions(
    options: QuicOptions,
    calls: QuicConfigCalls,
) {
    // Transport
    calls.setMaxIdleTimeout(options.idleTimeout.inWholeMilliseconds)
    calls.setMaxRecvUdpPayloadSize(options.maxUdpPayloadSize.toLong())
    calls.setMaxSendUdpPayloadSize(options.maxUdpPayloadSize.toLong())

    // Flow control
    val fc = options.flowControl
    calls.setInitialMaxData(fc.initialMaxData)
    calls.setInitialMaxStreamDataBidiLocal(fc.initialMaxStreamDataBidiLocal)
    calls.setInitialMaxStreamDataBidiRemote(fc.initialMaxStreamDataBidiRemote)
    calls.setInitialMaxStreamDataUni(fc.initialMaxStreamDataUni)
    calls.setInitialMaxStreamsBidi(fc.initialMaxStreamsBidi)
    calls.setInitialMaxStreamsUni(fc.initialMaxStreamsUni)
    fc.maxConnectionWindow?.let { calls.setMaxConnectionWindow(it) }
    fc.maxStreamWindow?.let { calls.setMaxStreamWindow(it) }

    calls.setDisableActiveMigration(options.disableActiveMigration)
    calls.setActiveConnectionIdLimit(options.activeConnectionIdLimit)
    calls.verifyPeer(options.verifyPeer)

    // Congestion control
    calls.setCcAlgorithm(options.congestionControl.quicheValue)
    when (val cc = options.congestionControl) {
        is CongestionControl.Cubic -> calls.enableHystart(cc.enableHystart)
        is CongestionControl.Reno, is CongestionControl.Bbr2 -> {}
    }
    options.initialCongestionWindowPackets?.let { calls.setInitialCongestionWindowPackets(it) }

    // Pacing
    when (val pacing = options.pacing) {
        is Pacing.Disabled -> calls.enablePacing(false)
        is Pacing.Unlimited -> calls.enablePacing(true)
        is Pacing.Limited -> {
            calls.enablePacing(true)
            calls.setMaxPacingRate(pacing.maxBytesPerSec)
        }
    }

    calls.discoverPmtu(options.enablePmtuDiscovery)
    if (options.enableEarlyData) calls.enableEarlyData()
    calls.grease(options.enableGrease)

    // Unreliable datagrams (RFC 9221) — only when explicitly enabled.
    options.datagrams?.let { calls.enableDgram(it.recvQueueLen.toLong(), it.sendQueueLen.toLong()) }
}
