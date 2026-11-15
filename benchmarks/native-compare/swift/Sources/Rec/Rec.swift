import Foundation

/// The Swift side of the measurement protocol every program shares: discard the warm-up, time the window,
/// print the MEASURING marker and one RESULT line.
public final class Recorder {
    let stack: String, workload: String
    let warmup: Double, window: Double
    let t0 = DispatchTime.now().uptimeNanoseconds
    var start: UInt64 = 0
    var cpu0: Int = 0
    var samples: [UInt64] = []

    public init(stack: String, workload: String) {
        self.stack = stack
        self.workload = workload
        let env = ProcessInfo.processInfo.environment
        warmup = Double(env["NATIVE_BENCH_WARMUP"] ?? "") ?? 2
        window = Double(env["NATIVE_BENCH_SECONDS"] ?? "") ?? 10
    }

    static func usage() -> (cpuMicros: Int, maxrss: Int) {
        var ru = rusage()
        getrusage(RUSAGE_SELF, &ru)
        let cpu = (ru.ru_utime.tv_sec + ru.ru_stime.tv_sec) * 1_000_000 + Int(ru.ru_utime.tv_usec + ru.ru_stime.tv_usec)
        return (cpu, ru.ru_maxrss)
    }

    /// Call with each finished operation's duration in nanoseconds; false once the window is over.
    public func record(_ nanos: UInt64) -> Bool {
        let now = DispatchTime.now().uptimeNanoseconds
        if start == 0 {
            if Double(now - t0) / 1e9 >= warmup {
                cpu0 = Recorder.usage().cpuMicros
                print("MEASURING")
                fflush(stdout)
                start = now
            }
            return true
        }
        samples.append(nanos)
        if Double(now - start) / 1e9 < window { return true }
        let secs = Double(now - start) / 1e9
        let u = Recorder.usage()
        samples.sort()
        let n = samples.count
        func pct(_ p: Double) -> Double { Double(samples[min(n - 1, Int(Double(n) * p))]) / 1000 }
        print("RESULT stack=\(stack) workload=\(workload) ops=\(n) seconds=\(secs) p50_us=\(pct(0.5)) p99_us=\(pct(0.99)) p999_us=\(pct(0.999)) ops_per_s=\(Double(n) / secs) mib_per_s=0 cpu_us=\(u.cpuMicros - cpu0) maxrss_raw=\(u.maxrss)")
        fflush(stdout)
        return false
    }
}
