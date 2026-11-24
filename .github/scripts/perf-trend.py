#!/usr/bin/env python3
"""Trend of the gated performance numbers: every count and allocation a CI gate checks, run over run.

The gates themselves (coreAllocationTest) fail a run only past their own margin; this catches what each gate lets
through, a rise smaller than its margin that the next change adds to.

  perf-trend.py merge --out perf-gates.json <dir>...
      Joins every build/perf-gates/*.jsonl under <dir> (one JSON record per gated number, written by the tests'
      PerfGates) into one file with this run's git SHA (GITHUB_SHA, else `git rev-parse HEAD`) and run id.

  perf-trend.py compare --current perf-gates.json (--history-dir DIR | --repo OWNER/NAME --branch B)
                        [--workflow review.yaml] [--artifact perf-gates] [--runs 10]
                        [--fraction 0.05] [--absolute 0.05] [--min-history 3] [--mode warn|fail]
                        [--summary FILE]
      Compares each number with the median of the same number (same test, metric, OS, arch, JDK, backend, stack)
      in the last --runs successful runs of --workflow on --branch, whose --artifact it fetches with `gh api`, or
      in the perf-gates*.json files of --history-dir. A number is flagged when it rose above that median by more
      than --fraction of it AND by more than --absolute: both, so a count near zero does not flag on one call in
      twenty operations and a large byte count does not flag on noise. Fewer than --min-history earlier values
      is "no history", never a flag. Writes a Markdown table to --summary (the job summary) and stdout; prints a
      ::warning:: per flag. --mode warn always exits 0; --mode fail exits 1 when anything is flagged.
"""
import argparse
import glob
import io
import json
import os
import statistics
import subprocess
import sys
import zipfile

KEY_FIELDS = ("test", "metric", "os", "arch", "jdk", "backend", "stack")


def key_of(record):
    return tuple(str(record.get(f, "")) for f in KEY_FIELDS)


def label_of(key):
    test, metric, os_name, arch, jdk, backend, stack = key
    return f"{test} {metric}", f"{os_name} {arch} JDK {jdk} {backend} {stack}"


def merge(args):
    records = []
    for root in args.dirs:
        for path in sorted(glob.glob(os.path.join(root, "**", "*.jsonl"), recursive=True)):
            with open(path) as lines:
                for line in lines:
                    line = line.strip()
                    if line:
                        records.append(json.loads(line))
    sha = os.environ.get("GITHUB_SHA") or git_head()
    merged = {"sha": sha, "run": os.environ.get("GITHUB_RUN_ID", ""), "records": records}
    with open(args.out, "w") as out:
        json.dump(merged, out, indent=1)
    print(f"perf-trend: merged {len(records)} gated numbers at {sha[:12]} into {args.out}")
    return 0


def git_head():
    try:
        return subprocess.run(["git", "rev-parse", "HEAD"], capture_output=True, text=True, check=True).stdout.strip()
    except (OSError, subprocess.CalledProcessError):
        return "unknown"


def values_of(run):
    """The gated numbers of one merged run, by key; a key recorded twice keeps its last value."""
    return {key_of(r): r for r in run.get("records", []) if isinstance(r.get("value"), (int, float))}


def history_from_dir(directory):
    runs = []
    for path in sorted(glob.glob(os.path.join(directory, "*.json"))):
        with open(path) as f:
            runs.append(json.load(f))
    return runs


def gh_json(path):
    out = subprocess.run(["gh", "api", path], capture_output=True, text=True)
    if out.returncode != 0:
        raise RuntimeError(f"gh api {path}: {out.stderr.strip()}")
    return json.loads(out.stdout)


def history_from_github(args):
    """The merged artifact of each of the last successful runs on the branch, newest first; runs without one are skipped."""
    runs = []
    listing = gh_json(
        f"repos/{args.repo}/actions/workflows/{args.workflow}/runs"
        f"?branch={args.branch}&status=success&per_page={min(100, args.runs * 3)}"
    )
    current_run = os.environ.get("GITHUB_RUN_ID", "")
    for run in listing.get("workflow_runs", []):
        if len(runs) >= args.runs:
            break
        if str(run["id"]) == current_run:
            continue
        artifacts = gh_json(f"repos/{args.repo}/actions/runs/{run['id']}/artifacts?name={args.artifact}")
        live = [a for a in artifacts.get("artifacts", []) if not a.get("expired")]
        if not live:
            continue
        blob = subprocess.run(
            ["gh", "api", f"repos/{args.repo}/actions/artifacts/{live[0]['id']}/zip"], capture_output=True
        )
        if blob.returncode != 0:
            print(f"perf-trend: run {run['id']}: artifact download failed, skipped", file=sys.stderr)
            continue
        with zipfile.ZipFile(io.BytesIO(blob.stdout)) as z:
            names = [n for n in z.namelist() if n.endswith(".json")]
            if names:
                runs.append(json.loads(z.read(names[0])))
    return runs


