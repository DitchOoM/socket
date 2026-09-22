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
  congestion, transport parameters), decrypted by quiche itself, named to pair with that
  connection's `traces/conn-NNNN.trace`. This is the one record that does not pass through this
  library's code, so it is what a bug in the trace or the log is checked against. A connection's
  qlog is a run of segments — `conn-v6-0007.sqlog` (the handshake and both sides' transport
  parameters), then `conn-v6-0007_seg0002.sqlog` and on, one per hour of traffic — so a long
  connection's older hours are whole files the moment the next one starts.
  ~1.8 GB per lane per 75 h at 250 ms (1,676 bytes per echo exchange), however many connections the
  lane splits across.

- `keys/conn-v6-0007.keys` — **the TLS secrets that decrypt that connection's datagrams**, in the NSS
  key log format (`SSLKEYLOGFILE`), named by the same stem as its trace and its qlog. With them the
  trace's `DGRAM_OUT`/`DGRAM_IN` bytes open in anything that speaks RFC 9001 — Wireshark reads the
  file as its TLS *(Pre)-Master-Secret log filename*, given a pcap built from the trace's hex — with
  no code of this library in the loop (`JvmTrafficSecretsLogTests` opens every packet of a recorded
  trace this way). A secret: the probe keeps them in the app's **private** files dir (`-e probeKeyLog 1`,
  which `start.sh` passes), and `pull.sh` copies them out through `run-as` into a 0700 `…-keys/`
  directory. A key log quiche cannot open is a `TRAFFIC-SECRETS-REFUSED` line of its lane.

Each lane's qlog is held to its own budget, which the probe derives from `<minutes>` and
`[echoIntervalMs]` exactly as it derives the trace's — twice the lane's expected volume, so a walk that
goes as planned drops nothing — and together to half of what the disk had free at START once the trace
budget is set aside. Each lane prints its `lane=v6 QLOG-BUDGET …`, and START and every HEARTBEAT carry
`diskFreeMb=` (the one number about the phone that cannot be read from outside it). When a limit is
reached, what goes first is what an analysis needs least — a reconnect storm's failed handshakes, then
the oldest middle segment of any connection, then whole closed connections, oldest first; every
connection keeps its head and its latest segment — and every drop is a `QLOG-TRUNCATED` /
`QLOG-EVICTED` / `QLOG-REFUSED` line of the lane it belongs to. One lane's storm never evicts the
other's records. `analyze.py` reports them under each lane's capture health, with the probe's `build=`
(the commit it was built from).

`pull.sh` fetches all of them and says loudly if the traces are missing. The trace costs **~7.9 MB/hour at
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

## Address families: one lane per target

A walk pinned to one server address confounds address family with device, and one connection that
rotates its target per attempt tests one family for as long as the network holds still — a phone on
a stable network keeps one connection for the whole run.

The probe therefore runs **one lane per target, all at once**. Each lane keeps its own connection to
its own target for the whole walk, with its own attempts, echo session, migration ledger, reconnect
backoff, silence watchdog and stall ring, so every family is exercised on every network the device
crosses and one lane's trouble cannot hide in the other's numbers. The second lane starts half an
interval after the first, so their sends interleave.

```bash
SERVER_HOST="178.156.248.95,2a01:4ff:f4:eb1a::1" device-probe/start.sh 4500 250    # Android
ios-probe/device/launch.sh "178.156.248.95,2a01:4ff:f4:eb1a::1"                   # iOS
```

**The separator is a comma**, never a colon — an IPv6 literal is made of colons. Quote the list: it
is one argument, and it reaches the Android probe through `am instrument -e probeHost` and the iOS
app through `-host` in UserDefaults' argument domain. One host is one lane.

⚠️ A lane **never falls back** to another family. This is not Happy Eyeballs. A target the device
cannot reach fails its own lane's attempts, which back off (up to 60 s between tries) and are each
recorded; that failure is the measurement. A probe that quietly used the other family would hide
the one thing lanes exist to measure — a family that does not work on some network.

