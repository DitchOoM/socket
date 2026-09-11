#!/usr/bin/env python3
"""Summarise a probe log (Android or iOS — same line grammar).

    ./analyze.py logs/<file>.log

Prints: run parameters, connections (lifetime, why it ended), migrations (Probing → Migrated
latency, failed paths), echo counts — late and unanswered apart from failed — with RTT and lateness
percentiles and the longest echo gap, the memory trend from heartbeats, every
STREAM-INTEGRITY-BROKEN / CONNECTION-DEAD line verbatim, and a RE-DERIVED #447 verdict.

The re-derived verdict exists because a probe build before #601 printed its own `447-VERDICT` line
using logic that counted the retries of a failing episode as recovery — it reported PASS for a
connection whose every probe went unanswered and which then died. Any log written by such a build
still carries the *evidence* (the MIGRATION-ATTEMPT sequence), so the verdict can be recomputed here
without reinstalling the probe and restarting a walk.
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
starts = [b for _, b in events if b.startswith("START ")]
print("START:", starts[0] if starts else "(none)")
print(f"lines={len(lines)} duration={(events[-1][0] or 0) / 3600000:.2f}h")

# connections
attempts = [(t, b) for t, b in events if b.startswith("CONNECT-ATTEMPT")]
ends = [(t, b) for t, b in events if b.startswith(("CONNECTION-ENDED", "CONNECTION-DEAD", "SCOPE-EXITED"))]
print(f"\nconnections: attempts={len(attempts)}")
for i, (t, b) in enumerate(attempts):
    nxt = attempts[i + 1][0] if i + 1 < len(attempts) else (events[-1][0] or t)
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
print(f"\nechoes: ok={len(ok)} late={len(late)} overdue={len(overdue)} unanswered={unanswered} fail={len(fail)} no-data={len(nodata)}"
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

# verbatim: the lines that matter
for tag in ("STREAM-INTEGRITY-BROKEN", "CONNECTION-DEAD", "WAKELOCK", "DONE", "MIGRATION-"):
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
print("\n#447 verdict (re-derived — the log's own line may predate #601):")
conn_bounds = [t for t, b in events if b.startswith("CONNECTED ")]
attempt_lines = [(t, b) for t, b in events if b.startswith("MIGRATION-ATTEMPT")]
OUTCOME = re.compile(r"outcome=(\w+)")
for i, start in enumerate(conn_bounds):
    end = conn_bounds[i + 1] if i + 1 < len(conn_bounds) else float("inf")
    outcomes = [OUTCOME.search(b).group(1) for t, b in attempt_lines if start <= t < end and OUTCOME.search(b)]
    lost = [j for j, o in enumerate(outcomes) if o == "PathNotValidated"]
    if not outcomes:
        continue
    if not lost:
        print(f"  connection {i + 1}: INCONCLUSIVE — no probe went unanswered ({len(outcomes)} attempt(s), #445 only)")
        continue
    after = outcomes[lost[0] + 1:]
    answered = [o for o in after if o == "Succeeded"]
    no_spare = [o for o in after if o == "NoSpareConnectionId"]
    if answered:
        verdict = f"PASS — {len(lost)} unanswered, then {len(answered)} later probe(s) ANSWERED: pool recovered"
    elif no_spare:
        verdict = f"REGRESSION — {len(lost)} unanswered, then {len(no_spare)} NoSpareConnectionId: pool did not come back"
    elif after:
        verdict = f"FAIL — {len(lost)} unanswered and {len(after)} later probe(s), none answered: never regained a path"
    else:
        verdict = f"INCONCLUSIVE — {len(lost)} unanswered, nothing attempted afterwards"
    print(f"  connection {i + 1}: {verdict}  [{','.join(outcomes)}]")
