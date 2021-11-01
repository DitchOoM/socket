package com.ditchoom.websocket

internal class FrameMetadata(
    val masked: Boolean,
    val payloadLength: Int,
)