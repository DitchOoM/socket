#!/usr/bin/env python3
"""Compares two JMH JSON result files (base and head of a PR) and prints a Markdown table.

Usage: bench-delta.py <label> <base.json | -> <head.json> [flag_pct]

  label     a heading for the table, such as "FFM backend"
  base      the base commit's results, or "-" when the base has no benchmarks (the table then has no
            delta column worth reading, and says so)
  head      the PR head's results
  flag_pct  a change worse than this many percent gets a marker (default 10). Never a failure: the two
            runs share one hosted runner, which removes machine-to-machine noise but not neighbour noise.

SampleTime benchmarks report p50 and p99 (lower is better), AverageTime the mean (lower is better),
Throughput the score (higher is better). Rows are matched by benchmark name and parameters; a
benchmark only one side has shows "-" on the other.
"""
import json
import sys


def rows(path):
    if path == "-":
        return {}
    out = {}
    for r in json.load(open(path)):
        name = r["benchmark"].split(".")[-2] + "." + r["benchmark"].split(".")[-1]
        params = ",".join(f"{k}={v}" for k, v in sorted((r.get("params") or {}).items()))
        key = name + (f" ({params})" if params else "")
        pm = r["primaryMetric"]
        unit = pm["scoreUnit"]
        mode = r["mode"]
        if mode == "sample":
            pct = pm.get("scorePercentiles", {})
            out[key + " p50"] = (pct.get("50.0"), unit, False)
            out[key + " p99"] = (pct.get("99.0"), unit, False)
        elif mode == "thrpt":
            # QuicBulkThroughput counts one MiB as one operation (@OperationsPerInvocation), so its
            # ops/s is MiB/s.
            out[key] = (pm["score"], "MiB/s" if name.endswith("mibPerSecond") else unit, True)
        else:
            out[key] = (pm["score"], unit, False)
    return out


def fmt(v):
    if v is None:
        return "-"
    return f"{v:,.1f}" if v >= 10 else f"{v:,.3f}"


def main(argv):
    if len(argv) not in (4, 5):
        print(__doc__, file=sys.stderr)
        return 2
    label, base_path, head_path = argv[1], argv[2], argv[3]
    flag = float(argv[4]) if len(argv) == 5 else 10.0
    base, head = rows(base_path), rows(head_path)
    lines = [f"### {label}", ""]
    if not base:
        lines += ["The base commit has no JVM benchmarks, so there is nothing to compare against; head numbers only.", ""]
    lines += ["| Benchmark | Unit | Base | Head | Change |", "|---|---|---|---|---|"]
    for key in list(head) + [k for k in base if k not in head]:
        h = head.get(key)
        b = base.get(key)
        unit = (h or b)[1]
        higher_better = (h or b)[2]
        hv = h[0] if h else None
        bv = b[0] if b else None
        change = "-"
        if hv is not None and bv:
            pct = (hv - bv) / bv * 100
            worse = -pct if higher_better else pct
            change = f"{pct:+.1f}%" + (" ⚠️" if worse > flag else "")
        lines.append(f"| {key} | {unit} | {fmt(bv)} | {fmt(hv)} | {change} |")
    lines += ["", f"⚠️ = more than {flag:.0f}% worse than the base, measured in the same job. A signal to look, not a verdict."]
    print("\n".join(lines))
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
