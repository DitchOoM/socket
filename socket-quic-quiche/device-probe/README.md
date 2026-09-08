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

`pull.sh` fetches both and says loudly if the traces are missing. Budget is ~1.5 MB/hour (`-e
probeTraceBudgetMb`, default 512), so even a 71-hour walk is ~100 MB.

⚠️ `start.sh` deletes **both** before it begins, exactly as it always has for the log. Pull first.

## Dry run (about 15 minutes)

```bash
device-probe/start.sh 20 400 250           # minutes, read deadline ms, echo cadence ms
device-probe/toggle.sh 2 45                # wifi off/on + airplane on/off, twice
device-probe/dryrun-doze.sh 180            # 3 minutes of forced deep idle (a hotel night)
device-probe/status.sh
```

Pass: `migrated` rises on each Wi-Fi toggle, `dead` rises once per airplane window and `attempts`
shows the backed-off reconnects, `broken` stays 0, echoes keep flowing through the idle window,
and the `HEARTBEAT` RSS goes up and *down* (a sawtooth, not a ramp).

## The real run

```bash
device-probe/start.sh 4500 400 250         # 75 hours
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
echo gaps, RTT percentiles, the memory trend, and every `STREAM-INTEGRITY-BROKEN` /
`CONNECTION-DEAD` line verbatim. Those last two are the lines that turn into issues.
