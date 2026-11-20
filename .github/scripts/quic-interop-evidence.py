#!/usr/bin/env python3
"""What one interop run shows about each test, for the gate to check failure signatures against.

Usage: quic-interop-evidence.py <runner logs dir> <direction> <peer> <out.json>

The runner writes <logs>/<server>_<client>/<test>/ with the two endpoints' logs and its own output.txt. Two
sources are read, both written by something this repository controls or the runner itself:
  - our endpoint's qlog (quiche's, under <our side>/qlog/*.sqlog): one record per connection with the
    transport parameters each side set, the MAX_STREAMS we sent, the highest stream the peer opened, the
    source connection id lengths of the peer's Initial packets and the CONNECTION_CLOSE frames we sent;
  - the runner's output.txt: each container's exit code, our client's MIGRATION-SUMMARY line (its typed
    move results) when it migrated, the runner's handshake count and time limit, what the simulator did
    with each datagram addressed to our endpoint, and how many of the peer's datagrams a drop-list scenario
    (`droplist --drops_to_server=...`) dropped.

Per connection the qlog also gives what the peer sent in the handshake spaces, how many contiguous Initial
CRYPTO bytes arrived, when we last heard from it, how the connection ended, what was still in flight then, how
many PTO expiries passed with nothing sent, the peer-opened streams that never received a FIN, the streams we answered and how fast, when our first stream data
went out, when the peer first acknowledged it, when its HANDSHAKE_DONE arrived, and every RESET_STREAM the peer
sent with how many copies of that stream's first bytes we had sent. After our last receipt it also gives how our
retransmission timer behaved: how many probe bursts left out a packet number space that still had CRYPTO or
STREAM data unacknowledged (RFC 9002 §6.2.4), and how many came later than one PTO, doubled per expiry, after
the send before them (or the connection ended past such an expiry with data in flight).

A third source, the simulator's captures (sim/trace_node_left.pcap on the client's side of the link,
trace_node_right.pcap on the server's), ties each connection to its UDP 4-tuple through the source connection id
of our long-header packets, which travels in the clear (the peer's datagrams are those addressed to a connection id
of ours). Its "wire" record says what the peer sent between our last receipt and the connection's end and how much
of it reached us unchanged, and how many of the peer's packets reached us unchanged, in a space we had already used,
with no logged receipt while a later packet of that space was read: a packet we should have read and did not. A
peer report tied to the tuple (the stock quiche client names its local port before it gives up on requests) is
"peer_gave_up_requests".

The output maps test -> {"connections": [...], "exits": {"client": n, "server": n}, "migrations": [...]}. A
connection, exit or summary the logs do not show is absent, never written as a placeholder.
"""
import collections
import json
import os
import re
import struct
import sys

EXIT_LINE = re.compile(r"\b(client|server|sim) exited with code (\d+)")
HANDSHAKES = re.compile(r"Expected (\d+) handshakes\. Got: (\d+)")
TIME_LIMIT = re.compile(r"took longer than (\d+)s")
NO_PATH_CHALLENGE = re.compile(r"First server packet on new path .* did not contain a PATH_CHALLENGE frame")
EMPTY_DOWNLOAD = re.compile(r"doesn't match\. Original: \d+ bytes, downloaded: 0 bytes")
SIM_DATAGRAM = re.compile(r"\b(Forwarding|Corrupting|Dropping) \d+ bytes [\d.]+:(\d+) -> ([\d.]+):(\d+)")
SIM_DROPLIST = re.compile(r"\bDropping packet \d+ \(\d+ bytes\) from ([\d.]+)")
OUR_ADDRESS = {"client": "193.167.0.100", "server": "193.167.100.100"}
PEER_ADDRESS = {"client": OUR_ADDRESS["server"], "server": OUR_ADDRESS["client"]}
MIGRATION_SUMMARY = re.compile(r"MIGRATION-SUMMARY attempted=(\d+) succeeded=(\d+)(?:.*?firstUnmoved=move \d+: (\w+))?")
PEER_IDLE_TIMEOUT = re.compile(r"connection timed out after [\d.]+s and only completed (\d+)/(\d+) requests")
PEER_IDLE_REQUEST_FAILED = re.compile(r"Request \S+ failed: .*IdleTimerExpired")
TRANSPORT_SPACE = "transport"


def our_side(direction):
    return "server" if "our-server" in direction else "client"


