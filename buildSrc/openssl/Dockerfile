# Reproducible OpenSSL Build for Kotlin/Native Compatibility
#
# This Dockerfile builds OpenSSL as a static library with old glibc
# to ensure compatibility with Kotlin/Native's bundled glibc.
#
# For x64: Uses CentOS 7 (glibc 2.17) for K/N compatibility with glibc 2.19
# For ARM64: Uses Debian 11 (glibc 2.31) - K/N ARM64 has newer glibc requirements
#
# USAGE:
#   # Build for x64 (default):
#   podman build -t openssl-builder .
#
#   # Build for ARM64:
#   podman build --platform linux/arm64 --build-arg TARGET_ARCH=arm64 -t openssl-builder-arm64 .
#
# VERIFICATION: Anyone can rebuild this and verify the output matches:
#   podman create --name tmp openssl-builder
#   podman cp tmp:/opt/openssl/lib/libssl.a .
#   podman cp tmp:/opt/openssl/lib/libcrypto.a .
#   sha256sum libssl.a libcrypto.a
#
# OpenSSL version: 3.0.16 (LTS, supported until 2026-09-07)
# Source: https://github.com/openssl/openssl/releases/tag/openssl-3.0.16

# Target architecture: x64 or arm64
ARG TARGET_ARCH=x64

# Use CentOS 7 for x64 (glibc 2.17), Debian 11 for ARM64 (has native ARM64 support)
FROM centos:7 AS base-x64
RUN sed -i 's/mirrorlist/#mirrorlist/g' /etc/yum.repos.d/CentOS-* && \
    sed -i 's|#baseurl=http://mirror.centos.org|baseurl=http://vault.centos.org|g' /etc/yum.repos.d/CentOS-*
RUN yum install -y gcc make perl-core zlib-devel wget ca-certificates && yum clean all

FROM debian:11 AS base-arm64
RUN apt-get update && apt-get install -y build-essential perl wget ca-certificates && rm -rf /var/lib/apt/lists/*

FROM base-${TARGET_ARCH} AS builder
ARG TARGET_ARCH=x64

WORKDIR /build

# OpenSSL version and checksum for verification
# Source: https://github.com/openssl/openssl/releases/download/openssl-3.0.16/openssl-3.0.16.tar.gz
# SHA256 from: https://github.com/openssl/openssl/releases/tag/openssl-3.0.16
ARG OPENSSL_VERSION=3.0.16
ARG OPENSSL_SHA256=57e03c50feab5d31b152af2b764f10379aecd8ee92f16c985983ce4a99f7ef86

# Download and verify OpenSSL source
RUN wget -q https://github.com/openssl/openssl/releases/download/openssl-${OPENSSL_VERSION}/openssl-${OPENSSL_VERSION}.tar.gz && \
    echo "${OPENSSL_SHA256}  openssl-${OPENSSL_VERSION}.tar.gz" | sha256sum -c - && \
    tar xzf openssl-${OPENSSL_VERSION}.tar.gz

# Build static OpenSSL with PIC for Kotlin/Native compatibility
# --prefix=/opt/openssl: Install location
# --libdir=lib: Use 'lib' not 'lib64' for consistency
# no-shared: Build static libraries only
# no-tests: Skip test suite build
# -fPIC: Position Independent Code (required for linking into shared libs)
#
# Minimal TLS build - disable legacy/deprecated algorithms for smaller size:
# - no-legacy: Disable legacy provider (MD2, MD4, RIPEMD, etc.)
# - no-engine: Disable hardware engine support
# - no-comp: Disable compression (security risk anyway)
# - no-dtls*: We only need TCP sockets, not DTLS
# - no-ssl3: SSL 3.0 is insecure
# - no-idea/rc2/rc4/rc5/des/md4/mdc2/whirlpool: Legacy ciphers
# - no-psk/srp/gost: Unused authentication methods
# - no-cms/ts/ocsp/srtp: Unused protocols
RUN cd openssl-${OPENSSL_VERSION} && \
    OPENSSL_TARGET=$(if [ "$TARGET_ARCH" = "arm64" ]; then echo "linux-aarch64"; else echo "linux-x86_64"; fi) && \
    ./Configure $OPENSSL_TARGET \
        no-shared \
        no-tests \
        no-legacy \
        no-engine \
        no-comp \
        no-dtls \
        no-dtls1 \
        no-dtls1-method \
        no-ssl3 \
        no-ssl3-method \
        no-idea \
        no-rc2 \
        no-rc4 \
        no-rc5 \
        no-des \
        no-md4 \
        no-mdc2 \
        no-whirlpool \
        no-psk \
        no-srp \
        no-gost \
        no-cms \
        no-ts \
        no-ocsp \
        no-srtp \
        no-seed \
        no-bf \
        no-cast \
        no-camellia \
        no-aria \
        no-sm2 \
        no-sm3 \
        no-sm4 \
        no-siphash \
        --prefix=/opt/openssl \
        --libdir=lib \
        -fPIC && \
    make -j$(nproc) && \
    make install_sw

# Record build metadata
RUN BASE_IMAGE=$(if [ "$TARGET_ARCH" = "arm64" ]; then echo "debian:11"; else echo "centos:7"; fi) && \
    echo "OpenSSL ${OPENSSL_VERSION}" > /opt/openssl/VERSION && \
    echo "Architecture: ${TARGET_ARCH}" >> /opt/openssl/VERSION && \
    echo "Built on: $(date -u +%Y-%m-%dT%H:%M:%SZ)" >> /opt/openssl/VERSION && \
    echo "Base image: $BASE_IMAGE" >> /opt/openssl/VERSION && \
    echo "Source SHA256: ${OPENSSL_SHA256}" >> /opt/openssl/VERSION && \
    sha256sum /opt/openssl/lib/libssl.a /opt/openssl/lib/libcrypto.a >> /opt/openssl/VERSION

# Output:
#   /opt/openssl/lib/libssl.a
#   /opt/openssl/lib/libcrypto.a
#   /opt/openssl/include/openssl/*.h
#   /opt/openssl/VERSION (build metadata)
