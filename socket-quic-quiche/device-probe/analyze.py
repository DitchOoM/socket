#!/usr/bin/env python3
"""Summarise a probe log (Android or iOS — same line grammar).

    ./analyze.py logs/<file>.log

Prints: run parameters, connections (lifetime, why it ended), migrations (Probing → Migrated
latency, failed paths), echo counts and the longest echo gap, the memory trend from heartbeats,
and every STREAM-INTEGRITY-BROKEN / CONNECTION-DEAD line verbatim.
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
fail = [(t, b) for t, b in events if b.startswith("ECHO-FAIL")]
nodata = [t for t, b in events if b.startswith("ECHO-NO-DATA")]
rtts = [int(m.group(1)) for _, b in ok for m in [re.search(r"rtt=(\d+)ms", b)] if m]
gaps = [(ok[i][0] - ok[i - 1][0], ok[i][0]) for i in range(1, len(ok))]
worst = sorted(gaps, reverse=True)[:5]
print(f"\nechoes: ok={len(ok)} fail={len(fail)} no-data={len(nodata)}")
if rtts:
    rtts.sort()
    print(f"  rtt p50={rtts[len(rtts) // 2]}ms p95={rtts[int(len(rtts) * 0.95)]}ms max={rtts[-1]}ms")
print("  longest gaps between ok echoes:", ", ".join(f"{g / 1000:.1f}s at t+{at / 1000:.0f}s" for g, at in worst))
errs = Counter(re.search(r"err=(\S+)", b).group(1) for _, b in fail if re.search(r"err=(\S+)", b))
print("  fail kinds:", dict(errs))

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
