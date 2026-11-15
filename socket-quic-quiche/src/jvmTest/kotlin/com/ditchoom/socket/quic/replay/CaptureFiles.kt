package com.ditchoom.socket.quic.replay

import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.time.Duration
import kotlin.time.Duration.Companion.nanoseconds

/** One end of a UDP flow in a packet capture. */
internal data class UdpEndpoint(
    val address: Int,
    val port: Int,
)

/** A UDP datagram from a packet capture, stamped on the capture's own clock. */
internal class UdpDatagram(
    val at: Duration,
    val from: UdpEndpoint,
    val to: UdpEndpoint,
    val payload: ByteArray,
)

/** Why a file is not a packet capture this reads. */
internal sealed interface PcapFault {
    data object NotPcapOrPcapng : PcapFault

    /** The link-layer type is none of Ethernet, PPP or Linux cooked capture. */
    data class LinkType(
        val type: Int,
    ) : PcapFault

    /** A block or record runs past the end of the file, at byte [offset]. */
    data class Truncated(
        val offset: Int,
    ) : PcapFault
}

internal sealed interface PcapRead {
    class Datagrams(
        val datagrams: List<UdpDatagram>,
    ) : PcapRead

    data class Unreadable(
        val fault: PcapFault,
    ) : PcapRead
}

/**
 * The IPv4 UDP datagrams of a pcap or pcapng file, in file order. Anything else in the capture is
 * skipped, so a capture of several connections is read whole and the caller picks its flow.
 */
internal fun readPcap(file: File): PcapRead {
    val bytes = ByteBuffer.wrap(file.readBytes())
    if (bytes.remaining() < 24) return PcapRead.Unreadable(PcapFault.NotPcapOrPcapng)
    return when (bytes.order(ByteOrder.LITTLE_ENDIAN).getInt(0)) {
        PCAPNG_SECTION -> readPcapng(bytes)
        PCAP_MICROS -> readClassicPcap(bytes, ByteOrder.LITTLE_ENDIAN, nanosPerTick = 1_000)
        PCAP_NANOS -> readClassicPcap(bytes, ByteOrder.LITTLE_ENDIAN, nanosPerTick = 1)
        PCAP_MICROS_SWAPPED -> readClassicPcap(bytes, ByteOrder.BIG_ENDIAN, nanosPerTick = 1_000)
        PCAP_NANOS_SWAPPED -> readClassicPcap(bytes, ByteOrder.BIG_ENDIAN, nanosPerTick = 1)
        else -> PcapRead.Unreadable(PcapFault.NotPcapOrPcapng)
    }
}

private fun readClassicPcap(
    bytes: ByteBuffer,
    order: ByteOrder,
    nanosPerTick: Long,
): PcapRead {
    bytes.order(order)
    val linkType = bytes.getInt(20)
    val datagrams = mutableListOf<UdpDatagram>()
    var at = 24
    while (at < bytes.limit()) {
        if (at + 16 > bytes.limit()) return PcapRead.Unreadable(PcapFault.Truncated(at))
        val seconds = bytes.getInt(at).toLong() and 0xFFFFFFFFL
        val ticks = bytes.getInt(at + 4).toLong() and 0xFFFFFFFFL
        val captured = bytes.getInt(at + 8)
        if (captured < 0 || at + 16 + captured > bytes.limit()) return PcapRead.Unreadable(PcapFault.Truncated(at))
        val stamp = (seconds * 1_000_000_000L + ticks * nanosPerTick).nanoseconds
        when (val frame = udpOf(linkType, bytes, at + 16, captured, stamp)) {
            is Frame.Udp -> datagrams += frame.datagram
            Frame.Other -> Unit
            is Frame.UnknownLink -> return PcapRead.Unreadable(PcapFault.LinkType(frame.type))
        }
        at += 16 + captured
    }
    return PcapRead.Datagrams(datagrams)
}

/** A pcapng interface: its link-layer type and how many nanoseconds one timestamp unit is. */
private class PcapngInterface(
    val linkType: Int,
    val nanosPerTick: Double,
)

