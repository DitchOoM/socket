#!/usr/bin/env python3
"""Summarise a probe log (Android or iOS — same line grammar).

    ./analyze.py logs/<file>.log

Prints: run parameters, connections (lifetime, why it ended), migrations (Probing → Migrated
latency, failed paths), echo counts — late and unanswered apart from failed — with RTT and lateness
percentiles and the longest echo gap, the memory trend from heartbeats, every
STREAM-INTEGRITY-BROKEN / CONNECTION-DEAD line verbatim, a RE-DERIVED ECHO-LIVENESS verdict and a
RE-DERIVED #447 verdict gated by it — each of those per connection AND rolled up per address
family, so a walk answers "does v6 behave differently from v4 on this device and this route".

A log whose echo loop timed echoes by its own schedule rather than by their arrival (no
`LOOP-SCHEDULE` line) is flagged RTT-UNRELIABLE, and its rtt comes from in-sync echoes only.

A probe with lanes (one concurrent connection per target) prefixes every line with `lane=<label>`,
`lane=run` for the run's own. Each lane is analysed exactly as a whole log is, then the lanes are
paired handoff by handoff. A log without the token is one lane and prints exactly what it always did.

The family comes from each connection's own `MIGRATION-LEDGER`/`ECHO-LIVENESS`/`447-VERDICT` line
(`connection=N family=FAM`), falling back to the covering `CONNECT-ATTEMPT`'s `target=`/`family=`
keys only where a connection has none of those. A log written before the rotation carries none of
it: its connections group under `unknown` and everything else still analyses.

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

T = re.compile(r"^t=(\d+)ms (.*)$")


def parse(line):
    m = T.match(line)
    return (int(m.group(1)), m.group(2)) if m else (None, line)


def analyze(path, lines, lane=None):
    """Everything below, for one lane's lines — or for a whole log that predates lanes (lane=None)."""
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
    # Which target each attempt walked. The probe names the family itself because it cannot ask the
    # transport: every platform's QUIC builder resolves the hostname inside itself (#615).
    FAMILY = re.compile(r"\bfamily=(\S+)")
    TARGET = re.compile(r"\btarget=(\S+)")
    UNKNOWN_FAMILY = "unknown"
    FAMILY_ORDER = {"v4": 0, "v6": 1, "resolver": 2, UNKNOWN_FAMILY: 3}


    def family_of(text):
        m = FAMILY.search(text)
        return m.group(1) if m else UNKNOWN_FAMILY


    def target_of(text):
        m = TARGET.search(text)
        return m.group(1) if m else "(target unrecorded)"


    def attempt_covering(t):
        """The CONNECT-ATTEMPT in force at time t — the last one at or before it."""
        covering = [b for at, b in attempts if at <= t]
        return covering[-1] if covering else ""


    def attempt_number(text):
        """Its `n=`, which is what the probe's own `connection=N` lines are tagged with."""
        m = re.match(r"CONNECT-ATTEMPT n=(\d+)", text)
        return m.group(1) if m else "?"
    ends = [(t, b) for t, b in events if b.startswith(("STREAM-ENDED-BY-PEER", "STREAM-RESET-BY-PEER", "STREAM-WRITES-STALLED",
                                                        "CONNECTION-ENDED", "CONNECTION-DEAD", "SCOPE-EXITED"))]
    print(f"\nconnections: attempts={len(attempts)}")
    for i, (t, b) in enumerate(attempts):
        nxt = attempts[i + 1][0] if i + 1 < len(attempts) else max(last_t, t)
        end = next(((et, eb) for et, eb in ends if t < et <= nxt), None)
        lived = ((end[0] if end else nxt) - t) / 1000
        why = end[1][:110] if end else "(still up at end of log)"
        # Silent on a pre-rotation log rather than printing "(unrecorded)" on every one of its connections.
        where = f"{target_of(b)} [{family_of(b)}] " if FAMILY.search(b) else ""
        print(f"  #{i + 1} t+{t / 1000:.0f}s {where}lived {lived:.0f}s — {why}")

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
    unanswered_events = [(t, int(m.group(1))) for t, b in events for m in [re.match(r"ECHO-UNANSWERED count=(\d+)", b)] if m]
    unanswered = sum(n for _, n in unanswered_events)
    answered = sorted(ok + late)
    # Which echo loop wrote the log decides which rtt samples are round trips:
    # - before #599 it wrote one ECHO-OK per READ (`got=…B`), so an echo one payload behind made every
    #   later read return the PREVIOUS echo at once and its rtt read ~0;
    # - from #599 until the loop announced `LOOP-SCHEDULE`, it read only right after each send. Once one
    #   reply missed its read deadline, every later echo waited in the receive buffer until the next
    #   send's read: rtt ≈ the loop period, `pending` = the payload just sent, the derived read deadline
    #   inflated with it (so OVERDUE fired late) and a reply that arrived inside its deadline could read
    #   LATE. The walk qlogs put the true round trip at ~44 ms under a logged ~256 ms.
    # On both, only an in-sync echo (pending=0B) carries a real round trip. A build that logs
    # `LOOP-SCHEDULE` keeps a read outstanding until the echo arrives, so every rtt is real.
    legacy = any("got=" in b for _, b in ok[:200])
    scheduled = any(b.startswith("LOOP-SCHEDULE") for _, b in events)
    rtt_unreliable = bool(answered) and not scheduled
    timed = [b for _, b in answered if not rtt_unreliable or re.search(r"pending=0B", b)]
    rtts = sorted(int(m.group(1)) for b in timed for m in [re.search(r"rtt=(\d+)ms", b)] if m)
    lateness = sorted(int(m.group(1)) for _, b in late for m in [re.search(r"late=\+(\d+)ms", b)] if m)
    gaps = [(answered[i][0] - answered[i - 1][0], answered[i][0]) for i in range(1, len(answered))]
    worst = sorted(gaps, reverse=True)[:5]
    write_timeouts = [t for t, b in events if b.startswith("ECHO-WRITE-TIMEOUT")]
    stream_gone = [(t, b) for t, b in events if b.startswith(("STREAM-ENDED-BY-PEER", "STREAM-RESET-BY-PEER", "STREAM-WRITES-STALLED"))]
    print(f"\nechoes: ok={len(ok)} late={len(late)} overdue={len(overdue)} unanswered={unanswered} fail={len(fail)} no-data={len(nodata)}"
          f" write-timeouts={len(write_timeouts)} stream-gone={len(stream_gone)}"
          + (f" in-sync={len(timed)} one-behind={len(ok) - len(timed)} (pre-#599 grammar)" if legacy else ""))
    if rtt_unreliable and not legacy:
        print(f"  ⚠ RTT-UNRELIABLE: no LOOP-SCHEDULE line, so this build read an echo only right after each send —"
              f" {len(answered) - len(timed)} of {len(answered)} answered echoes (pending>0B) are timed by the loop, not the"
              f" path, and LATE/OVERDUE are skewed with them. rtt below is from the {len(timed)} in-sync (pending=0B) echoes only.")
    if rtts:
        print(f"  rtt{' (in-sync only)' if rtt_unreliable else ''} p50={rtts[len(rtts) // 2]}ms p95={rtts[int(len(rtts) * 0.95)]}ms max={rtts[-1]}ms")
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
    # Which build ran, what the disk had, and everything the qlog budget dropped — the probe writes
    # them down because none can be read from outside the phone afterwards. Printed when the log
    # carries them, so a log from before they existed analyses exactly as it always did.
    build = re.search(r"\bbuild=(\S+)", starts[0]) if starts else None
    if build:
        print(f"  build: {build.group(1)}" + (" — NOT STAMPED: which probe ran is unrecorded" if build.group(1).startswith("unknown") else ""))
    disk = [m.group(1) for _, b in events if b.startswith(("START ", "HEARTBEAT")) for m in [re.search(r"diskFreeMb=(\S+)", b)] if m]
    if disk:
        print(f"  disk free: {disk[0]} MB at start, {disk[-1]} MB at the last heartbeat")
    qbudget = [b for _, b in events if b.startswith("QLOG-BUDGET ")]
    if qbudget:
        print(f"  qlog budget: {qbudget[0][len('QLOG-BUDGET '):]}")
    for tag in ("QLOG-TRUNCATED", "QLOG-EVICTED", "QLOG-REFUSED", "QLOG-ROTATION-REFUSED", "QLOG-DELETE-FAILED"):
        hits = [(t, b) for t, b in events if b.startswith(tag + " ")]
        if hits:
            print(f"  {tag}: {len(hits)} — first at t+{hits[0][0] // 1000}s: {hits[0][1][:140]}")
    import os
    traces_dir = os.path.splitext(path)[0] + "-traces"
    if os.path.isdir(traces_dir):
        # A lane's files are conn-<lane>-NNNN.trace; a log from before lanes names them conn-NNNN.trace.
        mine = re.compile(rf"^conn-{re.escape(lane)}-\d+\.trace$") if lane else re.compile(r"\.trace$")
        n_traces = len([f for f in os.listdir(traces_dir) if mine.search(f)])
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


    # The probe writes family= directly on each connection's OWN MIGRATION-LEDGER / ECHO-LIVENESS /
    # 447-VERDICT line (`connection=N family=FAM`, from WalkTarget.connectionTag) — that is the record
    # for that connection, not an attribution by time. Prefer it over the covering CONNECT-ATTEMPT
    # (family_of(attempt_covering(...)) below), which stays only as the fallback for a connection with
    # no such line — a pre-rotation log, or one truncated before any of the three were emitted.
    CONNECTION_FAMILY = re.compile(r"\bconnection=(\d+) family=(\S+)")
    recorded_family = {}
    for _, b in events:
        m = CONNECTION_FAMILY.search(b)
        if m:
            recorded_family.setdefault(m.group(1), m.group(2))

    silent_connections = []
    # (start, end, family) per connection, shared by the #447 loop and the per-family roll-up below.
    windows = []
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
        opening = attempt_covering(start)
        attempt_num = attempt_number(opening)
        derived_family = family_of(opening)
        recorded = recorded_family.get(attempt_num)
        if recorded is not None:
            family = recorded
            if FAMILY.search(opening) and derived_family != recorded:
                print(f"  ⚠ connection {i + 1} (attempt {attempt_num}): recorded family={recorded} disagrees with"
                      f" its CONNECT-ATTEMPT's family={derived_family} — trusting the recorded line")
        else:
            family = derived_family
        # Named by the probe's own attempt number: an attempt that never connected leaves no CONNECTED
        # line, so this index and the log's `connection=N` tag diverge the moment one fails. Blank on a
        # pre-rotation log, matching the guard on the `#{i+1}` line above.
        tag = f"attempt {attempt_num}, {family}" if recorded is not None or FAMILY.search(opening) else ""
        windows.append((start, end, family, tag))
        label = f" [{tag}]" if tag else ""
        print(f"  connection {i + 1}{label}: {word} — answered={len(answers)} no answered echo for {hours(gap)}"
              f" (from t+{gap_from / 1000:.0f}s); no echo line at all for {hours(loop_gap)} (from t+{loop_from / 1000:.0f}s)")
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
    verdict_by_connection = {}
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
        verdict_by_connection[i] = verdict.split(" ", 1)[0]
        label = f" [{windows[i][3]}]" if windows[i][3] else ""
        print(f"  connection {i + 1}{label}: {verdict}  [{','.join(outcomes) or 'no attempts'}]")
    if silent_connections:
        worst = max(silent_connections, key=lambda c: c[1])
        print(f"  run: FAIL — connection {worst[0]} went {hours(worst[1])} without an answered echo (from t+{worst[2] / 1000:.0f}s);"
              f" {len(silent_connections)} of {len(conn_bounds)} connection(s) SILENT")
    elif conn_bounds:
        print(f"  run: every connection LIVE — the path layer's verdict stands")


    # --- per address family ---
    #
    # The point of rotating the target per connection attempt: with one server address per device, family
    # was confounded with device (the iPhone walked v6, the Samsung v4), so no difference between the two
    # recordings could be attributed to either. Grouped here, one walk answers it.
    print("\nper-family summary (family from each CONNECT-ATTEMPT's target; `unknown` = a log from before "
          "the probe rotated targets):")


    def blank():
        return {"attempts": 0, "connected": 0, "migrated": 0, "mig_failed": 0, "probes": 0,
                "ok": 0, "late": 0, "overdue": 0, "unanswered": 0, "fail": 0,
                "verdicts": Counter(), "liveness": Counter()}


    by_family = {}


    def row(fam):
        return by_family.setdefault(fam, blank())


    for t, b in attempts:
        row(family_of(b))["attempts"] += 1


    def count_in(stamps, start, end):
        return sum(1 for x in stamps if start <= x < end)


    for i, (start, end, fam, _) in enumerate(windows):
        r = row(fam)
        r["connected"] += 1
        r["migrated"] += count_in([t for t, _ in mig_ok], start, end)
        r["mig_failed"] += count_in([t for t, _ in mig_fail], start, end)
        r["probes"] += count_in(probes, start, end)
        r["ok"] += count_in([t for t, _ in ok], start, end)
        r["late"] += count_in([t for t, _ in late], start, end)
        r["overdue"] += count_in([t for t, _ in overdue], start, end)
        r["fail"] += count_in([t for t, _ in fail], start, end)
        r["unanswered"] += sum(n for t, n in unanswered_events if start <= t < end)
        r["liveness"]["SILENT" if i + 1 in silent_by_connection else "LIVE"] += 1
        if i in verdict_by_connection:
            r["verdicts"][verdict_by_connection[i]] += 1

    if not by_family:
        print("  (no CONNECT-ATTEMPT lines)")
    for fam in sorted(by_family, key=lambda f: (FAMILY_ORDER.get(f, len(FAMILY_ORDER)), f)):
        r = by_family[fam]
        print(f"  {fam}: attempts={r['attempts']} connected={r['connected']}"
              f" | migrations ok={r['migrated']} failed={r['mig_failed']} probes={r['probes']}"
              f" | echoes ok={r['ok']} late={r['late']} overdue={r['overdue']} unanswered={r['unanswered']} fail={r['fail']}")
        print(f"      #447={dict(r['verdicts']) or '(no connection reached a verdict)'} liveness={dict(r['liveness'])}")
    # A lane is one family by construction; the advice is for a whole log that walked only one.
    if len(by_family) < 2 and lane is None:
        print("  one family only — the next walk should pass a comma-separated rotation "
              "(SERVER_HOST=\"<v4>,<v6>\" ./start.sh …, or ./launch.sh \"<v4>,<v6>\") so one device covers both")


