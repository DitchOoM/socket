#!/bin/bash
#
# Build OpenSSL static libraries compatible with Kotlin/Native's glibc 2.19
# Uses podman/docker with CentOS 7 (glibc 2.17) to ensure compatibility.
#
# USAGE:
#   ./build-openssl.sh              # Build for current architecture
#   ./build-openssl.sh x64          # Build for x64
#   ./build-openssl.sh arm64        # Build for ARM64 (requires QEMU or native ARM)
#   ./build-openssl.sh all          # Build for both architectures
#
# UPDATE PROCESS:
#   1. Check latest 3.0.x LTS version: https://github.com/openssl/openssl/releases
#   2. Update version in gradle/libs.versions.toml
#   3. Run: ./build-openssl.sh all
#   4. Commit: git add libs/openssl && git commit -m "Update OpenSSL to X.Y.Z"
#
# VERIFICATION:
#   Anyone can rebuild and verify checksums match the committed VERSION files
#

set -e

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "$SCRIPT_DIR/../.." && pwd)"

# Detect container runtime
if command -v podman &> /dev/null; then
    CONTAINER_CMD="podman"
elif command -v docker &> /dev/null; then
    CONTAINER_CMD="docker"
else
    echo "ERROR: Neither podman nor docker found. Install one of them:"
    echo "  sudo apt install podman"
    echo "  # or"
    echo "  sudo apt install docker.io"
    exit 1
fi

build_openssl() {
    local ARCH=$1
    local OUTPUT_DIR="$PROJECT_ROOT/libs/openssl/linux-$ARCH"
    local PLATFORM=""
    local IMAGE_TAG="openssl-kn-builder-$ARCH"

    if [ "$ARCH" = "arm64" ]; then
        PLATFORM="--platform linux/arm64"
    fi

    echo "=============================================="
    echo "Building OpenSSL for linux-$ARCH"
    echo "=============================================="
    echo "Container runtime: $CONTAINER_CMD"
    echo "Output directory: $OUTPUT_DIR"
    echo ""

    # Build container image
    echo "Building container image..."
    $CONTAINER_CMD build $PLATFORM --build-arg TARGET_ARCH=$ARCH -t $IMAGE_TAG "$SCRIPT_DIR"

    # Create output directories
    mkdir -p "$OUTPUT_DIR/lib"

    # Extract libraries only (headers are downloaded at build time by Gradle)
    echo ""
    echo "Extracting libraries..."
    CONTAINER_ID=$($CONTAINER_CMD create $PLATFORM $IMAGE_TAG)
    $CONTAINER_CMD cp "$CONTAINER_ID:/opt/openssl/lib/libssl.a" "$OUTPUT_DIR/lib/"
    $CONTAINER_CMD cp "$CONTAINER_ID:/opt/openssl/lib/libcrypto.a" "$OUTPUT_DIR/lib/"
    $CONTAINER_CMD cp "$CONTAINER_ID:/opt/openssl/VERSION" "$OUTPUT_DIR/"
    $CONTAINER_CMD rm "$CONTAINER_ID"

    # Verify glibc compatibility (only meaningful for native arch)
    if [ "$ARCH" = "x64" ] && [ "$(uname -m)" = "x86_64" ]; then
        echo ""
        echo "Verifying glibc compatibility..."
        MAX_GLIBC=$(objdump -T "$OUTPUT_DIR/lib/libssl.a" 2>/dev/null | grep -o 'GLIBC_[0-9.]*' | sort -V | tail -1 || echo "GLIBC_2.17")
        echo "Maximum glibc required: $MAX_GLIBC"
        if [[ "$MAX_GLIBC" > "GLIBC_2.19" ]]; then
            echo "WARNING: Library requires $MAX_GLIBC which is newer than Kotlin/Native's glibc 2.19!"
            exit 1
        fi
    fi

    # Show results
    echo ""
    echo "=============================================="
    echo "BUILD SUCCESSFUL ($ARCH)"
    echo "=============================================="
    echo ""
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

echo ""
echo "To commit:"
echo "  git add libs/openssl/ && git commit -m 'Update OpenSSL static libraries'"
