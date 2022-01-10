package com.ditchoom.websocket

import com.ditchoom.buffer.ReadBuffer

sealed class WebSocketDataRead {
    class OtherOpCodeWebSocketDataRead(opcode: Opcode, buffer: ReadBuffer) : WebSocketDataRead()
    class BinaryWebSocketDataRead(val data: ReadBuffer) : WebSocketDataRead()
    class CharSequenceWebSocketDataRead(val charSequence: CharSequence) : WebSocketDataRead()
}