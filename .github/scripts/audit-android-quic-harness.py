#!/usr/bin/env python3
"""Audit whether the Android emulator lane really exercised the quic-echo harness.

WHY THIS EXISTS
---------------
The step this replaces was copied from the Linux and Apple lanes, which count the
`harness OK` / `harness SKIP:` lines that `QuicHarnessIntegrationTests` prints. That suite is in
`commonTest`, and `androidInstrumentedTest` deliberately does not `dependsOn(commonTest)` (see the
note on that source set in socket-quic-quiche/build.gradle.kts), so it is never compiled into the
test APK. The step therefore printed `QuicHarnessIntegrationTests on Android: OK=0, SKIP=0` on every
run (runs 42434499783 and 42497983975, for example): a line that reads like a suite that ran
nothing, and it validated nothing.

On this lane the quic-echo harness (docker, reached from the emulator via its 10.0.2.2 alias) is
exercised by the instrumented classes that resolve `HarnessEndpoints.quicEcho`. This script finds
those classes in the source, so the list cannot go stale, and reads their outcomes from the JUnit
XML AGP writes. The XML is what the test engine recorded, not stdout: an instrumented test's stdout
goes to logcat, which AGP splits into `logcat-<class>-<method>.txt` beside the XML. Those files are
where a skip's reason is, so they are quoted for every skipped harness test.

VERDICTS
--------
* every harness class ran and at least one test in each passed  -> exit 0
* some harness tests skipped                                     -> ::warning:: with each reason
* a harness class has no test in the results (not in the APK, filtered out, or its module never
  reported), or no test in it passed                              -> ::error:: and exit 1
* no result XML at all: exit 1, unless the job has already failed (JOB_STATUS), in which case the
  earlier failure is the report and this says so.
"""

from __future__ import annotations

import argparse
import os
import re
import sys
import xml.etree.ElementTree as ET
from dataclasses import dataclass, field
from pathlib import Path

NOT_ON_ANDROID = (
    "QuicHarnessIntegrationTests on Android: not run, by design. It is a commonTest suite and "
    "androidInstrumentedTest does not dependsOn commonTest, so it is not in the test APK. This lane's "
    "quic-echo coverage is the instrumented classes listed below."
)

HARNESS_REFERENCE = re.compile(r"\bHarnessEndpoints\.quicEcho\b")
PACKAGE = re.compile(r"^\s*package\s+([\w.]+)", re.MULTILINE)
SKIP_MARKER = re.compile(r"\[TEST-SKIPPED\] .*")


@dataclass
class ClassResult:
    name: str
    passed: list[str] = field(default_factory=list)
    skipped: list[str] = field(default_factory=list)
    failed: list[str] = field(default_factory=list)
    skip_messages: dict[str, str] = field(default_factory=dict)

    @property
    def ran(self) -> int:
        return len(self.passed) + len(self.skipped) + len(self.failed)


def harness_classes(source_dir: Path) -> list[str]:
    """Fully-qualified names of the instrumented classes that resolve the quic-echo endpoint."""
    found = []
    for path in sorted(source_dir.rglob("*.kt")):
        text = path.read_text(encoding="utf-8", errors="replace")
        # The resolver itself names `quicEcho` in its own KDoc; it is not a test class.
        if path.name == "HarnessEndpoints.kt" or not HARNESS_REFERENCE.search(text):
            continue
        pkg = PACKAGE.search(text)
        found.append(f"{pkg.group(1)}.{path.stem}" if pkg else path.stem)
    return found


def read_results(xml_files: list[Path], wanted: set[str]) -> tuple[dict[str, ClassResult], set[str], list[str]]:
    results = {name: ClassResult(name) for name in wanted}
    seen_classes: set[str] = set()
    unreadable = []
    for xml in xml_files:
        try:
            root = ET.parse(xml).getroot()
        except ET.ParseError as e:
            unreadable.append(f"{xml}: {e}")
            continue
        for case in root.iter("testcase"):
            cls = case.get("classname", "")
            seen_classes.add(cls)
            if cls not in results:
                continue
            res = results[cls]
            method = case.get("name", "?")
            skipped = case.find("skipped")
            if case.find("failure") is not None or case.find("error") is not None:
                res.failed.append(method)
            elif skipped is not None:
                res.skipped.append(method)
                # The first line: AGP puts the whole assumption stack trace in the text.
                msg = (skipped.get("message") or skipped.text or "").strip().split("\n", 1)[0].strip()
                if msg:
                    res.skip_messages[method] = msg
            else:
                res.passed.append(method)
    return results, seen_classes, unreadable


def skip_evidence(report_root: Path, cls: str, method: str, xml_message: str | None) -> list[str]:
    """Everything recorded about why one test skipped: the XML message and its logcat markers."""
    lines = []
    if xml_message:
        lines.append(f"xml: {xml_message}")
    logcats = sorted(report_root.rglob(f"logcat-{cls}-{method}.txt"))
    for logcat in logcats:
        text = logcat.read_text(encoding="utf-8", errors="replace")
        markers = SKIP_MARKER.findall(text)
        lines.extend(f"logcat: {m.strip()}" for m in markers)
        if not markers:
            lines.append(f"logcat: no [TEST-SKIPPED] marker in {logcat}")
    if not logcats:
        lines.append(f"logcat: no logcat-{cls}-{method}.txt under {report_root}")
    return lines


