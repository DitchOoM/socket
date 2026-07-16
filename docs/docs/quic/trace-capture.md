---
sidebar_position: 4
title: Trace Capture & Retrace
---

# Trace Capture & Retrace

QUIC connections can record a **deterministic-replay trace** — every datagram, state transition,
error, path-stats snapshot, and (optionally) connectivity change, stamped against one clock. Capture
is **opt-in and zero-cost when off** (the default): a `null` trace is byte-identical to the
pre-capture path.

A trace is useful for three things:

- **Field diagnostics** — attach the trace to a bug report; it's a packet-level record of what the
  connection actually did.
- **Deterministic replay** — feed the input events back through the simulation harness to reproduce a
  failure offline (see `RFC_DETERMINISTIC_SIMULATION.md`).
- **Post-hoc retrace** — on an obfuscated release build, class names in the trace are renamed by
  R8/ProGuard; a companion tool maps them back using your app's `mapping.txt`.

## The sink is typed

Capture is driven by a `TraceSink` — a functional interface that receives a typed
[`TraceEvent`](#event-types), not a string:

```kotlin
fun interface TraceSink {
    fun emit(event: TraceEvent)
}
```

`TraceEvent.toString()` renders the canonical **`v1`** line (one event per line, space-delimited), so
a file/network sink is just `event.toString()`. But because the event is typed, a sink can also match
on it directly — filter, count, or react without parsing. `TraceEvent.parse(line)` / `parseAll(lines)`
decode a persisted trace back into events, and `parse(e.toString()) == e` holds for every event.

## Enabling capture on a client

Set `QuicOptions.trace` to a `QuicTraceCapture`. The engine then wraps the connection's UDP channels
in a recording decorator and mirrors state/error/stats transitions onto your sink.

```kotlin
import com.ditchoom.socket.NetworkMonitor
import com.ditchoom.socket.quic.QuicOptions
import com.ditchoom.socket.quic.trace.QuicTraceCapture
import com.ditchoom.socket.quic.trace.TraceEvent
import com.ditchoom.socket.quic.trace.TraceSink
import java.io.File
import kotlin.time.Duration.Companion.seconds

// A sink may be invoked from several coroutines (the driver loop, per-path readers, the monitor
// collectors) — treat it like a log sink and guard shared state.
val out = File("captured.trace").bufferedWriter()
val capture = QuicTraceCapture(
    sink = TraceSink { event -> synchronized(out) { out.appendLine(event.toString()) } },
    // Optional: also fold connectivity state (NET_AVAIL / NET_ID) into the SAME trace — the
    // airplane-mode toggle / Wi-Fi↔cellular handoff, not just QUIC traffic. Client-only.
    networkMonitor = NetworkMonitor.default(),
)

withQuicConnection(
    hostname = "example.com",
    port = 443,
    quicOptions = QuicOptions(alpnProtocols = listOf("h3"), trace = capture),
    timeout = 15.seconds,
) {
    val stream = openStream()
    // … normal QUIC usage; every datagram, state change, error, and stats snapshot is recorded …
    stream.close()
}
out.flush()
```

### Consuming typed events instead of lines

```kotlin
TraceSink { event ->
    when (event) {
        is TraceEvent.State -> println("state → ${event.name} ${event.detail ?: ""}")
        is TraceEvent.Error -> println("error → ${event.type}: ${event.message}")
        else -> out.appendLine(event.toString())
    }
}
```

## Enabling capture on a server

A server handles many connections; the `v1` grammar carries **no connection identifier** and each
connection records against its own clock origin. So for replayable per-connection traces, use the
`sinkFor` **factory** constructor — it's invoked **once per accepted connection**, and returning a
*fresh* sink each time keeps the connections isolated:

```kotlin
val capture = QuicTraceCapture(
    sinkFor = {
        val writer = newTraceFileForThisConnection().bufferedWriter()
        TraceSink { event -> synchronized(writer) { writer.appendLine(event.toString()) } }
    },
)

withQuicServer(port = 4433, tlsConfig = tlsConfig, quicOptions = quicOptions.copy(trace = capture)) {
    connections { /* … */ }
}
```

The single-`TraceSink` convenience constructor (`QuicTraceCapture(sink = …)`) is the opposite choice
on purpose: it hands the **same** sink to every connection (log-sink semantics) — fine for a single
connection or aggregate diagnostics, but it interleaves concurrent connections onto one stream.

## Event types

Each `TraceEvent` is either a replayable **input** or an observed **observation** (RFC §2):

| Event | `v1` tag | Role | Carries |
|-------|----------|------|---------|
| `DgramOut` | `DGRAM_OUT` | observation | datagram sent (len, path, hex) |
| `DgramIn` | `DGRAM_IN` | input | datagram received (len, path, hex) |
| `State` | `STATE` | observation | `QuicConnectionState` transition (**qualified** class name + detail) |
| `PathState` | `PATH_STATE` | observation | migration phase + local host/port |
| `Error` | `ERROR` | input | typed error (**qualified** class name + message) |
| `Stats` | `STATS` | observation | quiche path-stats snapshot |
| `NetAvail` | `NET_AVAIL` | input | `NetworkMonitor.availability` emission |
| `Net` | `NET_ID` | input | `NetworkMonitor.networkId` emission |
| `Liveness` | `LIVENESS` | input | liveness probe outcome |

`State.name` and `Error.type` are captured as **qualified** class names (`::class.qualifiedName`),
never `simpleName`. That's what makes the next section work.

## Retracing an obfuscated trace

On a minified release build (R8 on Android, or ProGuard/R8 over a JVM app), the class names in
`STATE` and `ERROR` are renamed — a trace line reads `STATE a.b.c` instead of
`STATE …QuicConnectionState.Established`. There are **no keep-rules to add**: capture uses qualified
names precisely so the rename is reversible after the fact, the way a crash reporter symbolicates a
stack trace. You just need to keep the build's `mapping.txt`.

The retrace tool is a separate, JVM-only artifact (it pulls `com.android.tools:r8`, so add Google's
Maven repo):

```kotlin
repositories {
    google()        // com.android.tools:r8 (runtime-transitive)
    mavenCentral()
}
dependencies {
    implementation("com.ditchoom:socket-quic-trace-tools:<latest-version>")
}
```

```kotlin
import com.ditchoom.socket.quic.trace.tools.TraceDeobfuscator
import java.io.File

// mapping.txt from the release build (e.g. app/build/outputs/mapping/release/mapping.txt).
val deobfuscator = TraceDeobfuscator.fromMapping(File("mapping.txt").readText())

val readable = deobfuscator.deobfuscateAll(File("captured.trace").readLines())
File("captured.deobf.trace").writeText(readable.joinToString("\n"))
```

Only the `STATE`/`ERROR` class-name tokens are rewritten; every other event and field passes through
untouched, and a name absent from the mapping (a non-obfuscated trace, or a Kotlin/Native or JS trace
— those aren't obfuscated) is a clean identity pass-through. It handles both R8 and plain ProGuard
mappings (same grammar).

### From Gradle

If you build from this repository, the same tool is wired as a Gradle task:

```bash
./gradlew :socket-quic-trace-tools:retraceQuicTrace \
    -PtraceIn=captured.trace -Pmapping=mapping.txt -PtraceOut=captured.deobf.trace
```

## End-to-end

1. **Capture** — set `QuicOptions.trace = QuicTraceCapture(…)` and persist each `event.toString()`.
2. **Ship** the app minified (R8/ProGuard) and archive its `mapping.txt`. `STATE`/`ERROR` names land
   obfuscated in traces from the field.
3. **Retrace** offline — feed the captured trace + `mapping.txt` to `TraceDeobfuscator` (or the
   Gradle task) to get readable class names back.

## Next Steps

- [Connections & Streams](./connection) — the stream model you're tracing
- [Typed Stream Multiplexing](./stream-mux) — codec-typed messages over the same connection
