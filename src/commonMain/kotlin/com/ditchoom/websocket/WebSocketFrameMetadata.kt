package com.ditchoom.websocket

internal data class WebSocketFrameMetadata(
    val masked: Boolean,
    val payloadLength: Int,
)