#!/usr/bin/env python3
"""What one interop run shows about each test, for the gate to check failure signatures against.

Usage: quic-interop-evidence.py <runner logs dir> <direction> <peer> <out.json>

The runner writes <logs>/<server>_<client>/<test>/ with the two endpoints' logs and its own output.txt. Two
sources are read, both written by something this repository controls or the runner itself:
  - our endpoint's qlog (quiche's, under <our side>/qlog/*.sqlog): one record per connection with the
    transport parameters each side set, the MAX_STREAMS we sent, the highest stream the peer opened, the
    source connection id lengths of the peer's Initial packets and the CONNECTION_CLOSE frames we sent;
  - the runner's output.txt: each container's exit code, and our client's MIGRATION-SUMMARY line (its typed
    move results) when it migrated.

The output maps test -> {"connections": [...], "exits": {"client": n, "server": n}, "migrations": [...]}. A
connection, exit or summary the logs do not show is absent, never written as a placeholder.
"""
import json
import os
import re
import sys

EXIT_LINE = re.compile(r"\b(client|server|sim) exited with code (\d+)")
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
    }
    remainder = peer_bidi_remainder(side)
    for record in qlog_records(path):
        name = record.get("name", "")
        data = record.get("data") or {}
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
    # One close per connection is what is judged; quiche repeats it in every packet of the closing period.
    facts["local_closes"] = [dict(t) for t in {tuple(sorted(c.items())) for c in facts["local_closes"]}]
    return facts


def runner_output(output_txt):
    """(container exits, our client's migration summaries) from the runner's output.txt."""
    exits, migrations = {}, []
    if os.path.exists(output_txt):
        with open(output_txt, errors="replace") as f:
            for line in f:
                m = EXIT_LINE.search(line)
                if m:
                    exits[m.group(1)] = int(m.group(2))
                m = MIGRATION_SUMMARY.search(line)
                if m:
                    summary = {"attempted": int(m.group(1)), "succeeded": int(m.group(2))}
                    if m.group(3):
                        summary["first_unmoved"] = m.group(3)
                    migrations.append(summary)
    return exits, migrations


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
        exits, migrations = runner_output(os.path.join(test_dir, "output.txt"))
        tests[test] = {
            "connections": [connection_facts(os.path.join(qlog_dir, q), side) for q in qlogs],
            "exits": exits,
            "migrations": migrations,
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
