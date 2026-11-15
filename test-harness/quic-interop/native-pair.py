#!/usr/bin/env python3
"""One client and one server of the QUIC interop endpoints, run natively on this machine over loopback.

The quic-interop-runner needs Docker and a simulated Linux network, so it cannot put our macOS or Windows
socket layer under test. This runs the same endpoint contract (ROLE, TESTCASE, REQUESTS, WWW, CERTS,
DOWNLOADS) without containers: it starts the server, runs the client, and checks every downloaded file
byte for byte. Results are written in the runner's JSON shape, so quic-interop-gate.py judges them against
the same expectations file.

  native-pair.py --direction <lane>-our-client|<lane>-our-server|<lane>-our-client-migrating
                 --peer <name> --tests handshake,transfer,resumption,zerortt
                 --server "<command>" --client "<command>" --client-urls-as-args|--client-urls-in-env
                 --results <dir>

Weaker than the runner in two ways, stated in every summary: no packet capture, so `resumption` and
`zerortt` are judged by the files alone, not by the handshake the runner reads from the capture; and no
network simulator, so nothing is delayed, lost or rebound.
"""
import argparse
import enum
import json
import os
import random
import shlex
import shutil
import signal
import socket
import subprocess
import sys
import tempfile
import threading
import time

KB = 1024
MB = 1024 * KB
UNSUPPORTED = 127

FILES = {
    "handshake": [1 * KB],
    "transfer": [2 * MB, 3 * MB, 5 * MB],
    "resumption": [1 * KB, 1 * KB],
    "zerortt": [1 * KB] * 5,
}
GRACE = 5  # seconds between SIGTERM and SIGKILL
TIMEOUT = {"handshake": 30, "transfer": 120, "resumption": 60, "zerortt": 60}


def free_udp_port():
    s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    s.bind(("127.0.0.1", 0))
    port = s.getsockname()[1]
    s.close()
    return port


def make_certs(certs):
    subprocess.run(
        ["openssl", "req", "-x509", "-newkey", "ec", "-pkeyopt", "ec_paramgen_curve:prime256v1", "-nodes",
         "-keyout", os.path.join(certs, "priv.key"), "-out", os.path.join(certs, "cert.pem"),
         "-days", "2", "-subj", "/CN=localhost",
         "-addext", "subjectAltName=DNS:localhost,DNS:server,IP:127.0.0.1"],
        check=True, capture_output=True,
    )


class Ended(enum.Enum):
    """How a child process ended: on its own, after SIGTERM, or only after SIGKILL."""
    EXITED = "exited on its own"
    TERMINATED = "ended by SIGTERM"
    KILLED = "ended by SIGKILL"


class Process:
    """A child process whose merged stdout/stderr is streamed to a timestamped file as it arrives."""

    def __init__(self, name, cmd, env, cwd, out_path, t0):
        self.name = name
        self.t0 = t0
        self.started_wall = time.strftime("%H:%M:%S", time.gmtime()) + "Z"
        self.first_byte = None
        self.ended_at = None
        self.end = None
        self.raw = []
        self.out = open(out_path, "w", errors="replace")
        self.proc = subprocess.Popen(cmd, env=env, cwd=cwd, stdout=subprocess.PIPE, stderr=subprocess.STDOUT)
        self.started = time.monotonic() - t0
        self.reader = threading.Thread(target=self._pump, daemon=True)
        self.reader.start()

    def _pump(self):
        for line in iter(self.proc.stdout.readline, b""):
            now = time.monotonic() - self.t0
            if self.first_byte is None:
                self.first_byte = now
            text = line.decode(errors="replace")
            self.raw.append(text)
            self.out.write(f"+{now:8.3f}s {text}")
            self.out.flush()

    def wait(self, timeout):
        """Wait for exit; past the timeout, SIGTERM then SIGKILL so buffered output and logs flush."""
        try:
            self.proc.wait(timeout)
        except subprocess.TimeoutExpired:
            return self.stop()
        self.end = Ended.EXITED
        self.ended_at = time.monotonic() - self.t0
        return self.end

    def stop(self):
        if self.end is not None:
            return self.end
        self.ended_at = time.monotonic() - self.t0
        if self.proc.poll() is not None:
            self.end = Ended.EXITED
            return self.end
        self.proc.terminate()
        try:
            self.proc.wait(GRACE)
            self.end = Ended.TERMINATED
        except subprocess.TimeoutExpired:
            self.proc.kill()
            self.proc.wait()
            self.end = Ended.KILLED
        return self.end

    def finish(self):
        self.reader.join(5)
        self.out.close()

    def text(self):
        return "".join(self.raw)

    def describe(self):
        spoke = "no output" if self.first_byte is None else f"first output at +{self.first_byte:.3f}s"
        return (f"{self.name} started {self.started_wall} (+{self.started:.3f}s), {spoke}, "
                f"{self.end.value} at +{self.ended_at:.3f}s, exit {describe_exit(self.proc.returncode)}")


def describe_exit(code):
    return f"signal {signal.Signals(-code).name}" if code < 0 else f"code {code}"


def timeline(server, client, total):
    """Facts a failed cell needs when its output is empty: when each process started, spoke and ended."""
    lines = ["--- timeline (UTC wall clock; seconds since the server started) ---", server.describe()]
    if client is not None:
        lines.append(client.describe())
    lines.append(f"cell ran {total:.3f}s")
    return "\n".join(lines)


