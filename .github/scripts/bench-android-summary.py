#!/usr/bin/env python3
"""Prints Jetpack Microbenchmark results (benchmarkData.json) side by side as a Markdown table.

Usage: bench-android-summary.py <title> <label>=<benchmarkData.json> [<label>=<benchmarkData.json> ...]

Each benchmark's median and minimum timeNs are shown in microseconds, one column pair per labelled
file (for example "R8=... no-R8=..."). The first file is the reference the last column compares with.
Emulator numbers: a regression signal, never a result.
"""
import json
import sys


def load(path):
    data = json.load(open(path))
    out = {}
    for b in data.get("benchmarks", []):
        t = b.get("metrics", {}).get("timeNs", {})
        # Microbenchmark prefixes every name with EMULATOR_ (and the like) on a device it distrusts.
        name = b["name"].split("_", 1)[1] if b["name"].startswith(("EMULATOR_", "DEBUGGABLE_", "UNLOCKED_")) else b["name"]
        out[name] = (t.get("median"), t.get("minimum"))
    return out, data.get("context", {}).get("build", {}).get("model", "?")


def us(ns):
    return "-" if ns is None else f"{ns / 1000:,.1f}"


def main(argv):
    if len(argv) < 3 or any("=" not in a for a in argv[2:]):
        print(__doc__, file=sys.stderr)
        return 2
    runs = [(a.split("=", 1)[0], *load(a.split("=", 1)[1])) for a in argv[2:]]
    names = []
    for _, r, _ in runs:
        names += [n for n in r if n not in names]
    header = "| Benchmark | " + " | ".join(f"{label} median µs | {label} min µs" for label, _, _ in runs)
    if len(runs) > 1:
        header += f" | {runs[-1][0]} vs {runs[0][0]} (median)"
    lines = [f"### {argv[1]}", "", f"Device: {runs[0][2]}", "", header + " |",
             "|" + "---|" * (1 + 2 * len(runs) + (1 if len(runs) > 1 else 0))]
    for n in names:
        cells = []
        for _, r, _ in runs:
            med, mn = r.get(n, (None, None))
            cells += [us(med), us(mn)]
        row = f"| {n} | " + " | ".join(cells)
        if len(runs) > 1:
            a, b = runs[0][1].get(n, (None,))[0], runs[-1][1].get(n, (None,))[0]
            row += " | " + (f"{(b - a) / a * 100:+.1f}%" if a and b else "-")
        lines.append(row + " |")
    print("\n".join(lines))
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
