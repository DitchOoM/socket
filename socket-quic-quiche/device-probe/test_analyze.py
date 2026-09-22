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


def two_lane_log():
    """Two lanes over two minutes and one handoff at t+60s: v4 migrates in 46 ms without missing an echo;
    v6 loses four probes, goes 16.75 s without an answered echo, then migrates in 764 ms."""
    lines = []

    def at(t, lane, body):
        lines.append((t, f"t={t}ms lane={lane} {body}"))

    at(0, "run", "START device=SM-F956U1 sdk=36 targets=178.156.248.95:44433/v4,[2a01:4ff:f4:eb1a::1]:44433/v6 "
                 "minutes=2 echoIntervalMs=250 qlog=off")
    at(1, "run", "LANES v4=178.156.248.95:44433 v6=[2a01:4ff:f4:eb1a::1]:44433 staggerMs=125")
    at(2, "run", "TRACE-BUDGET mb=512 plannedExchanges=960 lanes=2")
    at(3, "run", "WAKELOCK acquired held=true timeoutMs=240000")
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
