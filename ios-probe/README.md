# ios-probe — the iOS real-handoff rig

Records what a **real** QUIC connection does when the path underneath it dies: a walk into an
elevator or a garage, not a Wi-Fi toggle.

This module exists in the repository, rather than in a scratch directory, because the previous
version of it did not. That one lived only as untracked files under `/tmp`, and the system's temp
cleaner destroyed the whole rig — Kotlin source, Xcode project and run script — leaving nothing but
empty directories. Everything needed to rebuild it is therefore committed here.

Not published, not wired into CI.

## Why iOS is not a redundant second Android

Apple is the platform where Network.framework was measured **not to re-home a UDP connection**: a
network change kills the datapath *under* quiche in ~2s (POSIX 57) and it never recovers, which is
why the client's datagrams ride a second `NWConnection`. That is a wholly separate code path from
Android's, and a real handoff is the only thing that exercises it.

## Why a walk and not the desk toggle rig

`adb shell cmd -w wifi set-wifi-enabled` produces a real path change, but a *clean* one: the old path
is still alive as it goes away, so every PATH_CHALLENGE is answered on the first try. Measured
2026-08-23 — 38 forced handoffs, 38 answered probes, **zero unanswered**.

That matters because the two defects this rig validates need different conditions:

- **#445** is exercised by any handoff at all.
- **#447** only bites when a probe goes **unanswered**, which needs a path that genuinely dies.

So a run of clean handoffs proves #445 and says nothing whatever about #447 — while reading exactly
as if it had validated both. The probe's ledger reports that case as `INCONCLUSIVE` out loud rather
than letting it pass as a green.

## Build and install

```bash
# 1. The Kotlin/Native framework the app links.
./gradlew :ios-probe:linkDebugFrameworkIosArm64

# 2. The Xcode project (generated — never committed).
cd ios-probe/iosApp && xcodegen generate

# 3. Build, sign, install. Works over the network once the device is paired.
DEVICE=$(xcrun devicectl list devices | awk '/iPhone/ {print $3; exit}')
xcodebuild -project QuicProbe.xcodeproj -scheme QuicProbe -configuration Debug \
  -destination "id=$DEVICE" -derivedDataPath /tmp/qp-dd -allowProvisioningUpdates build
xcrun devicectl device install app --device "$DEVICE" \
  /tmp/qp-dd/Build/Products/Debug-iphoneos/QuicProbe.app
```

⚠️ **Uninstall any older build first** (`xcrun devicectl device uninstall app --device "$DEVICE"
com.ditchoom.quicprobe`). A stale app tests unfixed client code against a fixed server, which reads
as a regression rather than as the mistake it is.

## Running a walk

Tap **Start walk**, and grant location **Always**.

The location permission is not incidental — it is what makes the recording possible. iOS suspends an
app whose screen has locked, and a coroutine `delay` does not prevent that, so the echo loop and the
QUIC keepalive would both stall the instant the phone went into a pocket. A background location
session keeps the process scheduled. Coordinates are never read, stored or logged; only a count of
updates, which appears in the log as `KEEPALIVE-STATUS … locUpdates=N`.

**If `locUpdates` is stuck at 0 after the screen locks, every gap in the log below it is an artefact
of suspension rather than a network event.** Check that before reading anything else.

Then walk somewhere the signal *dies*.

## Reading the result

The log lands in the app's Documents directory (`quic-handoff-probe.log`, reachable from the Files
app — `UIFileSharingEnabled`). Each migration attempt records the `QuicPathState` leaf that resolved
it, and each connection ends with a verdict:

- `PASS` — a probe went unanswered **and** the connection still migrated afterwards: #447 fixed in
  the field.
- `REGRESSION` — `NoSpareConnectionId` after an unanswered probe: the CID pool never came back.
- `INCONCLUSIVE` — no probe went unanswered; the walk was too clean to say anything about #447.

The verdict keys on `NoSpareConnectionId` rather than on "a later attempt reached `Probing`", for the
same reason `FailedProbeConnectionIdTestSuite` does not assert "attempt N reports
`PathNotValidated`": which failure a given attempt reports is timing, but whether the connection can
ever migrate again is not. It is also the conflation-robust signal — `pathState` is a `StateFlow`, so
a `Probing` can be conflated away, whereas `NoSpareConnectionId` is emitted *instead of* probing.
