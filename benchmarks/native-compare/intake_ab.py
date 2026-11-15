#!/usr/bin/env python3 -I
"""MEASUREMENT ONLY: the server's reader-to-loop hand-off, base against variants, on one machine.

  intake_ab.py --base-jar <quic-echo.jar of the base> --jar <quic-echo.jar with the intake> --java <JDK 25 java>
               --certs <testcerts dir> --out <dir> [--rounds 3] [--seconds 8]

Runs ManyConnectionEchoMain for every variant, interleaved within each round (base, v1, v2, ..., base, v1, ...),
at 64 and 1024 connections, then one more pass per variant under JFR CPU-time sampling. Prints a table per
connection count: median [min-max] of throughput, p50 and p99, connections that completed no echo, the kernel's
RcvbufErrors, and per echo: socket-empty waits (reader wake-ups), datagrams per reader yield, reader yields, loop
waits, and datagrams the loop took by waiting (hand-offs) or without waiting.
"""
import argparse, json, os, re, statistics, subprocess

VARIANTS = [  # (name, jar key, JVM properties)
    ("base", "base", []),
    ("rendezvous", "new", ["-Dintake.capacity=0"]),
    ("yield-1", "new", ["-Dintake.yield=1"]),
    ("yield-8", "new", ["-Dintake.yield=8"]),
    ("yield-32", "new", ["-Dintake.yield=32"]),
    ("yield-128", "new", ["-Dintake.yield=128"]),
    ("yield-on-wake", "new", ["-Dintake.yield=wake"]),
    ("never", "new", ["-Dintake.yield=never"]),
]
COUNTERS = ("datagrams_read", "reader_yields", "loop_waits", "taken_by_waiting", "taken_without_waiting", "loop_yields",
            "socket_empty_waits")


def fields(line):
    return {k: v for k, v in (kv.split("=", 1) for kv in line.split() if "=" in kv)}


def run(args, name, jar, props, conns, jfr=""):
    cmd = [args.java, "--enable-native-access=ALL-UNNAMED", "--add-opens", "java.base/java.nio=ALL-UNNAMED"] + props
    if jfr:
        cmd.append(f"-XX:StartFlightRecording:filename={jfr},jdk.CPUTimeSample#enabled=true,jdk.CPUTimeSample#throttle=1ms")
    cmd += ["-cp", jar, "com.ditchoom.socket.quic.ManyConnectionEchoMainKt", f"{args.certs}/cert.crt", f"{args.certs}/cert.key",
            str(conns), "2", str(args.seconds)]
    out = subprocess.run(cmd, capture_output=True, text=True, timeout=900).stdout
    r = {"variant": name, "conns": conns}
    udp = re.findall(r"^UDP \w+ .*?Udp: (\d+) (\d+) (\d+) (\d+) (\d+)", out, re.M)
    if len(udp) >= 2:
        r["rcvbuf_errors"] = int(udp[-1][4]) - int(udp[0][4])
    m = re.search(r"^STUCK-COUNT (\d+)", out, re.M)
    if m:
        r["stuck"] = int(m.group(1))
    start, end = re.search(r"^INTAKE start (.*)", out, re.M), re.search(r"^INTAKE end (.*)", out, re.M)
    if start and end:
        a, b = fields(start.group(1)), fields(end.group(1))
        for c in COUNTERS:
            if c in a and c in b:
                r[c] = int(b[c]) - int(a[c])
    m = re.search(r"^RESULT (.*)", out, re.M)
    if not m:
        r["failed"] = out[-600:]
        return r
    res = fields(m.group(1))
    r.update(ops=float(res["ops"]), ops_per_s=float(res["ops_per_s"]), p50_ms=float(res["p50_us"]) / 1000,
             p99_ms=float(res["p99_us"]) / 1000, cpu_us_per_op=float(res["cpu_us"]) / max(float(res["ops"]), 1))
    return r


def rng(rs, key, fmt="{:.0f}"):
    v = [r[key] for r in rs if key in r]
    if not v:
        return "-"
    return f"{fmt.format(statistics.median(v))} [{fmt.format(min(v))}-{fmt.format(max(v))}]"


def per_echo(rs, key):
    v = [r[key] / r["ops"] for r in rs if key in r and r.get("ops")]
    return "-" if not v else f"{statistics.median(v):.2f}"


def table(runs, conns):
    out = [f"\n### {conns} connections\n",
           "| variant | op/s | p50 ms | p99 ms | CPU us/op | no-echo conns | RcvbufErrors | socket-empty waits /echo | "
           "reader yields /echo | datagrams per yield | loop waits /echo | taken by waiting /echo | taken without waiting /echo |",
           "|---|---|---|---|---|---|---|---|---|---|---|---|---|"]
    for name, _, _ in VARIANTS:
        rs = [r for r in runs if r["variant"] == name and r["conns"] == conns and "ops" in r]
        if not rs:
            out.append(f"| {name} | failed | | | | | | | | | | | |")
            continue
        per_yield = [r["datagrams_read"] / r["reader_yields"] for r in rs if r.get("reader_yields")]
        out.append(
            f"| {name} | {rng(rs, 'ops_per_s')} | {rng(rs, 'p50_ms', '{:.1f}')} | {rng(rs, 'p99_ms')} | {rng(rs, 'cpu_us_per_op')} | "
            f"{[r.get('stuck', '-') for r in rs]} | {rng(rs, 'rcvbuf_errors')} | {per_echo(rs, 'socket_empty_waits')} | "
            f"{per_echo(rs, 'reader_yields')} | {'-' if not per_yield else f'{statistics.median(per_yield):.1f}'} | "
            f"{per_echo(rs, 'loop_waits')} | {per_echo(rs, 'taken_by_waiting')} | {per_echo(rs, 'taken_without_waiting')} |")
    return "\n".join(out)


def main():
    a = argparse.ArgumentParser()
    a.add_argument("--base-jar", required=True)
    a.add_argument("--jar", required=True)
    a.add_argument("--java", required=True)
    a.add_argument("--certs", required=True)
    a.add_argument("--out", required=True)
    a.add_argument("--rounds", type=int, default=3)
    a.add_argument("--seconds", type=int, default=8)
    a.add_argument("--conns", default="64,1024")
    a.add_argument("--profile", default="base,rendezvous,yield-32,never")
    a.add_argument("--variants", default="", help="comma-separated subset of the variants (default: all)")
    args = a.parse_args()
    if args.variants:
        VARIANTS[:] = [v for v in VARIANTS if v[0] in args.variants.split(",")]
    os.makedirs(args.out, exist_ok=True)
    jars = {"base": args.base_jar, "new": args.jar}
    runs = []
    for conns in (int(c) for c in args.conns.split(",")):
        for rnd in range(args.rounds):
            for name, key, props in VARIANTS:
                r = run(args, name, jars[key], props, conns)
                r["round"] = rnd
                runs.append(r)
                print(f"round {rnd} conns {conns} {name}: {r.get('ops_per_s', r.get('failed', '?'))}", flush=True)
                json.dump(runs, open(os.path.join(args.out, "intake-ab.json"), "w"), indent=1)
    report = "\n".join(table(runs, int(c)) for c in args.conns.split(","))
    print(report)
    open(os.path.join(args.out, "intake-ab.md"), "w").write(report + "\n")
    for conns in (int(c) for c in args.conns.split(",")):
        for name, key, props in VARIANTS:
            if name in args.profile.split(","):
                run(args, name, jars[key], props, conns, jfr=os.path.join(os.path.abspath(args.out), f"{name}-{conns}.jfr"))


main()
