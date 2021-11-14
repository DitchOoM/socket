package com.ditchoom.websocket

import com.ditchoom.data.last4Bits

enum class Opcode(val value: Byte) {
    Continuation(0x0),
    Text(0x1),
    Binary(0x2),
    // 0x3-7 are reserved for further non-control frames
    ReservedBit3(0x3),
    ReservedBit4(0x4),
    ReservedBit5(0x5),
    ReservedBit6(0x6),
    ReservedBit7(0x7),
    Close(0x8),
    Ping(0x9),
    Pong(0xA),
    // 0xB-F are reserved for further control frames
    ReservedBitB(0xB),
    ReservedBitC(0xC),
    ReservedBitD(0xD),
    ReservedBitE(0xE),
    ReservedBitF(0xF);

    companion object {
        fun from(byte: Byte) =
            when (val actualValue = byte.last4Bits()) {
            Continuation.value -> Continuation
            Text.value -> Text
            Binary.value -> Binary
            ReservedBit3.value -> ReservedBit3
            ReservedBit4.value -> ReservedBit4
            ReservedBit5.value -> ReservedBit5
            ReservedBit6.value -> ReservedBit6
            ReservedBit7.value -> ReservedBit7
            Close.value -> Close
            Ping.value -> Ping
            Pong.value -> Pong
            ReservedBitB.value -> ReservedBitB
            ReservedBitC.value -> ReservedBitC
            ReservedBitD.value -> ReservedBitD
            ReservedBitE.value -> ReservedBitE
            ReservedBitF.value -> ReservedBitF
            else -> throw IllegalArgumentException("Invalid opcode found $actualValue")
        }
    }
}