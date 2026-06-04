package com.ditchoom.socket.http3

import com.ditchoom.buffer.ReadBuffer
import com.ditchoom.buffer.WriteBuffer
import com.ditchoom.buffer.codec.Codec
import com.ditchoom.buffer.codec.DecodeContext
import com.ditchoom.buffer.codec.DecodeException
import com.ditchoom.buffer.codec.EncodeContext

/**
 * Hand-written codec for the HTTP/3 frame layer (RFC 9114 §7.1):
 * `Type(varint) Length(varint) Payload[Length]`.
 *
 * Dispatch on the **varint** Type is hand-written because buffer-codec's annotation-driven
 * sealed-union dispatch (`@PacketType`/`@DispatchOn`) only supports a fixed-width single-byte
 * discriminator. Payload *structures* still use the framework: SETTINGS parameters round-trip
 * through the generated [Http3SettingsPairCodec], and varint fields use [QuicVarIntCodec].
 *
 * [decode] reads exactly one frame and leaves [buffer] positioned immediately after it, so a
 * caller can loop to read a stream of frames. Byte payloads ([Http3Frame.Data] /
 * [Http3Frame.Headers] / [Http3Frame.Unknown]) are zero-copy views — see the lifetime note on
 * [Http3Frame].
 */
object Http3FrameCodec : Codec<Http3Frame> {
    override fun encode(
        buffer: WriteBuffer,
        value: Http3Frame,
        context: EncodeContext,
    ) {
        QuicVarIntCodec.encode(buffer, value.type, context)
        when (value) {
            is Http3Frame.Data -> writeBytesPayload(buffer, value.payload, context)
            is Http3Frame.Headers -> writeBytesPayload(buffer, value.encodedFieldBlock, context)
            is Http3Frame.Unknown -> writeBytesPayload(buffer, value.payload, context)
            is Http3Frame.GoAway -> writeVarIntPayload(buffer, value.id, context)
            is Http3Frame.CancelPush -> writeVarIntPayload(buffer, value.pushId, context)
            is Http3Frame.MaxPushId -> writeVarIntPayload(buffer, value.pushId, context)
            is Http3Frame.Settings -> {
                val payloadLength =
                    value.parameters.sumOf {
                        QuicVarIntCodec.encodedLength(it.identifier) + QuicVarIntCodec.encodedLength(it.value)
                    }
                QuicVarIntCodec.encode(buffer, payloadLength.toLong(), context)
                value.parameters.forEach { Http3SettingsPairCodec.encode(buffer, it, context) }
            }
        }
    }

    override fun decode(
        buffer: ReadBuffer,
        context: DecodeContext,
    ): Http3Frame {
        val type = QuicVarIntCodec.decode(buffer, context)
        val length = QuicVarIntCodec.decode(buffer, context)
        if (length !in 0..Int.MAX_VALUE.toLong()) {
            throw DecodeException(
                fieldPath = "Http3Frame.length",
                bufferPosition = buffer.position(),
                expected = "0..${Int.MAX_VALUE}",
                actual = length.toString(),
            )
        }
        val payloadLength = length.toInt()
        val payloadStart = buffer.position()
        val payloadEnd = payloadStart + payloadLength

        val frame =
            when (type) {
                Http3FrameType.DATA -> Http3Frame.Data(buffer.readBytes(payloadLength))
                Http3FrameType.HEADERS -> Http3Frame.Headers(buffer.readBytes(payloadLength))
                Http3FrameType.GOAWAY -> Http3Frame.GoAway(QuicVarIntCodec.decode(buffer, context))
                Http3FrameType.CANCEL_PUSH -> Http3Frame.CancelPush(QuicVarIntCodec.decode(buffer, context))
                Http3FrameType.MAX_PUSH_ID -> Http3Frame.MaxPushId(QuicVarIntCodec.decode(buffer, context))
                Http3FrameType.SETTINGS -> {
                    val parameters = mutableListOf<Http3SettingsPair>()
                    while (buffer.position() < payloadEnd) {
                        parameters += Http3SettingsPairCodec.decode(buffer, context)
                    }
                    Http3Frame.Settings(parameters)
                }
                else -> Http3Frame.Unknown(type, buffer.readBytes(payloadLength))
            }

        // Leave the buffer positioned exactly after this frame regardless of how the payload
        // was consumed — skips any padding within the declared length and keeps a frame loop
        // aligned even for the varint-payload variants.
        buffer.position(payloadEnd)
        return frame
    }

    private fun writeBytesPayload(
        buffer: WriteBuffer,
        payload: ReadBuffer,
        context: EncodeContext,
    ) {
        QuicVarIntCodec.encode(buffer, payload.remaining().toLong(), context)
        if (payload.remaining() > 0) {
            buffer.write(payload)
        }
    }

    private fun writeVarIntPayload(
        buffer: WriteBuffer,
        value: Long,
        context: EncodeContext,
    ) {
        QuicVarIntCodec.encode(buffer, QuicVarIntCodec.encodedLength(value).toLong(), context)
        QuicVarIntCodec.encode(buffer, value, context)
    }
}
