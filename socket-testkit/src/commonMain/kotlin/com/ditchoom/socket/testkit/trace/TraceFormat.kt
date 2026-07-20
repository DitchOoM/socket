package com.ditchoom.socket.testkit.trace

import com.ditchoom.socket.NetworkAvailability
import com.ditchoom.socket.transport.NetworkId
import com.ditchoom.socket.transport.NetworkKind
import kotlin.time.Duration.Companion.nanoseconds
import com.ditchoom.socket.transport.Liveness as TransportLiveness

// The v1 trace line codec — one line per TraceEvent, versioned, single-line, space-delimited.
// The full grammar is documented on `QuicTraceRecorder`; [TraceEvent.parse] is the public decode
// entry point. encode/decode are exact inverses for every event type (`decode(encode(e)) == e`),
// which the round-trip tests assert for the input-event subset. Each TraceEvent.toString() routes
// here, so the string is only ever the sink serialization boundary — the typed event is canonical.

internal fun encodeTraceLine(event: TraceEvent): String =
    buildString {
        append("v1 ")
        append(event.atNanos)
        append(' ')
        when (event) {
            is TraceEvent.DgramOut -> {
                append("DGRAM_OUT ")
                append(event.len)
                append(' ')
                append(encodeTracePath(event.path))
                append(' ')
                append(event.payloadHex)
            }
            is TraceEvent.DgramIn -> {
                append("DGRAM_IN ")
                append(event.len)
                append(' ')
                append(encodeTracePath(event.path))
                append(' ')
                append(event.payloadHex)
            }
            is TraceEvent.State -> {
                append("STATE ")
                append(event.name)
                event.detail?.let {
                    append(' ')
                    append(flattenLine(it))
                }
            }
            is TraceEvent.PathState -> {
                append("PATH_STATE ")
                append(event.phase)
                append(' ')
                append(event.localHost ?: "-")
                append(' ')
                append(event.localPort)
            }
            is TraceEvent.Error -> {
                append("ERROR ")
                append(event.type)
                append(' ')
                append(flattenLine(event.message))
            }
            is TraceEvent.Stats -> {
                val s = event.stats
                append("STATS ")
                append(s.validationState).append(' ')
                append(if (s.active) 1 else 0).append(' ')
                append(s.recv).append(' ')
                append(s.sent).append(' ')
                append(s.lost).append(' ')
                append(s.retrans).append(' ')
                append(s.totalPtoCount).append(' ')
                append(s.rtt.inWholeNanoseconds).append(' ')
                append(s.minRtt.inWholeNanoseconds).append(' ')
                append(s.maxRtt.inWholeNanoseconds).append(' ')
                append(s.rttvar.inWholeNanoseconds).append(' ')
                append(s.cwnd).append(' ')
                append(s.sentBytes).append(' ')
                append(s.recvBytes).append(' ')
                append(s.lostBytes).append(' ')
                append(s.streamRetransBytes).append(' ')
                append(s.pmtu).append(' ')
                append(s.deliveryRate)
            }
            is TraceEvent.NetAvail -> {
                append("NET_AVAIL ")
                append(event.value.name)
            }
            is TraceEvent.Net -> {
                append("NET_ID ")
                append(encodeNetworkId(event.id))
            }
            is TraceEvent.Liveness -> {
                append("LIVENESS ")
                append(event.result.name)
            }
        }
    }

