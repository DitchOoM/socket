---
sidebar_position: 4
title: Linux (x64/arm64)
---

# Linux Native

On Linux, socket uses **io_uring** for high-performance async I/O and **OpenSSL** for TLS, providing zero-copy data transfer with Kotlin/Native.

## Supported Targets

- Linux x64 (linuxX64)
- Linux ARM64 (linuxArm64)

## Requirements

### Kernel Version

**Linux kernel 5.1 or newer** is required for io_uring support. Released in May 2019, this covers:
- Ubuntu 20.04+
- Debian 11+
- Fedora 31+
- RHEL/CentOS 8+
- Most modern distributions

Check your kernel version:
```bash
uname -r
```

### Runtime Dependencies

**None** - both OpenSSL and liburing are statically linked into the library. You only need a compatible kernel (5.1+).

### Build Dependencies (for development only)

```bash
# Debian/Ubuntu
sudo apt install liburing-dev

# Fedora/RHEL
sudo dnf install liburing-devel

# Arch Linux
sudo pacman -S liburing

# Alpine
apk add liburing-dev
```

## Implementation Details

### io_uring Async I/O

The Linux implementation uses io_uring, a modern async I/O interface that:

- Provides kernel-level async operations without syscall overhead per operation
- Supports batching multiple I/O operations
- Enables true zero-copy data transfer with proper buffer management

Key components:
- `IoUringManager` - manages the shared io_uring ring and completion dispatch
- `LinuxClientSocket` - client socket with io_uring-based connect/read/write
- `LinuxServerSocket` - server socket with io_uring-based accept

### OpenSSL TLS

TLS is provided via statically-linked OpenSSL 3.0 LTS libraries that are bundled with the library.

**Why static linking?** Kotlin/Native's toolchain bundles glibc 2.19 (from 2014), but modern Linux distributions ship OpenSSL compiled against newer glibc (2.33+). By statically linking OpenSSL built on CentOS 7 (glibc 2.17), we ensure compatibility across all systems.

The static OpenSSL libraries:
- Are built reproducibly via Docker/Podman (see `buildSrc/openssl/`)
- Support OpenSSL 3.0.16 LTS (maintained until 2026)
- Minimal build (~9MB) with only modern TLS algorithms (no legacy ciphers)
- Supports TLS 1.2/1.3 with AES-GCM, ChaCha20, ECDHE, RSA, SHA-256/384

### CA Certificates

System CA certificates are loaded from standard locations:
- `/etc/ssl/certs/ca-certificates.crt` (Debian/Ubuntu)
- `/etc/pki/tls/certs/ca-bundle.crt` (RHEL/Fedora/CentOS)
- `/etc/ssl/ca-bundle.pem` (OpenSUSE)
- `/etc/ssl/cert.pem` (Alpine/Arch)

## Performance Tuning

### Platform Socket Configuration

The io_uring settings can be configured from common code using `PlatformSocketConfig`:

```kotlin
import com.ditchoom.socket.PlatformSocketConfig
import kotlin.time.Duration.Companion.milliseconds

// Quick presets for common use cases
PlatformSocketConfig.configureForClient()  // Optimized for client apps (256 queue depth)
PlatformSocketConfig.configureForServer()  // Optimized for servers (4096 queue depth)

// Or configure manually
PlatformSocketConfig.configure {
    ioQueueDepth = 2048           // Default: 1024
    ioQueueRetries = 20           // Default: 10
    ioRetryDelay = 2.milliseconds // Default: 1ms
    readBufferSize = 131072       // Default: 64KB
}

// Reset to defaults
PlatformSocketConfig.reset()
```

#### Presets

| Preset | Queue Depth | Retries | Read Buffer | Use Case |
|--------|-------------|---------|-------------|----------|
| Default | 1024 | 10 | 64KB | General purpose |
| `configureForClient()` | 256 | 5 | 32KB | Client apps with few connections |
| `configureForServer()` | 4096 | 20 | 128KB | High-concurrency servers |

#### Manual Settings

| Setting | Default | Description |
|---------|---------|-------------|
| `ioQueueDepth` | 1024 | io_uring submission/completion queue size. Each entry uses ~64 bytes. |
| `ioQueueRetries` | 10 | Max retries when queue is temporarily full. Uses exponential backoff. |
| `ioRetryDelay` | 1ms | Base delay between retries. Actual delay is `ioRetryDelay * retryNumber`. |
| `readBufferSize` | 64KB* | Override for read buffer size. See hybrid behavior below. |