def escape(s: str) -> str:
    """One workflow-command message per line; GitHub renders %0A as a newline."""
    return s.replace("%", "%25").replace("\r", "%0D").replace("\n", "%0A")


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__.split("\n", 1)[0])
    ap.add_argument("--list-classes", action="store_true",
                    help="print the harness classes found under --source-dir and exit")
    ap.add_argument("--report-root", type=Path,
                    help="socket-quic-quiche/build/outputs/androidTest-results")
    ap.add_argument("--source-dir", required=True, type=Path,
                    help="socket-quic-quiche/src/androidInstrumentedTest")
    ap.add_argument("--api-level", default="?")
    args = ap.parse_args()
    if args.list_classes:
        print("\n".join(harness_classes(args.source_dir)))
        return 0
    if args.report_root is None:
        ap.error("--report-root is required")

    job_status = os.environ.get("JOB_STATUS", "")
    summary_path = os.environ.get("GITHUB_STEP_SUMMARY")
    summary: list[str] = [f"## QUIC harness — Android emulator (API {args.api_level})", "", NOT_ON_ANDROID, ""]
    errors: list[str] = []
    warnings: list[str] = []

    print(NOT_ON_ANDROID)

    classes = harness_classes(args.source_dir)
    if not classes:
        # The question this audit answers no longer has a subject: either the harness resolver was
        # renamed or the classes moved. Either way, say so rather than report a vacuous pass.
        errors.append(
            f"No instrumented class under {args.source_dir} resolves HarnessEndpoints.quicEcho, so this "
            "audit has nothing to check. Update .github/scripts/audit-android-quic-harness.py to the "
            "new way the harness is reached."
        )

    xml_files = sorted(args.report_root.rglob("TEST-*.xml")) if args.report_root.is_dir() else []
    if classes and not xml_files:
        why = f"No instrumented-test result XML under {args.report_root}."
        if job_status in ("failure", "cancelled"):
            print(f"{why} The job had already {'failed' if job_status == 'failure' else 'been cancelled'}; "
                  "that earlier failure is the report, so this audit adds nothing.")
            summary.append(f"{why} Job status before this step: {job_status}.")
            write_summary(summary_path, summary)
            return 0
        errors.append(
            f"{why} The run-tests step passed without writing the :socket-quic-quiche results, so whether "
            f"the quic-echo harness was exercised is unknown. Harness classes expected: {', '.join(classes)}."
        )

    if classes and xml_files:
        results, seen, unreadable = read_results(xml_files, set(classes))
        for u in unreadable:
            errors.append(f"Unreadable result XML: {u}")
        summary += ["| class | ran | passed | skipped | failed |", "|---|---|---|---|---|"]
        print(f"Android quic-echo harness coverage (API {args.api_level}), from {len(xml_files)} result file(s):")
        for cls in classes:
            r = results[cls]
            line = f"  {cls}: ran={r.ran} passed={len(r.passed)} skipped={len(r.skipped)} failed={len(r.failed)}"
            print(line)
            summary.append(f"| `{cls}` | {r.ran} | {len(r.passed)} | {len(r.skipped)} | {len(r.failed)} |")
            if r.ran == 0:
                others = sorted(c for c in seen if c)
                errors.append(
                    f"{cls} reached the harness in source but has no test in the results: it is not in "
                    f"the test APK, a filter excluded it, or the instrumentation stopped before it. "
                    f"Classes the results do contain ({len(others)}): {', '.join(others) or 'none'}."
                )
                continue
            evidence = []
            for method in r.skipped:
                evidence.append(f"{cls}#{method}")
                evidence.extend("    " + e for e in skip_evidence(
                    args.report_root, cls, method, r.skip_messages.get(method)))
            if r.skipped:
                print("  skipped, with the reason each recorded:")
                for e in evidence:
                    print(f"    {e}")
            if not r.passed and not r.failed:
                errors.append(
                    f"Every test in {cls} skipped ({len(r.skipped)}), so the emulator never completed a "
                    f"QUIC exchange with the quic-echo harness. Recorded reasons:\n" + "\n".join(evidence)
                )
            elif r.skipped:
                warnings.append(f"{len(r.skipped)} test(s) in {cls} skipped:\n" + "\n".join(evidence))

    for w in warnings:
        print(f"::warning title=Android quic-echo harness tests skipped (API {args.api_level})::{escape(w)}")
        summary += ["", "<details><summary>Skipped</summary>", "", "```", w, "```", "</details>"]
    for e in errors:
        print(f"::error title=Android quic-echo harness not exercised (API {args.api_level})::{escape(e)}")
        summary += ["", f"**Error:** {e}"]
    write_summary(summary_path, summary)
    return 1 if errors else 0


def write_summary(path: str | None, lines: list[str]) -> None:
    if path:
        with open(path, "a", encoding="utf-8") as f:
            f.write("\n".join(lines) + "\n")


if __name__ == "__main__":
    sys.exit(main())
