#!/usr/bin/env python3 -I
"""Runs every stack for every workload, interleaved by round, and prints a scoreboard.

  run.py --kexe <test.kexe> --certs <dir from gen-cert.sh> [--rounds 5] [--seconds 10] [--only stack,...] [--json out.json]

Each program is started on its own (one process per workload, so ru_maxrss is that workload's), prints MEASURING
when its window opens, and one RESULT line when it closes. This script samples the thread count halfway through
the window and records `uptime` before each run. CPU is the process's user+system time over the window (getrusage
in the program itself), summed over all threads, divided by the operations completed.
"""
import argparse, json, os, platform, re, statistics, subprocess, sys, time

HERE = os.path.dirname(os.path.abspath(__file__))
LINUX = platform.system() == "Linux"

def stacks(kexe):
    kotlin = lambda w: ([kexe, "--ktest_filter=*NativeCompareBench*"], {"NATIVE_BENCH": w})
    rust = lambda b, w=None: ([f"{HERE}/rust/target/release/{b}"] + ([w] if w else []), {})
    go = lambda b, w=None: ([f"{HERE}/go/bin/{b}"] + ([w] if w else []), {})
    swift = lambda b: ([f"{HERE}/swift/.build/release/{b}"], {})
    table = {
        "kotlin-native-ours": {w: kotlin(w) for w in ("qrr", "qspr", "qbulk", "tcp")},
        "quiche-floor": {w: rust("quiche_floor", w) for w in ("qrr", "qspr", "qbulk")},
        "rust-quinn": {w: rust("quinn_bench", w) for w in ("qrr", "qspr", "qbulk")},
        "go-quic-go": {w: go("quicgo", w) for w in ("qrr", "qspr", "qbulk")},
        "rust-tokio": {"tcp": rust("tokio_tcp")},
        "go-net": {"tcp": go("gonet")},
    }
    if not LINUX:
        table["swift-network-framework"] = {"tcp": swift("nwtcp")}
        table["swift-nio"] = {"tcp": swift("niotcp")}
    return table

def threads(pid):
    if LINUX:
        try:
            return len(os.listdir(f"/proc/{pid}/task"))
        except OSError:
            return 0
    out = subprocess.run(["ps", "-M", "-p", str(pid)], capture_output=True, text=True).stdout
    return max(0, len(out.strip().splitlines()) - 1)

def run_once(cmd, env, seconds, warmup, certs):
    e = dict(os.environ, NATIVE_BENCH_SECONDS=str(seconds), NATIVE_BENCH_WARMUP=str(warmup), BENCH_CERT_DIR=certs, **env)
    load = subprocess.run(["uptime"], capture_output=True, text=True).stdout.split("load average")[-1].strip(" s:\n")
    p = subprocess.Popen(cmd, env=e, stdout=subprocess.PIPE, text=True)
    nthreads, result = 0, None
    for line in p.stdout:
        if line.startswith("MEASURING"):
            time.sleep(seconds / 2)
            nthreads = threads(p.pid)
        m = re.match(r"RESULT (.*)", line)
        if m:
            result = dict(kv.split("=", 1) for kv in m.group(1).split())
    p.wait(timeout=60)
    if result is None:
        return None
    r = {k: (v if k in ("stack", "workload") else float(v)) for k, v in result.items()}
    r["threads"] = nthreads
    r["load"] = load
    r["rss_mib"] = r["maxrss_raw"] / (1024 if LINUX else 1048576)
    r["cpu_us_per_op"] = r["cpu_us"] / r["ops"]
    r["cpu_us_per_mib"] = r["cpu_us"] / (r["ops"] * 16) if r["workload"] == "qbulk" else 0
    return r

def main():
    a = argparse.ArgumentParser()
    a.add_argument("--kexe", required=True)
    a.add_argument("--certs", required=True)
    a.add_argument("--rounds", type=int, default=5)
    a.add_argument("--seconds", type=int, default=10)
    a.add_argument("--warmup", type=int, default=2)
    a.add_argument("--only", default="")
    a.add_argument("--workloads", default="qrr,qspr,qbulk,tcp")
    a.add_argument("--json", default="")
    a.add_argument("--lock", default="", help="lock directory to mkdir while running (released on exit)")
    args = a.parse_args()
    if args.lock:
        os.mkdir(args.lock)  # fails if another run holds it
    try:
        table = stacks(args.kexe)
        only = [s for s in args.only.split(",") if s] or list(table)
        runs = []
        for rnd in range(args.rounds):
            for w in args.workloads.split(","):
                for s in only:
                    if w in table[s]:
                        cmd, env = table[s][w]
                        r = run_once(cmd, env, args.seconds, args.warmup, args.certs)
                        print(f"round {rnd} {s} {w}: {None if r is None else (r['p50_us'], r['ops_per_s'], r['mib_per_s'], r['load'])}", file=sys.stderr, flush=True)
                        if r:
                            r["stack"] = s
                            runs.append(r)
        if args.json:
            json.dump(runs, open(args.json, "w"), indent=1)
        print(scoreboard(runs))
    finally:
        if args.lock:
            os.rmdir(args.lock)

def rng(rs, key, fmt="{:.1f}"):
    v = [r[key] for r in rs]
    return f"{fmt.format(statistics.median(v))} [{fmt.format(min(v))}-{fmt.format(max(v))}]"

def scoreboard(runs):
    out = []
    for w, title in (("qrr", "QUIC long-lived stream, 64 B echo"), ("qspr", "QUIC stream per request, 64 B"),
                     ("qbulk", "QUIC bulk, 16 MiB per stream in 64 KiB writes"), ("tcp", "TCP 64 B echo")):
        rows = [s for s in dict.fromkeys(r["stack"] for r in runs if r["workload"] == w)]
        if not rows:
            continue
        bulk = w == "qbulk"
        out.append(f"\n### {title}\n")
        out.append("| stack | p50 us | p99 us | " + ("MiB/s | CPU us/MiB" if bulk else "ops/s | CPU us/op") + " | peak RSS MiB | threads |")
        out.append("|---|---|---|---|---|---|---|")
        for s in rows:
            rs = [r for r in runs if r["workload"] == w and r["stack"] == s]
            rate = rng(rs, "mib_per_s") if bulk else rng(rs, "ops_per_s", "{:.0f}")
            cpu = rng(rs, "cpu_us_per_mib") if bulk else rng(rs, "cpu_us_per_op")
            out.append(f"| {s} | {rng(rs, 'p50_us')} | {rng(rs, 'p99_us')} | {rate} | {cpu} | {rng(rs, 'rss_mib', '{:.0f}')} | {rng(rs, 'threads', '{:.0f}')} |")
    out.append("\nCells are median [min-max] over the rounds. Bulk p50/p99 are per 16 MiB transfer, in us.")
    return "\n".join(out)

main()