internal fun decodeTraceLine(line: String): TraceEvent {
    val afterVersion =
        line.removePrefix("v1 ").also {
            require(it !== line) { "unsupported trace line (expected 'v1 ' prefix): $line" }
        }
    val tEnd = afterVersion.indexOf(' ')
    require(tEnd > 0) { "malformed trace line (no timestamp): $line" }
    val at = afterVersion.substring(0, tEnd).toLong()
    val rest = afterVersion.substring(tEnd + 1)
    val evEnd = rest.indexOf(' ')
    val eventName = if (evEnd < 0) rest else rest.substring(0, evEnd)
    val fields = if (evEnd < 0) "" else rest.substring(evEnd + 1)
    return when (eventName) {
        "DGRAM_OUT", "DGRAM_IN" -> {
            val (lenStr, pathStr, hex) = fields.split(' ', limit = 3)
            val len = lenStr.toInt()
            require(hex.length == len * 2) { "DGRAM payload hex length ${hex.length} != 2*len ($len): $line" }
            val path = decodeTracePath(pathStr)
            if (eventName == "DGRAM_OUT") {
                TraceEvent.DgramOut(at, len, path, hex)
            } else {
                TraceEvent.DgramIn(at, len, path, hex)
            }
        }
        "STATE" -> {
            val sp = fields.indexOf(' ')
            if (sp < 0) {
                TraceEvent.State(at, fields, null)
            } else {
                TraceEvent.State(at, fields.substring(0, sp), fields.substring(sp + 1))
            }
        }
        "PATH_STATE" -> {
            val (phase, host, port) = fields.split(' ', limit = 3)
            TraceEvent.PathState(at, phase, host.takeUnless { it == "-" }, port.toInt())
        }
        "ERROR" -> {
            val sp = fields.indexOf(' ')
            if (sp < 0) {
                TraceEvent.Error(at, fields, "")
            } else {
                TraceEvent.Error(at, fields.substring(0, sp), fields.substring(sp + 1))
            }
        }
        "STATS" -> {
            val f = fields.split(' ')
            require(f.size == 18) { "STATS expects 18 fields, got ${f.size}: $line" }
            TraceEvent.Stats(
                at,
                TracePathStats(
                    validationState = f[0].toLong(),
                    active = f[1] == "1",
                    recv = f[2].toLong(),
                    sent = f[3].toLong(),
                    lost = f[4].toLong(),
                    retrans = f[5].toLong(),
                    totalPtoCount = f[6].toLong(),
                    rtt = f[7].toLong().nanoseconds,
                    minRtt = f[8].toLong().nanoseconds,
                    maxRtt = f[9].toLong().nanoseconds,
                    rttvar = f[10].toLong().nanoseconds,
                    cwnd = f[11].toLong(),
                    sentBytes = f[12].toLong(),
                    recvBytes = f[13].toLong(),
                    lostBytes = f[14].toLong(),
                    streamRetransBytes = f[15].toLong(),
                    pmtu = f[16].toLong(),
                    deliveryRate = f[17].toLong(),
                ),
            )
        }
        "NET_AVAIL" -> TraceEvent.NetAvail(at, NetworkAvailability.valueOf(fields))
        "NET_ID" -> TraceEvent.Net(at, decodeNetworkId(fields))
        "LIVENESS" -> TraceEvent.Liveness(at, TransportLiveness.Result.valueOf(fields))
        else -> throw IllegalArgumentException("unknown trace event '$eventName': $line")
    }
}

/** Newlines would break the one-event-per-line invariant — flatten them (diagnostic fields only). */
private fun flattenLine(s: String): String = if ('\n' in s || '\r' in s) s.replace('\n', ' ').replace('\r', ' ') else s

// --- TracePath: "-" (unknown) or family:port:hiHex:loHex ---

internal fun encodeTracePath(path: TracePath?): String =
    if (path == null) {
        "-"
    } else {
        "${path.family}:${path.port}:${path.hi.toULong().toString(16)}:${path.lo.toULong().toString(16)}"
    }

internal fun decodeTracePath(s: String): TracePath? {
    if (s == "-") return null
    val p = s.split(':')
    require(p.size == 4) { "malformed TracePath '$s'" }
    return TracePath(p[0].toInt(), p[1].toInt(), p[2].toULong(16).toLong(), p[3].toULong(16).toLong())
}

// --- NetworkId: Unidentified | KindOnly:<kind> | Link:<kind>:<handle> ---
// <kind> := Wifi | Cellular | Ethernet | Vpn(<kind>,<kind>,...) | Other(<escaped>)
// <kind> never contains ':' or ' ' (Other's raw label is %-escaped), so Link splits on the LAST ':'.

