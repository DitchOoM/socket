#!/usr/bin/env python3
"""What one interop run shows about each test, for the gate to check failure signatures against.

Usage: quic-interop-evidence.py <runner logs dir> <direction> <peer> <out.json>

The runner writes <logs>/<server>_<client>/<test>/ with the two endpoints' logs and its own output.txt. Two
sources are read, both written by something this repository controls or the runner itself:
  - our endpoint's qlog (quiche's, under <our side>/qlog/*.sqlog): one record per connection with the
    transport parameters each side set, the MAX_STREAMS we sent, the highest stream the peer opened, the
    source connection id lengths of the peer's Initial packets and the CONNECTION_CLOSE frames we sent;
  - the runner's output.txt: each container's exit code, our client's MIGRATION-SUMMARY line (its typed
    move results) when it migrated, the runner's handshake count and time limit, and what the simulator did
    with each datagram addressed to our endpoint.

Per connection the qlog also gives what the peer sent in the handshake spaces, when we last heard from it,
how the connection ended, what was still in flight then, the streams we answered and how fast, and every
RESET_STREAM the peer sent with how many copies of that stream's first bytes we had sent.

The output maps test -> {"connections": [...], "exits": {"client": n, "server": n}, "migrations": [...]}. A
connection, exit or summary the logs do not show is absent, never written as a placeholder.
"""
import json
import os
import re
import sys

EXIT_LINE = re.compile(r"\b(client|server|sim) exited with code (\d+)")
HANDSHAKES = re.compile(r"Expected (\d+) handshakes\. Got: (\d+)")
TIME_LIMIT = re.compile(r"took longer than (\d+)s")
NO_PATH_CHALLENGE = re.compile(r"First server packet on new path .* did not contain a PATH_CHALLENGE frame")
UNANSWERED_CHALLENGE = re.compile(r"PATH_CHALLENGE without a PATH_RESPONSE: \[(.*)\]")
EMPTY_DOWNLOAD = re.compile(r"doesn't match\. Original: \d+ bytes, downloaded: 0 bytes")
SIM_DATAGRAM = re.compile(r"\b(Forwarding|Corrupting|Dropping) \d+ bytes [\d.]+:(\d+) -> ([\d.]+):(\d+)")
OUR_ADDRESS = {"client": "193.167.0.100", "server": "193.167.100.100"}
MIGRATION_SUMMARY = re.compile(r"MIGRATION-SUMMARY attempted=(\d+) succeeded=(\d+)(?:.*?firstUnmoved=move \d+: (\w+))?")
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
        "rx_path_challenges": 0,
        "tx_path_responses": 0,
        "bytes_in_flight_at_end": 0,
        "longest_wait_in_flight_ms": 0.0,
        "fin_sent_streams": 0,
        "peer_resets": [],
    }
    remainder = peer_bidi_remainder(side)
    first_rx, first_tx, fin_sent, copies = {}, {}, set(), {}
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
        if name.endswith("connection_closed") and data.get("reason"):
            facts["close_reason"] = data["reason"]
        if name.endswith("packet_received"):
            facts["rx_packets"] += 1
            facts["rx_path_challenges"] += sum(f.get("frame_type") == "path_challenge" for f in data.get("frames") or [])
            facts["last_rx_ms"] = at
            facts["tx_after_last_rx"] = 0
            space = (data.get("header") or {}).get("packet_type", "")
            frames = {f.get("frame_type") for f in data.get("frames") or []}
            if space in ("initial", "handshake"):
                facts[f"rx_{space}_crypto"] += "crypto" in frames
                facts[f"rx_{space}_acks"] += "ack" in frames
            for frame in data.get("frames") or []:
                if frame.get("frame_type") == "stream":
                    first_rx.setdefault(int(frame.get("stream_id", 0)), at)
                elif frame.get("frame_type") == "reset_stream":
                    facts["peer_resets"].append(
                        {"stream_id": int(frame.get("stream_id", 0)), "final_size": int(frame.get("final_size", 0))}
                    )
        elif name.endswith("packet_sent"):
            facts["tx_after_last_rx"] += 1
            facts["tx_path_responses"] += sum(f.get("frame_type") == "path_response" for f in data.get("frames") or [])
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


def runner_output(output_txt, side):
    """(container exits, our client's migration summaries, runner verdicts, simulator deliveries to us)."""
    exits, migrations, runner, to_us, to_peer, from_peer = {}, [], {}, {}, {}, {}
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
                m = UNANSWERED_CHALLENGE.search(line)
                if m:
                    runner["unanswered_path_challenges"] = len(m.group(1).split(","))
                if EMPTY_DOWNLOAD.search(line):
                    runner["empty_downloads"] = runner.get("empty_downloads", 0) + 1
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
    return exits, migrations, runner, by_port(to_us), by_port(to_peer), by_port(from_peer)


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
        exits, migrations, runner, to_us, to_peer, from_peer = runner_output(os.path.join(test_dir, "output.txt"), side)
        tests[test] = {
            "connections": [connection_facts(os.path.join(qlog_dir, q), side) for q in qlogs],
            "exits": exits,
            "migrations": migrations,
            "runner": runner,
            "sim_to_us": to_us,
            "sim_to_peer": to_peer,
            "sim_from_peer": from_peer,
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
