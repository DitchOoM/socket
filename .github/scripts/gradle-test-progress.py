#!/usr/bin/env python3
"""Where a Gradle test run was when a step's budget ended: progressing, or stuck.

Our Gradle builds print one `TEST START <name>` line when a test begins and one
`TEST SUCCESS|FAILURE|SKIPPED <name> (<n>ms)` line when it ends. A step killed at its budget says
nothing about which of "tests were still finishing" and "one test never ended" happened; the age of
the last such line and the tests started but not finished do.

  stamp <file>              copy stdin to stdout unchanged, and to <file> with each line prefixed by
                            its UTC time (`2026-11-22T16:06:34.698Z `), the form GitHub's own logs use.
                            Placed after the test command's `2>&1 |` so the console is unaffected.
  last <file> [--now T]     read such a file (ours, or a downloaded GitHub job log) and print one line:
                              state=<progressing|silent|none> age=<seconds> last=<EVENT name> unfinished=<name or ->
                            T is an epoch or an ISO time; default is the current time. `progressing` is
                            an event within PROGRESS_WINDOW_SECONDS (default 120), `silent` an older one,
                            `none` no event at all.
"""
import os
import re
import sys
import time
from datetime import datetime, timezone

STAMP = re.compile(r"^(\d{4}-\d\d-\d\dT\d\d:\d\d:\d\d(?:\.\d+)?)Z ")
EVENT = re.compile(r"^(START|SUCCESS|FAILURE|SKIPPED) (\S+)")


def parse_iso(text):
    base, _, frac = text.partition(".")
    value = datetime.strptime(base, "%Y-%m-%dT%H:%M:%S").replace(tzinfo=timezone.utc).timestamp()
    return value + (float("0." + frac) if frac else 0.0)


def stamp(path):
    with open(path, "w", encoding="utf-8", errors="replace") as out:
        for line in sys.stdin.buffer:
            sys.stdout.buffer.write(line)
            sys.stdout.buffer.flush()
            now = time.time()
            text = line.decode("utf-8", errors="replace")
            out.write(datetime.fromtimestamp(now, timezone.utc).strftime("%Y-%m-%dT%H:%M:%S.") + "%03dZ " % (now % 1 * 1000) + text)
            out.flush()


def last(path, now):
    window = float(os.environ.get("PROGRESS_WINDOW_SECONDS", "120"))
    open_tests = {}  # insertion-ordered: the most recently started test still open is the last key
    last_time = None
    last_event = None
    with open(path, encoding="utf-8", errors="replace") as handle:
        for line in handle:
            m = STAMP.match(line)
            if not m:
                continue
            body = line[m.end():]
            if not body.startswith("TEST "):
                continue
            e = EVENT.match(body[5:])
            if not e:
                continue
            kind, name = e.groups()
            last_time = parse_iso(m.group(1))
            last_event = "%s %s" % (kind, name)
            if kind == "START":
                open_tests.pop(name, None)
                open_tests[name] = last_time
            else:
                open_tests.pop(name, None)
    if last_time is None:
        print("state=none age=- last=- unfinished=-")
        return
    age = max(0, int(round(now - last_time)))
    state = "progressing" if age <= window else "silent"
    unfinished = list(open_tests)[-1] if open_tests else "-"
    print("state=%s age=%d last=%s unfinished=%s" % (state, age, last_event, unfinished))


def main(argv):
    if len(argv) >= 3 and argv[1] == "stamp":
        stamp(argv[2])
    elif len(argv) >= 3 and argv[1] == "last":
        now = time.time()
        if len(argv) >= 5 and argv[3] == "--now":
            now = float(argv[4]) if re.fullmatch(r"\d+(\.\d+)?", argv[4]) else parse_iso(argv[4].rstrip("Z"))
        last(argv[2], now)
    else:
        sys.stderr.write("usage: gradle-test-progress.py stamp <file> | last <file> [--now T]\n")
        return 2
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
