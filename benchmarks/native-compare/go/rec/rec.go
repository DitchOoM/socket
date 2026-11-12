// Package rec is the Go side of the measurement protocol every program shares: discard the warm-up, time the
// window, print the MEASURING marker and one RESULT line.
package rec

import (
	"fmt"
	"os"
	"sort"
	"strconv"
	"syscall"
	"time"
)

const (
	Size      = 64
	Chunk     = 64 * 1024
	BulkBytes = 16 * 1024 * 1024
)

type Recorder struct {
	stack, workload string
	warmup, window  time.Duration
	bytesPerOp      float64
	t0, start       time.Time
	cpu0            int64
	samples         []int64
}

func secs(name string, def int) time.Duration {
	if v, err := strconv.Atoi(os.Getenv(name)); err == nil {
		return time.Duration(v) * time.Second
	}
	return time.Duration(def) * time.Second
}

func cpuMicros() (int64, int64) {
	var ru syscall.Rusage
	syscall.Getrusage(syscall.RUSAGE_SELF, &ru)
	return ru.Utime.Sec*1e6 + int64(ru.Utime.Usec) + ru.Stime.Sec*1e6 + int64(ru.Stime.Usec), int64(ru.Maxrss)
}

func New(stack, workload string, bytesPerOp int) *Recorder {
	return &Recorder{stack: stack, workload: workload, warmup: secs("NATIVE_BENCH_WARMUP", 2),
		window: secs("NATIVE_BENCH_SECONDS", 10), bytesPerOp: float64(bytesPerOp), t0: time.Now()}
}

// Record takes one finished operation's duration and reports whether to keep going.
func (r *Recorder) Record(took time.Duration) bool {
	if r.start.IsZero() {
		if time.Since(r.t0) >= r.warmup {
			r.cpu0, _ = cpuMicros()
			fmt.Println("MEASURING")
			r.start = time.Now()
		}
		return true
	}
	r.samples = append(r.samples, took.Nanoseconds())
	if time.Since(r.start) < r.window {
		return true
	}
	secs := time.Since(r.start).Seconds()
	cpu, rss := cpuMicros()
	sort.Slice(r.samples, func(i, j int) bool { return r.samples[i] < r.samples[j] })
	n := len(r.samples)
	pct := func(p float64) float64 {
		i := int(float64(n) * p)
		if i > n-1 {
			i = n - 1
		}
		return float64(r.samples[i]) / 1000
	}
	fmt.Printf("RESULT stack=%s workload=%s ops=%d seconds=%v p50_us=%v p99_us=%v p999_us=%v ops_per_s=%v mib_per_s=%v cpu_us=%d maxrss_raw=%d\n",
		r.stack, r.workload, n, secs, pct(0.5), pct(0.99), pct(0.999), float64(n)/secs,
		float64(n)*r.bytesPerOp/secs/1048576, cpu-r.cpu0, rss)
	return false
}
