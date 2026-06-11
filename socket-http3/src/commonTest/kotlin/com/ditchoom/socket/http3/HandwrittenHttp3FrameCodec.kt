package com.ditchoom.socket.http3

import com.ditchoom.buffer.ReadBuffer
import com.ditchoom.buffer.WriteBuffer
import com.ditchoom.buffer.codec.Codec
import com.ditchoom.buffer.codec.DecodeContext
import com.ditchoom.buffer.codec.DecodeException
import com.ditchoom.buffer.codec.EncodeContext
import com.ditchoom.buffer.codec.PeekResult
import com.ditchoom.buffer.codec.WireSize
import com.ditchoom.buffer.stream.StreamProcessor

/**
 * The retired hand-written codec for the HTTP/3 frame envelope (RFC 9114 §7.1):
 * `Type (i)`, `Length (i)`, then `Length` payload bytes — kept in the test
 * source set as the **differential oracle** for the KSP-generated
 * [Http3FrameCodec] that replaced it in production. This implementation is the
 * one that was interop-proven against Cloudflare/Google and the aioquic docker
 * peer, so [Http3FrameCodecDifferentialTests] pins the generated codec's wire
 * bytes against it. Do not extend it for new frame types — new protocol work
 * goes in the declarative [Http3Frame] model.
 *
 * [decode] recognizes the modeled frame types; every other type decodes to
 * [Http3Frame.Unknown] with its payload captured (RFC 9114 §9 ignore-unknown).
 * [encode] computes the length up front (so [wireSize] is always
 * [WireSize.Exact] — no back-patching).
 */
object HandwrittenHttp3FrameCodec : Codec<Http3Frame> {
    override fun decode(
        buffer: ReadBuffer,
        context: DecodeContext,
    ): Http3Frame {
        val type = VarIntCodec.decode(buffer, context)
        val length = VarIntCodec.decode(buffer, context)
        if (length !in 0..Int.MAX_VALUE.toLong()) {
            throw DecodeException(
                fieldPath = "Http3Frame.length",
                bufferPosition = buffer.position(),
                expected = "0..${Int.MAX_VALUE}",
                actual = length.toString(),
            )
        }
        val len = length.toInt()
        return when (type) {
            Http3FrameType.DATA -> Http3Frame.Data(buffer.readBytes(len))
            Http3FrameType.HEADERS -> Http3Frame.Headers(buffer.readBytes(len))
            Http3FrameType.SETTINGS -> Http3Frame.Settings(decodeSettings(buffer, len, context))
            Http3FrameType.GOAWAY -> Http3Frame.GoAway(decodeSingleVarIntFrame(buffer, len, context, "Http3Frame.GoAway.id"))
            Http3FrameType.MAX_PUSH_ID ->
                Http3Frame.MaxPushId(decodeSingleVarIntFrame(buffer, len, context, "Http3Frame.MaxPushId.pushId"))
            Http3FrameType.CANCEL_PUSH ->
                Http3Frame.CancelPush(decodeSingleVarIntFrame(buffer, len, context, "Http3Frame.CancelPush.pushId"))
            Http3FrameType.PUSH_PROMISE -> decodePushPromise(buffer, len, context)
            else -> Http3Frame.Unknown(type, buffer.readBytes(len))
        }
    }

    /**
     * Decodes a frame whose entire payload is a single varint (GOAWAY / MAX_PUSH_ID /
     * CANCEL_PUSH, RFC 9114 §7.2.6/§7.2.7/§7.2.3). The varint is bounds-checked against the
     * declared frame [length]; the buffer is then advanced to the frame end so a malformed
     * over-long frame can't leave a reader misaligned.
     */
    private fun decodeSingleVarIntFrame(
        buffer: ReadBuffer,
        length: Int,
        context: DecodeContext,
        fieldPath: String,
    ): Long {
        val end = buffer.position() + length
        val value = decodeBoundedVarInt(buffer, end, context, fieldPath)
        buffer.position(end)
        return value
    }

    /**
     * Decodes a PUSH_PROMISE payload (RFC 9114 §7.2.5): a `Push ID` varint followed by the
     * promised request's encoded field section, which is the remainder of the frame. The push id
     * is bounds-checked against the declared frame [length] before the section is sliced.
     */
    private fun decodePushPromise(
        buffer: ReadBuffer,
        length: Int,
        context: DecodeContext,
    ): Http3Frame.PushPromise {
        val end = buffer.position() + length
        val pushId = decodeBoundedVarInt(buffer, end, context, "Http3Frame.PushPromise.pushId")
        val sectionLength = end - buffer.position()
        if (sectionLength < 0) {
            throw DecodeException(
                fieldPath = "Http3Frame.PushPromise.encodedFieldSection",
                bufferPosition = buffer.position(),
                expected = "a non-negative field-section length within the frame",
                actual = sectionLength.toString(),
            )
        }
        return Http3Frame.PushPromise(pushId, buffer.readBytes(sectionLength))
    }