# --- lanes ---
#
# A probe with lanes prefixes every line with `lane=<label>` (`lane=run` for the run's own lines), so
# one log carries several connections running at once. Each lane is analysed exactly as a whole log
# used to be, with the run's START / PREVIOUS-RUN / TRACE-BUDGET / WAKELOCK lines shared into it, and
# the lanes are then paired handoff by handoff. A log without the token is one lane and analyses
# exactly as before.
LANE = re.compile(r"^lane=(\S+) (.*)$")
RUN_LANE = "run"
SHARED_RUN_LINES = ("START ", "PREVIOUS-RUN", "TRACE-BUDGET", "WAKELOCK")


def demux(lines):
    """{lane: [line, …]} with the token stripped, run lines under RUN_LANE; empty when no line has a token."""
    lanes = {}
    for line in lines:
        t, body = parse(line)
        m = LANE.match(body) if t is not None else None
        if m:
            lanes.setdefault(m.group(1), []).append((t, m.group(2)))
    return lanes


def stamp(events):
    return [f"t={t}ms {b}" for t, b in events]


# A handoff is path activity on any lane; events closer together than this are one handoff.
HANDOFF_GAP_MS = 30_000
ENDINGS = ("CONNECTION-DEAD", "CONNECTION-ENDED", "STREAM-ENDED-BY-PEER", "STREAM-RESET-BY-PEER", "STREAM-WRITES-STALLED")
DISRUPTION = ("PATH Probing", "PATH Validated", "PATH Migrated", "PATH Failed") + ENDINGS


