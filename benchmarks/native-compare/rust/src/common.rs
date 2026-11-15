//! Shared by every Rust program: the workload constants and the measurement protocol.
//! A program calls `Recorder::record` once per finished operation; the recorder discards the warm-up, times the
//! window, and prints the `MEASURING` marker and the single `RESULT` line the runner parses.
#![allow(dead_code)]
use std::time::{Duration, Instant};

pub const SIZE: usize = 64;
pub const CHUNK: usize = 64 * 1024;
pub const BULK_BYTES: usize = 16 * 1024 * 1024;

pub fn env_secs(name: &str, default: u64) -> Duration {
    Duration::from_secs(std::env::var(name).ok().and_then(|v| v.parse().ok()).unwrap_or(default))
}

fn rusage() -> libc::rusage {
    let mut ru: libc::rusage = unsafe { std::mem::zeroed() };
    unsafe { libc::getrusage(libc::RUSAGE_SELF, &mut ru) };
    ru
}

fn cpu_us() -> u64 {
    let ru = rusage();
    (ru.ru_utime.tv_sec + ru.ru_stime.tv_sec) as u64 * 1_000_000 + (ru.ru_utime.tv_usec + ru.ru_stime.tv_usec) as u64
}

pub struct Recorder {
    stack: &'static str,
    workload: String,
    warmup: Duration,
    window: Duration,
    bytes_per_op: u64,
    t0: Instant,
    start: Option<Instant>,
    cpu0: u64,
    samples: Vec<u64>,
}

impl Recorder {
    pub fn new(stack: &'static str, workload: &str, bytes_per_op: usize) -> Self {
        Recorder {
            stack,
            workload: workload.to_string(),
            warmup: env_secs("NATIVE_BENCH_WARMUP", 2),
            window: env_secs("NATIVE_BENCH_SECONDS", 10),
            bytes_per_op: bytes_per_op as u64,
            t0: Instant::now(),
            start: None,
            cpu0: 0,
            samples: Vec::new(),
        }
    }

    /// Call after each operation with its duration; returns false once the window is over.
    pub fn record(&mut self, took: Duration) -> bool {
        match self.start {
            None => {
                if self.t0.elapsed() >= self.warmup {
                    self.cpu0 = cpu_us();
                    println!("MEASURING");
                    self.start = Some(Instant::now());
                }
                true
            }
            Some(start) => {
                self.samples.push(took.as_nanos() as u64);
                if start.elapsed() < self.window {
                    return true;
                }
                self.finish(start);
                false
            }
        }
    }

    fn finish(&mut self, start: Instant) {
        let secs = start.elapsed().as_secs_f64();
        let cpu = cpu_us() - self.cpu0;
        self.samples.sort_unstable();
        let n = self.samples.len();
        let pct = |p: f64| self.samples[(((n as f64) * p) as usize).min(n - 1)] as f64 / 1000.0;
        println!(
            "RESULT stack={} workload={} ops={} seconds={} p50_us={} p99_us={} p999_us={} ops_per_s={} mib_per_s={} cpu_us={} maxrss_raw={}",
            self.stack, self.workload, n, secs, pct(0.5), pct(0.99), pct(0.999), n as f64 / secs,
            n as f64 * self.bytes_per_op as f64 / secs / 1048576.0, cpu, rusage().ru_maxrss
        );
    }
}
