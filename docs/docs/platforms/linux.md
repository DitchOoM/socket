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

Install the liburing library:

```bash
# Debian/Ubuntu
sudo apt install liburing2

# Fedora/RHEL
sudo dnf install liburing

# Arch Linux
sudo pacman -S liburing

# Alpine
apk add liburing
```

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

## Graceful Degradation

If io_uring is not available (kernel < 5.1), socket initialization will fail with a clear error message. There is currently no fallback to poll/select - io_uring is required.

To check if your system supports io_uring:
```bash
# Should return 0 if io_uring is available
cat /proc/sys/kernel/io_uring_disabled 2>/dev/null || echo "io_uring available"
```

## Building

```bash
# Run tests
./gradlew linuxX64Test

# Build for release
./gradlew linuxX64MainKlibrary
```

## Rebuilding OpenSSL (maintainers only)

If you need to update the bundled OpenSSL version:

```bash
# Requires podman or docker
./buildSrc/openssl/build-openssl.sh

# Verify the build
cat libs/openssl/linux-x64/VERSION
```

The build process:
1. Uses CentOS 7 Docker image (glibc 2.17)
2. Downloads OpenSSL source with SHA256 verification
3. Builds static libraries with `-fPIC`
4. Outputs to `libs/openssl/linux-x64/`

Anyone can rebuild and verify the checksums match.