The QUIC client cannot do this itself: every platform's builder resolves the hostname internally, so
`TransportConfig.nameResolution` and the connect racer reach TCP only. That gap is issue #615; until
it closes, the family is the probe's to choose and to record.

Every line starts with its lane — the target's family, `v6-2` for a second v6 target — or `run` for
the run's own lines:

```
lane=run START … targets=178.156.248.95:44433/v4,[2a01:4ff:f4:eb1a::1]:44433/v6 minutes=4500 …
lane=run LANES v4=178.156.248.95:44433 v6=[2a01:4ff:f4:eb1a::1]:44433 staggerMs=125
lane=v6 CONNECT-ATTEMPT n=7 target=[2a01:4ff:f4:eb1a::1]:44433 family=v6
lane=v6 ECHO-OK seq=1234 rtt=44ms pending=0B
lane=v6 447-VERDICT connection=7 family=v6 PASS — …
lane=run MIGRATION-TOTALS connections=9 …        (the lanes' sum; each lane logs its own first)
```

An IPv6 literal is bracketed in `target=` so its own colons cannot be read as the port separator.
`family=` is `v4` or `v6` for a literal and `resolver` for a name — a name's family is whichever the
platform resolver picks, which the probe cannot know, so it says so instead of guessing. Trace and
qlog files are named by lane: `conn-v6-0007.trace` beside `conn-v6-0007.sqlog`.

`analyze.py` demultiplexes on the lane token, analyses each lane exactly as it analyses a whole
one-lane log, and adds a paired table: one row per handoff with what every lane's path did and how
long each went without an answered echo. A log without the token (every walk before lanes) is one
lane and analyses exactly as before; an analyzer from before lanes reads a lane log as empty rather
than merging its lanes. `status.sh` (both rigs) prints a line per lane.

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

### The server's side (#624)

`quic-echo-test` on the walk server writes its own qlog per connection (`QUIC_QLOG_DIR=/app/qlog`,
bind-mounted to `/root/quic-echo-qlog`) — the one record of a walk that does not pass through this
library's client code at all, so it is what a client-side trace or log is checked against when they
disagree.

```bash
device-probe/server-pull.sh walk logs/<stamp>-walk-qlog ../ios-probe/device/logs/<stamp>-walk-qlog
```

Copies every server qlog file to `device-probe/logs/<utc-stamp>-walk-server-qlog/` — including the
ones a live connection is still writing, as a snapshot of the bytes they held when listed, cut back to
their last complete record — with `MANIFEST.tsv` (each file's state, `settled` / `live-snapshot` /
`vanished`, and its size and mtime on the server), the server's `qlog-budget.log`, its START / READY /
`[qlog]` stdout lines in `server-stdout.txt`, and `provenance.txt` (`BUILD-INFO.txt` and the
container's image, start time and `QUIC_*` environment from `docker inspect`). It says plainly when
there is nothing to pull, when the server's START says `qlog=off`, and when its build is not stamped.

Given the device pulls' qlog directories, it pairs every lane's connection (`conn-v6-0007`) with its
server record (`quiche-server-<session>`) by the original destination CID both heads carry, and writes
`PAIRS.tsv` (`qlog-pair.py`, runnable on its own). A device connection with no server record is named
as a capture gap; a server record no device claims is another client or a health check.

The server prints `START build=<commit> port=… qlog=<dir> QLOG-BUDGET …` before READY and writes the
same line into `qlog-budget.log`, so every pulled capture names the build that wrote it. Its qlog
directory is budgeted like a probe's — `QlogBudget.forWalk` for the walk it serves, set by
`QUIC_QLOG_WALK_MINUTES` (default 4500), `QUIC_QLOG_WALK_ECHO_MS` (250) and `QUIC_QLOG_WALK_LANES`
(4: two phones, one lane per address family each): 13,808 MB, 23 MB segments, 18,000 connection
records — and the qlog an earlier server process left there counts against it. Everything it drops is
a line in `qlog-budget.log`.

`SERVER_SSH` (default `root@178.156.248.95`, or set `SERVER_USER`/`SERVER_HOST` separately) points it
at a different box. Unlike `pull.sh`, it never deletes anything on the server: what is kept there is
the server's budget's decision.

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
