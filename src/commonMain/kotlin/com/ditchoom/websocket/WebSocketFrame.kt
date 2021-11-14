@file:Suppress("EXPERIMENTAL_API_USAGE")

package com.ditchoom.websocket

import com.ditchoom.buffer.PlatformBuffer
import com.ditchoom.buffer.TransformedReadBuffer
import com.ditchoom.buffer.WriteBuffer
import com.ditchoom.buffer.allocateNewBuffer
import com.ditchoom.data.get
import com.ditchoom.data.toBooleanArray
import com.ditchoom.data.toByte
import kotlin.experimental.xor

/**
 *
 * 0                   1                   2                   3
 * 0 1 2 3 4 5 6 7 8 9 0 1 2 3 4 5 6 7 8 9 0 1 2 3 4 5 6 7 8 9 0 1
 * +-+-+-+-+-------+-+-------------+-------------------------------+
 * |F|R|R|R| opcode|M| Payload len |    Extended payload length    |
 * |I|S|S|S|  (4)  |A|     (7)     |             (16/64)           |
 * |N|V|V|V|       |S|             |   (if payload len==126/127)   |
 * | |1|2|3|       |K|             |                               |
 * +-+-+-+-+-------+-+-------------+ - - - - - - - - - - - - - - - +
 * |     Extended payload length continued, if payload len == 127  |
 * + - - - - - - - - - - - - - - - +-------------------------------+
 * |                               |Masking-key, if MASK set to 1  |
 * +-------------------------------+-------------------------------+
 * | Masking-key (continued)       |          Payload Data         |
 * +-------------------------------- - - - - - - - - - - - - - - - +
 * :                     Payload Data continued ...                :
 * + - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - +
 * |                     Payload Data continued ...                |
 * +---------------------------------------------------------------+
 *
 */
class WebSocketFrame(
    /**
     * Indicates that this is the final fragment in a message. The first fragment MAY also be the final fragment.
     */
    val fin: Boolean,
    /**
     * MUST be false unless an extension is negotiated that defines meanings for non-zero values. If a nonzero value is
     * received and none of the negotiated extensions defines the meaning of such a nonzero value, the receiving
     * endpoint MUST _Fail the WebSocket Connection_.
     */
    val rsv1: Boolean,
    val rsv2: Boolean,
    val rsv3: Boolean,
    /** Defines the interpretation of the "Payload data".  If an unknown opcode is received, the receiving endpoint
     * MUST _Fail the WebSocket Connection_.
     */
    val opcode: Opcode,
    /**
     * All frames sent from the client to the server are masked by a 32-bit value that is contained within the frame.
     * This field is present if the mask bit is set to true and is absent if the mask bit is set to false.  See Section
     * 5.3 for further information on client- to-server masking.
     */
    val maskingKey: MaskingKey,
    val payloadData: PlatformBuffer,
) {
    /**
     * The length of the "Payload data", in bytes: if 0-125, that is the payload length.  If 126, the following 2 bytes
     * interpreted as a 16-bit unsigned integer are the payload length.  If 127, the following 8 bytes interpreted as a
     * 64-bit unsigned integer (the most significant bit MUST be 0) are the payload length.  Multibyte length quantities
     * are expressed in network byte order.  Note that in all cases, the minimal number of bytes MUST be used to encode
     * the length, for example, the length of a 124-byte-long string can't be encoded as the sequence 126, 0, 124.  The
     * payload length is the length of the "Extension data" + the length of the "Application data".  The length of the
     * "Extension data" may be zero, in which case the payload length is the length of the "Application data".
     */
    private val payloadLength = if (payloadData.limit() <= 125u) {
        payloadData.limit().toInt()
    } else if (payloadData.limit() <= UInt.MAX_VALUE) {
        126
    } else {
        127
    }.also { check(it in 0..127) }

    constructor(fin: Boolean, opcode: Opcode, maskingKey: MaskingKey, payloadData: PlatformBuffer)
        :this(fin, false, false, false, opcode, maskingKey, payloadData)

    constructor(opcode: Opcode, payloadData: PlatformBuffer = allocateNewBuffer(0u)) : this(false, opcode, MaskingKey.NoMaskingKey, payloadData)

    fun toBuffer(): PlatformBuffer {
        val buffer = allocateNewBuffer(size().toUInt())
        serialize(buffer)
        return buffer
    }


    fun size() :Int {
        val actualPayloadLength = if (payloadLength <= 125) {
            payloadLength
        } else if (payloadLength == 126) {
            payloadLength + UShort.SIZE_BYTES
        } else if (payloadLength == 127) {
            payloadLength + ULong.SIZE_BYTES
        } else {
            throw IllegalStateException("Internal payload len size cannot be larger than 127")
        }
        // the first byte includes fin, rsv1-3, and opcode. second byte includes mask and payload len
        val ws2ByteOverhead = 2
        return actualPayloadLength +  ws2ByteOverhead + when (maskingKey) {
            is MaskingKey.FourByteMaskingKey -> 4
            MaskingKey.NoMaskingKey -> 0
        }
    }

    fun serialize(writeBuffer: WriteBuffer) {
        serializeByte1(writeBuffer)
        serializeMaskAndPayloadLength(writeBuffer)
        serializeMaskingKeyAndPayload(writeBuffer)
    }

    private fun serializeByte1(writeBuffer: WriteBuffer) {
        val byte1Array = BooleanArray(8)
        byte1Array[0] = fin
        byte1Array[1] = rsv1
        byte1Array[2] = rsv2
        byte1Array[3] = rsv3
        byte1Array[4] = opcode.value[4]
        byte1Array[5] = opcode.value[5]
        byte1Array[6] = opcode.value[6]
        byte1Array[7] = opcode.value[7]
        writeBuffer.write(byte1Array.toByte())
    }

    private fun serializeMaskAndPayloadLength(writeBuffer: WriteBuffer) {
        // write mask and payload len
        val maskedBitAndPayloadLengthArray = payloadLength.toByte().toBooleanArray()
        maskedBitAndPayloadLengthArray[0] = maskingKey is MaskingKey.FourByteMaskingKey
        val byte = maskedBitAndPayloadLengthArray.toByte()
        writeBuffer.write(byte)
        if (payloadLength == 126) {
            writeBuffer.write(payloadData.limit().toUShort())
        } else if (payloadLength == 127) {
            writeBuffer.write(payloadData.limit().toULong().toLong())
        }
    }

    private fun serializeMaskingKeyAndPayload(writeBuffer: WriteBuffer) {
        if (maskingKey is MaskingKey.FourByteMaskingKey) {
            writeBuffer.write(maskingKey.maskingKey)
        }
        val data = if (maskingKey is MaskingKey.FourByteMaskingKey) {
            TransformedReadBuffer(payloadData) {i, original ->
                original xor maskingKey.maskingKey[i.toLong().mod(4)]
            }
        } else {
            payloadData
        }
        writeBuffer.write(data)
    }

}

