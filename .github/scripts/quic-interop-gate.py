#!/usr/bin/env python3
"""The QUIC interop gate: the runner's verdict for every cell, judged against a checked-in expectation.

A cell is one direction, one peer and one test:
  our-client            our client against the peer's server
  our-server            the peer's client against our server
  our-client-migrating  our client's `transfer` with six active migrations (QuicInteropEndpoint.kt), plus the
                        pseudo-test `active-migration`: every move the client made succeeded

Expectations (test-harness/quic-interop/expectations.json) map direction -> peer -> test -> one of
  "pass"                         must succeed
  "unsupported"                  one side does not implement it
  {"known-failure": "<why>"}     fails today, for the stated reason
  {"flaky": "<why>"}             either outcome is accepted and reported

Verdicts: a cell expected to pass that fails (and fails its one retry), turns unsupported, or was skipped is a
REGRESSION. A cell whose expectation no longer matches (a known failure that passes, an unsupported test that
now runs) is STALE: the file must be updated, so it is never silently out of date. A cell that ran but has no
expectation is NEW. REGRESSION, NEW and MISSING always fail the gate; STALE fails it with --stale=fail.

Subcommands:
  retry <expectations> <direction> <peer> <runner.json>   tests to run once more: expected to pass, did not
  judge <expectations> <results-dir> [--stale=warn|fail] [--record=<file>]
"""
import json
import os
import re
import sys

DIRECTIONS = ("our-client", "our-server", "our-client-migrating")
MIGRATION_TEST = "active-migration"


def runner_results(path):
    """{test name: succeeded|failed|unsupported|skipped} from one runner JSON holding one client and one server."""
    with open(path) as f:
        r = json.load(f)
    names = {abbr: t["name"] for abbr, t in r.get("tests", {}).items()}
    out = {}
    for group in ("results", "measurements"):
        cells = r.get(group) or []
        if not cells:
            continue
        for entry in cells[0] or []:
            name = names.get(entry["abbr"], entry["abbr"])
            out[name] = entry.get("result") or "skipped"
    return out


def migration_outcome(summary_path):
    """The `active-migration` pseudo-test from the client's MIGRATION-SUMMARY line."""
    if not os.path.exists(summary_path):
        return "failed", "no MIGRATION-SUMMARY (the client did not finish the transfer)"
    with open(summary_path) as f:
        line = f.read().strip()
    if not line:
        return "failed", "no MIGRATION-SUMMARY (the client did not finish the transfer)"
    attempted = re.search(r"attempted=(\d+)", line)
    succeeded = re.search(r"succeeded=(\d+)", line)
    if attempted and succeeded and int(attempted.group(1)) > 0 and attempted.group(1) == succeeded.group(1):
        return "succeeded", line
    return "failed", line


def expectation(exp, direction, peer, test):
    value = exp.get(direction, {}).get(peer, {}).get(test)
    if value is None:
        return "absent", ""
    if isinstance(value, str):
        return value, ""
    (kind, why), = value.items()
    return kind, why


def cmd_retry(args):
    exp_path, direction, peer, results_path = args
    with open(exp_path) as f:
        exp = json.load(f)
    actual = runner_results(results_path)
    again = [t for t, r in actual.items() if r != "succeeded" and expectation(exp, direction, peer, t)[0] == "pass"]
    print(",".join(sorted(again)))
    return 0


def load_cells(results_dir):
    """{(direction, peer, test): (result, detail)} with a passing retry turning a failure into 'retried'."""
    cells = {}
    for name in sorted(os.listdir(results_dir)):
        m = re.fullmatch(r"(our-client|our-server|our-client-migrating)__(.+)\.json", name)
        if not m:
            continue
        direction, peer = m.groups()
        first = runner_results(os.path.join(results_dir, name))
        retry_path = os.path.join(results_dir, f"{direction}__{peer}.retry")
        retried = runner_results(retry_path) if os.path.exists(retry_path) else {}
        for test, result in first.items():
            if result == "failed" and retried.get(test) == "succeeded":
                cells[(direction, peer, test)] = ("retried", "failed once, passed on retry")
            else:
                cells[(direction, peer, test)] = (result, "")
        if direction == "our-client-migrating":
            cells[(direction, peer, MIGRATION_TEST)] = migration_outcome(
                os.path.join(results_dir, f"{direction}__{peer}.summary")
            )
    return cells


