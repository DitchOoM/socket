#!/usr/bin/env python3
"""Per-lane verdicts for an iOS probe log with core lanes (`core-v4`, `core-v6`) beside the 4.x lanes (`v4`, `v6`).

    analyze-core.py logs/<stamp>-<tag>.log [more logs...]           # a whole walk
    analyze-core.py logs/<stamp>-<tag>.log.4 ... logs/<stamp>-<tag>.log   # rotated pieces, oldest first
    analyze-core.py LOG --since-ms 120000 --until-ms 400000          # only a window of the log (the lines' own t=)
    analyze-core.py LOG --window-s 60                                # how long after each network change to look

What it prints, per lane:
  * the run's totals: echoes answered, connections made, how each ended (the typed close reason), and the
    path events the lane saw (core lanes: Moved / Migrated / Refused / Rehomed / Gone / Usable from the core's own
    trace; 4.x lanes: the `PATH` states);
  * for every change the OS reported of the device's network (a `lane=run OS-NET` line whose `state=` differs from
    the previous one: Wi-Fi off, Wi-Fi on, cellular taking over...), what the lane did in the next window:
      MIGRATED     the connection moved to the new path and kept going (a Migrated path event, no end);
      RODE-IT-OUT  the connection kept going with no path event at all (the change did not touch its path);
      ENDED        the connection ended in the window (and the typed reason), RECOVERED or NOT-RECOVERED by a reconnect;
    with `echoGapMs` (the silence around the change: last answered echo before it to the first after it) and
    `firstEchoAfterMs` (change to the first answered echo).
Standard library only. Read-only.
"""
import argparse
import re
import sys
from collections import defaultdict

LINE = re.compile(r"^t=(\d+)ms lane=(\S+) (\S+)(?: (.*))?$")
PATH_WORD = re.compile(r"\bPATH (\w+)")
STATE_FIELD = re.compile(r"\bstate=(\S+)")
CLOSE_REASON = re.compile(r"reason=(.*?)(?: typed=| — |$)")
CORE_PATH_PHASES = ("Moved", "Migrated", "Refused", "Rehomed", "Gone", "Usable")


class Lane:
    def __init__(self, label):
        self.label = label
        self.echoes = []  # t of every ECHO-OK
        self.events = []  # (t, kind, detail) of everything that is not an echo
        self.connected = 0
        self.attempts = 0
        self.ends = []  # (t, reason)
        self.paths = defaultdict(int)

    @property
    def is_core(self):
        return self.label.startswith("core-")


def parse(paths, since, until):
    lanes, anchors, run_lines = {}, [], []
    last_state = None
    for path in paths:
        with open(path, errors="replace") as handle:
            for raw in handle:
                m = LINE.match(raw.rstrip("\n"))
                if not m:
                    continue
                t, lane, word, rest = int(m.group(1)), m.group(2), m.group(3), m.group(4) or ""
                if t < since or t > until:
                    continue
                if lane == "run":
                    run_lines.append((t, word, rest))
                    if word == "OS-NET":
                        s = STATE_FIELD.search(rest)
                        state = s.group(1) if s else "?"
                        if last_state is not None and state != last_state:
                            anchors.append((t, last_state, state))
                        last_state = state
                    continue
                ln = lanes.setdefault(lane, Lane(lane))
                if word == "ECHO-OK":
                    ln.echoes.append(t)
                    continue
                if word == "CONNECT-ATTEMPT":
                    ln.attempts += 1
                elif word == "CONNECTED":
                    ln.connected += 1
                if word == "CORE-EVENT":
                    pm = PATH_WORD.search(rest)
                    if pm:
                        ln.paths[pm.group(1)] += 1
                        ln.events.append((t, "PATH", pm.group(1)))
                    continue
                if word == "PATH":
                    pm = re.match(r"(\w+)", rest)
                    if pm and pm.group(1) != "Original":
                        ln.paths[pm.group(1)] += 1
                        ln.events.append((t, "PATH", pm.group(1)))
                    continue
                if word in ("CONNECTION-END", "CONNECTION-DEAD", "CONNECTION-ENDED"):
                    rm = CLOSE_REASON.search(rest)
                    reason = rm.group(1) if rm else rest[:80]
                    # ENDED repeats END, and END repeats DEAD, for the same connection: one end per connection.
                    if word != "CONNECTION-ENDED" and not (ln.ends and t - ln.ends[-1][0] < 2000):
                        ln.ends.append((t, reason))
                    ln.events.append((t, word, reason))
                    continue
                if word in ("CONNECT-ATTEMPT", "CONNECTED", "RECONNECTING", "FLIGHT-DUMP", "FLIGHT-MISSING", "FLIGHT-DROPPED", "CORE-PATHS", "APP-STATE"):
                    ln.events.append((t, word, rest[:100]))
    return lanes, anchors, run_lines


