#!/usr/bin/env python3
"""Stop pyshark reporting a tshark that exited normally as crashed (TSharkCrashException, retcode 255).

Usage: patch-pyshark-reap.py [<path to pyshark/capture/capture.py>]   (default: the installed pyshark)

pyshark's cleanup calls process.kill() on a tshark that has usually exited already. Popen.send_signal polls
first, so when the event loop has not yet handled SIGCHLD that poll reaps tshark; asyncio's child watcher
then finds no child ("Unknown child process pid N, will report returncode 255"), and Capture.close() raises
on the 255. The quic-interop-runner dies there and writes no results.

The patch signals the pid with os.kill instead, which reaps nothing: the watcher collects the real status.
A process that is already gone raises ProcessLookupError, which the surrounding code handles. Marker-guarded:
a second run is a no-op, and a pyshark whose cleanup no longer has the patched line fails loudly.
"""
import importlib.util
import pathlib
import sys

MARKER = "socket-kill-without-reaping"
ANCHOR = "                process.kill()\n"
REPLACEMENT = (
    f"                # {MARKER}: process.kill() polls first and can reap a tshark that already exited,\n"
    "                # which asyncio's child watcher then reports as returncode 255.\n"
    "                import signal as _signal\n"
    "                os.kill(process.pid, _signal.SIGKILL)\n"
)


def main(argv):
    if len(argv) > 1:
        path = pathlib.Path(argv[1])
    else:
        spec = importlib.util.find_spec("pyshark")
        if spec is None or spec.origin is None:
            print("patch-pyshark-reap: pyshark is not installed", file=sys.stderr)
            return 1
        path = pathlib.Path(spec.origin).parent / "capture" / "capture.py"
    text = path.read_text()
    if MARKER in text:
        print(f"patch-pyshark-reap: {path} is already patched")
        return 0
    if text.count(ANCHOR) != 1 or "import os\n" not in text:
        print(
            f"patch-pyshark-reap: `process.kill()` not found exactly once in {path}: pyshark changed its "
            "subprocess cleanup. Re-fit this patch, or delete it if pyshark no longer kills through Popen.",
            file=sys.stderr,
        )
        return 1
    path.write_text(text.replace(ANCHOR, REPLACEMENT))
    print(f"patch-pyshark-reap: patched {path}")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
