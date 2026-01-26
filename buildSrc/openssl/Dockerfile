# Reproducible OpenSSL Build for Kotlin/Native Compatibility
#
# This Dockerfile builds OpenSSL as a static library on CentOS 7 (glibc 2.17)
# to ensure compatibility with Kotlin/Native's bundled glibc 2.19.
#
# VERIFICATION: Anyone can rebuild this and verify the output matches:
#   podman build -t openssl-builder .
#   podman create --name tmp openssl-builder
#   podman cp tmp:/opt/openssl/lib/libssl.a .
#   podman cp tmp:/opt/openssl/lib/libcrypto.a .
#   sha256sum libssl.a libcrypto.a
#
# Build environment: CentOS 7 (glibc 2.17)
# OpenSSL version: 3.0.16 (LTS, supported until 2026-09-07)
# Source: https://github.com/openssl/openssl/releases/tag/openssl-3.0.16

FROM centos:7

# CentOS 7 reached EOL 2024-06-30, use vault mirror
RUN sed -i 's/mirrorlist/#mirrorlist/g' /etc/yum.repos.d/CentOS-* && \
    sed -i 's|#baseurl=http://mirror.centos.org|baseurl=http://vault.centos.org|g' /etc/yum.repos.d/CentOS-*

# Install build dependencies
RUN yum install -y gcc make perl-core zlib-devel wget ca-certificates && \
    yum clean all

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
RUN cd openssl-${OPENSSL_VERSION} && \
    ./Configure linux-x86_64 \
        no-shared \
        no-tests \
        --prefix=/opt/openssl \
        --libdir=lib \
        -fPIC && \
    make -j$(nproc) && \
    make install_sw

# Record build metadata
RUN echo "OpenSSL ${OPENSSL_VERSION}" > /opt/openssl/VERSION && \
    echo "Built on: $(date -u +%Y-%m-%dT%H:%M:%SZ)" >> /opt/openssl/VERSION && \
    echo "Base image: centos:7 (glibc 2.17)" >> /opt/openssl/VERSION && \
    echo "Source SHA256: ${OPENSSL_SHA256}" >> /opt/openssl/VERSION && \
    sha256sum /opt/openssl/lib/libssl.a /opt/openssl/lib/libcrypto.a >> /opt/openssl/VERSION

# Output:
#   /opt/openssl/lib/libssl.a
#   /opt/openssl/lib/libcrypto.a
#   /opt/openssl/include/openssl/*.h
#   /opt/openssl/VERSION (build metadata)