private fun readPcapng(bytes: ByteBuffer): PcapRead {
    bytes.order(ByteOrder.LITTLE_ENDIAN)
    val interfaces = mutableListOf<PcapngInterface>()
    val datagrams = mutableListOf<UdpDatagram>()
    var at = 0
    while (at < bytes.limit()) {
        if (at + 12 > bytes.limit()) return PcapRead.Unreadable(PcapFault.Truncated(at))
        val type = bytes.getInt(at)
        if (type == PCAPNG_SECTION) {
            bytes.order(
                if (bytes.order(ByteOrder.LITTLE_ENDIAN).getInt(at + 8) ==
                    PCAPNG_BYTE_ORDER
                ) {
                    ByteOrder.LITTLE_ENDIAN
                } else {
                    ByteOrder.BIG_ENDIAN
                },
            )
        }
        val length = bytes.getInt(at + 4)
        if (length < 12 || at + length > bytes.limit()) return PcapRead.Unreadable(PcapFault.Truncated(at))
        val body = at + 8
        when (type) {
            PCAPNG_INTERFACE -> interfaces += pcapngInterface(bytes, body, at + length - 4)
            PCAPNG_ENHANCED_PACKET -> {
                val id = bytes.getInt(body)
                if (id !in interfaces.indices) return PcapRead.Unreadable(PcapFault.Truncated(at))
                val ticks = (bytes.getInt(body + 4).toLong() shl 32) or (bytes.getInt(body + 8).toLong() and 0xFFFFFFFFL)
                val captured = bytes.getInt(body + 12)
                if (captured < 0 || body + 20 + captured > at + length) return PcapRead.Unreadable(PcapFault.Truncated(at))
                val stamp = (ticks * interfaces[id].nanosPerTick).toLong().nanoseconds
                when (val frame = udpOf(interfaces[id].linkType, bytes, body + 20, captured, stamp)) {
                    is Frame.Udp -> datagrams += frame.datagram
                    Frame.Other -> Unit
                    is Frame.UnknownLink -> return PcapRead.Unreadable(PcapFault.LinkType(frame.type))
                }
            }
            else -> Unit
        }
        at += length
    }
    return PcapRead.Datagrams(datagrams)
}

private fun pcapngInterface(
    bytes: ByteBuffer,
    body: Int,
    end: Int,
): PcapngInterface {
    val linkType = bytes.getShort(body).toInt() and 0xFFFF
    var nanosPerTick = 1_000.0
    var at = body + 8
    while (at + 4 <= end) {
        val code = bytes.getShort(at).toInt() and 0xFFFF
        val length = bytes.getShort(at + 2).toInt() and 0xFFFF
        if (code == PCAPNG_OPTION_END) break
        if (code == PCAPNG_OPTION_TSRESOL && length >= 1) {
            val resolution = bytes.get(at + 4).toInt() and 0xFF
            val exponent = resolution and 0x7F
            val perSecond = if (resolution and 0x80 != 0) Math.pow(2.0, exponent.toDouble()) else Math.pow(10.0, exponent.toDouble())
            nanosPerTick = 1_000_000_000.0 / perSecond
        }
        at += 4 + ((length + 3) and 3.inv())
    }
    return PcapngInterface(linkType, nanosPerTick)
}

private sealed interface Frame {
    class Udp(
        val datagram: UdpDatagram,
    ) : Frame

    /** Not IPv4 UDP, or cut short by the capture's snap length. */
    data object Other : Frame

    data class UnknownLink(
        val type: Int,
    ) : Frame
}

private fun udpOf(
    linkType: Int,
    bytes: ByteBuffer,
    start: Int,
    length: Int,
    stamp: Duration,
): Frame {
    val network =
        when (linkType) {
            LINK_ETHERNET -> if (length >= 14 && beShort(bytes, start + 12) == ETHERTYPE_IPV4) start + 14 else return Frame.Other
            LINK_PPP -> if (length >= 2 && beShort(bytes, start) == PPP_IPV4) start + 2 else return Frame.Other
            LINK_LINUX_COOKED -> if (length >= 16 && beShort(bytes, start + 14) == ETHERTYPE_IPV4) start + 16 else return Frame.Other
            else -> return Frame.UnknownLink(linkType)
        }
    val end = start + length
    if (network + 20 > end || (bytes.get(network).toInt() and 0xF0) != 0x40 || bytes.get(network + 9).toInt() != IP_UDP) return Frame.Other
    val udp = network + (bytes.get(network).toInt() and 0x0F) * 4
    if (udp + 8 > end) return Frame.Other
    val payloadEnd = udp + beShort(bytes, udp + 4)
    if (payloadEnd > end || payloadEnd < udp + 8) return Frame.Other
    val payload = ByteArray(payloadEnd - udp - 8)
    bytes.duplicate().position(udp + 8).get(payload)
    return Frame.Udp(
        UdpDatagram(
            at = stamp,
            from = UdpEndpoint(beInt(bytes, network + 12), beShort(bytes, udp)),
            to = UdpEndpoint(beInt(bytes, network + 16), beShort(bytes, udp + 2)),
            payload = payload,
        ),
    )
}

private fun beShort(
    bytes: ByteBuffer,
    at: Int,
): Int = ((bytes.get(at).toInt() and 0xFF) shl 8) or (bytes.get(at + 1).toInt() and 0xFF)

private fun beInt(
    bytes: ByteBuffer,
    at: Int,
): Int = (beShort(bytes, at) shl 16) or beShort(bytes, at + 2)

private const val PCAPNG_SECTION = 0x0A0D0D0A
private const val PCAPNG_BYTE_ORDER = 0x1A2B3C4D
private const val PCAPNG_INTERFACE = 1
private const val PCAPNG_ENHANCED_PACKET = 6
private const val PCAPNG_OPTION_END = 0
private const val PCAPNG_OPTION_TSRESOL = 9
private const val PCAP_MICROS = 0xA1B2C3D4.toInt()
private const val PCAP_NANOS = 0xA1B23C4D.toInt()
private const val PCAP_MICROS_SWAPPED = 0xD4C3B2A1.toInt()
private const val PCAP_NANOS_SWAPPED = 0x4D3CB2A1
private const val LINK_ETHERNET = 1
private const val LINK_PPP = 9
private const val LINK_LINUX_COOKED = 113
private const val ETHERTYPE_IPV4 = 0x0800
private const val PPP_IPV4 = 0x0021
private const val IP_UDP = 17

