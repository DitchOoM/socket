#!/usr/bin/env python3
"""Dump one connection's replay trace (`traces/conn-NNNN.trace`, v1 grammar) as a timeline.

    trace-dump.py <conn.trace>                     events (everything but datagrams) + per-path tallies
    trace-dump.py <conn.trace> --from 12.7 --to 24  ...restricted to a window, in seconds from the trace origin
    trace-dump.py <conn.trace> --datagrams          also every datagram in the window: direction, size,
                                                    local path, header form and the DCID it carries

The path column is the LOCAL endpoint the datagram left from or arrived on (`family:port:hi:lo`, the
recorder's `PathKey` projection) — a migration probe shows up as a new local port. The DCID is read
from the plaintext header, so it is the one number that ties a recorded datagram to the CID
accounting on both sides: a short-header packet's first 20 bytes after the flags byte (every CID this
library mints is 20 bytes), or the length-prefixed field of a long header.

Repeated ERROR / STATS / SILENCE lines are thinned so a 30-second stall reads as a page, not a scroll;
`--all` prints every one.
"""

import argparse
import sys

CID_LEN = 20


def parse_args():
    p = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    p.add_argument("trace")
    p.add_argument("--from", dest="lo", type=float, default=0.0, help="window start, seconds")
    p.add_argument("--to", dest="hi", type=float, default=float("inf"), help="window end, seconds")
    p.add_argument("--datagrams", action="store_true", help="print every datagram in the window")
    p.add_argument("--all", action="store_true", help="do not thin repeated ERROR/STATS/SILENCE lines")
    return p.parse_args()


def header_of(hex_payload):
    first = int(hex_payload[:2], 16)
    if first & 0x80:
        dcid_len = int(hex_payload[10:12], 16)
        return "LONG", hex_payload[12 : 12 + 2 * dcid_len]
    return "SHORT", hex_payload[2 : 2 + 2 * CID_LEN]


def main():
    args = parse_args()
    lo_ns, hi_ns = args.lo * 1e9, args.hi * 1e9
    thinned = {"ERROR": 0, "STATS": 0, "SILENCE": 0}
    tally = {}
    last_in = None
    first_probe = None
    print(f"== {args.trace}  window [{args.lo:.3f}s, {'end' if args.hi == float('inf') else f'{args.hi:.3f}s'}]")
    with open(args.trace) as f:
        for line in f:
            parts = line.rstrip("\n").split(" ", 4)
            if len(parts) < 3 or parts[0] != "v1":
                continue
            at_ns, kind = int(parts[1]), parts[2]
            rest = parts[3] + (" " + parts[4] if len(parts) > 4 else "") if len(parts) > 3 else ""
            secs = at_ns / 1e9
            if kind in ("DGRAM_IN", "DGRAM_OUT"):
                if kind == "DGRAM_IN":
                    last_in = secs
                if at_ns < lo_ns or at_ns > hi_ns:
                    continue
                size, path, payload = parts[3], parts[4].split(" ", 1)[0], parts[4].split(" ", 1)[1]
                counts = tally.setdefault(path, [0, 0])
                counts[0 if kind == "DGRAM_OUT" else 1] += 1
                if args.datagrams:
                    form, dcid = header_of(payload)
                    print(f"{secs:12.3f} {kind:<9} {size:>5} {path:<24} {form:<5} dcid={dcid}")
                continue
            if at_ns < lo_ns or at_ns > hi_ns:
                continue
            if kind == "PATH_STATE" and "Probing" in rest and first_probe is None:
                first_probe = secs
            if kind in thinned and not args.all:
                thinned[kind] += 1
                if thinned[kind] > 3 and thinned[kind] % 10 != 0:
                    continue
            print(f"{secs:12.3f} {kind:<10} {rest}")
    print()
    print(f"last DGRAM_IN anywhere in the trace: {'none' if last_in is None else f'{last_in:.3f}s'}")
    if first_probe is not None:
        print(f"first PATH_STATE Probing in window: {first_probe:.3f}s")
    print("per-path datagrams in window (local path: OUT / IN):")
    for path, (out, inn) in tally.items():
        print(f"  {path:<24} OUT {out:>6}  IN {inn:>6}")
    for kind, n in thinned.items():
        if n > 3 and not args.all:
            print(f"({n} {kind} lines in window, thinned; --all prints them)")
    return 0


if __name__ == "__main__":
    sys.exit(main())
