---
sidebar_position: 2
title: Apple (iOS/macOS/tvOS/watchOS)
---

# Apple Platforms

On Apple platforms, socket uses `NWConnection` from Network.framework via a Swift interop wrapper, providing zero-copy data transfer.

## Supported Targets

- macOS (arm64, x64)
- iOS (arm64, simulator arm64, simulator x64)
- tvOS (arm64, simulator arm64, simulator x64)
- watchOS (arm64, simulator arm64, simulator x64)

## Implementation Details

The Swift wrapper (`SocketWrapper.swift`) provides:

- `ClientSocketWrapper` - outbound TCP connections
- `ServerToClientSocketWrapper` - accepted inbound connections
- `ServerListenerWrapper` - `NWListener` for accepting connections

Data is passed as `NSData` between Kotlin and Swift without copying.

## TLS

TLS is handled natively by Network.framework. When `tls = true`, `NWProtocolTLS.Options` is configured on the connection parameters.

## Building

Apple targets require macOS and Xcode. The Swift library is built by `buildSwift.sh` and linked via cinterop:

```bash
./buildSwift.sh  # builds libSocketWrapper for all Apple platforms
./gradlew macosArm64Test  # run tests
```

## Requirements

- macOS with Xcode installed
- Swift toolchain (included with Xcode)
