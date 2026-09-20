# Android handoff-probe rig

Hand-driven, multi-day runs of `DeviceHandoffProbe` on a real phone against the public echo server.
Everything here talks to the phone through `adb`; the probe itself runs detached and survives unplugging.

## Before a run (phone plugged in and UNLOCKED)

```bash
./gradlew :socket-quic-quiche:assembleDebugAndroidTest     # from the repo root; needs rustup's cargo + JDK 21
device-probe/pull.sh previous-run          # collects the log, traces, qlog and any previous/ runs
device-probe/install.sh                    # proves the APK on the phone by sha256
device-probe/doze.sh                       # Doze / App Standby exemption
device-probe/preflight.sh                  # Tailscale OFF, route, install, notifications, whitelist, battery
```

## What a run leaves behind

Three records, kept side by side so a bug in any one instrument leaves the others standing:

- `quic-handoff-probe.log` — the human record. What happened, in order, as this probe understood it.
- `traces/conn-NNNN.trace` — **one replayable trace per connection**, in the v1 grammar: every
  datagram's bytes and path, path stats, state and path transitions, migrations, the network
  observations. Feed it to `TraceToFixture` and the connection replays through the sim in virtual
  time, on every platform, forever. A field bug becomes a committed regression test instead of
  another walk.
- `qlog/conn-NNNN.sqlog` — **quiche's own frame-level record** (packets, frames, recovery,
  congestion, transport parameters), decrypted by quiche itself, one per connection and named to
  pair with that connection's `traces/conn-NNNN.trace`. This is the one record that does not pass
  through this library's code, so it is what a bug in the trace or the log is checked against.
  ~1.4 GB per 75 h at 250 ms, however many connections the walk splits across.

`pull.sh` fetches both and says loudly if the traces are missing. The trace costs **~7.9 MB/hour at
this rig's 250ms cadence** — measured on device, 574 bytes per echo exchange — so the 75-hour run
below is ~590 MB. The probe derives its own budget from `<minutes>` and `[echoIntervalMs]` and prints
it as `TRACE-BUDGET`; override with a 3rd argument to `start.sh` only if you want a different one.

⚠️ The ~1.5 MB/hour an earlier revision of this file quoted was computed against the probe's *own*
2s default, not the 250ms this script sends — 8x out, which put the flat 512 MB default's exhaustion
at hour 65 of a 75-hour walk. The tail is exactly where a handoff is most likely, so the part that
stopped being replayable was the part worth having.

A START no longer deletes anything: whatever the previous run left (log, traces, qlog) moves to
`previous/<stamp>/` and the new log opens with a `PREVIOUS-RUN kept …` line. `pull.sh` collects the
current run, its qlog, and every previous run, and only then removes the previous runs from the
phone. Pulling first is still the habit worth keeping; forgetting is no longer fatal.

## Reading the echo lines

Echoes ride a reliable, ordered QUIC stream, so while a connection lives a reply cannot be lost —
only late. Each exchange gets exactly one verdict, given when its reply arrives:

- `ECHO-OK seq=N rtt=Rms` — answered inside the deadline.
- `ECHO-LATE seq=N rtt=Rms late=+Xms deadline=Dms` — answered, but after the deadline. Still every byte.
- `ECHO-OVERDUE seq=N waited=Wms deadline=Dms` — not yet answered and past its deadline; the
  verdict is still open. Printed once per exchange, at the moment it crosses.
- `ECHO-UNANSWERED count=N first=A last=B` — what was still owed when the connection ended. These
  are the exchanges that actually failed.
- `ECHO-FAIL seq=N err=…` — the read threw something other than its deadline: a real error, and
  when it is the connection dying a `CONNECTION-DEAD` follows.

The deadline is the path's own probe timeout (RFC 9002 §6.2.1), computed from the round trips the
probe measures, so it is ~1 s before the first reply and settles to a few times the smoothed RTT.
A coalesced read (several replies in one chunk) still gives every exchange its own round trip.

## Address families: one walk covers both

Through 2026-09 each phone was pinned to one server address — the iPhone to
`2a01:4ff:f4:eb1a::1`, this phone to `178.156.248.95` — so address family was confounded with
device. Any difference between the two recordings could be the family or the phone, and nothing in
either log could tell them apart.

The probe therefore rotates its target **per connection attempt**: attempt 1 takes the first target,
attempt 2 the second, and so on round. One device, one route, both families.

```bash
SERVER_HOST="178.156.248.95,2a01:4ff:f4:eb1a::1" device-probe/start.sh 4500 250    # Android
ios-probe/device/launch.sh "178.156.248.95,2a01:4ff:f4:eb1a::1"                   # iOS
```

**The separator is a comma**, never a colon — an IPv6 literal is made of colons. Quote the list: it
is one argument, and it reaches the Android probe through `am instrument -e probeHost` and the iOS
app through `-host` in UserDefaults' argument domain. One host is one target on every attempt,
exactly as every walk before this.

