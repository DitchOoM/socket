#!/usr/bin/env python3
"""Pair a walk's server qlog records with the device records of the same connections.

    ./qlog-pair.py <server-qlog-dir> <device-qlog-dir> [<device-qlog-dir> …]

A device names each connection's qlog by lane (`conn-v6-0007.sqlog`, then `conn-v6-0007_seg0002.sqlog`
and on); the server names its record by its own session id (`quiche-server-<id>.sqlog`, then
`…_seg0002.sqlog`). Neither name says which is which, but both heads carry the connection's
original destination CID: the server's own `parameters_set` states it, the client's records it as the
peer's. That id is the pairing key.

Prints one row per device connection — its server record, or why it has none — then the server records
no device claimed (another client, a health check), and writes the same rows to PAIRS.tsv in the server
directory.
"""
import os
import re
import sys

ODCID = re.compile(rb'"initiator":"(local|remote)"[^}]*?"original_destination_connection_id":"([0-9a-f]+)"')
HEAD_BYTES = 256 * 1024


def heads(directory):
    """{record name: path of its head segment} — every `<name>.sqlog` that is not a `_segNNNN` continuation."""
    found = {}
    for name in sorted(os.listdir(directory)):
        if name.endswith(".sqlog") and not re.search(r"_seg\d+\.sqlog$", name):
            found[name[: -len(".sqlog")]] = os.path.join(directory, name)
    return found


def odcid(path, initiator):
    """The original destination CID a head's `parameters_set` from [initiator] states, or None when it has none."""
    with open(path, "rb") as f:
        head = f.read(HEAD_BYTES)
    for m in ODCID.finditer(head):
        if m.group(1).decode() == initiator:
            return m.group(2).decode()
    return None


def pair(server_dir, device_dirs):
    """(rows, unclaimed): one row per device record, and the server records no device record matched."""
    by_odcid = {}
    for name, path in heads(server_dir).items():
        key = odcid(path, "local")
        if key:
            by_odcid.setdefault(key, []).append(name)
    claimed = set()
    rows = []
    for device_dir in device_dirs:
        label = os.path.basename(os.path.normpath(device_dir))
        for name, path in heads(device_dir).items():
            key = odcid(path, "remote")
            if key is None:
                rows.append((f"{label}/{name}", "-", "-", "no peer parameters: the handshake never completed"))
                continue
            servers = by_odcid.get(key, [])
            claimed.update(servers)
            rows.append((f"{label}/{name}", ",".join(servers) or "-", key, "" if servers else "no server record with this id"))
    unclaimed = sorted(n for names in by_odcid.values() for n in names if n not in claimed)
    return rows, unclaimed


def main():
    if len(sys.argv) < 3:
        sys.exit(__doc__.strip().splitlines()[2].strip())
    server_dir, device_dirs = sys.argv[1], sys.argv[2:]
    rows, unclaimed = pair(server_dir, device_dirs)
    lines = ["device\tserver\todcid\tnote"] + ["\t".join(r) for r in rows] + [f"-\t{n}\t-\tno device record" for n in unclaimed]
    with open(os.path.join(server_dir, "PAIRS.tsv"), "w") as out:
        out.write("\n".join(lines) + "\n")
    for line in lines:
        print(line)
    paired = sum(1 for r in rows if r[1] != "-")
    print(f"paired {paired} of {len(rows)} device record(s); {len(unclaimed)} server record(s) unclaimed -> {os.path.join(server_dir, 'PAIRS.tsv')}")


if __name__ == "__main__":
    main()