/** A JSON value, as much of JSON as a qlog line holds. */
internal sealed interface Json {
    data class Obj(
        val fields: Map<String, Json>,
    ) : Json

    data class Arr(
        val items: List<Json>,
    ) : Json

    data class Str(
        val value: String,
    ) : Json

    data class Num(
        val value: Double,
    ) : Json

    data class Bool(
        val value: Boolean,
    ) : Json

    data object Null : Json

    /** The text is not JSON; [at] is where the reading stopped. */
    data class Malformed(
        val at: Int,
    ) : Json

    /** The field [name] of an object; [Null] for anything else, so a path of lookups needs no checks on the way. */
    operator fun get(name: String): Json =
        when (this) {
            is Obj -> fields.getOrElse(name) { Null }
            is Arr, is Str, is Num, is Bool, Null, is Malformed -> Null
        }

    val elements: List<Json>
        get() =
            when (this) {
                is Arr -> items
                is Obj, is Str, is Num, is Bool, Null, is Malformed -> emptyList()
            }

    fun text(otherwise: String = ""): String =
        when (this) {
            is Str -> value
            is Obj, is Arr, is Num, is Bool, Null, is Malformed -> otherwise
        }

    fun number(otherwise: Double = 0.0): Double =
        when (this) {
            is Num -> value
            is Obj, is Arr, is Str, is Bool, Null, is Malformed -> otherwise
        }

    fun flag(): Boolean =
        when (this) {
            is Bool -> value
            is Obj, is Arr, is Str, is Num, Null, is Malformed -> false
        }

    companion object {
        fun parse(text: String): Json = JsonReader(text).document()
    }
}

private class JsonReader(
    private val text: String,
) {
    private var at = 0

    /** Thrown inside the reader only, to unwind to [document]; never leaves this class. */
    private class Stop : RuntimeException()

    fun document(): Json =
        try {
            val value = value()
            skipSpace()
            if (at == text.length) value else Json.Malformed(at)
        } catch (_: Stop) {
            Json.Malformed(at)
        }

    private fun skipSpace() {
        while (at < text.length && text[at].isWhitespace()) at++
    }

    private fun peek(): Char {
        skipSpace()
        if (at >= text.length) throw Stop()
        return text[at]
    }

    private fun take(expected: Char) {
        if (peek() != expected) throw Stop()
        at++
    }

    private fun value(): Json =
        when (val c = peek()) {
            '{' -> obj()
            '[' -> arr()
            '"' -> Json.Str(string())
            't' -> literal("true", Json.Bool(true))
            'f' -> literal("false", Json.Bool(false))
            'n' -> literal("null", Json.Null)
            else -> if (c == '-' || c.isDigit()) number() else throw Stop()
        }

    private fun literal(
        word: String,
        value: Json,
    ): Json {
        if (!text.startsWith(word, at)) throw Stop()
        at += word.length
        return value
    }

    private fun number(): Json {
        val start = at
        while (at < text.length && (text[at].isDigit() || text[at] in "+-.eE")) at++
        return try {
            Json.Num(text.substring(start, at).toDouble())
        } catch (_: NumberFormatException) {
            throw Stop()
        }
    }

    private fun string(): String {
        take('"')
        val out = StringBuilder()
        while (true) {
            if (at >= text.length) throw Stop()
            when (val c = text[at++]) {
                '"' -> return out.toString()
                '\\' -> {
                    if (at >= text.length) throw Stop()
                    when (val escaped = text[at++]) {
                        'n' -> out.append('\n')
                        't' -> out.append('\t')
                        'r' -> out.append('\r')
                        'b' -> out.append('\b')
                        'f' -> out.append('\u000C')
                        'u' -> {
                            if (at + 4 > text.length) throw Stop()
                            try {
                                out.append(text.substring(at, at + 4).toInt(16).toChar())
                            } catch (_: NumberFormatException) {
                                throw Stop()
                            }
                            at += 4
                        }
                        else -> out.append(escaped)
                    }
                }
                else -> out.append(c)
            }
        }
    }

    private fun obj(): Json {
        take('{')
        val fields = LinkedHashMap<String, Json>()
        if (peek() == '}') {
            at++
            return Json.Obj(fields)
        }
        while (true) {
            skipSpace()
            val name = string()
            take(':')
            fields[name] = value()
            when (peek()) {
                ',' -> at++
                '}' -> {
                    at++
                    return Json.Obj(fields)
                }
                else -> throw Stop()
            }
        }
    }

    private fun arr(): Json {
        take('[')
        val items = mutableListOf<Json>()
        if (peek() == ']') {
            at++
            return Json.Arr(items)
        }
        while (true) {
            items += value()
            when (peek()) {
                ',' -> at++
                ']' -> {
                    at++
                    return Json.Arr(items)
                }
                else -> throw Stop()
            }
        }
    }
}
