---
sidebar_position: 1
title: JVM & Android
---

# JVM & Android

On JVM and Android, socket uses Java NIO2's `AsynchronousSocketChannel` with a fallback to `SocketChannel` (NIO).

## Implementation Details

The JVM and Android targets share code via a custom `commonJvmMain` source set:

- **Primary**: `AsynchronousSocketChannel` (NIO2) - fully async, callback-based
- **Fallback**: `SocketChannel` (NIO) - for environments where NIO2 is unavailable
- **Buffer allocation**: Uses `BufferFactory.deterministic()` by default for I/O buffers, providing direct `ByteBuffer` with explicit cleanup. This is required because the TLS handler (`SSLEngine`) and NIO channels need native memory access via `nativeAddress`.

## TLS

TLS is implemented using Java's `SSLEngine`, which wraps the underlying NIO socket channels. The `SSLClientSocket` class handles the TLS handshake and encrypt/decrypt pipeline.

## Requirements

- **JVM**: Java 8+ (bytecode target)
- **Android**: API 21+ (minSdk)
- **Build**: JDK 21 (Gradle toolchain)

## Source Sets

```
commonJvmMain (shared JVM code)
├── jvmMain
└── androidMain
```

## Shrinkers (ProGuard / R8) on the JVM

Several JVM artifacts are **multi-release JARs**: they carry a `META-INF/versions/21`
tier holding the FFM (Project Panama) implementations, which the JVM selects
automatically on JDK 21+. At 4.2.0 that is 11 versioned entries in
`socket-quic-quiche-jvm` and 16 in `network-monitor-jvm`.

That tier interacts badly with shrinkers, in two stages. **This section applies to
JVM consumers only** — the Android artifacts contain no versioned tier, so an
Android/R8 build never encounters either problem.

### 1. A shrinker silently drops the versioned tier

ProGuard does not read `META-INF/versions/**` as classes. It copies those entries
through or discards them, but never processes them — so after shrinking, the FFM
implementations are simply **gone**, and the JVM falls back to the base tier. There
is no error and no warning. The symptoms are a silent behavioural downgrade:
the polling network monitor instead of the event-driven one, and the JNI quiche
backend instead of FFM.

Adding `-keep` rules does **not** fix this. The classes are never read in the first
place, so there is nothing for a keep rule to match.

The fix is to resolve the versioned tier onto the JAR root *before* the shrinker
runs — flatten `META-INF/versions/N/**` (for every N at or below your runtime) over
the root entries, then feed the flattened JAR to ProGuard. Verify by checking that
the FFM class names appear in the resulting `mapping.txt`; if they are absent, the
tier was dropped.

Note that this is what the JVM would have done anyway at runtime. Flattening only
makes the shrinker agree with the runtime.

### 2. Flattening surfaces unresolvable `MethodHandle` references

Once the tier is visible, ProGuard reports unresolved library members and aborts
with `Please correct the above warnings first`. Measured against 4.2.0 with the FFM
tiers flattened, the complete set is **38 warnings across exactly two classes**:

| Class | Artifact | Warnings |
|---|---|---|
| `com.ditchoom.socket.quic.FfmQuicheApi` | `socket-quic-quiche-jvm` | 34 |
| `com.ditchoom.socket.Libc` | `network-monitor-jvm` | 4 |

Every one is of the form `can't find referenced method '… invokeExact(…)' in library
class java.lang.invoke.MethodHandle`.

**These are false positives, and silencing them is safe.** `MethodHandle.invokeExact`
is annotated `@PolymorphicSignature`: the JVM synthesizes the exact descriptor at
each call site, so there is no literal method of that shape in `MethodHandle` for a
static analyser to resolve. The references are correct and link fine at runtime —
ProGuard simply cannot model signature polymorphism. Both classes are FFM downcall
bindings, which is why they are the only two affected.

So the complete rule set is two lines, and **this is the most you need**:

```proguard
-dontwarn com.ditchoom.socket.quic.FfmQuicheApi
-dontwarn com.ditchoom.socket.Libc
```

### Why these rules are narrow, and why that matters

Both rules are **class-scoped on purpose**. Do not widen them to
`-dontwarn com.ditchoom.**` or to whole packages, and do not reach for
`-ignorewarnings`.

The reason is concrete rather than stylistic. Before 4.2.0, the same flatten
produced *39* warnings for these two classes: the 38 above, plus one that was
genuinely real —

```
com.ditchoom.socket.quic.FfmQuicheApi: can't find referenced method
'java.lang.foreign.MemorySegment allocateUtf8String(java.lang.String)'
in library class java.lang.foreign.Arena
```

`allocateUtf8String` existed in JDK 21's preview FFM API and was renamed to
`allocateFrom` when JEP 454 finalised in JDK 22, so that tier genuinely could not
link on a JDK 22+ runtime, and the FFM loader has no JNI fallback to catch it. A
package-wide `-dontwarn` would have hidden that one warning among the 38 harmless
ones and shipped a broken backend silently. (The defect itself is fixed as of
4.2.0 — the current 38 are all benign, which is exactly why the count dropped by
one rather than by 38.)

Keeping the rules pinned to these two class names means a future genuine
unresolvable reference — a renamed JDK API, a dropped method, a new FFM binding —
still fails your build loudly, which is the behaviour you want from a backend that
cannot fall back.

### On jar-embedded rules

These artifacts deliberately do **not** ship `META-INF/proguard/*.pro`. That
convention is read by R8 under the Android Gradle Plugin, and the Android artifacts
have no versioned tier and therefore no need of it; meanwhile plain ProGuard — the
shrinker JVM/desktop builds actually use — does not read jar-embedded rules at all.
Shipping such a file would imply an automatic fix that would not happen on the only
builds that need it. Copy the two lines above into your own configuration instead.
