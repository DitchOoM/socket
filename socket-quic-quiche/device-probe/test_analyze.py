"""Tests for analyze.py's lane demultiplexing.

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

    at(0, "run", "START device=SM-F956U1 sdk=36 targets=178.156.248.95:44433/v4,[2a01:4ff:f4:eb1a::1]:44433/v6 "
                 "minutes=2 echoIntervalMs=250 qlog=off")
    at(1, "run", "LANES v4=178.156.248.95:44433 v6=[2a01:4ff:f4:eb1a::1]:44433 staggerMs=125")
    at(2, "run", "TRACE-BUDGET mb=512 plannedExchanges=960 lanes=2")
    at(3, "run", "WAKELOCK acquired held=true timeoutMs=240000")
    at(4, "run", "OS-NET-SOURCE monitor=PlatformSignalled/RouteAndInternet cellular=Signalled")
    at(5, "run", f"OS-NET {WIFI_OS_NET}")
    at(59_500, "run", f"OS-NET {CELL_OS_NET}")
    at(120_450, "run", f"OS-NET {WIFI_OS_NET}")
    for lane, offset in (("v4", 0), ("v6", 125)):
        at(offset + 10, lane, f"CONNECT-ATTEMPT n=1 target={'178.156.248.95:44433' if lane == 'v4' else '[2a01:4ff:f4:eb1a::1]:44433'} family={lane}")
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


if __name__ == "__main__":
    unittest.main()
