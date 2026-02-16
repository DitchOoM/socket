#!/bin/bash
#
# Build OpenSSL static libraries for Kotlin/Native
# Works on any Linux system with build-essential and perl installed.
#
# USAGE:
#   ./build-openssl.sh              # Build for current architecture
#   ./build-openssl.sh x64          # Build for x64
#   ./build-openssl.sh arm64        # Build for ARM64
#   ./build-openssl.sh all          # Build for both (requires both toolchains)
#
# REQUIREMENTS:
#   sudo apt install build-essential perl
#   # For ARM64 cross-compilation on x64:
#   sudo apt install gcc-aarch64-linux-gnu
#
# UPDATE PROCESS:
#   1. Update version in gradle/libs.versions.toml
#   2. Run: ./build-openssl.sh
#   3. Commit: git add libs/openssl && git commit -m "Update OpenSSL to X.Y.Z"
#

set -e

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "$SCRIPT_DIR/../.." && pwd)"
BUILD_DIR="/tmp/openssl-build-$$"

# Read version from gradle/libs.versions.toml
OPENSSL_VERSION=$(grep '^openssl = ' "$PROJECT_ROOT/gradle/libs.versions.toml" | cut -d'"' -f2)
OPENSSL_SHA256=$(grep '^opensslSha256 = ' "$PROJECT_ROOT/gradle/libs.versions.toml" | cut -d'"' -f2)

if [ -z "$OPENSSL_VERSION" ] || [ -z "$OPENSSL_SHA256" ]; then
    echo "ERROR: Could not read OpenSSL version from gradle/libs.versions.toml"
    exit 1
fi

echo "OpenSSL version: $OPENSSL_VERSION"
echo "SHA256: $OPENSSL_SHA256"

build_openssl() {
    local ARCH=$1
    local OUTPUT_DIR="$PROJECT_ROOT/libs/openssl/linux-$ARCH"
    local OPENSSL_TARGET
    local CROSS_COMPILE=""

    if [ "$ARCH" = "arm64" ]; then
        OPENSSL_TARGET="linux-aarch64"
        # Check if we need cross-compilation
        if [ "$(uname -m)" != "aarch64" ]; then
            CROSS_COMPILE="aarch64-linux-gnu-"
            if ! command -v ${CROSS_COMPILE}gcc &> /dev/null; then
                echo "ERROR: Cross-compiler not found. Install with:"
                echo "  sudo apt install gcc-aarch64-linux-gnu"
                exit 1
            fi
        fi
    else
        OPENSSL_TARGET="linux-x86_64"
    fi

    echo "=============================================="
    echo "Building OpenSSL $OPENSSL_VERSION for linux-$ARCH"
    echo "=============================================="
    echo "Target: $OPENSSL_TARGET"
    echo "Output: $OUTPUT_DIR"
    [ -n "$CROSS_COMPILE" ] && echo "Cross-compile: $CROSS_COMPILE"
    echo ""

    # Create build directory
    rm -rf "$BUILD_DIR"
    mkdir -p "$BUILD_DIR"
    cd "$BUILD_DIR"

    # Download
    echo "Downloading OpenSSL $OPENSSL_VERSION..."
    wget -q "https://github.com/openssl/openssl/releases/download/openssl-${OPENSSL_VERSION}/openssl-${OPENSSL_VERSION}.tar.gz"

    # Verify
    echo "Verifying SHA256..."
    echo "$OPENSSL_SHA256  openssl-${OPENSSL_VERSION}.tar.gz" | sha256sum -c -

    # Extract
    tar xzf "openssl-${OPENSSL_VERSION}.tar.gz"
    cd "openssl-${OPENSSL_VERSION}"

    # Configure with minimal TLS build
    echo "Configuring..."
    ./Configure "$OPENSSL_TARGET" \
        ${CROSS_COMPILE:+--cross-compile-prefix=$CROSS_COMPILE} \
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
        -fPIC

    # Build
    echo "Building (this may take a few minutes)..."
    make -j$(nproc)

    # Copy output
    mkdir -p "$OUTPUT_DIR/lib"
    cp libssl.a libcrypto.a "$OUTPUT_DIR/lib/"

    # Create VERSION file
    cat > "$OUTPUT_DIR/VERSION" << EOF
OpenSSL ${OPENSSL_VERSION}
Architecture: ${ARCH}
Built on: $(date -u +%Y-%m-%dT%H:%M:%SZ)
Host: $(uname -s) $(uname -r) $(uname -m)
Compiler: $(${CROSS_COMPILE}gcc --version | head -1)
Source SHA256: ${OPENSSL_SHA256}
$(sha256sum "$OUTPUT_DIR/lib/libssl.a" "$OUTPUT_DIR/lib/libcrypto.a")
EOF

    # Cleanup
    rm -rf "$BUILD_DIR"

    echo ""
    echo "=============================================="
    echo "BUILD SUCCESSFUL ($ARCH)"
    echo "=============================================="
    cat "$OUTPUT_DIR/VERSION"
    echo ""
    echo "Files:"
    ls -lh "$OUTPUT_DIR/lib/"*.a
    echo ""
}

# Parse arguments
ARCH="${1:-$(uname -m | sed 's/x86_64/x64/' | sed 's/aarch64/arm64/')}"

case "$ARCH" in
    x64|x86_64)
        build_openssl "x64"
        ;;
    arm64|aarch64)
        build_openssl "arm64"
        ;;
    all)
        build_openssl "x64"
        echo ""
        build_openssl "arm64"
        ;;
    *)
        echo "Unknown architecture: $ARCH"
        echo "Usage: $0 [x64|arm64|all]"
        exit 1
        ;;
esac

echo "To commit:"
echo "  git add libs/openssl/ && git commit -m 'Update OpenSSL static libraries'"
