"""Tests for analyze-core.py on small synthetic logs (documentation-range addresses only).

    python3 -m unittest discover -s walk-tools -p 'test_*.py'
"""
import os
import subprocess
import sys
import tempfile
import unittest

HERE = os.path.dirname(os.path.abspath(__file__))
SCRIPT = os.path.join(HERE, "analyze-core.py")
ENV = dict(os.environ, PYTHONIOENCODING="utf-8")

WIFI = "OS-NET state=Routable|Link:Wifi:1|Confirmed v4=SiteLocal v6=LinkLocal"
CELL = "OS-NET state=Routable|Link:Cellular:2|Confirmed v4=SiteLocal v6=Global"


def core(ns, body):
    return "CORE-EVENT v1 %d %s" % (ns, body)


def analyze(lines, *extra):
    with tempfile.TemporaryDirectory() as d:
        path = os.path.join(d, "walk.log")
        with open(path, "w", encoding="utf-8") as f:
            f.write("\n".join(lines) + "\n")
        r = subprocess.run([sys.executable, "-I", SCRIPT, path, *extra], capture_output=True, encoding="utf-8", env=ENV)
        return r


def echoes(lane, times):
    return ["t=%dms lane=%s ECHO-OK seq=%d rtt=20ms pending=0B" % (t, lane, i) for i, t in enumerate(times)]


def base():
    return [
        "t=0ms lane=run " + WIFI,
        "t=1ms lane=v4 CONNECTED session=a wire=192.0.2.1:443",
        "t=1ms lane=core-v4 CONNECTED session=b wire=192.0.2.1:443",
    ]


class AnalyzeCore(unittest.TestCase):
    def test_core_first_and_fourx_second(self):
        lines = base() + echoes("v4", [1000, 2000, 5000]) + echoes("core-v4", [1000, 2000, 3000])
        lines += [
            "t=2500ms lane=run " + CELL,
            "t=2700ms lane=core-v4 " + core(2700, "PATH_STATE Migrated 192.0.2.7 5000"),
            "t=3400ms lane=v4 PATH Migrated(endpoint=192.0.2.7:5000)",
        ]
        out = analyze(lines).stdout
        self.assertIn("lane=core-v4  MIGRATED", out)
        self.assertIn("migratedAfterMs=200", out)
        self.assertIn("migratedAfterMs=900", out)
        self.assertIn("v4 vs core-v4: core first, 700ms ahead (core +200ms, 4.x +900ms)", out)

    def test_rode_it_out_and_only_fourx(self):
        lines = base() + echoes("v4", [1000, 3000]) + echoes("core-v4", [1000, 3000])
        lines += ["t=2000ms lane=run " + CELL, "t=2500ms lane=v4 PATH Migrated(endpoint=192.0.2.9:1)"]
        out = analyze(lines).stdout
        self.assertIn("lane=core-v4  RODE-IT-OUT", out)
        self.assertIn("only 4.x migrated (+500ms)", out)

    def test_ended_reason_and_recovery(self):
        lines = base() + echoes("core-v4", [1000, 4000])
        lines += [
            "t=2000ms lane=run " + CELL,
            "t=2100ms lane=core-v4 CONNECTION-END reason=IdleTimeout typed=Idle",
            "t=2600ms lane=core-v4 CONNECTED session=c wire=192.0.2.1:443",
        ]
        out = analyze(lines).stdout
        self.assertIn("ENDED(IdleTimeout) RECOVERED", out)
        self.assertIn("end x1  IdleTimeout", out)

    def test_late_and_overdue_counted_in_window(self):
        lines = base() + echoes("core-v4", [1000, 9000])
        lines += [
            "t=2000ms lane=run " + CELL,
            "t=3000ms lane=core-v4 ECHO-LATE seq=5 rtt=900ms",
            "t=4000ms lane=core-v4 ECHO-OVERDUE seq=6 waited=2000ms deadline=1000ms",
        ]
        out = analyze(lines).stdout
        self.assertIn("late=1 overdue=1", out)

    def test_unchanged_os_state_is_no_anchor(self):
        out = analyze(base() + ["t=500ms lane=run " + WIFI] + echoes("core-v4", [1000])).stdout
        self.assertIn("(none in this window)", out)

    def test_non_path_core_events_are_not_path_events(self):
        lines = base() + echoes("core-v4", [1000, 3000])
        lines += ["t=2000ms lane=run " + CELL, core(0, "NET Wifi").join(["t=2100ms lane=core-v4 ", ""])]
        out = analyze(lines).stdout
        self.assertIn("RODE-IT-OUT", out)

    def test_not_a_probe_log_fails(self):
        r = analyze(["nothing to see"])
        self.assertEqual(r.returncode, 1)


if __name__ == "__main__":
    unittest.main()