**Note:** Changes take effect on next io_uring initialization. Configure before first socket use, or call `IoUringManager.cleanup()` to force reinitialization.

### Read Buffer Size (Hybrid SO_RCVBUF)

The library uses a **hybrid approach** for read buffer sizing, similar to JVM socket behavior:

1. **By default**, the library queries the kernel's `SO_RCVBUF` socket option once at connect/accept time
2. This respects system-level tuning (e.g., `net.core.rmem_default`) without additional configuration
3. **If you explicitly set** `PlatformSocketConfig.readBufferSize` to a non-default value, it overrides the SO_RCVBUF query

```kotlin
// Option 1: Let the library use SO_RCVBUF (recommended)
// No configuration needed - uses kernel's socket receive buffer size

// Option 2: Explicit override
PlatformSocketConfig.configure {
    readBufferSize = 131072  // Forces 128KB buffers regardless of SO_RCVBUF
}
```

This design provides:
- **Zero-config optimization**: Applications automatically benefit from system-level socket tuning
- **Explicit control**: Developers can override when specific buffer sizes are needed
- **Efficiency**: SO_RCVBUF is queried once per socket (single `getsockopt` syscall at connect/accept time)

### System Buffer Sizes

For high-throughput applications, tune the system-level socket buffer sizes:

```bash
# View current settings
sysctl net.core.rmem_default
sysctl net.core.rmem_max

# Increase receive buffer (requires root)
sudo sysctl -w net.core.rmem_default=262144  # 256KB default
sudo sysctl -w net.core.rmem_max=16777216    # 16MB max

# Make persistent (add to /etc/sysctl.conf)
echo "net.core.rmem_default = 262144" | sudo tee -a /etc/sysctl.conf
echo "net.core.rmem_max = 16777216" | sudo tee -a /etc/sysctl.conf
```

With the hybrid approach, the library automatically picks up these system settings - no need to mirror them in `PlatformSocketConfig`.

## Graceful Degradation

If io_uring is not available (kernel < 5.1), socket initialization will fail with a clear error message. There is currently no fallback to poll/select - io_uring is required.

To check if your system supports io_uring:
```bash
# Should return 0 if io_uring is available
cat /proc/sys/kernel/io_uring_disabled 2>/dev/null || echo "io_uring available"
```

### High Load Handling

When the io_uring submission queue is full (more concurrent operations than `ioQueueDepth`), the library will:

1. Retry with exponential backoff (1ms, 2ms, 4ms, ...)
2. Retry up to `ioQueueRetries` times (default: 10)
3. Throw `SocketException` with a clear message if retries are exhausted

This provides graceful handling of burst traffic. For sustained high load, increase `ioQueueDepth` via `PlatformSocketConfig`.

## Building

```bash
# Run x64 tests (on any Linux x64 machine)
./gradlew linuxX64Test

# Run ARM64 tests (requires ARM64 machine or cross-compilation headers)
./gradlew linuxArm64Test

# Build for release
./gradlew linuxX64MainKlibrary linuxArm64MainKlibrary
```

### Cross-compilation (x64 host building for ARM64)

To build/test ARM64 targets on an x64 machine, install cross-compilation tools:

```bash
# Ubuntu/Debian
sudo apt install gcc-aarch64-linux-gnu libc6-dev-arm64-cross

# Then you can build ARM64 targets
./gradlew linuxArm64MainKlibrary
```

## Rebuilding OpenSSL (maintainers only)

If you need to update the bundled OpenSSL version:

```bash
# Update version in gradle/libs.versions.toml first, then:

# Build for current architecture
./buildSrc/openssl/build-openssl.sh

# Build for specific architecture
./buildSrc/openssl/build-openssl.sh x64
./buildSrc/openssl/build-openssl.sh arm64

# Build for both (requires ARM64 cross-compiler for arm64 on x64)
./buildSrc/openssl/build-openssl.sh all

# Verify the build
cat libs/openssl/linux-x64/VERSION
cat libs/openssl/linux-arm64/VERSION
```

Requirements:
```bash
# Basic build tools
sudo apt install build-essential perl wget

# For ARM64 cross-compilation on x64
sudo apt install gcc-aarch64-linux-gnu
```

The build process:
1. Downloads OpenSSL source with SHA256 verification
2. Configures with minimal TLS build (no legacy algorithms)
3. Builds static libraries with `-fPIC`
4. Outputs to `libs/openssl/linux-{x64,arm64}/`

Anyone can rebuild and verify the checksums match.
