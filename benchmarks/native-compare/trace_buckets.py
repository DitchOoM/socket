#!/usr/bin/env python3 -I
"""On-CPU time per cost bucket, from the traces run.py --trace-dir writes.

  trace_buckets.py <trace> [<trace> ...]

Formats, by file name: `*.trace` (macOS xctrace Time Profiler), `*.perf.data` (Linux `perf record`, read through
`perf script`), `*.jfr` (JDK Flight Recorder execution samples, through `jfr print`). Every format is read as on-CPU
stack samples. Per trace it prints each bucket's share of the leaf (self) time, the top leaf symbols, and the
nearest frame of ours or of Kotlin above each sample (which of our functions the time is spent under).
"""
import collections, os, re, subprocess, sys
import xml.etree.ElementTree as ET

# First match wins, against the leaf symbol.
BUCKETS = [
    ("socket syscalls", r"^_*(sendmsg|recvmsg|sendto|recvfrom|sendmmsg|recvmmsg|io_uring_enter)\b|^(__)?sys_(send|recv)|"
                        r"^(udp|ip6?)_|DatagramChannel|NioDatagram|DatagramDispatcher|^__x64_sys_(send|recv)|"
                        r"^__arm64_sys_(send|recv)"),
    ("thread hand-off", r"psynch|pthread_cond|pthread_mutex|_dispatch_|dispatch_|futex|sched_yield|workq|wqthread|"
                        r"thread_switch|ulock|Worker#park|Worker#execute|LockSupport|os_unfair_lock|semaphore|"
                        r"^(__)?schedule|try_to_wake_up|finish_task_switch|wake_up|__ulock|kevent|epoll|io_uring|"
                        r"syscall_exit|entry_SYSCALL|el0_svc|do_syscall"),
    ("clock reads", r"gettimeofday|mach_absolute_time|mach_continuous_time|clock_gettime|TimeSource|nanoTime|vdso"),
    ("coroutine machinery", r"kotlinx\.coroutines|kotlin\.coroutines|Continuation|DispatchedTask|"
                            r"EventLoop|SuspendLambda|invokeSuspend|createCoroutine|ChannelSegment|BufferedChannel|"
                            r"JobSupport|LimitedDispatcher|CoroutineScheduler"),
    ("Network.framework", r"\bnw_|NWConcrete|swift_|swift::|objc_|uuid_|__nw_|libnetwork|necp"),
    ("quiche + crypto", r"quiche|ring::|boring|bssl|EVP_|aes_|gcm_|chacha|sha256|x25519|ecdsa|RSA_|BN_|"
                        r"CRYPTO_|ssl::|SSL_|tls_|poly1305|hkdf|aesv8|aes_gcm|ghash|gcm_"),
    ("GC + allocation", r"kotlin::gc|kotlin::alloc|gc::|GCSweep|Mark|alloc|Alloc|malloc|\bfree\b|_free|xzm|"
                        r"mi_|madvise|mmap|munmap|memset|ObjectFactory|ExtraObject|HeapRef|Safepoint"),
    ("memory copies", r"memcpy|memmove|copy_user|copy_to|copy_from|_platform_mem"),
    ("our driver + streams", r"com\.ditchoom"),
    ("kotlin runtime", r"kotlin\.|kfun:|Kotlin_|konan|kotlin::|_tlv_get_addr"),
    ("kernel / other", r"."),
]
COMPILED = [(name, re.compile(rx)) for name, rx in BUCKETS]
OURS = re.compile(r"com\.ditchoom|kfun:com\.ditchoom")
KOTLIN = re.compile(r"kfun:|kotlinx\.coroutines|kotlin\.")


def bucket(symbol):
    for name, rx in COMPILED:
        if rx.search(symbol):
            return name
    return "kernel / other"


def from_xctrace(path):
    """[(weight, [leaf..root frame names])] from the time-profile table; rows reference earlier ids."""
    xml = subprocess.run(["xcrun", "xctrace", "export", "--input", path, "--xpath",
                          '/trace-toc/run[@number="1"]/data/table[@schema="time-profile"]'],
                         capture_output=True, text=True).stdout
    root = ET.fromstring(xml)
    by_id = {}
    for el in root.iter():
        if "id" in el.attrib:
            by_id[el.attrib["id"]] = el

    def deref(el):
        return by_id.get(el.attrib["ref"], el) if "ref" in el.attrib else el

    samples = []
    for row in root.iter("row"):
        state = row.find("thread-state")
        if state is not None and deref(state).attrib.get("fmt", "Running") != "Running":
            continue
        weight_el = row.find("weight")
        weight = int(deref(weight_el).text or 1) if weight_el is not None else 1
        bt = row.find("tagged-backtrace")
        bt = row.find("backtrace") if bt is None else bt
        if bt is None:
            continue
        bt = deref(bt)
        frames = [deref(f).attrib.get("name", "?") for f in bt.findall("frame")]
        if frames:
            samples.append((weight, frames))
    return samples


def from_perf(path):
    text = subprocess.run(["perf", "script", "-i", path, "-F", "ip,sym"], capture_output=True, text=True).stdout
    samples, frames = [], []
    for line in text.splitlines():
        if not line.strip():
            if frames:
                samples.append((1, frames))
            frames = []
            continue
        parts = line.strip().split(None, 1)
        frames.append(parts[1] if len(parts) > 1 else parts[0])
    if frames:
        samples.append((1, frames))
    return samples


def from_jfr(path):
    text = subprocess.run(["jfr", "print", "--events", "jdk.ExecutionSample", "--stack-depth", "64", path],
                          capture_output=True, text=True).stdout
    samples = []
    for block in text.split("jdk.ExecutionSample")[1:]:
        frames = re.findall(r"^\s+(\S[^\n]*?)\s+line:", block, re.M)
        if frames:
            samples.append((1, frames))
    return samples


def report(path):
    if path.endswith(".trace"):
        samples = from_xctrace(path)
    elif path.endswith(".perf.data"):
        samples = from_perf(path)
    elif path.endswith(".jfr"):
        samples = from_jfr(path)
    else:
        return f"{path}: unknown trace format"
    total = sum(w for w, _ in samples) or 1
    per_bucket, per_leaf, per_ours = collections.Counter(), collections.Counter(), collections.Counter()
    for w, frames in samples:
        leaf = frames[0]
        per_bucket[bucket(leaf)] += w
        per_leaf[leaf] += w
        ours = next((f for f in frames if OURS.search(f)), None) or next((f for f in frames if KOTLIN.search(f)), "(no Kotlin frame)")
        per_ours[re.sub(r"\(.*", "", ours)[:140]] += w
    lines = [f"## {os.path.basename(path)}  ({len(samples)} on-CPU samples)", "",
             "| bucket (leaf) | share |", "|---|---|"]
    lines += [f"| {b} | {100 * n / total:.1f} % |" for b, n in per_bucket.most_common()]
    lines += ["", "Top leaf symbols:", "```"]
    lines += [f"{100 * n / total:5.1f}%  [{bucket(s)}]  {s[:140]}" for s, n in per_leaf.most_common(15)]
    lines += ["```", "", "Nearest frame of ours (else Kotlin) above each sample:", "```"]
    lines += [f"{100 * n / total:5.1f}%  {s}" for s, n in per_ours.most_common(15)]
    lines += ["```", ""]
    return "\n".join(lines)


if __name__ == "__main__":
    print("\n".join(report(p) for p in sys.argv[1:]))
