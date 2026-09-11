# Android handoff-probe rig

Hand-driven, multi-day runs of `DeviceHandoffProbe` on a real phone against the public echo server.
Everything here talks to the phone through `adb`; the probe itself runs detached and survives unplugging.

## Before a run (phone plugged in and UNLOCKED)

```bash
./gradlew :socket-quic-quiche:assembleDebugAndroidTest     # from the repo root; needs rustup's cargo + JDK 21
device-probe/pull.sh previous-run          # START wipes the log AND the replay traces — save them first
device-probe/install.sh                    # proves the APK on the phone by sha256
device-probe/doze.sh                       # Doze / App Standby exemption
device-probe/preflight.sh                  # Tailscale OFF, route, install, notifications, whitelist, battery
```

## What a run leaves behind

Two artifacts, and the second is the one that stops a walk having to be repeated:

- `quic-handoff-probe.log` — the human record. What happened, in order.
- `traces/conn-NNNN.trace` — **one replayable trace per connection**, in the v1 grammar. Feed it to
  `TraceToFixture` and the connection replays through the sim in virtual time, on every platform,
  forever. A field bug becomes a committed regression test instead of another walk.

`pull.sh` fetches both and says loudly if the traces are missing. The trace costs **~7.9 MB/hour at
this rig's 250ms cadence** — measured on device, 574 bytes per echo exchange — so the 75-hour run
below is ~590 MB. The probe derives its own budget from `<minutes>` and `[echoIntervalMs]` and prints
it as `TRACE-BUDGET`; override with a 3rd argument to `start.sh` only if you want a different one.

⚠️ The ~1.5 MB/hour an earlier revision of this file quoted was computed against the probe's *own*
2s default, not the 250ms this script sends — 8x out, which put the flat 512 MB default's exhaustion
at hour 65 of a 75-hour walk. The tail is exactly where a handoff is most likely, so the part that
stopped being replayable was the part worth having.

⚠️ `start.sh` deletes **both** before it begins, exactly as it always has for the log. Pull first.

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
```

The analyzer prints every connection and why it ended, every migration and how long it took, the
echo counts (late and unanswered separately from failed), RTT and lateness percentiles, the memory
trend, and every `STREAM-INTEGRITY-BROKEN` / `CONNECTION-DEAD` line verbatim. Those last two are
the lines that turn into issues.