def paired_handoffs(lanes):
    """One row per handoff: what each lane's path did, and the longest stretch it went without an answered echo."""
    names = [n for n in lanes if n != RUN_LANE]
    events = sorted((t, n, b) for n in names for t, b in lanes[n]
                    if b.startswith(DISRUPTION) or (b.startswith("CONNECT-ATTEMPT") and not b.startswith("CONNECT-ATTEMPT n=1 ")))
    clusters = []
    for t, n, b in events:
        if clusters and t - clusters[-1][-1][0] <= HANDOFF_GAP_MS:
            clusters[-1].append((t, n, b))
        else:
            clusters.append([(t, n, b)])
    print(f"\npaired handoffs — every lane at each handoff (path activity on any lane, events < {HANDOFF_GAP_MS // 1000}s apart):")
    if not clusters:
        print("  none — no lane saw path activity or a reconnect")
        return
    answered = {n: sorted(t for t, b in lanes[n] if b.startswith(("ECHO-OK", "ECHO-LATE"))) for n in names}
    outcome = re.compile(r"MIGRATION-ATTEMPT n=\d+ outcome=(\w+) tookMs=(\d+)")
    for i, cluster in enumerate(clusters):
        start, end = cluster[0][0], cluster[-1][0]
        cells = []
        for n in names:
            # Every attempt resolved inside the handoff, and a few seconds past its last event.
            outcomes = [m.group(1) + (f" {m.group(2)}ms" if m.group(1) == "Succeeded" else "")
                        for t, b in lanes[n] if start <= t <= end + 5_000 for m in [outcome.match(b)] if m]
            died = [b.split(" ", 1)[0] for t, b in lanes[n] if start <= t <= end and b.startswith(ENDINGS)]
            reconnects = sum(1 for t, b in lanes[n] if start <= t <= end and b.startswith("CONNECT-ATTEMPT"))
            parts = []
            if outcomes:
                runs = []
                for o in outcomes:
                    if runs and runs[-1][0] == o and not o.startswith("Succeeded"):
                        runs[-1][1] += 1
                    else:
                        runs.append([o, 1])
                parts.append(" → ".join(o if c == 1 else f"{o}×{c}" for o, c in runs))
            parts += died
            if reconnects:
                parts.append(f"reconnect×{reconnects}")
            path = "; ".join(parts) if parts else "no path event"
            # The longest stretch without an answered echo across the handoff, edges included.
            lo, hi = start - 5_000, end + 30_000
            points = [t for t in answered[n] if lo <= t <= hi]
            before = [t for t in answered[n] if t < lo]
            after = [t for t in answered[n] if t > hi]
            edges = ([before[-1]] if before else [lo]) + points + ([after[0]] if after else [hi])
            gap = max(edges[k + 1] - edges[k] for k in range(len(edges) - 1))
            cells.append(f"{n}: {path}, echo gap {gap / 1000:.1f}s")
        print(f"  #{i + 1} t+{start / 1000:.0f}s ({(end - start) / 1000:.1f}s) | " + " | ".join(cells))


def main():
    path = sys.argv[1]
    lines = open(path, encoding="utf-8", errors="replace").read().splitlines()
    lanes = demux(lines)
    if not lanes:
        analyze(path, lines)
        return
    run = lanes.get(RUN_LANE, [])
    names = [n for n in lanes if n != RUN_LANE]
    declared = next((b for _, b in run if b.startswith("LANES ")), "")
    print(f"lanes: {', '.join(names)}" + (f"  ({declared})" if declared else ""))
    for _, b in run:
        if b.startswith(("MIGRATION-TOTALS", "DONE")):
            print(f"  run: {b[:200]}")
    shared = [(t, b) for t, b in run if b.startswith(SHARED_RUN_LINES)]
    for n in names:
        print(f"\n=================== lane {n} ===================")
        analyze(path, stamp(sorted(shared + lanes[n], key=lambda e: e[0])), lane=n)
    paired_handoffs(lanes)


if __name__ == "__main__":
    main()
