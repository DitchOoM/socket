package com.ditchoom.socket.quic.netctrl

import com.ditchoom.buffer.codec.annotations.LengthPrefixed
import com.ditchoom.buffer.codec.annotations.PacketType
import com.ditchoom.buffer.codec.annotations.ProtocolMessage

/**
 * Network control commands sent from the device (emulator) to the host.
 * KSP generates [NetCtrlCommandCodec] with dispatch on the [PacketType] tag byte.
 */
@Suppress("unused") // reserved fields are wire-format padding required by codec generation
@ProtocolMessage
sealed interface NetCtrlCommand {
    @ProtocolMessage
    @PacketType(0x01)
    data class BlockUdp(
        val reserved: UByte = 0u,
    ) : NetCtrlCommand

    @ProtocolMessage
    @PacketType(0x02)
    data class UnblockUdp(
        val reserved: UByte = 0u,
    ) : NetCtrlCommand

    @ProtocolMessage
    @PacketType(0x03)
    data class AddLatency(
        val ms: Int,
    ) : NetCtrlCommand

    @ProtocolMessage
    @PacketType(0x04)
    data class RemoveLatency(
        val reserved: UByte = 0u,
    ) : NetCtrlCommand

    @ProtocolMessage
    @PacketType(0x05)
    data class AirplaneOn(
        val reserved: UByte = 0u,
    ) : NetCtrlCommand

    @ProtocolMessage
    @PacketType(0x06)
    data class AirplaneOff(
        val reserved: UByte = 0u,
    ) : NetCtrlCommand

    /** Pre-schedule airplane mode off after [delayMs] (before sending [AirplaneOn]). */
    @ProtocolMessage
    @PacketType(0x07)
    data class ScheduleAirplaneOff(
        val delayMs: Long,
    ) : NetCtrlCommand

    @ProtocolMessage
    @PacketType(0x08)
    data class Cleanup(
        val reserved: UByte = 0u,
    ) : NetCtrlCommand

    @ProtocolMessage
    @PacketType(0x09)
    data class Ping(
        val reserved: UByte = 0u,
    ) : NetCtrlCommand

    /**
     * Ask whether this host can actually impair the device's network (#389).
     *
     * [Ping] answers "the control server is up", which is a different question and was being read as
     * this one. Every impairment runs as `adb shell su 0 …`; on a device without root each returns
     * `su: inaccessible or not found`, the server logged it as non-fatal and still replied [Ok], and
     * five `AndroidQuicMigrationTests` cases passed against a completely healthy network — measured
     * on a physical SM-F956U1. A capability nobody asks about is a capability that gets assumed.
     */
    @ProtocolMessage
    @PacketType(0x0A)
    data class QueryImpairment(
        val reserved: UByte = 0u,
    ) : NetCtrlCommand
}

/**
 * Network control responses sent from the host back to the device.
 * KSP generates [NetCtrlResponseCodec].
 */
@ProtocolMessage
sealed interface NetCtrlResponse {
    @ProtocolMessage
    @PacketType(0x01)
    data class Ok(
        val reserved: UByte = 0u,
    ) : NetCtrlResponse

    @ProtocolMessage
    @PacketType(0x02)
    data class Scheduled(
        val delayMs: Long,
    ) : NetCtrlResponse

    @ProtocolMessage
    @PacketType(0x03)
    data class Error(
        @LengthPrefixed val message: String,
    ) : NetCtrlResponse

    /**
     * The host can run privileged impairment commands on this device: `su 0` works.
     *
     * A distinct member rather than [Ok] because the two answer different questions, and the whole of
     * #389 is that they were sharing a token: "the command was accepted" is not "the command took
     * effect", and a caller reading [Ok] could not tell them apart.
     */
    @ProtocolMessage
    @PacketType(0x04)
    data class ImpairmentAvailable(
        val reserved: UByte = 0u,
    ) : NetCtrlResponse

    /**
     * The host cannot impair this device's network; [why] is what it found out, verbatim.
     *
     * Carries the reason rather than a bare flag so the caller's skip can name the missing capability
     * instead of saying "not available" — the same complaint [NetworkControl.probe] already answers
     * by returning the throwable.
     */
    @ProtocolMessage
    @PacketType(0x05)
    data class ImpairmentUnavailable(
        @LengthPrefixed val why: String,
    ) : NetCtrlResponse
}