    private fun decodeSettings(
        buffer: ReadBuffer,
        length: Int,
        context: DecodeContext,
    ): List<Http3Setting> {
        val end = buffer.position() + length
        val entries = mutableListOf<Http3Setting>()
        while (buffer.position() < end) {
            val identifier = decodeBoundedVarInt(buffer, end, context, "Http3Frame.Settings.identifier")
            val value = decodeBoundedVarInt(buffer, end, context, "Http3Frame.Settings.value")
            entries.add(Http3Setting(identifier, value))
        }
        return entries
    }

    /**
     * Decodes a varint that must lie entirely within [end], checked from its
     * leading byte *before* any bytes are consumed — so a SETTINGS pair that
     * straddles or runs past the declared frame length fails cleanly instead of
     * reading into whatever follows the frame.
     */
    private fun decodeBoundedVarInt(
        buffer: ReadBuffer,
        end: Int,
        context: DecodeContext,
        fieldPath: String,
    ): Long {
        val available = end - buffer.position()
        val varIntLength =
            if (available >= 1) VarIntCodec.lengthFromPrefix(buffer.get(buffer.position()).toInt()) else 1
        if (varIntLength > available) {
            throw DecodeException(
                fieldPath = fieldPath,
                bufferPosition = buffer.position(),
                expected = "a $varIntLength-byte varint within the frame",
                actual = "$available byte(s) remaining",
            )
        }
        return VarIntCodec.decode(buffer, context)
    }

    override fun encode(
        buffer: WriteBuffer,
        value: Http3Frame,
        context: EncodeContext,
    ) {
        when (value) {
            is Http3Frame.Data -> writeOpaque(buffer, Http3FrameType.DATA, value.payload, context)
            is Http3Frame.Headers -> writeOpaque(buffer, Http3FrameType.HEADERS, value.encodedFieldSection, context)
            is Http3Frame.Unknown -> writeOpaque(buffer, value.type, value.payload, context)
            is Http3Frame.GoAway -> writeSingleVarInt(buffer, Http3FrameType.GOAWAY, value.id, context)
            is Http3Frame.MaxPushId -> writeSingleVarInt(buffer, Http3FrameType.MAX_PUSH_ID, value.pushId, context)
            is Http3Frame.CancelPush -> writeSingleVarInt(buffer, Http3FrameType.CANCEL_PUSH, value.pushId, context)
            is Http3Frame.PushPromise -> {
                VarIntCodec.encode(buffer, Http3FrameType.PUSH_PROMISE, context)
                val bodyLength = VarIntCodec.encodedLength(value.pushId) + value.encodedFieldSection.remaining()
                VarIntCodec.encode(buffer, bodyLength.toLong(), context)
                VarIntCodec.encode(buffer, value.pushId, context)
                // Non-destructive, like writeOpaque: restore the section's position after copying.
                val savedPosition = value.encodedFieldSection.position()
                buffer.write(value.encodedFieldSection)
                value.encodedFieldSection.position(savedPosition)
            }
            is Http3Frame.Settings -> {
                VarIntCodec.encode(buffer, Http3FrameType.SETTINGS, context)
                VarIntCodec.encode(buffer, settingsBodyLength(value.entries).toLong(), context)
                for (entry in value.entries) {
                    VarIntCodec.encode(buffer, entry.identifier, context)
                    VarIntCodec.encode(buffer, entry.value, context)
                }
            }
        }
    }

    private fun writeOpaque(
        buffer: WriteBuffer,
        type: Long,
        payload: ReadBuffer,
        context: EncodeContext,
    ) {
        VarIntCodec.encode(buffer, type, context)
        VarIntCodec.encode(buffer, payload.remaining().toLong(), context)
        // Restore the payload's position so encode is non-destructive: a frame
        // can be size-checked and re-encoded without its payload draining.
        val savedPosition = payload.position()
        buffer.write(payload)
        payload.position(savedPosition)
    }

