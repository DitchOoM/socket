#!/usr/bin/env python3
"""Summarise a probe log (Android or iOS — same line grammar).

    ./analyze.py logs/<file>.log

Prints: run parameters, connections (lifetime, why it ended), migrations (Probing → Migrated
latency, failed paths), echo counts — late and unanswered apart from failed — with RTT and lateness
percentiles and the longest echo gap, the memory trend from heartbeats, every
STREAM-INTEGRITY-BROKEN / CONNECTION-DEAD line verbatim, a RE-DERIVED ECHO-LIVENESS verdict and a
RE-DERIVED #447 verdict gated by it.

The re-derived verdicts exist because a probe build before #601 printed its own `447-VERDICT` line
using logic that counted the retries of a failing episode as recovery — it reported PASS for a
connection whose every probe went unanswered and which then died — and a build before #620 printed
PASS for a connection whose stream had not carried an answered echo for 68 h, because the path layer
kept validating while the peer had long since FIN'd the stream. Any log written by such a build
still carries the *evidence* (the MIGRATION-ATTEMPT sequence, the ECHO-OK/LATE timestamps), so both
verdicts can be recomputed here without reinstalling the probe and restarting a walk.
"""
import re
import sys
from collections import Counter

path = sys.argv[1]
lines = open(path, encoding="utf-8", errors="replace").read().splitlines()
T = re.compile(r"^t=(\d+)ms (.*)$")


def parse(line):
    m = T.match(line)
    return (int(m.group(1)), m.group(2)) if m else (None, line)


events = [parse(l) for l in lines]
# The last STAMPED time: a trailing line without a stamp (a truncated write, a bare DONE) must not
# read as t=0 and turn every gap that reaches the end of the log negative.
last_t = max((t for t, _ in events if t is not None), default=0)
starts = [b for _, b in events if b.startswith("START ")]
print("START:", starts[0] if starts else "(none)")
print(f"lines={len(lines)} duration={last_t / 3600000:.2f}h")

# connections — a connection ends at the first of: the stream going (peer FIN/RESET, writes stalled),
# the connection dying, or the scope exiting; the stream lines come first, so they name the real end.
attempts = [(t, b) for t, b in events if b.startswith("CONNECT-ATTEMPT")]
ends = [(t, b) for t, b in events if b.startswith(("STREAM-ENDED-BY-PEER", "STREAM-RESET-BY-PEER", "STREAM-WRITES-STALLED",
                                                    "CONNECTION-ENDED", "CONNECTION-DEAD", "SCOPE-EXITED"))]
print(f"\nconnections: attempts={len(attempts)}")
for i, (t, b) in enumerate(attempts):
    nxt = attempts[i + 1][0] if i + 1 < len(attempts) else max(last_t, t)
    end = next(((et, eb) for et, eb in ends if t < et <= nxt), None)
    lived = ((end[0] if end else nxt) - t) / 1000
    why = end[1][:110] if end else "(still up at end of log)"
    print(f"  #{i + 1} t+{t / 1000:.0f}s lived {lived:.0f}s — {why}")

# migrations
mig_ok = [(t, b) for t, b in events if b.startswith("PATH Migrated")]
mig_fail = [(t, b) for t, b in events if b.startswith("PATH Failed")]
probes = [t for t, b in events if b.startswith("PATH Probing")]
print(f"\nmigrations: succeeded={len(mig_ok)} failed={len(mig_fail)} probes={len(probes)}")
for t, b in mig_ok:
    p = max([pt for pt in probes if pt <= t], default=None)
    print(f"  t+{t / 1000:.0f}s {b[:90]} {'took ' + str(t - p) + 'ms' if p is not None else ''}")
for t, b in mig_fail:
    print(f"  t+{t / 1000:.0f}s {b[:110]}")

