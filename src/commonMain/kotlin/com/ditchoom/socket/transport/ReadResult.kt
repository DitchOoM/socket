package com.ditchoom.socket.transport

import com.ditchoom.buffer.ReadBuffer
import kotlin.jvm.JvmInline

@JvmInline
value class BytesWritten(
    val count: Int,
)

sealed interface ReadResult {
    @JvmInline
    value class Data(
        val buffer: ReadBuffer,
    ) : ReadResult

    data object End : ReadResult

    data object Reset : ReadResult
}
