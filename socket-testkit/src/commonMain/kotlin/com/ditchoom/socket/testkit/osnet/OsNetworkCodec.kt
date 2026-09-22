package com.ditchoom.socket.testkit.osnet

import com.ditchoom.socket.NetworkState
import com.ditchoom.socket.testkit.trace.decodeInternetAccess
import com.ditchoom.socket.testkit.trace.decodeNetworkId
import com.ditchoom.socket.testkit.trace.encodeInternetAccess
import com.ditchoom.socket.testkit.trace.encodeNetworkId
import com.ditchoom.socket.testkit.trace.escapeLabel
import com.ditchoom.socket.testkit.trace.unescapeLabel

// The one rendering of OsNetworkFacts, used verbatim by the probe log's `OS-NET` line and the replay
// trace's `OS_NET` line. Every field is a space-free `key=value`, so the fields are read by name and
// the whole record still fits the one-event-per-line trace grammar.
//
// `v4`/`v6` are derived from `links` and are written anyway, because they are the summary a reader
// scans a 700,000-line walk log for. Decoding recomputes them from `links` rather than trusting them,
// so a hand-edited line cannot make the two disagree.

internal fun encodeOsNetworkFacts(facts: OsNetworkFacts): String =
    "state=${encodeOsNetworkState(facts.state)} v4=${facts.v4.name} v6=${facts.v6.name} " +
        "cell=${encodeCellular(facts.cellular)} links=${encodeLinks(facts.links)}"

internal fun decodeOsNetworkFacts(fields: String): OsNetworkFacts {
    val byKey = mutableMapOf<String, String>()
    for (token in fields.split(' ')) {
        val sep = token.indexOf('=')
        require(sep > 0) { "malformed OS_NET field '$token' in '$fields'" }
        byKey[token.substring(0, sep)] = token.substring(sep + 1)
    }

    fun field(name: String): String = byKey[name] ?: throw IllegalArgumentException("OS_NET is missing '$name=': '$fields'")
    return OsNetworkFacts(
        state = decodeOsNetworkState(field("state")),
        cellular = decodeCellular(field("cell")),
        links = decodeLinks(field("links")),
    )
}

// --- NetworkState, as ONE token ---------------------------------------------------------------
// Unknown | Offline | LinkLocal|<id> | Routable|<id>|<internet>
//
// Pipe-joined rather than space-joined (as the standalone `NET` line writes it) so the whole state
// is a single `key=value` value: a variable-arity field would have to be last and could never be
// followed by another, which is exactly the constraint that makes a line grammar impossible to
// extend. '|' appears in no NetworkId — NetworkKind.Other's free-form label escapes it.

internal fun encodeOsNetworkState(state: NetworkState): String =
    when (state) {
        NetworkState.Unknown -> "Unknown"
        NetworkState.Offline -> "Offline"
        is NetworkState.LinkLocal -> "LinkLocal|${encodeNetworkId(state.id)}"
        is NetworkState.Routable -> "Routable|${encodeNetworkId(state.id)}|${encodeInternetAccess(state.internet)}"
    }

internal fun decodeOsNetworkState(s: String): NetworkState {
    val f = s.split('|')
    return when (f[0]) {
        "Unknown" -> NetworkState.Unknown
        "Offline" -> NetworkState.Offline
        "LinkLocal" -> {
            require(f.size == 2) { "LinkLocal expects 1 field (id), got ${f.size - 1}: '$s'" }
            NetworkState.LinkLocal(decodeNetworkId(f[1]))
        }
        "Routable" -> {
            require(f.size == 3) { "Routable expects 2 fields (id, internet), got ${f.size - 1}: '$s'" }
            NetworkState.Routable(decodeNetworkId(f[1]), decodeInternetAccess(f[2]))
        }
        else -> throw IllegalArgumentException("malformed NetworkState '$s'")
    }
}

// --- CellularStatus ---------------------------------------------------------------------------
// NotReported | Reported(sim=<SimState>,reg=<CellularRegistration>,data=<CellularData>,
//                        roaming=<CellularRoaming>,bearer=<CellularBearer>)

private fun encodeCellular(status: CellularStatus): String =
    when (status) {
        CellularStatus.NotReported -> "NotReported"
        is CellularStatus.Reported ->
            "Reported(sim=${status.sim.name},reg=${status.registration.name},data=${status.data.name}," +
                "roaming=${status.roaming.name},bearer=${encodeBearer(status.bearer)})"
    }

private fun decodeCellular(s: String): CellularStatus {
    if (s == "NotReported") return CellularStatus.NotReported
    require(s.startsWith("Reported(") && s.endsWith(")")) { "malformed CellularStatus '$s'" }
    val byKey =
        s.substring("Reported(".length, s.length - 1).split(',').associate { token ->
            val sep = token.indexOf('=')
            require(sep > 0) { "malformed CellularStatus field '$token' in '$s'" }
            token.substring(0, sep) to token.substring(sep + 1)
        }

    fun field(name: String): String = byKey[name] ?: throw IllegalArgumentException("CellularStatus is missing '$name=': '$s'")
    return CellularStatus.Reported(
        sim = SimState.valueOf(field("sim")),
        registration = CellularRegistration.valueOf(field("reg")),
        data = CellularData.valueOf(field("data")),
        roaming = CellularRoaming.valueOf(field("roaming")),
        bearer = decodeBearer(field("bearer")),
    )
}

private fun encodeBearer(bearer: CellularBearer): String =
    when (bearer) {
        CellularBearer.NotReported -> "NotReported"
        CellularBearer.None -> "None"
        CellularBearer.Gsm -> "Gsm"
        CellularBearer.Umts -> "Umts"
        CellularBearer.Cdma -> "Cdma"
        CellularBearer.Lte -> "Lte"
        CellularBearer.Nr -> "Nr"
        is CellularBearer.Other -> "Other(${escapeLabel(bearer.raw)})"
    }

private fun decodeBearer(s: String): CellularBearer =
    when {
        s == "NotReported" -> CellularBearer.NotReported
        s == "None" -> CellularBearer.None
        s == "Gsm" -> CellularBearer.Gsm
        s == "Umts" -> CellularBearer.Umts
        s == "Cdma" -> CellularBearer.Cdma
        s == "Lte" -> CellularBearer.Lte
        s == "Nr" -> CellularBearer.Nr
        s.startsWith("Other(") && s.endsWith(")") -> CellularBearer.Other(unescapeLabel(s.substring(6, s.length - 1)))
        else -> throw IllegalArgumentException("malformed CellularBearer '$s'")
    }

// --- links ------------------------------------------------------------------------------------
// '-' (none) | name=<addr>,<addr>;name=<addr>   — a link with no address renders its list as '-'.
// Addresses are numeric literals with the zone stripped, so they hold none of the delimiters; a
// link NAME is escaped, since nothing but convention keeps a platform from putting one in it.

private fun encodeLinks(links: List<OsLink>): String =
    if (links.isEmpty()) {
        "-"
    } else {
        links.joinToString(";") { link ->
            "${escapeLabel(link.name)}=" + if (link.addresses.isEmpty()) "-" else link.addresses.joinToString(",")
        }
    }

private fun decodeLinks(s: String): List<OsLink> =
    if (s == "-") {
        emptyList()
    } else {
        s.split(';').map { entry ->
            val sep = entry.indexOf('=')
            require(sep > 0) { "malformed link '$entry' in '$s'" }
            val addresses = entry.substring(sep + 1)
            OsLink(
                name = unescapeLabel(entry.substring(0, sep)),
                addresses = if (addresses == "-") emptyList() else addresses.split(','),
            )
        }
    }