# echoes
ok = [(t, b) for t, b in events if b.startswith("ECHO-OK")]
late = [(t, b) for t, b in events if b.startswith("ECHO-LATE")]
overdue = [(t, b) for t, b in events if b.startswith("ECHO-OVERDUE")]
fail = [(t, b) for t, b in events if b.startswith("ECHO-FAIL")]
nodata = [t for t, b in events if b.startswith("ECHO-NO-DATA")]
unanswered = sum(int(m.group(1)) for _, b in events for m in [re.match(r"ECHO-UNANSWERED count=(\d+)", b)] if m)
answered = sorted(ok + late)
# A probe built before #599 wrote one ECHO-OK per READ (`got=…B`), so an echo that arrived one
# payload behind made every later read return the PREVIOUS echo at once: its rtt read ~0 and only
# in-sync echoes (pending=0B) carried a real round trip. Since #599 every exchange is judged on its
# own send time, so every rtt is real.
legacy = any("got=" in b for _, b in ok[:200])
timed = [b for _, b in answered if not legacy or re.search(r"pending=0B", b)]
rtts = sorted(int(m.group(1)) for b in timed for m in [re.search(r"rtt=(\d+)ms", b)] if m)
lateness = sorted(int(m.group(1)) for _, b in late for m in [re.search(r"late=\+(\d+)ms", b)] if m)
gaps = [(answered[i][0] - answered[i - 1][0], answered[i][0]) for i in range(1, len(answered))]
worst = sorted(gaps, reverse=True)[:5]
write_timeouts = [t for t, b in events if b.startswith("ECHO-WRITE-TIMEOUT")]
stream_gone = [(t, b) for t, b in events if b.startswith(("STREAM-ENDED-BY-PEER", "STREAM-RESET-BY-PEER", "STREAM-WRITES-STALLED"))]
print(f"\nechoes: ok={len(ok)} late={len(late)} overdue={len(overdue)} unanswered={unanswered} fail={len(fail)} no-data={len(nodata)}"
      f" write-timeouts={len(write_timeouts)} stream-gone={len(stream_gone)}"
      + (f" in-sync={len(timed)} one-behind={len(ok) - len(timed)} (pre-#599 grammar)" if legacy else ""))
if rtts:
    print(f"  rtt{' (in-sync only)' if legacy else ''} p50={rtts[len(rtts) // 2]}ms p95={rtts[int(len(rtts) * 0.95)]}ms max={rtts[-1]}ms")
if lateness:
    print(f"  late by p50=+{lateness[len(lateness) // 2]}ms p95=+{lateness[int(len(lateness) * 0.95)]}ms max=+{lateness[-1]}ms")
print("  longest gaps between answered echoes:", ", ".join(f"{g / 1000:.1f}s at t+{at / 1000:.0f}s" for g, at in worst))
errs = Counter(re.search(r"err=(\S+)", b).group(1) for _, b in fail if re.search(r"err=(\S+)", b))
print("  fail kinds:", dict(errs), "(a pre-#599 build files every missed deadline here as TimeoutCancellationException)" if legacy and errs else "")

# heartbeats (memory trend)
hb = [(t, b) for t, b in events if b.startswith("HEARTBEAT")]
if hb:
    rss = [int(m.group(1)) for _, b in hb for m in [re.search(r"(?:VmRSS:|rss=)(\d+)", b)] if m]
    print(f"\nheartbeats: {len(hb)}  rss first={rss[0] if rss else '?'}kB last={rss[-1] if rss else '?'}kB "
          f"max={max(rss) if rss else '?'}kB")
    for t, b in (hb[:2] + hb[-2:] if len(hb) > 4 else hb):
        print(f"  t+{t / 1000:.0f}s {b[:150]}")

