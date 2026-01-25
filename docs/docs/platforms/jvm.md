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
