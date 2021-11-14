package com.ditchoom.websocket

sealed class MaskingKey {
    object NoMaskingKey: MaskingKey()
    class FourByteMaskingKey(val maskingKey: ByteArray): MaskingKey()
}