def around(echoes, t0):
    prev = max((e for e in echoes if e <= t0), default=None)
    nxt = min((e for e in echoes if e > t0), default=None)
    return prev, nxt


def verdict(lane, t0, window_ms):
    inside = [e for e in lane.events if t0 <= e[0] <= t0 + window_ms]
    prev, nxt = around(lane.echoes, t0)
    ended = [e for e in inside if e[1] in ("CONNECTION-END", "CONNECTION-DEAD")]
    migrated = [e for e in inside if e[1] == "PATH" and e[2] == "Migrated"]
    pathful = [e for e in inside if e[1] == "PATH"]
    gap = (nxt - prev) if (prev is not None and nxt is not None) else None
    first = (nxt - t0) if nxt is not None else None
    reconnected = any(e[1] == "CONNECTED" for e in inside)
    if ended:
        outcome = "ENDED(%s) %s" % (ended[0][2], "RECOVERED" if reconnected and nxt is not None and nxt <= t0 + window_ms else "NOT-RECOVERED")
    elif migrated:
        outcome = "MIGRATED"
    elif nxt is None:
        outcome = "NO-ECHO-IN-LOG-AFTER"
    else:
        outcome = "RODE-IT-OUT"
    detail = ",".join("%s@+%dms" % (e[2], e[0] - t0) for e in pathful) or "-"
    return outcome, gap, first, detail


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("logs", nargs="+")
    ap.add_argument("--since-ms", type=int, default=0)
    ap.add_argument("--until-ms", type=int, default=1 << 60)
    ap.add_argument("--window-s", type=int, default=60)
    args = ap.parse_args()
    lanes, anchors, _ = parse(args.logs, args.since_ms, args.until_ms)
    if not lanes:
        print("no lane lines found (is this a probe log?)", file=sys.stderr)
        return 1
    order = sorted(lanes, key=lambda l: (lanes[l].is_core, l))
    print("== per lane totals ==")
    for label in order:
        ln = lanes[label]
        reasons = defaultdict(int)
        for _, r in ln.ends:
            reasons[r] += 1
        print(
            "lane=%-8s echoes=%d connects=%d/%d ends=%d paths={%s}"
            % (
                label,
                len(ln.echoes),
                ln.connected,
                ln.attempts,
                len(ln.ends),
                ",".join("%s:%d" % kv for kv in sorted(ln.paths.items())) or "-",
            )
        )
        for r, n in sorted(reasons.items(), key=lambda kv: -kv[1]):
            print("    end x%d  %s" % (n, r))
    print("\n== OS network changes (lane=run OS-NET state changed) and what each lane did in the next %ds ==" % args.window_s)
    if not anchors:
        print("(none in this window)")
    for t0, old, new in anchors:
        print("\nt=%dms  %s  ->  %s" % (t0, old, new))
        for label in order:
            outcome, gap, first, detail = verdict(lanes[label], t0, args.window_s * 1000)
            print(
                "  lane=%-8s %-44s echoGapMs=%-7s firstEchoAfterMs=%-7s pathEvents=%s"
                % (label, outcome, "-" if gap is None else gap, "-" if first is None else first, detail)
            )
    return 0


if __name__ == "__main__":
    sys.exit(main())