    /** Writes a frame whose whole payload is a single varint (GOAWAY / MAX_PUSH_ID / CANCEL_PUSH). */
    private fun writeSingleVarInt(
        buffer: WriteBuffer,
        type: Long,
        value: Long,
        context: EncodeContext,
    ) {
        VarIntCodec.encode(buffer, type, context)
        VarIntCodec.encode(buffer, VarIntCodec.encodedLength(value).toLong(), context)
        VarIntCodec.encode(buffer, value, context)
    }

    private fun settingsBodyLength(entries: List<Http3Setting>): Int =
        entries.sumOf { VarIntCodec.encodedLength(it.identifier) + VarIntCodec.encodedLength(it.value) }

    private fun singleVarIntFrameSize(
        type: Long,
        value: Long,
    ): Int {
        val body = VarIntCodec.encodedLength(value)
        return VarIntCodec.encodedLength(type) + VarIntCodec.encodedLength(body.toLong()) + body
    }

    override fun wireSize(
        value: Http3Frame,
        context: EncodeContext,
    ): WireSize =
        WireSize.Exact(
            when (value) {
                is Http3Frame.Data -> frameSize(Http3FrameType.DATA, value.payload.remaining())
                is Http3Frame.Headers -> frameSize(Http3FrameType.HEADERS, value.encodedFieldSection.remaining())
                is Http3Frame.Unknown -> frameSize(value.type, value.payload.remaining())
                is Http3Frame.GoAway -> singleVarIntFrameSize(Http3FrameType.GOAWAY, value.id)
                is Http3Frame.MaxPushId -> singleVarIntFrameSize(Http3FrameType.MAX_PUSH_ID, value.pushId)
                is Http3Frame.CancelPush -> singleVarIntFrameSize(Http3FrameType.CANCEL_PUSH, value.pushId)
                is Http3Frame.PushPromise -> {
                    val body = VarIntCodec.encodedLength(value.pushId) + value.encodedFieldSection.remaining()
                    VarIntCodec.encodedLength(Http3FrameType.PUSH_PROMISE) + VarIntCodec.encodedLength(body.toLong()) + body
                }
                is Http3Frame.Settings -> {
                    val body = settingsBodyLength(value.entries)
                    VarIntCodec.encodedLength(Http3FrameType.SETTINGS) + VarIntCodec.encodedLength(body.toLong()) + body
                }
            },
        )

    private fun frameSize(
        type: Long,
        payloadLength: Int,
    ): Int = VarIntCodec.encodedLength(type) + VarIntCodec.encodedLength(payloadLength.toLong()) + payloadLength

    /**
     * Reports the total frame size — `type varint + length varint + payload` —
     * as soon as the two varint header fields are readable, without requiring
     * the payload to have arrived (the streaming layer then waits for that many
     * bytes). Returns [PeekResult.NeedsMoreData] until the header is complete.
     */
    override fun peekFrameSize(
        stream: StreamProcessor,
        baseOffset: Int,
    ): PeekResult {
        val typeLen = peekVarIntByteLength(stream, baseOffset) ?: return PeekResult.NeedsMoreData
        val lengthOffset = baseOffset + typeLen
        val lengthByteLen = peekVarIntByteLength(stream, lengthOffset) ?: return PeekResult.NeedsMoreData
        val payloadLength = peekVarIntValue(stream, lengthOffset, lengthByteLen)
        val total = typeLen.toLong() + lengthByteLen.toLong() + payloadLength
        if (total > Int.MAX_VALUE.toLong()) {
            throw DecodeException(
                fieldPath = "Http3Frame.length",
                bufferPosition = baseOffset,
                expected = "total frame size <= ${Int.MAX_VALUE}",
                actual = total.toString(),
            )
        }
        return PeekResult.Complete(total.toInt())
    }

    /** Byte length of the varint at [offset], or null if it isn't fully readable yet. */
    private fun peekVarIntByteLength(
        stream: StreamProcessor,
        offset: Int,
    ): Int? {
        if (stream.available() - offset < 1) return null
        val length = VarIntCodec.lengthFromPrefix(stream.peekByte(offset).toInt())
        return if (stream.available() - offset >= length) length else null
    }

    /** Decodes the [byteLength]-byte varint at [offset] without consuming the stream. */
    private fun peekVarIntValue(
        stream: StreamProcessor,
        offset: Int,
        byteLength: Int,
    ): Long {
        var value = (stream.peekByte(offset).toInt() and 0x3F).toLong()
        for (i in 1 until byteLength) {
            value = (value shl 8) or (stream.peekByte(offset + i).toLong() and 0xFF)
        }
        return value
    }
}
