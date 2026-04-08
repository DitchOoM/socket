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
    data class BlockUdp(val reserved: UByte = 0u) : NetCtrlCommand

    @ProtocolMessage
    @PacketType(0x02)
    data class UnblockUdp(val reserved: UByte = 0u) : NetCtrlCommand

    @ProtocolMessage
    @PacketType(0x03)
    data class AddLatency(val ms: Int) : NetCtrlCommand

    @ProtocolMessage
    @PacketType(0x04)
    data class RemoveLatency(val reserved: UByte = 0u) : NetCtrlCommand

    @ProtocolMessage
    @PacketType(0x05)
    data class AirplaneOn(val reserved: UByte = 0u) : NetCtrlCommand

    @ProtocolMessage
    @PacketType(0x06)
    data class AirplaneOff(val reserved: UByte = 0u) : NetCtrlCommand

    /** Pre-schedule airplane mode off after [delayMs] (before sending [AirplaneOn]). */
    @ProtocolMessage
    @PacketType(0x07)
    data class ScheduleAirplaneOff(val delayMs: Long) : NetCtrlCommand

    @ProtocolMessage
    @PacketType(0x08)
    data class Cleanup(val reserved: UByte = 0u) : NetCtrlCommand

    @ProtocolMessage
    @PacketType(0x09)
    data class Ping(val reserved: UByte = 0u) : NetCtrlCommand
}

/**
 * Network control responses sent from the host back to the device.
 * KSP generates [NetCtrlResponseCodec].
 */
@ProtocolMessage
sealed interface NetCtrlResponse {
    @ProtocolMessage
    @PacketType(0x01)
    data class Ok(val reserved: UByte = 0u) : NetCtrlResponse

    @ProtocolMessage
    @PacketType(0x02)
    data class Scheduled(val delayMs: Long) : NetCtrlResponse

    @ProtocolMessage
    @PacketType(0x03)
    data class Error(
        @LengthPrefixed val message: String,
    ) : NetCtrlResponse
}
