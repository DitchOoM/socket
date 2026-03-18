# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

Kotlin Multiplatform library (`com.ditchoom:socket`) for cross-platform socket networking. Provides suspend-based async socket I/O with platform-native implementations.

## Build Commands

```bash
# Build all targets
./gradlew build

# Run all tests
./gradlew allTests

# Run platform-specific tests
./gradlew jvmTest
./gradlew jsNodeTest
./gradlew macosArm64Test        # or macosX64Test
./gradlew iosSimulatorArm64Test

# Lint
./gradlew ktlintCheck
./gradlew ktlintFormat           # auto-fix

# Run a single test class (JVM example)
./gradlew jvmTest --tests "com.ditchoom.socket.SimpleSocketTests"

# Publish to local Maven
./gradlew publishToMavenLocal
```

Requires JDK 21 (configured via toolchain). Apple targets only build on macOS.

## Architecture

### expect/actual Pattern

The library uses Kotlin's `expect`/`actual` mechanism for platform-specific socket allocation:

- `src/commonMain/` - Shared interfaces and `expect` declarations
- `src/commonJvmMain/` - JVM/Android implementations (shared via custom source set hierarchy)
- `src/appleNativeImpl/` - Apple platforms using Network.framework via Swift cinterop
- `src/jsMain/` - Node.js `net.Socket` implementation (browser throws `UnsupportedOperationException`)

### Core Types (in `com.ditchoom.socket`)

- `ClientSocket` - Main client interface; extends `Reader`, `Writer`, `SuspendCloseable`
- `ServerSocket` - Server that emits `Flow<ClientToServerSocket>` from `bind()`
- `ClientToServerSocket` - Server-side client connection
- `SocketOptions` - TCP options (noDelay, keepAlive, buffer sizes)
- `ConnectionState` - Sealed interface: `Initialized`, `Connecting`, `Connected`, `Disconnected`

### Reader/Writer (in `com.ditchoom.data`)

- `Reader` - `suspend fun read()`, `readString()`, `readFlow()`, `readFlowString()`
- `Writer` - `suspend fun write()`, `writeString()`
- Uses `ReadBuffer`/`WriteBuffer` from the `com.ditchoom:buffer` dependency

### Platform Implementations

| Platform | Implementation | Source Set |
|----------|---------------|------------|
| JVM | `AsynchronousSocketChannel` (NIO2), fallback to `SocketChannel` (NIO) | `commonJvmMain` |
| Android | Same as JVM (shared via `commonJvmMain` source set) | `commonJvmMain` + `androidMain` |
| Apple | `NWConnection` via Swift wrapper built with `buildSwift.sh` | `appleNativeImpl` |
| Node.js | `net.Socket` API | `jsMain` |

### Source Set Hierarchy

```
commonMain
├── commonJvmMain (custom - shared JVM code)
│   ├── jvmMain
│   └── androidMain
├── jsMain
└── [apple targets] ← each includes src/appleNativeImpl/kotlin via srcDir
```

The `commonJvmMain`/`commonJvmTest` source sets are manually created (not part of the default hierarchy template) to share NIO/NIO2 code between JVM and Android.

### Apple/Swift Interop

Apple targets use a Swift wrapper library built by `buildSwift.sh`. The cinterop is configured per-target in `build.gradle.kts` via `configureSocketWrapperCinterop()`. Swift source lives in `src/nativeInterop/cinterop/swift/`.

### TLS/SSL

- JVM: `SSLClientSocket` wraps NIO sockets with `SSLEngine`
- Apple: Handled natively by Network.framework
- Enabled by passing `tls = true` to `ClientSocket.connect()` or `ClientSocket.allocate()`

## Testing

Tests in `src/commonTest/` run on all platforms. They use real network connections (echo servers via `ServerSocket.allocate()` + `bind()`). Platform-specific test helpers use `expect`/`actual` in test source sets.

Key test files: `SimpleSocketTests`, `DataIntegrityTests`, `ErrorHandlingTests`, `ResourceCleanupTests`, `TlsErrorTests`.

## CI/CD

- PR validation: `review.yaml` runs `./gradlew check` on Ubuntu (JVM/JS/Android) and macOS (Apple)
- Release: `merged.yaml` publishes to Maven Central on merge to main; version bumps controlled by PR labels (`major`, `minor`, or patch by default)
