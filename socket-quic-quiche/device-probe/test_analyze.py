"""Tests for analyze.py: lane demultiplexing, the OS timeline, stalls by phase.

    python3 -m unittest discover -s socket-quic-quiche/device-probe -p 'test_*.py'
"""
import os
import re
import subprocess
import sys
import tempfile
import unittest

HERE = os.path.dirname(os.path.abspath(__file__))
ANALYZE = os.path.join(HERE, "analyze.py")
TESTDATA = os.path.join(HERE, "testdata")
# The report is UTF-8 whatever the runner's locale is.
ENV = dict(os.environ, PYTHONIOENCODING="utf-8")


def run(path):
    return subprocess.run([sys.executable, ANALYZE, path], capture_output=True, encoding="utf-8", env=ENV, check=True).stdout


def analyze(log_text):
    with tempfile.TemporaryDirectory() as d:
        path = os.path.join(d, "walk.log")
        with open(path, "w", encoding="utf-8") as f:
            f.write(log_text)
        return run(path)


# The three OS network records the synthetic walk carries, device-wide: on Wi-Fi, onto cellular at the
# handoff, back onto Wi-Fi just before the run ends. Written by the probe under `lane=run`.
# These two are pinned verbatim on the Kotlin side (OsNetworkFactsTests), so this walk log is
# seeded with text the probe really writes rather than an approximation of it.
WIFI_OS_NET = ("state=Routable|Link:Wifi:441492361229|Confirmed v4=SiteLocal v6=LinkLocal "
               "cell=Reported(sim=Ready,reg=InService,data=Disconnected,roaming=Roaming,bearer=Lte) "
               "links=wlan0=192.168.1.6,fe80::4c2a:8bff:fe31:9f10")
CELL_OS_NET = ("state=Routable|Link:Cellular:559036687885|Confirmed v4=SiteLocal v6=Global "
               "cell=Reported(sim=Ready,reg=InService,data=Connected,roaming=Home,bearer=Nr) "
               "links=rmnet_data0=100.79.14.2,2600:387:f:6e13::41,fe80::9c1e:22ff:fe08:114c")


def two_lane_log():
    """Two lanes over two minutes and one handoff at t+60s: v4 migrates in 46 ms without missing an echo;
    v6 loses four probes, goes 16.75 s without an answered echo, then migrates in 764 ms. The device
    leaves Wi-Fi for cellular at the handoff and comes back just before the end."""
    lines = []

    def at(t, lane, body):
        lines.append((t, f"t={t}ms lane={lane} {body}"))

    at(0, "run", "START device=SM-F956U1 sdk=36 targets=192.0.2.1:44433/v4,[2001:db8::1]:44433/v6 "
                 "minutes=2 echoIntervalMs=250 qlog=off")
    at(1, "run", "LANES v4=192.0.2.1:44433 v6=[2001:db8::1]:44433 staggerMs=125")
    at(2, "run", "TRACE-BUDGET mb=512 plannedExchanges=960 lanes=2")
    at(3, "run", "WAKELOCK acquired held=true timeoutMs=240000")
    at(4, "run", "OS-NET-SOURCE monitor=PlatformSignalled/RouteAndInternet cellular=Signalled")
    at(5, "run", f"OS-NET {WIFI_OS_NET}")
    at(59_500, "run", f"OS-NET {CELL_OS_NET}")
    at(120_450, "run", f"OS-NET {WIFI_OS_NET}")
    for lane, offset in (("v4", 0), ("v6", 125)):
        at(offset + 10, lane, f"CONNECT-ATTEMPT n=1 target={'192.0.2.1:44433' if lane == 'v4' else '[2001:db8::1]:44433'} family={lane}")
        at(offset + 60, lane, "CONNECTED session=aa wire=aa alpn=test")
        at(offset + 61, lane, "PATH Original")
        at(offset + 62, lane, "LOOP-SCHEDULE read=held-until-answered intervalMs=250")
    seq = {"v4": 0, "v6": 0}
    for k in range(480):
        for lane, offset in (("v4", 0), ("v6", 125)):
            sent = 100 + offset + k * 250
            seq[lane] += 1
            if lane == "v6" and 60_000 <= sent < 76_500:
                continue
            at(sent + 44, lane, f"ECHO-OK seq={seq[lane]} rtt=44ms pending=0B")
    at(60_010, "v4", "PATH Probing(endpoint=10.0.0.2:5000)")
    at(60_056, "v4", "PATH Validated(endpoint=10.0.0.2:5000)")
    at(60_056, "v4", "PATH Migrated(endpoint=10.0.0.2:5000)")
    at(60_057, "v4", "MIGRATION-ATTEMPT n=1 outcome=Succeeded tookMs=46")
    t = 60_020
    for n in range(1, 5):
        at(t, "v6", "PATH Probing(endpoint=[2600::2]:5001)")
        at(t + 3000, "v6", "PATH Failed(result=PathNotValidated)")
        at(t + 3001, "v6", f"MIGRATION-ATTEMPT n={n} outcome=PathNotValidated tookMs=3000")
        t += 3500
    at(t, "v6", "PATH Probing(endpoint=[2600::2]:5002)")
    at(t + 764, "v6", "PATH Migrated(endpoint=[2600::2]:5002)")
    at(t + 765, "v6", "MIGRATION-ATTEMPT n=5 outcome=Succeeded tookMs=764")
    for lane in ("v4", "v6"):
        at(120_500, lane, "SCOPE-EXITED cleanly")
        at(120_501, lane, f"ECHO-LIVENESS connection=1 family={lane} LIVE — answered=1 longestQuietMs=1 limitMs=600000 ended=WalkOver")
    at(120_600, "run", "MIGRATION-TOTALS connections=2 attempts=6 succeeded=2 unansweredProbes=4 probedAfterUnanswered=4 "
                       "answeredAfterUnanswered=1 noSpareAfterUnanswered=0 outcomes=[Succeeded=2,PathNotValidated=4]")
    at(120_601, "run", "DONE attempts=2 lanes=2 log=/sdcard/quic-handoff-probe.log")
    return "".join(l + "\n" for _, l in sorted(lines, key=lambda e: e[0])), seq