def judge_cell(kind, result):
    """(verdict, accepted) for one cell's expectation kind and actual result."""
    if kind == "absent":
        return "NEW", False
    if kind == "flaky":
        return "flaky", True
    if kind == "pass":
        if result == "succeeded":
            return "ok", True
        if result == "retried":
            return "passed on retry", True
        return "REGRESSION", False
    if kind == "unsupported":
        return ("ok", True) if result == "unsupported" else ("STALE", False)
    if kind == "known-failure":
        return ("ok", True) if result == "failed" else ("STALE", False)
    return f"bad expectation {kind!r}", False


def recorded(result, previous_kind, previous_why):
    if result in ("succeeded", "retried"):
        return "pass"
    if result == "unsupported":
        return "unsupported"
    if previous_kind in ("known-failure", "flaky") and previous_why:
        return {previous_kind: previous_why}
    return {"known-failure": "TODO: root cause"}


def cmd_judge(args):
    stale = "warn"
    record = ""
    positional = []
    for a in args:
        if a.startswith("--stale="):
            stale = a.split("=", 1)[1]
        elif a.startswith("--record="):
            record = a.split("=", 1)[1]
        else:
            positional.append(a)
    exp_path, results_dir = positional
    with open(exp_path) as f:
        exp = json.load(f)
    cells = load_cells(results_dir)
    ran_rows = {(d, p) for d, p, _ in cells}

    rows = []
    failing = 0
    for (direction, peer, test), (result, detail) in sorted(cells.items()):
        kind, why = expectation(exp, direction, peer, test)
        verdict, accepted = judge_cell(kind, result)
        if verdict == "STALE" and stale == "warn":
            accepted = True
        if not accepted:
            failing += 1
        rows.append((verdict, direction, peer, test, result, kind, why or detail))
    for direction in DIRECTIONS:
        for peer, tests in exp.get(direction, {}).items():
            if (direction, peer) not in ran_rows:
                rows.append(("MISSING", direction, peer, "*", "not run", "", "the peer's run produced no result"))
                failing += 1

    noteworthy = [r for r in rows if r[0] not in ("ok",)]
    print("## QUIC interop gate")
    print()
    print(f"{len(cells)} cells; {failing} failing the gate; stale expectations {'fail' if stale == 'fail' else 'warn'}.")
    print()
    if noteworthy:
        print("| Verdict | Direction | Peer | Test | Result | Expected | Note |")
        print("| --- | --- | --- | --- | --- | --- | --- |")
        for r in noteworthy:
            note = r[6].replace("|", "\\|")
            print(f"| {r[0]} | {r[1]} | {r[2]} | {r[3]} | {r[4]} | {r[5]} | {note} |")
        print()

    if record:
        out = {}
        for (direction, peer, test), (result, _) in sorted(cells.items()):
            kind, why = expectation(exp, direction, peer, test)
            out.setdefault(direction, {}).setdefault(peer, {})[test] = recorded(result, kind, why)
        with open(record, "w") as f:
            json.dump(out, f, indent=2, sort_keys=True)
            f.write("\n")
        print(f"Recorded today's results as expectations in {record}.")
        return 0
    return 1 if failing else 0


def main(argv):
    if len(argv) < 2 or argv[1] not in ("retry", "judge"):
        print(__doc__, file=sys.stderr)
        return 2
    return {"retry": cmd_retry, "judge": cmd_judge}[argv[1]](argv[2:])


if __name__ == "__main__":
    sys.exit(main(sys.argv))