# capture health: did the instruments keep recording for the whole run?
spent = [(t, b) for t, b in events if b.startswith("TRACE-BUDGET-SPENT")]
prev = [b for _, b in events if b.startswith("PREVIOUS-RUN")]
hb_gaps = [(hb[i][0] - hb[i - 1][0], hb[i][0]) for i in range(1, len(hb)) if hb[i][0] - hb[i - 1][0] > 180_000]
print("\ncapture health:")
print(f"  trace budget: {'SPENT at t+' + str(spent[0][0] // 1000) + 's — the rest of the run is NOT replayable' if spent else 'never spent'}")
print(f"  heartbeat gaps > 3 min: {len(hb_gaps)}" + (" — " + ", ".join(f"{g / 60000:.0f} min ending t+{at / 1000:.0f}s" for g, at in hb_gaps[:5]) if hb_gaps else ""))
print(f"  previous run at start: {prev[0][13:] if prev else '(pre-rotation build: a START deleted whatever was there)'}")
import os
traces_dir = os.path.splitext(path)[0] + "-traces"
if os.path.isdir(traces_dir):
    n_traces = len([f for f in os.listdir(traces_dir) if f.endswith(".trace")])
    n_conns = len([1 for _, b in events if b.startswith("CONNECTED ")])
    print(f"  replay traces: {n_traces} file(s) for {n_conns} connection(s)" + ("" if n_traces >= n_conns else " — SOME CONNECTIONS HAVE NO TRACE"))
else:
    print(f"  replay traces: no {os.path.basename(traces_dir)}/ beside the log — pull.sh puts it there")

# verbatim: the lines that matter
for tag in ("STREAM-INTEGRITY-BROKEN", "STREAM-ENDED-BY-PEER", "STREAM-RESET-BY-PEER", "STREAM-WRITES-STALLED",
            "STALL-SUSPECTED", "CONNECTION-DEAD", "WAKELOCK", "DONE", "MIGRATION-", "ECHO-LIVENESS"):
    hits = [(t, b) for t, b in events if b.startswith(tag)]
    if hits:
        print(f"\n{tag}: {len(hits)}")
        for t, b in hits[:8]:
            print(f"  t+{t / 1000:.0f}s {b[:160]}")

# --- #447 verdict, re-derived from the attempt sequence (see the module docstring) ---
#
# Recovery means a probe armed AFTER an unanswered one was itself ANSWERED. Another probe merely
# being sent is the retry ladder of the same failure, and a migration that succeeded BEFORE the
# first unanswered probe says nothing about the pool afterwards.
# --- ECHO-LIVENESS, re-derived from the answered-echo timestamps (see the module docstring) ---
#
# A connection is SILENT if it went longer than QUIET_LIMIT without an answered echo (ECHO-OK or
# ECHO-LATE), measured from CONNECTED to the first answer, between answers, and from the last answer
# to the connection's end. The limit is the probe's own (EchoLivenessVerdict.QUIET_LIMIT): longer than
# any silence a healthy walk produces, since a reconnect backoff is 60 s and a dead radio ends the
# connection at its 30 s idle timeout. The loop's own silence — no echo line of ANY kind — is printed
# beside it: a pre-#620 probe logged ECHO-NO-DATA on every FIN it re-read, then nothing at all once
# the write blocked, so the two figures differ exactly where the old build went quiet.
QUIET_LIMIT_MS = 10 * 60 * 1000
print(f"\nECHO-LIVENESS (re-derived — the log's own line may predate #620; limit {QUIET_LIMIT_MS // 60000} min):")
conn_bounds = [t for t, b in events if b.startswith("CONNECTED ")]
echo_lines = sorted(t for t, b in events if b.startswith("ECHO-"))


def hours(ms):
    return f"{ms / 3600000:.1f}h" if ms >= 3600000 else f"{ms / 1000:.0f}s"


def longest_gap(points, start, end):
    """(gap_ms, gap_from_ms) over start → points… → end; the whole life if there are no points."""
    edges = [start] + points + [end]
    gaps = [(edges[k + 1] - edges[k], edges[k]) for k in range(len(edges) - 1)]
    return max(gaps)