def section(out, lane):
    m = re.search(rf"=+ lane {lane} =+\n(.*?)(?=\n=+ lane |\npaired handoffs|\Z)", out, re.S)
    assert m, f"no section for lane {lane} in:\n{out}"
    return m.group(1)


class LaneDemuxTests(unittest.TestCase):
    def test_each_lane_is_analysed_on_its_own_lines(self):
        text, _ = two_lane_log()
        v6_echoes = text.count("lane=v6 ECHO-OK")
        out = analyze(text)

        self.assertIn("lanes: v4, v6", out)
        v4, v6 = section(out, "v4"), section(out, "v6")
        self.assertIn("echoes: ok=480 ", v4)
        self.assertIn(f"echoes: ok={v6_echoes} ", v6)
        self.assertIn("migrations: succeeded=1 failed=0 probes=1", v4)
        self.assertIn("migrations: succeeded=1 failed=4 probes=5", v6)
        self.assertNotIn("RTT-UNRELIABLE", out, "both lanes logged LOOP-SCHEDULE")
        self.assertIn("START: START device=SM-F956U1", v6, "the run's START is shared into every lane")

    def test_the_paired_table_puts_both_lanes_of_one_handoff_on_one_row(self):
        out = analyze(two_lane_log()[0])

        table = out[out.index("paired handoffs"):]
        rows = [l for l in table.splitlines() if l.startswith("  #")]
        self.assertEqual(1, len(rows), out)
        self.assertIn("v4: Succeeded 46ms, echo gap 0.2s", rows[0])
        self.assertIn("v6: PathNotValidated×4 → Succeeded 764ms, echo gap 16.8s", rows[0])

    def test_the_os_timeline_is_printed_once_for_the_device_as_a_field_diff(self):
        out = analyze(two_lane_log()[0])

        self.assertEqual(1, out.count("OS network (what the OS said"), "the device's record is not per lane")
        os_section = out[out.index("OS network (what the OS said"):out.index("=== lane")]
        self.assertIn("source: monitor=PlatformSignalled/RouteAndInternet cellular=Signalled", os_section)
        self.assertIn("changes: 3", os_section)
        # The first record is printed whole; later ones show only what moved, which is the one thing a
        # reader lining an OS event up against a handoff needs.
        self.assertIn(f"t+0s {WIFI_OS_NET}", os_section)
        self.assertIn("v6: LinkLocal -> Global", os_section)
        self.assertIn("state: Routable|Link:Wifi:441492361229|Confirmed -> Routable|Link:Cellular:559036687885|Confirmed",
                      os_section)
        self.assertIn("data=Connected", os_section)
        # ...and the lane sections do not repeat it.
        self.assertNotIn("OS network (what the OS said", section(out, "v4"))

    def test_each_lane_reports_whether_its_connections_died_near_an_os_event(self):
        text, _ = two_lane_log()

        near = analyze(text)
        self.assertIn("OS-NET within ±30s of a connection ending: 1/1", section(near, "v4"))
        self.assertIn("OS-NET within ±30s of a connection ending: 1/1", section(near, "v6"))

        # Drop the OS record that sat beside the end: the death is now one the OS never mentioned.
        orphaned = analyze("".join(l + "\n" for l in text.splitlines() if "t=120450ms" not in l))
        self.assertIn("OS-NET within ±30s of a connection ending: 0/1 — no OS event near t+120s",
                      section(orphaned, "v4"))

    def test_a_log_without_lane_tokens_analyses_exactly_as_before_lanes(self):
        """A real one-lane walk excerpt (hours of echoes cut out, so its verdicts describe the excerpt), against
        the output of the analyzer before lanes existed."""
        with open(os.path.join(TESTDATA, "one-lane-ios-leg2-excerpt.expected.txt"), encoding="utf-8") as f:
            expected = f.read()
        out = run(os.path.join(TESTDATA, "one-lane-ios-leg2-excerpt.log"))

        self.assertEqual(expected, out)
        self.assertNotIn("=== lane", out)