⚠️ There is deliberately **no fallback inside an attempt**. This is not Happy Eyeballs. A target the
device cannot reach must fail *its own* attempt and be recorded against itself; the next attempt
moves to the next target. A probe that quietly retried the other family would hide the one thing the
rotation exists to measure — a family that does not work on some network.

The QUIC client cannot do this itself: every platform's builder resolves the hostname internally, so
`TransportConfig.nameResolution` and the connect racer reach TCP only. That gap is issue #615; until
it closes, the family is the probe's to choose and to record.

Every line that can be attributed to a family carries it, in the log's own `key=value` style:

```
START … targets=178.156.248.95:44433/v4,[2a01:4ff:f4:eb1a::1]:44433/v6 minutes=4500 …
CONNECT-ATTEMPT n=7 target=[2a01:4ff:f4:eb1a::1]:44433 family=v6
MIGRATION-LEDGER connection=7 family=v6 attempts=3 succeeded=2 outcomes=[…]
ECHO-LIVENESS connection=7 family=v6 LIVE — answered=…
447-VERDICT connection=7 family=v6 PASS — …
```

An IPv6 literal is bracketed in `target=` so its own colons cannot be read as the port separator.
`family=` is `v4` or `v6` for a literal and `resolver` for a name — a name's family is whichever the
platform resolver picks, which the probe cannot know, so it says so instead of guessing.

`analyze.py` groups the per-connection reporting by that key and prints a per-family summary. A log
written before the rotation carries no `family=` at all: it groups under `unknown` and everything
else analyses exactly as before.

## Dry run (about 15 minutes)

```bash
device-probe/start.sh 20 250               # minutes, echo cadence ms
device-probe/toggle.sh 2 45                # wifi off/on + airplane on/off, twice
device-probe/dryrun-doze.sh 180            # 3 minutes of forced deep idle (a hotel night)
device-probe/status.sh
```

Pass: `migrated` rises on each Wi-Fi toggle, `dead` rises once per airplane window and `attempts`
shows the backed-off reconnects, `broken` stays 0, echoes keep flowing through the idle window,
and the `HEARTBEAT` RSS goes up and *down* (a sawtooth, not a ramp).

## The real run

```bash
device-probe/start.sh 4500 250             # 75 hours
device-probe/status.sh                     # then unplug; the probe keeps going
```

Keep the phone charging whenever possible (Doze never starts on power) and Tailscale off (with it
on there is nothing to migrate). Airplane mode is fine: the probe backs off to one attempt a minute
and reconnects when a route returns.

## After the run

```bash
device-probe/pull.sh walk
device-probe/analyze.py device-probe/logs/<stamp>-walk.log
device-probe/trace-dump.py device-probe/logs/<stamp>-walk-traces/conn-0007.trace --from 12.7 --to 44 --datagrams
```

`trace-dump.py` reads one connection's replay trace as a timeline: every non-datagram event, the
per-path datagram tallies, and with `--datagrams` each datagram's direction, size, local path and the
DCID from its plaintext header — which is how a failed handoff is read without the TLS keys: a probe
is a new local port, and its DCID is the spare the pool spent on it.

### A failing window as a committed fixture

`TraceToFixture.window(events, from)` (jvmTest) takes the input events from `from` on, re-based so
`from` is the fixture's t0, and `generateKotlin` writes the `simFixture` source. The two walk fixtures
in `socket-quic-quiche/src/commonTest/.../sim/fixtures/` were made this way, from the last inbound
datagram of each connection to its close:

| fixture | trace | `from` (ns) |
|---|---|---|
| `Walk20260912Conn7DeadLinkHandoff` | `ios-probe/device/logs/20260919T153909Z-walk-0912-0915-traces/conn-0007.trace` | `13066795292` |
| `Walk20260910Conn1DeadLinkHandoff` | `ios-probe/device/logs/20260911T155035Z-walk-2026-09-10-traces/conn-0001.trace` | `1572876724625` |

The windows themselves (`awk '$2>=<from>'` over the trace, ~30 KB each) are committed under
`socket-quic-quiche/src/jvmTest/resources/walk-traces/`, and `WalkFixtureRegenerationTests` proves
the committed fixture source is byte-identical to `TraceToFixture`'s output over them — so the pulled
traces are not needed to check or regenerate a fixture, only to cut a new window.

The analyzer prints every connection and why it ended, every migration and how long it took, the
echo counts (late and unanswered separately from failed), RTT and lateness percentiles, the memory
trend, a **capture health** section (was the trace budget spent, did the heartbeat ever stop, does
every connection have a trace file), and every `STREAM-INTEGRITY-BROKEN` / `CONNECTION-DEAD` line
verbatim. Those last two are the lines that turn into issues.

It closes with a **per-family summary** — attempts, connections established, migrations, echoes, the
re-derived `#447` verdicts and the echo-liveness verdicts, each grouped by the family of the target
that connection walked. That is the line that answers "does v6 behave differently from v4 on this
device and this route", and a family whose `attempts` far exceed its `connected` is a family the
network would not carry.