silent_connections = []
for i, start in enumerate(conn_bounds):
    nxt = conn_bounds[i + 1] if i + 1 < len(conn_bounds) else float("inf")
    end = next((et for et, eb in ends if start < et <= nxt), None)
    if end is None:
        end = last_t if nxt == float("inf") else nxt
    answers = [t for t, _ in answered if start <= t < end]
    gap, gap_from = longest_gap(answers, start, end)
    loop_gap, loop_from = longest_gap([t for t in echo_lines if start <= t < end], start, end)
    word = "SILENT" if gap > QUIET_LIMIT_MS else "LIVE"
    if word == "SILENT":
        silent_connections.append((i + 1, gap, gap_from))
    print(f"  connection {i + 1}: {word} — answered={len(answers)} no answered echo for {hours(gap)} (from t+{gap_from / 1000:.0f}s);"
          f" no echo line at all for {hours(loop_gap)} (from t+{loop_from / 1000:.0f}s)")
if not conn_bounds:
    print("  never connected")

# --- #447 verdict, re-derived from the attempt sequence (see the module docstring) ---
#
# Recovery means a probe armed AFTER an unanswered one was itself ANSWERED. Another probe merely
# being sent is the retry ladder of the same failure, and a migration that succeeded BEFORE the
# first unanswered probe says nothing about the pool afterwards. And a connection whose stream went
# SILENT cannot pass at all: a path that answers no echo has not been validated, whatever its probes
# said, so the path layer's verdict is printed as void.
print("\n#447 verdict (re-derived — the log's own line may predate #601/#620):")
attempt_lines = [(t, b) for t, b in events if b.startswith("MIGRATION-ATTEMPT")]
OUTCOME = re.compile(r"outcome=(\w+)")
silent_by_connection = {c: (gap, gap_from) for c, gap, gap_from in silent_connections}
for i, start in enumerate(conn_bounds):
    end = conn_bounds[i + 1] if i + 1 < len(conn_bounds) else float("inf")
    outcomes = [OUTCOME.search(b).group(1) for t, b in attempt_lines if start <= t < end and OUTCOME.search(b)]
    lost = [j for j, o in enumerate(outcomes) if o == "PathNotValidated"]
    if not outcomes and (i + 1) not in silent_by_connection:
        continue
    if not lost:
        verdict = f"INCONCLUSIVE — no probe went unanswered ({len(outcomes)} attempt(s), #445 only)"
    else:
        after = outcomes[lost[0] + 1:]
        answered_after = [o for o in after if o == "Succeeded"]
        no_spare = [o for o in after if o == "NoSpareConnectionId"]
        if answered_after:
            verdict = f"PASS — {len(lost)} unanswered, then {len(answered_after)} later probe(s) ANSWERED: pool recovered"
        elif no_spare:
            verdict = f"REGRESSION — {len(lost)} unanswered, then {len(no_spare)} NoSpareConnectionId: pool did not come back"
        elif after:
            verdict = f"FAIL — {len(lost)} unanswered and {len(after)} later probe(s), none answered: never regained a path"
        else:
            verdict = f"INCONCLUSIVE — {len(lost)} unanswered, nothing attempted afterwards"
    if (i + 1) in silent_by_connection:
        gap, _ = silent_by_connection[i + 1]
        verdict = f"FAIL — no answered echo for {hours(gap)}, so the path layer's verdict is void; on its own it read: {verdict}"
    print(f"  connection {i + 1}: {verdict}  [{','.join(outcomes) or 'no attempts'}]")
if silent_connections:
    worst = max(silent_connections, key=lambda c: c[1])
    print(f"  run: FAIL — connection {worst[0]} went {hours(worst[1])} without an answered echo (from t+{worst[2] / 1000:.0f}s);"
          f" {len(silent_connections)} of {len(conn_bounds)} connection(s) SILENT")
elif conn_bounds:
    print(f"  run: every connection LIVE — the path layer's verdict stands")