def stall_section(out):
    m = re.search(r"\nstalls by phase: (.*?)\n\n", out, re.S)
    assert m, f"no stalls section in:\n{out}"
    return m.group(1)


class StallPhaseTests(unittest.TestCase):
    TARGET = "CONNECT-ATTEMPT n={n} target=[2001:db8::1]:4433 family=v6"

    def test_phased_stalls_are_reported_by_phase_with_their_bound(self):
        out = analyze("".join(l + "\n" for l in [
            "t=0ms START device=test minutes=60 echoIntervalMs=2000",
            "t=10ms " + self.TARGET.format(n=1),
            "t=120000ms STALL-SUSPECTED phase=Connecting attempt=1 waited=120s bound=40s — expected the connect to succeed "
            "or fail; it has done neither. Dumping the last 256 trace events.",
            "t=180000ms STALL-RECOVERED phase=Connecting attempt=1 now phase=Echoing attempt=1 loopTicks=0",
            "t=180000ms CONNECTED session=aa wire=aa alpn=test",
            "t=420000ms STALL-SUSPECTED phase=Echoing attempt=1 loopTicks=57 waited=180s bound=120s — expected loopTicks to "
            "advance; the echo loop is not running. Dumping the last 256 trace events.",
        ]))

        self.assertEqual(
            "2 suspected, 1 recovered — Connecting=1 Echoing=1\n"
            "  t+120s phase=Connecting attempt=1 waited=120s bound=40s\n"
            "  t+420s phase=Echoing attempt=1 waited=180s bound=120s",
            stall_section(out),
        )

    def test_an_unphased_stall_in_a_reconnect_backoff_is_read_as_backoff(self):
        """2026-09-24: every v6 connect failed fast for want of a route and the lane sat in its 60 s backoff;
        the pre-phase watchdog called the echo loop stalled."""
        out = analyze("".join(l + "\n" for l in [
            "t=0ms START device=test minutes=60 echoIntervalMs=2000",
            "t=1370000ms " + self.TARGET.format(n=7),
            "t=1370100ms CONNECTION-ENDED err=UnresolvedRouteSourceException msg=no route",
            "t=1377000ms RECONNECTING in 60s (last attempt lived 100ms)",
            "t=1381000ms STALL-SUSPECTED loopTicks=4838 unchanged for 120s attempt=7 — the echo loop is not running. "
            "Dumping the last 256 trace events.",
        ]))

        self.assertEqual(
            "1 suspected, 0 recovered — Backoff=1\n"
            "  t+1381s phase=Backoff attempt=7 waited=120s (pre-phase line: phase inferred from RECONNECTING at t+1377s, 4s earlier)\n"
            "  1 pre-phase alarm(s) fired in a reconnect backoff: the lane had no connection and so no echo loop to stall — not a hang",
            stall_section(out),
        )

    def test_an_unphased_stall_while_connected_is_read_as_echoing(self):
        out = analyze("".join(l + "\n" for l in [
            "t=0ms START device=test minutes=60 echoIntervalMs=2000",
            "t=10ms " + self.TARGET.format(n=1),
            "t=60ms CONNECTED session=aa wire=aa alpn=test",
            "t=200000ms STALL-SUSPECTED loopTicks=12 unchanged for 120s attempt=1 — the echo loop is not running. "
            "Dumping the last 256 trace events.",
        ]))

        self.assertEqual(
            "1 suspected, 0 recovered — Echoing=1\n"
            "  t+200s phase=Echoing attempt=1 waited=120s (pre-phase line: phase inferred from CONNECTED at t+0s, 200s earlier)",
            stall_section(out),
        )


