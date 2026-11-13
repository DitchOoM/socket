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
import json
import os
import random
import shlex
import shutil
import socket
import subprocess
import sys
import tempfile
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


def run_test(args, test, workdir):
    www = os.path.join(workdir, "www")
    downloads = os.path.join(workdir, "downloads")
    certs = os.path.join(workdir, "certs")
    for d in (www, downloads, certs):
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
    base = dict(os.environ, TESTCASE=test, WWW=www, CERTS=certs, DOWNLOADS=downloads, PORT=str(port))
    server_env = dict(base, ROLE="server")
    client_env = dict(base, ROLE="client", REQUESTS=" ".join(urls))
    if args.direction.endswith("our-client-migrating"):
        client_env["CLIENT_PARAMS"] = "migrate=active"
    else:
        client_env.pop("CLIENT_PARAMS", None)

    log = []
    server = subprocess.Popen(shlex.split(args.server, posix=os.name != "nt"), env=server_env, cwd=workdir,
                              stdout=subprocess.PIPE, stderr=subprocess.STDOUT)
    try:
        time.sleep(args.server_start)
        if server.poll() is not None:
            out = server.stdout.read().decode(errors="replace")
            log.append(out)
            return ("unsupported" if server.returncode == UNSUPPORTED else "failed"), log
        client_cmd = shlex.split(args.client, posix=os.name != "nt") + (urls if args.client_urls_as_args else [])
        try:
            client = subprocess.run(client_cmd, env=client_env, cwd=workdir, capture_output=True,
                                    timeout=TIMEOUT[test])
            out = (client.stdout + client.stderr).decode(errors="replace")
            log.append(out)
            code = client.returncode
        except subprocess.TimeoutExpired as e:
            log.append(((e.stdout or b"") + (e.stderr or b"")).decode(errors="replace"))
            log.append(f"client took longer than {TIMEOUT[test]} s")
            return "failed", log
    finally:
        server.terminate()
        try:
            server.wait(10)
        except subprocess.TimeoutExpired:
            server.kill()
        log.append("--- server ---\n" + server.stdout.read().decode(errors="replace")[-4000:])
    if code == UNSUPPORTED:
        return "unsupported", log
    if code != 0:
        log.append(f"client exited with code {code}")
        return "failed", log
    for n in names:
        got = os.path.join(downloads, n)
        if not os.path.exists(got):
            log.append(f"Missing download {n}")
            return "failed", log
        with open(got, "rb") as a, open(os.path.join(www, n), "rb") as b:
            if a.read() != b.read():
                log.append(f"Wrong content in {n}")
                return "failed", log
    return "succeeded", log


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
    args = p.parse_args()

    os.makedirs(args.results, exist_ok=True)
    tests = [t for t in args.tests.split(",") if t]
    results = []
    reasons = []
    summary = ""
    for test in tests:
        workdir = tempfile.mkdtemp(prefix=f"interop-{test}-")
        try:
            result, log = run_test(args, test, workdir)
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
            reasons.append(f"== {test}: {result}\n" + "\n".join(text.splitlines()[-40:]))
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