def compare(args):
    with open(args.current) as f:
        current = json.load(f)
    if args.history_dir:
        history = history_from_dir(args.history_dir)
    else:
        try:
            history = history_from_github(args)
        except RuntimeError as failed:
            print(f"perf-trend: history unavailable: {failed}", file=sys.stderr)
            history = []
    past = {}
    for run in history:
        for key, record in values_of(run).items():
            past.setdefault(key, []).append(float(record["value"]))

    rows = []
    flagged = []
    no_history = 0
    for key, record in sorted(values_of(current).items()):
        value = float(record["value"])
        earlier = past.get(key, [])
        name, where = label_of(key)
        gate = record.get("gate")
        gate_text = "-" if gate is None else fmt(gate)
        if len(earlier) < args.min_history:
            no_history += 1
            rows.append((name, where, fmt(value), gate_text, "-", f"no history ({len(earlier)})", ""))
            continue
        median = statistics.median(earlier)
        rise = value - median
        relative = rise / median if median else (float("inf") if rise > 0 else 0.0)
        is_flag = rise > args.absolute and relative > args.fraction
        change = f"{relative * 100:+.1f}%" if median else f"{rise:+.3f}"
        rows.append((name, where, fmt(value), gate_text, fmt(median), change, "⚠️" if is_flag else ""))
        if is_flag:
            flagged.append((name, where, value, median, change))

    lines = [
        "## Performance gates: trend",
        "",
        f"{len(rows)} gated numbers at `{str(current.get('sha', ''))[:12]}` against the median of up to "
        f"{args.runs} earlier successful runs ({len(history)} found). Flagged: a rise of more than "
        f"{args.fraction * 100:.0f}% and more than {args.absolute} over that median. Mode: {args.mode}.",
        "",
    ]
    if not history:
        lines += ["_No earlier run has the perf-gates artifact: nothing to compare with yet._", ""]
    lines += ["| Gate | Runtime | Value | Gate limit | Median | Change | |", "|---|---|---|---|---|---|---|"]
    # Flags first, then everything else in key order.
    for row in sorted(rows, key=lambda r: r[6] == ""):
        lines.append("| " + " | ".join(row) + " |")
    report = "\n".join(lines) + "\n"
    print(report)
    if args.summary:
        with open(args.summary, "a") as out:
            out.write(report)
    for name, where, value, median, change in flagged:
        print(f"::warning title=perf trend: {name}::{where}: {fmt(value)} against a median of {fmt(median)} ({change})")
    print(f"perf-trend: {len(flagged)} flagged, {no_history} without history, {len(rows)} compared")
    return 1 if flagged and args.mode == "fail" else 0


def fmt(number):
    number = float(number)
    return f"{number:.0f}" if abs(number) >= 1000 else f"{number:.3f}"


def main(argv):
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    sub = parser.add_subparsers(dest="command", required=True)
    m = sub.add_parser("merge")
    m.add_argument("--out", required=True)
    m.add_argument("dirs", nargs="+")
    c = sub.add_parser("compare")
    c.add_argument("--current", required=True)
    c.add_argument("--history-dir")
    c.add_argument("--repo")
    c.add_argument("--branch")
    c.add_argument("--workflow", default="review.yaml")
    c.add_argument("--artifact", default="perf-gates")
    c.add_argument("--runs", type=int, default=10)
    c.add_argument("--fraction", type=float, default=0.05)
    c.add_argument("--absolute", type=float, default=0.05)
    c.add_argument("--min-history", type=int, default=3)
    c.add_argument("--mode", choices=("warn", "fail"), default="warn")
    c.add_argument("--summary")
    args = parser.parse_args(argv)
    if args.command == "merge":
        return merge(args)
    if not args.history_dir and not (args.repo and args.branch):
        parser.error("compare needs --history-dir, or --repo and --branch")
    return compare(args)


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