internal fun encodeNetworkId(id: NetworkId): String =
    when (id) {
        is NetworkId.Unidentified -> "Unidentified"
        is NetworkId.KindOnly -> "KindOnly:${encodeKind(id.kind)}"
        is NetworkId.Link -> "Link:${encodeKind(id.kind)}:${id.handle}"
    }

internal fun decodeNetworkId(s: String): NetworkId =
    when {
        s == "Unidentified" -> NetworkId.Unidentified
        s.startsWith("KindOnly:") -> NetworkId.KindOnly(decodeKind(s.removePrefix("KindOnly:")))
        s.startsWith("Link:") -> {
            val body = s.removePrefix("Link:")
            val sep = body.lastIndexOf(':')
            require(sep > 0) { "malformed NetworkId '$s'" }
            NetworkId.Link(decodeKind(body.substring(0, sep)), body.substring(sep + 1).toLong())
        }
        else -> throw IllegalArgumentException("malformed NetworkId '$s'")
    }

private fun encodeKind(kind: NetworkKind): String =
    when (kind) {
        NetworkKind.Wifi -> "Wifi"
        NetworkKind.Cellular -> "Cellular"
        NetworkKind.Ethernet -> "Ethernet"
        is NetworkKind.Vpn -> "Vpn(${kind.transports.joinToString(",") { encodeKind(it) }})"
        is NetworkKind.Other -> "Other(${escapeLabel(kind.raw)})"
    }

private fun decodeKind(s: String): NetworkKind =
    when {
        s == "Wifi" -> NetworkKind.Wifi
        s == "Cellular" -> NetworkKind.Cellular
        s == "Ethernet" -> NetworkKind.Ethernet
        s.startsWith("Vpn(") && s.endsWith(")") -> {
            val inner = s.substring(4, s.length - 1)
            if (inner.isEmpty()) {
                NetworkKind.Vpn(emptySet())
            } else {
                NetworkKind.Vpn(splitTopLevel(inner).map { decodeKind(it) }.toSet())
            }
        }
        s.startsWith("Other(") && s.endsWith(")") ->
            NetworkKind.Other(unescapeLabel(s.substring(6, s.length - 1)))
        else -> throw IllegalArgumentException("malformed NetworkKind '$s'")
    }

/** Split on commas that are not nested inside `Vpn(...)` parentheses. */
private fun splitTopLevel(s: String): List<String> {
    val out = mutableListOf<String>()
    var depth = 0
    var start = 0
    for (i in s.indices) {
        when (s[i]) {
            '(' -> depth++
            ')' -> depth--
            ',' ->
                if (depth == 0) {
                    out += s.substring(start, i)
                    start = i + 1
                }
        }
    }
    out += s.substring(start)
    return out
}

// Minimal %-escape for NetworkKind.Other's free-form platform label — the only free-form string in
// the NET_ID grammar — so a raw label can never contain the delimiters ' ', ':', '(', ')', ','.
// Char-level (no ByteArray): non-delimiter characters, including non-ASCII, pass through unchanged.

private fun escapeLabel(raw: String): String =
    buildString(raw.length) {
        for (c in raw) {
            when (c) {
                '%' -> append("%25")
                ' ' -> append("%20")
                ':' -> append("%3A")
                '(' -> append("%28")
                ')' -> append("%29")
                ',' -> append("%2C")
                '\n' -> append("%0A")
                '\r' -> append("%0D")
                else -> append(c)
            }
        }
    }

private fun unescapeLabel(escaped: String): String =
    buildString(escaped.length) {
        var i = 0
        while (i < escaped.length) {
            val c = escaped[i]
            if (c == '%' && i + 3 <= escaped.length) {
                append(escaped.substring(i + 1, i + 3).toInt(16).toChar())
                i += 3
            } else {
                append(c)
                i++
            }
        }
    }
