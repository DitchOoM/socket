# Handoff — socket-quic CI flakes (next session)

## TL;DR

The 9 jvmTest flakes from the prior handoff are **not a single bug**. They are
the visible symptom of a **resource-pressure root cause**: tests across many
classes create `QuicServerEngine` / `QuicEngine` instances without calling
`close()`, accumulating threads + native heap + coroutine scopes inside the
forked test JVM. On the GH ubuntu-24.04 hosted runner (2-4 cores, 7 GB RAM)
the threshold crosses around the 130th test, and the QUIC handshake (or even
unrelated `withTimeout` watchdogs) stops getting scheduled in time. Tests pass
locally because dev boxes have so much headroom that the threshold is never
crossed even with the leak.

**Today's session shipped partial mitigation** (engine-leak `@AfterTest` cleanup
in 2 of ~10 affected suites, buffer 5.2.0 bump, cargo `target/` cache) but the
threshold is still crossed. The next session should pivot from "find and fix
the leaks one at a time" (whack-a-mole) to **API redesign** so leaks are
impossible by construction — see [Recommended direction](#recommended-direction).

## State at handoff

- **Branch:** `feature/transport-codec-api`, all pushed to PR #48.
- **Latest commit:** `3c516ea` (note: ahead, the workflow-revert + this handoff
  update will be the next commit).
- **CI:** core build green. 9 jvmTest flakes still gated via `assumeTrue` on
  `System.getenv("CI") == null || System.getenv("RUN_FLAKY_TESTS") == "1"`.

## What today (2026-05-27 session) added

### Production / dep wins (keep, don't regress)

- **`fd9c308`** — Bumped `com.ditchoom:buffer` 5.0.0 → 5.2.0. All four
  modules (`buffer`, `buffer-flow`, `buffer-codec`, `buffer-codec-processor`)
  share the same version ref; compiled clean on JVM/JS/linuxX64. Full
  socket-quic JVM suite passed with it (304 non-flaky tests).
- **Cargo `target/` cache** in `build-linux.yaml`. Caches
  `socket-quic/build/quiche` (clone + per-triple `target/<triple>/release/`)
  keyed on `quiche_jni.c` hash + `libs.versions.toml`. Cut "Build quiche
  native libs" step from ~4m23s to ~1m30s on first run; should drop further
  on warm hits.
- **`5b3905b`** — Engine-leak fix in `JvmQuicServerTestSuite` +
  `JvmQuicServerLifecycleTestSuite`. The abstract suites' `serverEngine()` /
  `clientEngine()` factory pattern was called per test but never closed; each
  test leaked an engine (scope + threads + native quiche state). Added a
  `MutableList<...>` tracker in each concrete override and an `@AfterTest`
  closing every tracked engine (with `runCatching` so tests that already
  close don't break). Locally: all 14 tests still pass in 46s.

### CI hardening (keep)

- `timeout-minutes: 8` on the main `:socket-quic:jvmTest` step. On a prior
  rerun a single test hung 53 minutes and burned the 60-min job timeout,
  wiping subsequent steps. The 8min cap is 8-16x headroom over normal duration
  (~30-60s) and prevents any future single-hang from cascading.
- `RUN_FLAKY_TESTS=1` escape hatch added to the 3 flaky-test files'
  `assumeTrue` gates (`ServerConnectionTimingTest`, `StaleConnectionDiagnosticTests`,
  `QuicStreamMuxTests`). Lets a deliberate diagnostic run bypass the skip
  without flipping the gate globally.

### What got reverted before pushing

A pile of diagnostic CI steps were used to bisect the root cause and **have
been removed**. Don't re-add them blindly — see [What we learned](#what-we-learned)
for the signal they extracted; further bisection inside this lifecycle-gap
model would be wasted.

## What we learned

### The bisection (4 CI cycles)

| Run | Setup | Result |
|---|---|---|
| `d1cf6c7` | baseline isolation (3 flaky classes alone) | ✅ 9/9 pass — leak is from earlier tests, not from canary in vacuum |
| `b689bd7` | Half A (classes 1-9) + canary; Half B (10-18) + canary | Half A: 7/9 flaky FAIL; Half B: 0/9 fail → poisoner in 1-9 |
| `24718d3` | Quarter A1 (1-5) + canary; Quarter A2 (6-9) + canary | A1: last 2 flaky FAIL; A2: clean → poisoner in 1-5 |
| `89a4f8f` | 5 per-class steps inside Quarter A1 | classes 1, 3, 4 each reproduce a hang with distinct signatures |

The bisection ran each diagnostic step as a separate `./gradlew :socket-quic:jvmTest --tests ...` invocation in the same gradle daemon. We later realized this contaminates the signal: state from earlier invocations persists in the daemon and in OS-level resources, so per-class signal is mixed with cumulative-state-from-prior-invocations.

### The actual root cause (high confidence)

1. **Tests create engines without closing them.** Abstract suites
   `QuicServerTestSuite`, `QuicServerLifecycleTestSuite` use a factory pattern
   `serverEngine()` / `clientEngine()` invoked per test. Test bodies only
   close the `server` / `connection` bound to the engine, not the engine
   itself. Each leaked engine retains a coroutine scope, ~2 worker threads,
   a native quiche state block, and a UDP wakeup pipe.
2. **Accumulation crosses a CI-runner-specific threshold.** GH Actions
   ubuntu-24.04 hosted runners have 2-4 cores and 7 GB RAM. After ~130 tests,
   coroutine dispatchers thrash and packet-loop coroutines stop getting
   scheduled in time. The 10s `withTimeout` on `awaitEstablished` fires.
3. **The hang shape: dispatcher starvation.** In run `3c516ea` we saw
   `ReactiveDriverTests.streamRecv_returns_data` hang past its OWN
   `withTimeout(2.seconds)` (last 4m+ before step timeout). That test uses a
   `StubQuicheApi` — no real engine, no leak of its own. The fact that even
   its internal watchdog couldn't fire is direct evidence the dispatcher is
   starved by upstream pressure.
4. **Local boxes have so much headroom the threshold never crosses.** A
   dev laptop (24 cores, 32 GB) absorbs the leak invisibly. CI is where the
   threshold matters.

### Why @AfterTest in 2 suites wasn't enough

We added the fix to the two `HANDOFF.md`-identified suspects:
`JvmQuicServerTestSuite` (~6 tests, 6 engines saved) and
`JvmQuicServerLifecycleTestSuite` (~8 tests, ~10 engines saved). But ~10
other test classes have the same pattern. Quick grep:

```
$ grep -rn "defaultQuicServerEngine()\|defaultQuicEngine()" \
    socket-quic/src/{commonTest,jvmTest}/kotlin
```

shows ~30+ call sites that aren't paired with `close()`. Each test leaks 1-2.

## Recommended direction

**Stop fixing leaks test-by-test. Make leaks impossible by construction.**

You already have `[[project_quic_driver_redesign]]` and
`socket-quic/DRIVER_REDESIGN.md` — the "no impossible states" thesis. Today's
session is hard evidence for *why* it matters.

Four concrete API moves, in increasing order of invasiveness:

1. **`java.lang.ref.Cleaner` safety net** (smallest). `QuicServerEngine` /
   `QuicEngine` register their native resources with `Cleaner` at construction.
   If GC'd without `close()`, the cleaner releases native memory + cancels the
   scope. Leak still happens (slightly delayed) but doesn't grow unbounded
   under JVM memory pressure.
2. **Promote engine to `SuspendCloseable`** (already used elsewhere per
   `CLAUDE.md`). Then `.use { }` blocks work. Tests need a one-line wrap
   instead of per-test tracker lists.
3. **Engine tied to a `CoroutineScope` parent.** Cancelling the parent scope
   closes the engine automatically. Tests do
   `coroutineScope { val engine = engineIn(this); ... }` and cleanup is
   automatic on scope exit.
4. **Scope-only construction.** Make `defaultQuicServerEngine()` private;
   expose only `withQuicServerEngine { engine -> ... }`. Engine reference
   can't escape the block. Tests *can't* forget to close. This is the Rust
   borrow pattern in Kotlin.

(2) and (4) eliminate the foot-gun. (1) is a JVM-only safety net. Pick the
combination that lines up with `DRIVER_REDESIGN.md`.

After the API redesign:
- Mechanical test migration (rewrite each test against the new lifecycle).
- Remove the 9 `assumeTrue` gates one by one as the suite confirms green.
- Delete the `RUN_FLAKY_TESTS=1` escape hatch.

## Don't repeat (failed approaches)

- ❌ Per-class bisection inside the same gradle daemon. The daemon retains
  state across `--tests` invocations; you can't get clean per-class signal
  this way.
- ❌ Adding `@AfterTest` engine-tracking to just the two HANDOFF suspects.
  Helps but doesn't move the threshold enough.
- ❌ Polling sweeps removing `delay()` calls. Documented in prior handoff;
  broke 8 tests when applied uniformly. The reactive-throughout work that
  landed (commit `cfadcdc`) is the production fix; tests need pragmatic
  settle-delays.

## Other open items (carried)

- `validate / Validate Artifacts` failure on empty version string
  (`com.ditchoom:socket:.`). Separate triage from this thread.
- Windows JVM Tests has a known flake on `ResourceCleanupTests.socketClosedOnException`
  (Windows NIO2 `WindowsAsynchronousServerSocketChannelImpl.implAccept` →
  `ClosedChannelException` on teardown race). Same shape as the existing
  `isWindowsJvm()` skip on `repeatedOpenClose`. If it recurs, apply the same
  skip pattern (commit reference: `5629310`).
- Main socket module: 37 `delay()` calls across 5 test files. Same
  polling-vs-reactive trap that bit us in the QUIC suite. Leave alone until
  the API redesign settles.

## Commits this session (newest first)

```
(this commit)         docs/ci: revert diagnostic noise, update HANDOFF
3c516ea               ci: FULL-suite-with-flakies diagnostic (DROPPED in revert)
5b3905b               fix(test): close engines in JVM server-suite @AfterTest
89a4f8f               ci: per-class bisection inside Quarter A1 (DROPPED)
24718d3               ci: narrow bisection into Half A, cache cargo target/
fd9c308               deps: bump com.ditchoom:buffer 5.0.0 → 5.2.0
b689bd7               ci(test): bisect — split classes 1-18 (DROPPED)
d1cf6c7               ci(test): RUN_FLAKY_TESTS bypass + isolation diagnostic
```

The "DROPPED" entries had their workflow-yaml changes reverted in the final
revert commit but the assumeTrue-bypass code change (RUN_FLAKY_TESTS env var
read) is kept in the 3 flaky-test files for future diagnostic runs.

## Next-session starter

1. Open `socket-quic/DRIVER_REDESIGN.md` and confirm whether engine lifecycle
   is already in scope or whether to extend it.
2. Pick (1) or (4) from [Recommended direction](#recommended-direction) and
   prototype on one engine (probably `JvmQuicServerEngine` since the leak is
   currently JVM-specific).
3. Migrate one test class to the new lifecycle. Validate locally + CI.
4. If CI passes the formerly-flaky tests, remove the corresponding
   `assumeTrue` gate.
5. Iterate per suite. Aim for net diff: 9 gates removed, no `RUN_FLAKY_TESTS=1`
   reference remaining, `assumeTrue` only used for genuine
   not-on-this-platform skips.