def run_test(args, test, workdir):
    www = os.path.join(workdir, "www")
    downloads = os.path.join(workdir, "downloads")
    certs = os.path.join(workdir, "certs")
    logs = os.path.join(workdir, "logs")
    for d in (www, downloads, certs, logs):
        os.makedirs(d, exist_ok=True)
    make_certs(certs)
    names = []
    for size in FILES[test]:
        name = "".join(random.choice("abcdefghijklmnopqrstuvwxyz") for _ in range(10))
        with open(os.path.join(www, name), "wb") as f:
            f.write(os.urandom(size))
        names.append(name)
    port = free_udp_port()
    urls = [f"https://127.0.0.1:{port}/{n}" for n in names]
    base = dict(os.environ, TESTCASE=test, WWW=www, CERTS=certs, DOWNLOADS=downloads, LOGS=logs, PORT=str(port))
    server_env = dict(base, ROLE="server")
    client_env = dict(base, ROLE="client", REQUESTS=" ".join(urls))
    if args.direction.endswith("our-client-migrating"):
        client_env["CLIENT_PARAMS"] = "migrate=active"
    else:
        client_env.pop("CLIENT_PARAMS", None)

    log = []
    t0 = time.monotonic()
    server = Process("server", shlex.split(args.server, posix=os.name != "nt"), server_env, workdir,
                     os.path.join(logs, "server.out"), t0)
    client = None
    try:
        time.sleep(args.server_start)
        if server.proc.poll() is not None:
            server.finish()
            log.append(server.text())
            return ("unsupported" if server.proc.returncode == UNSUPPORTED else "failed"), log, logs
        client_cmd = shlex.split(args.client, posix=os.name != "nt") + (urls if args.client_urls_as_args else [])
        client = Process("client", client_cmd, client_env, workdir, os.path.join(logs, "client.out"), t0)
        end = client.wait(TIMEOUT[test])
        client.finish()
        log.append(client.text())
        code = client.proc.returncode
        if end is not Ended.EXITED:
            log.append(f"client took longer than {TIMEOUT[test]} s")
    finally:
        server.stop()
        server.finish()
        log.append("--- server ---\n" + server.text()[-4000:])
        log.append(timeline(server, client, time.monotonic() - t0))
    if end is not Ended.EXITED:
        return "failed", log, logs
    if code == UNSUPPORTED:
        return "unsupported", log, logs
    if code != 0:
        log.append(f"client exited with {describe_exit(code)}")
        return "failed", log, logs
    for n in names:
        got = os.path.join(downloads, n)
        if not os.path.exists(got):
            log.append(f"Missing download {n}")
            return "failed", log, logs
        with open(got, "rb") as a, open(os.path.join(www, n), "rb") as b:
            if a.read() != b.read():
                log.append(f"Wrong content in {n}")
                return "failed", log, logs
    return "succeeded", log, logs


def keep_logs(logs, dest):
    shutil.rmtree(dest, ignore_errors=True)
    shutil.copytree(logs, dest)


def main():
    p = argparse.ArgumentParser()
    p.add_argument("--direction", required=True)
    p.add_argument("--peer", required=True)
    p.add_argument("--tests", required=True)
    p.add_argument("--server", required=True)
    p.add_argument("--client", required=True)
    p.add_argument("--client-urls-as-args", action="store_true")
    p.add_argument("--server-start", type=float, default=4.0)
    p.add_argument("--results", required=True)
    p.add_argument("--captures", help="directory receiving each failed cell's LOGS dir (qlog, keylog, traces, "
                                      "timestamped process output); passing cells keep nothing")
    args = p.parse_args()

    os.makedirs(args.results, exist_ok=True)
    tests = [t for t in args.tests.split(",") if t]
    results = []
    reasons = []
    summary = ""
    for test in tests:
        workdir = tempfile.mkdtemp(prefix=f"interop-{test}-")
        try:
            result, log, logs = run_test(args, test, workdir)
            if result == "failed" and args.captures:
                keep_logs(logs, os.path.join(args.captures, f"{args.direction}__{args.peer}__{test}"))
        finally:
            shutil.rmtree(workdir, ignore_errors=True)
        print(f"{args.direction} {args.peer} {test}: {result}", flush=True)
        results.append({"abbr": test, "result": result})
        text = "\n".join(log)
        for line in text.splitlines():
            if line.startswith("MIGRATION"):
                print(line, flush=True)
                if line.startswith("MIGRATION-SUMMARY"):
                    summary = line
        if result != "succeeded":
            reasons.append(f"== {test}: {result}\n" + "\n".join(text.splitlines()[-60:]))
    out = {
        "servers": [args.peer], "clients": ["ditchoom"],
        "tests": {t: {"name": t} for t in tests},
        "results": [results], "measurements": [[]],
    }
    stem = os.path.join(args.results, f"{args.direction}__{args.peer}")
    with open(stem + ".json", "w") as f:
        json.dump(out, f, indent=1)
    with open(stem + ".reasons.txt", "w") as f:
        f.write("\n\n".join(reasons))
    if args.direction.endswith("our-client-migrating"):
        with open(stem + ".summary", "w") as f:
            f.write(summary)
    return 0


if __name__ == "__main__":
    sys.exit(main())