def peer_bidi_remainder(side):
    """Stream ids the peer opens bidirectionally: client-initiated are 0 mod 4, server-initiated 1 mod 4."""
    return 0 if side == "server" else 1


def qlog_records(path):
    with open(path, errors="replace") as f:
        for line in f:
            line = line.strip().lstrip("\x1e")
            if not line:
                continue
            try:
                yield json.loads(line)
            except json.JSONDecodeError:
                continue


def acked(ranges, packet_number):
    """Whether a qlog ACK frame's acked_ranges ([[n]] or [[low, high]] entries) cover packet_number."""
    return any(r and r[0] <= packet_number <= r[-1] for r in ranges)


def contiguous_end(ranges):
    """How far from offset 0 the (start, end) byte ranges cover without a gap."""
    end = 0
    for start, stop in sorted(ranges):
        if start > end:
            break
        end = max(end, stop)
    return end


def connection_facts(path, side):
    facts = {
        "qlog": os.path.basename(path),
        "local_initial_max_streams_bidi": 0,
        "remote_disable_active_migration": False,
        "peer_source_cid_lengths": [],
        "max_streams_bidi_sent": 0,
        "peer_bidi_streams_opened": 0,
        "local_closes": [],
        "rx_packets": 0,
        "rx_initial_crypto": 0,
        "rx_initial_acks": 0,
        "rx_handshake_crypto": 0,
        "rx_handshake_acks": 0,
        "tx_crypto": 0,
        "tx_after_last_rx": 0,
        "pto_expiries_after_last_tx": 0,
        "bytes_in_flight_at_end": 0,
        "longest_wait_in_flight_ms": 0.0,
        "fin_sent_streams": 0,
        "peer_resets": [],
    }
    remainder = peer_bidi_remainder(side)
    first_rx, first_tx, fin_sent, copies = {}, {}, set(), {}
    fin_rx, reset_rx = set(), set()
    initial_crypto = []
    initial_rx, initial_acked = set(), set()
    stream_packets = set()
    last_tx = -1.0
    for record in qlog_records(path):
        name = record.get("name", "")
        data = record.get("data") or {}
        timed = isinstance(record.get("time"), (int, float))
        at = float(record["time"]) if timed else 0.0
        if timed:
            facts["end_ms"] = at
        if name.endswith("recovery_metrics_updated") and "bytes_in_flight" in data:
            facts["bytes_in_flight_at_end"] = int(data["bytes_in_flight"])
        if name.endswith("recovery_metrics_updated") and int(data.get("pto_count", 0)) > 0:
            facts["pto_expiries_after_last_tx"] += 1
        if name.endswith("connection_closed") and data.get("reason"):
            facts["close_reason"] = data["reason"]
        if name.endswith("packet_received"):
            facts["rx_packets"] += 1
            facts["last_rx_ms"] = at
            facts["tx_after_last_rx"] = 0
            space = (data.get("header") or {}).get("packet_type", "")
            frames = {f.get("frame_type") for f in data.get("frames") or []}
            if space == "initial" and "packet_number" in (data.get("header") or {}):
                initial_rx.add(int(data["header"]["packet_number"]))
            if space in ("initial", "handshake"):
                facts[f"rx_{space}_crypto"] += "crypto" in frames
                facts[f"rx_{space}_acks"] += "ack" in frames
            for frame in data.get("frames") or []:
                if frame.get("frame_type") == "crypto" and space == "initial":
                    length = int(frame.get("length", (frame.get("raw") or {}).get("payload_length", 0)))
                    offset = int(frame.get("offset", 0))
                    initial_crypto.append((offset, offset + length))
                elif frame.get("frame_type") == "handshake_done":
                    facts.setdefault("handshake_done_rx_ms", at)
                elif frame.get("frame_type") == "ack" and space == "1RTT" and "first_stream_ack_ms" not in facts:
                    if any(acked(frame.get("acked_ranges") or [], n) for n in stream_packets):
                        facts["first_stream_ack_ms"] = at
                if frame.get("frame_type") == "stream":
                    first_rx.setdefault(int(frame.get("stream_id", 0)), at)
                    if frame.get("fin"):
                        fin_rx.add(int(frame.get("stream_id", 0)))
                elif frame.get("frame_type") == "reset_stream":
                    reset_rx.add(int(frame.get("stream_id", 0)))
                    facts["peer_resets"].append(
                        {"stream_id": int(frame.get("stream_id", 0)), "final_size": int(frame.get("final_size", 0))}
                    )
        elif name.endswith("packet_sent"):
            facts["tx_after_last_rx"] += 1
            facts["pto_expiries_after_last_tx"] = 0
            header = data.get("header") or {}
            if header.get("packet_type") == "initial":
                for frame in data.get("frames") or []:
                    if frame.get("frame_type") == "ack":
                        for r in frame.get("acked_ranges") or []:
                            if r:
                                initial_acked.update(range(int(r[0]), int(r[-1]) + 1))
            carries_stream = any(f.get("frame_type") == "stream" for f in data.get("frames") or [])
            if carries_stream and header.get("packet_type") == "1RTT" and "packet_number" in header:
                stream_packets.add(int(header["packet_number"]))
            facts["tx_crypto"] += any(f.get("frame_type") == "crypto" for f in data.get("frames") or [])
            # How long we waited to send again while data was in flight: our own retransmission timer's gaps.
            if last_tx >= 0 and facts["bytes_in_flight_at_end"] > 0:
                facts["longest_wait_in_flight_ms"] = max(facts["longest_wait_in_flight_ms"], at - last_tx)
            last_tx = at
            for frame in data.get("frames") or []:
                if frame.get("frame_type") == "handshake_done":
                    facts.setdefault("handshake_done_sent_ms", at)
                if frame.get("frame_type") == "stream":
                    stream_id = int(frame.get("stream_id", 0))
                    first_tx.setdefault(stream_id, at)
                    if frame.get("fin"):
                        fin_sent.add(stream_id)
                    if int(frame.get("offset", 0)) == 0:
                        copies[stream_id] = copies.get(stream_id, 0) + 1
        if name.endswith("parameters_set"):
            if data.get("initiator") == "local" and "initial_max_streams_bidi" in data:
                facts["local_initial_max_streams_bidi"] = int(data["initial_max_streams_bidi"])
            if data.get("initiator") == "remote" and "disable_active_migration" in data:
                facts["remote_disable_active_migration"] = bool(data["disable_active_migration"])
        elif name.endswith("packet_sent"):
            for frame in data.get("frames") or []:
                kind = frame.get("frame_type")
                if kind == "max_streams" and frame.get("stream_type") == "bidirectional":
                    facts["max_streams_bidi_sent"] = max(facts["max_streams_bidi_sent"], int(frame.get("maximum", 0)))
                elif kind == "connection_close":
                    facts["local_closes"].append(
                        {"space": frame.get("error_space", ""), "code": int(frame.get("error_code", 0))}
                    )
        elif name.endswith("packet_received"):
            header = data.get("header") or {}
            if header.get("packet_type") == "initial" and "scil" in header and int(header["scil"]) not in facts["peer_source_cid_lengths"]:
                facts["peer_source_cid_lengths"].append(int(header["scil"]))
            for frame in data.get("frames") or []:
                if frame.get("frame_type") == "stream":
                    stream_id = int(frame.get("stream_id", 0))
                    if stream_id % 4 == remainder:
                        facts["peer_bidi_streams_opened"] = max(facts["peer_bidi_streams_opened"], stream_id // 4 + 1)
    facts["fin_sent_streams"] = len(fin_sent)
    # Initial packets we acknowledged that the qlog never shows arriving: a server's qlog opens after the datagram
    # that created the connection was processed, so that datagram's packet appears only in our acknowledgements.
    facts["rx_initial_unlogged"] = len(initial_acked - initial_rx)
    # Requests the peer opened and began but never finished: stream data arrived, no FIN and no RESET_STREAM ever did.
    facts["peer_unfinished_streams"] = len([s for s in first_rx if s % 4 == remainder and s not in fin_rx and s not in reset_rx])
    # Requests of ours (bidirectional streams we opened) whose response never finished: no FIN and no reset arrived.
    facts["our_streams_unfinished"] = len([s for s in first_tx if s % 4 == 1 - remainder and s not in fin_rx and s not in reset_rx])
    if initial_crypto:
        facts["rx_initial_crypto_end"] = contiguous_end(initial_crypto)
    if first_tx:
        facts["first_stream_tx_ms"] = min(first_tx.values())
    if copies:
        facts["max_stream_copies"] = max(copies.values())
    for reset in facts["peer_resets"]:
        reset["our_copies"] = copies.get(reset["stream_id"], 0)
    answers = [first_tx[s] - first_rx[s] for s in first_rx if s in first_tx and s % 4 == remainder]
    if answers:
        facts["max_answer_ms"] = max(answers)
    requests = [first_rx[s] for s in first_rx if s % 4 == remainder]
    if requests:
        facts["first_request_ms"] = min(requests)
    # One close per connection is what is judged; quiche repeats it in every packet of the closing period.
    facts["local_closes"] = [dict(t) for t in {tuple(sorted(c.items())) for c in facts["local_closes"]}]
    return facts


CAPTURE = {"client": "trace_node_left.pcap", "server": "trace_node_right.pcap"}
PCAPNG_SECTION, PCAPNG_INTERFACE, PCAPNG_PACKET = 0x0A0D0D0A, 1, 6
LINKTYPE_ETHERNET, ETHERTYPE_IPV4, IPPROTO_UDP = 1, 0x0800, 17
LONG_SPACE = {0: "initial", 1: "0RTT", 2: "handshake"}
# A qlog time and a capture time of the same datagram differ by the scheduling between them; closer than this
# to a boundary, a datagram is judged on neither side of it.
WIRE_SLACK_MS = 5.0
# How long after a datagram reached our side our endpoint may take to log reading it.
READ_LAG_MS = 250.0
# A burst of probes: our ack-eliciting packets sent this close together answer one timer expiry.
BURST_MS = 2.0
# A probe is late when it waits longer than its PTO times this, plus PTO_SLACK_MS (max_ack_delay, timer slack).
PTO_TOLERANCE, PTO_SLACK_MS = 1.1, 50.0
DEFAULT_MAX_ACK_DELAY_MS = 25.0  # RFC 9000 §18.2
NOT_ACK_ELICITING = {"ack", "padding", "connection_close"}
QUICHE_CLIENT_CLOSED = re.compile(r"connection closed, .*local_addr=[^ ]*:(\d+) ")


def read_capture(path):
    """[(time ms, source address, source port, destination address, destination port, UDP payload)] of a pcapng
    file's IPv4 UDP datagrams; [] when the file is absent."""
    if not os.path.exists(path):
        return []
    with open(path, "rb") as f:
        data = f.read()
    out, divisors, endian, i = [], [], "<", 0
    while i + 12 <= len(data):
        if struct.unpack_from("<I", data, i)[0] == PCAPNG_SECTION:
            endian = "<" if data[i + 8:i + 12] == b"\x4d\x3c\x2b\x1a" else ">"
            divisors = []
        kind, length = struct.unpack_from(endian + "II", data, i)
        if length < 12:
            break
        if kind == PCAPNG_INTERFACE:
            divisor, j = 10 ** 6, i + 16
            while j + 4 <= i + length - 4:
                code, size = struct.unpack_from(endian + "HH", data, j)
                if code == 0:
                    break
                if code == 9:
                    r = data[j + 4]
                    divisor = 2 ** (r & 0x7F) if r & 0x80 else 10 ** r
                j += 4 + (size + 3) // 4 * 4
            divisors.append((struct.unpack_from(endian + "H", data, i + 8)[0], divisor))
        elif kind == PCAPNG_PACKET:
            iface, high, low, captured = struct.unpack_from(endian + "IIII", data, i + 8)
            frame = data[i + 28:i + 28 + captured]
            if iface < len(divisors) and divisors[iface][0] == LINKTYPE_ETHERNET and len(frame) >= 42:
                ip = frame[14:]
                if struct.unpack_from(">H", frame, 12)[0] == ETHERTYPE_IPV4 and ip[9] == IPPROTO_UDP:
                    udp = ip[(ip[0] & 0x0F) * 4:]
                    sport, dport, ulen = struct.unpack_from(">HHH", udp, 0)
                    at = ((high << 32) | low) * 1000.0 / divisors[iface][1]
                    out.append((at, ".".join(map(str, ip[12:16])), sport, ".".join(map(str, ip[16:20])), dport, bytes(udp[8:ulen])))
        i += length
    return out


def varint(buf, at):
    """(value, next offset) of the QUIC variable-length integer at buf[at]."""
    size = 1 << (buf[at] >> 6)
    value = buf[at] & 0x3F
    for b in buf[at + 1:at + size]:
        value = (value << 8) | b
    return value, at + size


def wire_packets(payload, short_dcid_length):
    """[(space, destination connection id, source connection id or b"")] of the QUIC packets coalesced in one
    datagram, read from their unprotected header fields alone; a short header (1-RTT) runs to the datagram's end."""
    out, at = [], 0
    try:
        while at < len(payload) and any(payload[at:]):
            first = payload[at]
            if not first & 0x80:
                out.append(("1RTT", payload[at + 1:at + 1 + short_dcid_length], b""))
                break
            dcid = payload[at + 6:at + 6 + payload[at + 5]]
            scid_at = at + 6 + payload[at + 5]
            scid = payload[scid_at + 1:scid_at + 1 + payload[scid_at]]
            space = LONG_SPACE.get((first >> 4) & 3)
            if space is None or struct.unpack_from(">I", payload, at + 1)[0] == 0:
                break  # Retry or Version Negotiation: no packet number space
            p = scid_at + 1 + payload[scid_at]
            if space == "initial":
                token, p = varint(payload, p)
                p += token
            length, p = varint(payload, p)
            out.append((space, dcid, scid))
            at = p + length
    except (IndexError, struct.error):
        pass
    return out


def our_connection_ids(records):
    """(our first source connection id, every id the peer may address this connection by: that one, those we
    issued in NEW_CONNECTION_ID, and the original destination id a client chose for our server)."""
    scid, ids = None, set()
    for r in records:
        data = r.get("data") or {}
        name = r.get("name", "")
        if name.endswith("packet_sent"):
            if scid is None and (data.get("header") or {}).get("scid"):
                scid = bytes.fromhex(data["header"]["scid"])
            ids.update(bytes.fromhex(f["connection_id"]) for f in data.get("frames") or []
                       if f.get("frame_type") == "new_connection_id" and f.get("connection_id"))
        elif name.endswith("parameters_set") and data.get("initiator") == "local" and data.get("original_destination_connection_id"):
            ids.add(bytes.fromhex(data["original_destination_connection_id"]))
    if scid is not None:
        ids.add(scid)
    return scid, ids


def wire_facts(records, side, facts, captures):
    """The connection's record on the wire, or None when the captures do not show it: its datagrams are those on
    its UDP 4-tuple addressed to a connection id of ours (a peer may reuse a port across connections)."""
    ours, theirs = OUR_ADDRESS[side], PEER_ADDRESS[side]
    our_capture, peer_capture = captures[side], captures["server" if side == "client" else "client"]
    scid, ids = our_connection_ids(records)
    first_tx = next((float(r["time"]) for r in records if r.get("name", "").endswith("packet_sent") and isinstance(r.get("time"), (int, float))), None)
    if scid is None or first_tx is None:
        return None
    first_ours = next((d for d in our_capture if d[1] == ours and any(p[2] == scid for p in wire_packets(d[5], len(scid)))), None)
    if first_ours is None:
        return None
    _, _, our_port, _, peer_port, _ = first_ours
    offset = first_ours[0] - first_tx  # capture ms minus qlog ms

    def from_peer(d):
        packets = wire_packets(d[5], len(scid))
        return d[1] == theirs and d[2] == peer_port and d[4] == our_port and bool(packets) and packets[0][1] in ids

    arrived = collections.defaultdict(list)
    for d in our_capture:
        if from_peer(d):
            arrived[d[5]].append(d[0] - offset)
    delivered = []  # (arrival in qlog ms, payload) of each datagram of the peer's that reached us unchanged
    sent_after = 0
    last_rx, end = facts.get("last_rx_ms", float("-inf")), facts.get("end_ms", float("inf"))
    for d in peer_capture:
        if from_peer(d):
            sent_after += last_rx < d[0] - offset <= end
            if arrived[d[5]]:
                delivered.append((arrived[d[5]].pop(0), d[5]))
    return {
        "port": our_port if side == "client" else peer_port,
        "peer_sent_after_last_rx": sent_after,
        "peer_delivered_after_last_rx": sum(last_rx + WIRE_SLACK_MS < t <= end for t, _ in delivered),
        "peer_packets_unread": unread_packets(records, sorted(delivered), len(scid)),
    }


def unread_packets(records, delivered, short_dcid_length):
    """How many packets of the peer's reached us unchanged, after we first logged a packet of their space, without
    a logged receipt of that space within READ_LAG_MS (our endpoint's own scheduling), while a later packet of the
    same space that reached us was read: a gap in our reading, not keys we had already discarded."""
    first_logged, logged_rx = {}, collections.defaultdict(list)
    for r in records:
        name = r.get("name", "")
        if (name.endswith("packet_sent") or name.endswith("packet_received")) and isinstance(r.get("time"), (int, float)):
            space = ((r.get("data") or {}).get("header") or {}).get("packet_type", "")
            first_logged.setdefault(space, float(r["time"]))
            if name.endswith("packet_received"):
                logged_rx[space].append(float(r["time"]))
    unmatched, last_read = collections.defaultdict(list), {}
    for t, payload in delivered:
        for space, _, _ in wire_packets(payload, short_dcid_length):
            if space not in first_logged or t <= first_logged[space]:
                continue
            match = next((i for i, at in enumerate(logged_rx[space]) if t - WIRE_SLACK_MS <= at <= t + READ_LAG_MS), None)
            if match is None:
                unmatched[space].append(t)
            else:
                del logged_rx[space][match]
                last_read[space] = t
    return sum(1 for space, times in unmatched.items() for t in times if t < last_read.get(space, float("-inf")))


def probe_facts(records, side):
    """(probe bursts after our last receipt that left out a space with unacknowledged CRYPTO or STREAM data, probe
    waits longer than PTO x 2^expiries): our retransmission timer judged against RFC 9002 §6.2 from our qlog."""
    timed = [r for r in records if isinstance(r.get("time"), (int, float))]
    if not timed:
        return 0, 0
    received = [float(r["time"]) for r in timed if r.get("name", "").endswith("packet_received")]
    last_rx = received[-1] if received else float("-inf")
    end = float(timed[-1]["time"])
    sent_data = collections.defaultdict(dict)  # space -> packet number -> data units
    acked_pns = collections.defaultdict(set)
    discarded = set()
    rtt, in_flight, max_ack_delay, closed = (333.0, 166.5, 0.0), 0, DEFAULT_MAX_ACK_DELAY_MS, False
    bursts = []  # [time, spaces it carried data in, spaces with unacknowledged data before it, rtt estimate then]
    last_eliciting = None  # (time, rtt) of our last ack-eliciting send at or before our last receipt
    for r in timed:
        name, data, at = r["name"], r.get("data") or {}, float(r["time"])
        space = ((data.get("header") or {}).get("packet_type", "")).replace("0RTT", "1RTT")
        frames = data.get("frames") or []
        kinds = {f.get("frame_type") for f in frames}
        closed |= "connection_close" in kinds
        if name.endswith("recovery_metrics_updated"):
            rtt = (float(data.get("smoothed_rtt", rtt[0])), float(data.get("rtt_variance", rtt[1])), rtt[2])
            in_flight = int(data.get("bytes_in_flight", in_flight))
        elif name.endswith("parameters_set") and data.get("initiator") == "remote" and "max_ack_delay" in data:
            max_ack_delay = float(data["max_ack_delay"])
        elif name.endswith("packet_received"):
            for f in frames:
                if f.get("frame_type") == "ack":
                    for r_ in f.get("acked_ranges") or []:
                        if r_:
                            acked_pns[space].update(range(int(r_[0]), int(r_[-1]) + 1))
            # RFC 9001 §4.9: a server drops Initial keys on its first Handshake packet, a client its Handshake keys
            # on HANDSHAKE_DONE.
            if space == "handshake" and side == "server":
                discarded.add("initial")
            if "handshake_done" in kinds:
                discarded.add("handshake")
                rtt = (rtt[0], rtt[1], max_ack_delay)
        elif name.endswith("packet_sent"):
            units = data_units(frames)
            eliciting = bool(kinds - NOT_ACK_ELICITING)
            if eliciting and at > last_rx + WIRE_SLACK_MS:
                if bursts and at - bursts[-1][0] <= BURST_MS:
                    if units:
                        bursts[-1][1].add(space)
                else:
                    owed = {s for s, packets in sent_data.items() if s not in discarded and unacked(packets, acked_pns[s])}
                    bursts.append([at, {space} if units else set(), owed, rtt])
            elif eliciting:
                last_eliciting = (at, rtt)
            pn = (data.get("header") or {}).get("packet_number")
            if pn is not None and units:
                sent_data[space][int(pn)] = units
            # RFC 9001 §4.9: a client drops Initial keys on its first Handshake packet, a server its Handshake keys
            # once its handshake completes (it sends HANDSHAKE_DONE).
            if space == "handshake" and side == "client":
                discarded.add("initial")
            if "handshake_done" in kinds:
                discarded.add("handshake")
                rtt = (rtt[0], rtt[1], max_ack_delay)
    # Each ack-eliciting send re-arms the timer (RFC 9002 §6.2.1). A burst at least half its due PTO after the send
    # before it is the timer's probe; one sooner is a send of the connection's own (a NEW_CONNECTION_ID after an
    # acknowledgement), which re-arms the timer without an expiry.
    # The PTO is the one the RTT estimate gives when the timer fires: samples taken after the send re-arm it.
    missing = late = 0
    if last_eliciting is not None:
        ref, expiries = last_eliciting[0], 0
        for at, carried, owed, before in bursts:
            due = pto_ms(before) * 2 ** expiries
            if at - ref >= due / 2:
                missing += bool(owed - carried)
                late += at - ref > due * PTO_TOLERANCE + PTO_SLACK_MS
                expiries += 1
            ref = at
        if in_flight > 0 and not closed and end - ref > pto_ms(rtt) * 2 ** expiries * PTO_TOLERANCE + PTO_SLACK_MS:
            late += 1
    return missing, late


def data_units(frames):
    """[(key, first byte, end)] of the CRYPTO and STREAM data a packet's frames carry; a bare FIN counts one byte."""
    units = []
    for f in frames:
        if f.get("frame_type") not in ("crypto", "stream"):
            continue
        off = int(f.get("offset", 0))
        size = int(f.get("length", (f.get("raw") or {}).get("payload_length", 0)))
        key = "crypto" if f["frame_type"] == "crypto" else ("stream", int(f.get("stream_id", 0)))
        units.append((key, off, off + max(size, 1 if f.get("fin") else 0)))
    return units


def pto_ms(rtt):
    """RFC 9002 §6.2.1's PTO from (smoothed_rtt, rttvar, the max_ack_delay it adds: the peer's once the handshake
    is confirmed, 0 before)."""
    return rtt[0] + max(4 * rtt[1], 1.0) + rtt[2]


def unacked(packets, acked):
    """Whether any data unit sent in packets is covered by no acknowledged packet's copy of it."""
    covered = collections.defaultdict(list)
    for pn, units in packets.items():
        if pn in acked:
            for key, lo, hi in units:
                covered[key].append((lo, hi))
    for pn, units in packets.items():
        if pn in acked:
            continue
        for key, lo, hi in units:
            if not any(a <= lo and hi <= b for a, b in merged(covered[key])):
                return True
    return False


def merged(ranges):
    out = []
    for lo, hi in sorted(ranges):
        if out and lo <= out[-1][1]:
            out[-1] = (out[-1][0], max(out[-1][1], hi))
        else:
            out.append((lo, hi))
    return out


def peer_gave_up_by_port(log_txt):
    """{client port: requests the stock quiche client gave up on after its idle timeout} from its log, which names
    the connection's local port in the line before."""
    out = collections.Counter()
    port = None
    if os.path.exists(log_txt):
        with open(log_txt, errors="replace") as f:
            for line in f:
                m = QUICHE_CLIENT_CLOSED.search(line)
                if m:
                    port = int(m.group(1))
                m = PEER_IDLE_TIMEOUT.search(line)
                if m and port is not None:
                    out[port] += int(m.group(2)) - int(m.group(1))
    return out


def peer_idle_timeouts(log_txt, output_txt):
    """How many requests the peer's own report says it gave up on after its idle timeout: the stock quiche client
    counts them in its log, the s2n-quic client names each one in the runner's output."""
    unanswered = 0
    if os.path.exists(log_txt):
        with open(log_txt, errors="replace") as f:
            for line in f:
                m = PEER_IDLE_TIMEOUT.search(line)
                if m:
                    unanswered += int(m.group(2)) - int(m.group(1))
    if os.path.exists(output_txt):
        with open(output_txt, errors="replace") as f:
            unanswered += sum(1 for line in f if PEER_IDLE_REQUEST_FAILED.search(line))
    return unanswered


def runner_output(output_txt, side):
    """(container exits, our client's migration summaries, runner verdicts, simulator deliveries to us, to the
    peer and from the peer, the peer's datagrams a drop list dropped)."""
    exits, migrations, runner, to_us, to_peer, from_peer = {}, [], {}, {}, {}, {}
    droplist_from_peer = 0
    if os.path.exists(output_txt):
        with open(output_txt, errors="replace") as f:
            for line in f:
                m = HANDSHAKES.search(line)
                if m:
                    runner["handshakes"] = {"expected": int(m.group(1)), "got": int(m.group(2))}
                m = TIME_LIMIT.search(line)
                if m:
                    runner["time_limit_s"] = int(m.group(1))
                if NO_PATH_CHALLENGE.search(line):
                    runner["server_first_packets_without_path_challenge"] = (
                        runner.get("server_first_packets_without_path_challenge", 0) + 1
                    )
                if EMPTY_DOWNLOAD.search(line):
                    runner["empty_downloads"] = runner.get("empty_downloads", 0) + 1
                droplist_from_peer += SIM_DROPLIST.findall(line).count(PEER_ADDRESS[side])
                for action, source, address, port in SIM_DATAGRAM.findall(line):
                    ours = address == OUR_ADDRESS[side]
                    counted = [(to_us if ours else to_peer, int(port))]
                    if ours:
                        counted.append((from_peer, int(source)))
                    for toward, key in counted:
                        seen = toward.setdefault(key, {"port": key, "datagrams": 0, "delivered": 0})
                        seen["datagrams"] += 1
                        seen["delivered"] += action == "Forwarding"
                m = EXIT_LINE.search(line)
                if m:
                    exits[m.group(1)] = int(m.group(2))
                m = MIGRATION_SUMMARY.search(line)
                if m:
                    summary = {"attempted": int(m.group(1)), "succeeded": int(m.group(2))}
                    if m.group(3):
                        summary["first_unmoved"] = m.group(3)
                    migrations.append(summary)
    by_port = lambda seen: sorted(seen.values(), key=lambda d: d["port"])
    return exits, migrations, runner, by_port(to_us), by_port(to_peer), by_port(from_peer), droplist_from_peer


def timer_and_wire(path, side, captures, gave_up):
    """connection_facts plus what our retransmission timer did after our last receipt and, when the captures show
    the connection's 4-tuple, its wire record and the peer's report on it."""
    facts = connection_facts(path, side)
    records = list(qlog_records(path))
    facts["probes_missing_a_space"], facts["late_probes"] = probe_facts(records, side)
    wire = wire_facts(records, side, facts, captures)
    if wire is not None:
        facts["wire"] = wire
        facts["peer_gave_up_requests"] = gave_up[wire["port"]]
    return facts


def evidence(logs, direction, peer):
    side = our_side(direction)
    pair = f"ditchoom_{peer}" if side == "server" else f"{peer}_ditchoom"
    root = os.path.join(logs, pair)
    tests = {}
    if not os.path.isdir(root):
        return tests
    for test in sorted(os.listdir(root)):
        test_dir = os.path.join(root, test)
        if not os.path.isdir(test_dir):
            continue
        qlog_dir = os.path.join(test_dir, side, "qlog")
        qlogs = sorted(f for f in os.listdir(qlog_dir) if f.endswith(".sqlog")) if os.path.isdir(qlog_dir) else []
        exits, migrations, runner, to_us, to_peer, from_peer, droplist_from_peer = runner_output(
            os.path.join(test_dir, "output.txt"), side
        )
        captures = {s: read_capture(os.path.join(test_dir, "sim", CAPTURE[s])) for s in CAPTURE}
        gave_up = peer_gave_up_by_port(os.path.join(test_dir, "client", "log.txt")) if side == "server" else collections.Counter()
        tests[test] = {
            "connections": [timer_and_wire(os.path.join(qlog_dir, q), side, captures, gave_up) for q in qlogs],
            "exits": exits,
            "migrations": migrations,
            "runner": runner,
            "sim_to_us": to_us,
            "sim_to_peer": to_peer,
            "sim_from_peer": from_peer,
            "sim_droplist_from_peer": droplist_from_peer,
            "peer_unanswered_requests": peer_idle_timeouts(
                os.path.join(test_dir, "client" if side == "server" else "server", "log.txt"), os.path.join(test_dir, "output.txt")
            ),
        }
    return tests


def main(argv):
    if len(argv) != 5:
        print(__doc__, file=sys.stderr)
        return 2
    logs, direction, peer, out = argv[1:]
    with open(out, "w") as f:
        json.dump({"direction": direction, "peer": peer, "tests": evidence(logs, direction, peer)}, f, indent=1, sort_keys=True)
        f.write("\n")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