def one_connection_log(outcomes):
    """One connection on documentation addresses, echoing every second for four minutes, with one
    MIGRATION-ATTEMPT per outcome at 20 s intervals from t+20s."""
    lines = [
        "t=0ms START device=test targets=192.0.2.10:44433/v4 minutes=4 echoIntervalMs=1000 qlog=off",
        "t=10ms CONNECT-ATTEMPT n=1 target=192.0.2.10:44433 family=v4",
        "t=60ms CONNECTED session=aa wire=aa alpn=test",
        "t=62ms LOOP-SCHEDULE read=held-until-answered intervalMs=1000",
    ]
    for k in range(1, 240):
        lines.append(f"t={k * 1000 + 44}ms ECHO-OK seq={k} rtt=44ms pending=0B")
    for n, outcome in enumerate(outcomes, start=1):
        lines.append(f"t={n * 20_000 + 500}ms MIGRATION-ATTEMPT n={n} outcome={outcome} tookMs=100")
    lines.sort(key=lambda l: int(re.match(r"t=(\d+)ms", l).group(1)))
    return "".join(l + "\n" for l in lines)


class Verdict447Test(unittest.TestCase):
    def verdict(self, outcomes):
        out = analyze(one_connection_log(outcomes))
        return next(l for l in out.splitlines() if l.startswith("  connection 1") and "unanswered" in l)

    def test_an_answer_before_the_last_unanswered_run_is_not_recovery(self):
        """The 2026-09-25 walk: the one answered probe came BEFORE most of the unanswered ones, and the
        connection never regained a path after them. Judged from its first failure, that read PASS."""
        line = self.verdict(["PathNotValidated", "Succeeded"] + ["PathNotValidated"] * 4)

        self.assertIn("FAIL — 4 unanswered (an earlier run of 1 was answered) and 3 later probe(s), none answered", line)
        self.assertNotIn("PASS", line)

    def test_an_answer_after_the_last_unanswered_run_is_recovery(self):
        line = self.verdict(["PathNotValidated", "Succeeded", "PathNotValidated", "PathNotValidated", "Succeeded"])

        self.assertIn("PASS — 2 unanswered (an earlier run of 1 was answered), then 1 later probe(s) ANSWERED", line)

    def test_a_single_run_answered_afterwards_passes_as_it_always_did(self):
        line = self.verdict(["PathNotValidated", "PathNotValidated", "Succeeded"])

        self.assertIn("PASS — 2 unanswered, then 1 later probe(s) ANSWERED", line)


def v6_lane_without_a_route(grammar):
    """A v6 lane on a phone with no IPv6 route for a while: attempt 1 connects and dies, attempts 2–6
    fail before any handshake (four at the route probe, one closed by the peer), attempt 7 connects and
    runs to the end. `grammar` is "old" — every failed attempt reported as a connection with its own
    liveness and #447 verdict, the build that walked 2026-09-26 — or "new", where it is an attempt."""
    lines = []

    def at(t, lane, body):
        lines.append((t, f"t={t}ms lane={lane} {body}"))

    at(0, "run", "START device=test targets=[2001:db8::1]:44433/v6 minutes=10 echoIntervalMs=1000 qlog=off")
    at(1, "run", "LANES v6=[2001:db8::1]:44433 staggerMs=0")
    connected = {1: (10, 60_000, "CONNECTION-DEAD seq=50 reason=local: IdleTimeout (0x-1) — leaving scope to reconnect",
                     "ConnectionDead"),
                 7: (400_000, 600_000, "SCOPE-EXITED cleanly", "WalkOver")}
    failures = {2: "UnresolvedRouteSourceException", 3: "UnresolvedRouteSourceException",
                4: "UnresolvedRouteSourceException", 5: "QuicCloseException", 6: "UnresolvedRouteSourceException"}
    for n in range(1, 8):
        start = connected[n][0] if n in connected else 60_000 + (n - 1) * 60_000
        at(start, "v6", f"CONNECT-ATTEMPT n={n} target=[2001:db8::1]:44433 family=v6")
        tag = f"connection={n} family=v6"
        if n in connected:
            _, end, why, ended = connected[n]
            at(start + 50, "v6", "CONNECTED session=aa wire=aa alpn=test")
            at(start + 51, "v6", "LOOP-SCHEDULE read=held-until-answered intervalMs=1000")
            for k in range(1, (end - start) // 1000):
                at(start + k * 1000 + 44, "v6", f"ECHO-OK seq={k} rtt=44ms pending=0B")
            at(end, "v6", why)
            at(end + 1, "v6", f"ECHO-LIVENESS {tag} LIVE — answered=1 longestQuietMs=1000 limitMs=600000 ended={ended}")
            at(end + 2, "v6", f"MIGRATION-LEDGER {tag} attempts=0 succeeded=0 outcomes=[none]")
            at(end + 3, "v6", f"447-VERDICT {tag} INCONCLUSIVE — no probe went unanswered on this connection (attempts=0)")
            continue
        err = failures[n]
        at(start + 15, "v6", f"CONNECTION-ENDED err={err} msg=the route probe could not be opened: no route to the peer")
        if grammar == "old":
            # A #689 build ends the session ConnectionDead on any QuicCloseException, a handshake's included.
            ended = "ConnectionDead" if err == "QuicCloseException" else "ScopeFailed"
            at(start + 16, "v6", f"ECHO-LIVENESS {tag} LIVE — answered=0 longestQuietMs=15 limitMs=600000 ended={ended}")
            at(start + 17, "v6", f"MIGRATION-LEDGER {tag} attempts=0 succeeded=0 outcomes=[none]")
            at(start + 18, "v6", f"447-VERDICT {tag} INCONCLUSIVE — no probe went unanswered on this connection (attempts=0)")
        else:
            reason = "UnresolvedRoute" if err == "UnresolvedRouteSourceException" else err
            at(start + 16, "v6", f"CONNECT-NEVER-ESTABLISHED n={n} family=v6 reason={reason}")
        at(start + 20, "v6", "RECONNECTING in 60s (last attempt lived 20ms)")
    if grammar == "old":
        at(600_010, "v6", "MIGRATION-TOTALS connections=7 attempts=0 succeeded=0 unansweredProbes=0 probedAfterUnanswered=0 "
                          "answeredAfterUnanswered=0 noSpareAfterUnanswered=0 outcomes=[none]")
        at(600_011, "v6", "ECHO-LIVENESS run LIVE — connections=7 answered=2 longestQuietMs=1000 onConnection=1 "
                          "limitMs=600000 ended=[ConnectionDead=2,ScopeFailed=4,WalkOver=1]")
    else:
        at(600_009, "v6", "CONNECT-TOTALS attempts=7 established=2 neverEstablished=[UnresolvedRoute=4,QuicCloseException=1]")
        at(600_010, "v6", "MIGRATION-TOTALS connections=2 attempts=0 succeeded=0 unansweredProbes=0 probedAfterUnanswered=0 "
                          "answeredAfterUnanswered=0 noSpareAfterUnanswered=0 outcomes=[none]")
        at(600_011, "v6", "ECHO-LIVENESS run LIVE — connections=2 answered=2 longestQuietMs=1000 onConnection=1 "
                          "limitMs=600000 ended=[ConnectionDead=1,WalkOver=1]")
        at(600_020, "run", "CONNECT-TOTALS attempts=7 established=2 neverEstablished=[UnresolvedRoute=4,QuicCloseException=1]")
    at(600_021, "run", "DONE attempts=7 lanes=1 log=/sdcard/quic-handoff-probe.log")
    return "".join(l + "\n" for _, l in sorted(lines, key=lambda e: e[0]))


class NeverEstablishedTests(unittest.TestCase):
    """2026-09-26, Samsung v6 lane: 215 of 219 attempts failed at the route probe, and the probe reported
    each as a LIVE connection. An attempt that never established is counted as one, and only as one."""

    def check_counts_established_connections_only(self, grammar):
        v6 = section(analyze(v6_lane_without_a_route(grammar)), "v6")

        self.assertIn("connections: attempts=7 established=2 neverEstablished=[UnresolvedRoute=4,QuicCloseException=1]", v6)
        self.assertIn("#2–#6 t+120s [v6] 5 attempt(s) never established — UnresolvedRoute=4,QuicCloseException=1", v6)
        self.assertIn("v6: attempts=7 connected=2 neverEstablished=[UnresolvedRoute=4,QuicCloseException=1]", v6)
        liveness = v6[v6.index("ECHO-LIVENESS (re-derived"):v6.index("\n#447 verdict (re-derived")]
        self.assertEqual(2, len(re.findall(r"^  connection \d+ ", liveness, re.M)), liveness)
        self.assertIn("[attempt 7, v6]", liveness)
        for n in range(2, 7):
            self.assertNotIn(f"connection={n} family=v6", v6, "a hollow per-connection line was shown")
        return v6

    def test_an_old_log_is_read_as_attempts_and_its_own_totals_are_flagged(self):
        v6 = self.check_counts_established_connections_only("old")

        self.assertIn("ECHO-LIVENESS: 3 (+5 for attempts that never established, not shown)", v6)
        self.assertIn("⚠ the probe's own run lines claim connections=7", v6)

    def test_a_new_log_reads_the_same_and_raises_no_flag(self):
        out = analyze(v6_lane_without_a_route("new"))
        v6 = self.check_counts_established_connections_only("new")

        self.assertIn("ECHO-LIVENESS: 3\n", v6)
        self.assertNotIn("⚠ the probe's own run lines", v6)
        self.assertIn("run: CONNECT-TOTALS attempts=7 established=2", out)


def engines_and_lifecycle_log():
    """A 4.x lane and a core lane over ten minutes. At t+120s the device hands off: the 4.x lane migrates (a 2 s
    echo gap, no death) and the core lane dies with a typed reason and is back 7 s later. Then three lifecycle
    steps: Doze (4.x survives, core dies idle-timed-out and recovers in 20 s), a trim-memory (both survive) and a
    force-stop (both die with the probe, the 4.x lane recovers in 12 s, the core lane never does)."""
    lines = []

    def at(t, lane, body):
        lines.append((t, f"t={t}ms lane={lane} {body}"))

    def echoes(lane, spans):
        for lo, hi in spans:
            for t in range(lo, hi, 1000):
                at(t, lane, f"ECHO-OK seq={t} rtt=40ms pending=0B")

    at(0, "run", "START device=test targets=192.0.2.1:44433/v4 minutes=10 echoIntervalMs=1000 qlog=off")
    at(1, "run", "LANES v4=192.0.2.1:44433 core-v4=192.0.2.1:44433 staggerMs=500")
    for lane in ("v4", "core-v4"):
        at(10, lane, "CONNECT-ATTEMPT n=1 target=192.0.2.1:44433 family=v4")
        at(60, lane, "CONNECTED session=aa wire=aa alpn=test")
    # handoff at t+120s
    at(120_000, "v4", "PATH Probing")
    at(120_040, "v4", "PATH Migrated")
    echoes("v4", [(1_000, 119_000), (122_000, 300_000)])
    echoes("core-v4", [(1_000, 119_000)])
    at(120_100, "core-v4", "CONNECTION-DEAD seq=120 reason=local: IdleTimeout (0x-1) \u2014 leaving scope to reconnect")
    at(120_200, "core-v4", "CONNECT-ATTEMPT n=2 target=192.0.2.1:44433 family=v4")
    at(126_900, "core-v4", "CONNECTED session=bb wire=bb alpn=test")
    echoes("core-v4", [(127_000, 300_000)])
    # step 1: doze, 300s..400s. 4.x lane keeps echoing; core lane dies at 310s, back at 330s.
    at(300_000, "run", "LIFECYCLE step=1 name=doze phase=begin probe=alive")
    echoes("v4", [(300_000, 400_000)])
    at(310_000, "core-v4", "CONNECTION-DEAD seq=300 reason=local: IdleTimeout (0x-1) \u2014 leaving scope to reconnect")
    at(310_100, "core-v4", "CONNECT-ATTEMPT n=3 target=192.0.2.1:44433 family=v4")
    at(329_000, "core-v4", "CONNECTED session=cc wire=cc alpn=test")
    echoes("core-v4", [(330_000, 400_000)])
    at(400_000, "run", "LIFECYCLE step=1 name=doze phase=end probe=alive")
    # step 2: trim-memory, 410s..420s: nothing happens.
    at(410_000, "run", "LIFECYCLE step=2 name=trim-memory phase=begin probe=alive")
    echoes("v4", [(400_000, 500_000)])
    echoes("core-v4", [(400_000, 500_000)])
    at(420_000, "run", "LIFECYCLE step=2 name=trim-memory phase=end probe=alive")
    # step 3: force-stop at 500s; the restart brings the 4.x lane back at 512s, the core lane never.
    at(500_000, "run", "LIFECYCLE step=3 name=force-stop phase=begin probe=alive")
    at(500_500, "v4", "CONNECTION-ENDED err=QuicCloseException msg=process killed")
    at(500_900, "core-v4", "CONNECTION-ENDED err=QuicCloseException msg=process killed")
    at(505_000, "run", "LIFECYCLE step=3 name=force-stop phase=end probe=dead")
    at(511_000, "v4", "CONNECT-ATTEMPT n=2 target=192.0.2.1:44433 family=v4")
    at(511_500, "v4", "CONNECTED session=dd wire=dd alpn=test")
    echoes("v4", [(512_000, 600_000)])
    at(600_000, "run", "DONE attempts=5 lanes=2 log=/sdcard/quic-handoff-probe.log")
    return "".join(l + "\n" for _, l in sorted(lines, key=lambda e: e[0]))


class EngineAndLifecycleTests(unittest.TestCase):
    """The core has no migration, so the report has to put its deaths and reconnect times next to the 4.x lane's,
    and say per lifecycle step what each lane did."""

    def setUp(self):
        self.out = analyze(engines_and_lifecycle_log())
        self.comparison = self.out[self.out.index("engine comparison"):self.out.index("lifecycle steps")]
        self.lifecycle = self.out[self.out.index("lifecycle steps"):]

    def test_deaths_are_counted_per_lane_by_typed_reason(self):
        self.assertIn("lane v4 [4.x]: deaths=1 by reason: ended: QuicCloseException=1", self.comparison)
        self.assertIn("lane core-v4 [core]: deaths=3 by reason: local: IdleTimeout (0x-1)=2,ended: QuicCloseException=1", self.comparison)

    def test_reconnect_time_is_death_to_first_answered_echo(self):
        core = self.comparison[self.comparison.index("lane core-v4"):]
        # 120.1s -> 127.0s, 310.0s -> 330.0s, and the force-stop one never recovers.
        self.assertIn("reconnect (death -> first answered echo): p50=20.0s max=20.0s (1 never recovered)", core)
        v4 = self.comparison[self.comparison.index("lane v4"):self.comparison.index("lane core-v4")]
        self.assertIn("reconnect (death -> first answered echo): p50=11.5s max=11.5s", v4)

    def test_echo_gap_per_handoff_is_reported_for_each_engine(self):
        v4 = self.comparison[self.comparison.index("lane v4"):self.comparison.index("lane core-v4")]
        self.assertRegex(v4, r"echo gap per handoff \(3\): 4\.0s 1\.0s 13\.0s")
        self.assertRegex(self.comparison, r"core-v4 \[core\].*\n.*\n.*echo gap per handoff \(3\): 9\.0s")
        self.assertIn("4.x overall:", self.comparison)
        self.assertIn("core overall:", self.comparison)

    def test_each_lifecycle_step_says_what_each_lane_did(self):
        doze = self.lifecycle[self.lifecycle.index("step 1 doze"):self.lifecycle.index("step 2")]
        self.assertIn("v4 [4.x]: survived", doze)
        self.assertIn("core-v4 [core]: died (local: IdleTimeout (0x-1)), recovered in 20.0s", doze)
        trim = self.lifecycle[self.lifecycle.index("step 2 trim-memory"):self.lifecycle.index("step 3")]
        self.assertIn("v4 [4.x]: survived", trim)
        self.assertIn("core-v4 [core]: survived", trim)
        stop = self.lifecycle[self.lifecycle.index("step 3 force-stop"):]
        self.assertIn("probe=alive", stop)
        self.assertIn("v4 [4.x]: died (ended: QuicCloseException), recovered in 11.5s", stop)
        self.assertIn("core-v4 [core]: died (ended: QuicCloseException), NOT recovered by window end", stop)

    def test_a_death_with_the_network_away_is_broken_down_into_os_probe_connect_and_echo(self):
        log = "".join(l + "\n" for l in [
            "t=0ms lane=run START device=test targets=192.0.2.1:44433/v4 minutes=10 echoIntervalMs=1000 qlog=off",
            "t=1ms lane=run LANES core-v4=192.0.2.1:44433 staggerMs=500",
            "t=10ms lane=core-v4 CONNECT-ATTEMPT n=1 target=192.0.2.1:44433 family=v4",
            "t=60ms lane=core-v4 CONNECTED session=aa wire=aa alpn=test",
            "t=1000ms lane=core-v4 ECHO-OK seq=1 rtt=40ms pending=0B",
            "t=10000ms lane=run LIFECYCLE step=1 name=wifi phase=begin probe=alive",
            "t=10100ms lane=run LIFECYCLE step=1 name=wifi phase=action probe=alive action=wifi-off",
            "t=10700ms lane=run OS-NET state=Offline v4=Absent v6=LinkLocal cell=NotReported links=lo=127.0.0.1",
            "t=40500ms lane=core-v4 CONNECTION-DEAD seq=30 reason=local: IdleTimeout (0x-1) \u2014 leaving scope to reconnect",
            "t=50100ms lane=run LIFECYCLE step=1 name=wifi phase=action probe=alive action=wifi-on",
            "t=52400ms lane=run OS-NET state=Routable|Link:Wifi:1|Pending v4=SiteLocal v6=LinkLocal cell=NotReported links=wlan0=192.168.1.6",
            "t=52450ms lane=core-v4 CONNECT-ATTEMPT n=2 target=192.0.2.1:44433 family=v4",
            "t=52550ms lane=core-v4 CONNECTED session=bb wire=bb alpn=test",
            "t=52760ms lane=core-v4 ECHO-OK seq=1 rtt=40ms pending=0B",
            "t=100000ms lane=run LIFECYCLE step=1 name=wifi phase=end probe=alive",
        ])
        out = analyze(log)
        self.assertIn("core-v4 [core]: died (local: IdleTimeout (0x-1)), recovered in 12.3s", out)
        self.assertIn("breakdown: wifi-on -> OS usable 2.3s | death -> OS usable 11.9s | OS usable -> reconnect started 0.1s"
                      " | connect -> established 0.1s | established -> first echo 0.2s", out)

    def test_a_force_stop_is_read_off_the_restart_because_a_killed_process_writes_no_ending(self):
        log = "".join(l + "\n" for l in [
            "t=0ms lane=run START device=test targets=192.0.2.1:44433/v4 minutes=10 echoIntervalMs=1000 qlog=off",
            "t=1ms lane=run LANES v4=192.0.2.1:44433 core-v4=192.0.2.1:44433 staggerMs=500",
            "t=10ms lane=v4 CONNECT-ATTEMPT n=1 target=192.0.2.1:44433 family=v4",
            "t=60ms lane=v4 CONNECTED session=aa wire=aa alpn=test",
            "t=10ms lane=core-v4 CONNECT-ATTEMPT n=1 target=192.0.2.1:44433 family=v4",
            "t=60ms lane=core-v4 CONNECTED session=aa wire=aa alpn=test",
            "t=1000ms lane=v4 ECHO-OK seq=1 rtt=40ms pending=0B",
            "t=1000ms lane=core-v4 ECHO-OK seq=1 rtt=40ms pending=0B",
            "t=50000ms lane=run LIFECYCLE step=1 name=force-stop phase=begin probe=alive",
            "t=55000ms lane=run LIFECYCLE step=1 name=force-stop phase=end probe=dead action=stopped",
            "t=56000ms lane=run PREVIOUS-RUN none \u2014 resuming this walk in place",
            "t=56100ms lane=v4 CONNECT-ATTEMPT n=1 target=192.0.2.1:44433 family=v4",
            "t=56200ms lane=core-v4 CONNECT-ATTEMPT n=1 target=192.0.2.1:44433 family=v4",
            "t=58000ms lane=v4 ECHO-OK seq=1 rtt=40ms pending=0B",
            "t=62000ms lane=core-v4 ECHO-OK seq=1 rtt=40ms pending=0B",
            "t=90000ms lane=run DONE attempts=2 lanes=2 log=/sdcard/quic-handoff-probe.log",
        ])
        out = analyze(log)

        self.assertIn("step 1 force-stop t+50s..t+55s probe=alive>dead", out)
        self.assertIn("v4 [4.x]: killed with the process, recovered in 8.0s", out)
        self.assertIn("core-v4 [core]: killed with the process, recovered in 12.0s", out)

    def test_a_log_with_one_engine_prints_no_comparison(self):
        out = analyze(two_lane_log()[0])
        self.assertNotIn("engine comparison", out)
        self.assertNotIn("lifecycle steps", out)


if __name__ == "__main__":
    unittest.main()
