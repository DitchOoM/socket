#!/bin/bash
#
# Build OpenSSL static libraries compatible with Kotlin/Native's glibc 2.19
# Uses podman/docker with CentOS 7 (glibc 2.17) to ensure compatibility.
#
# USAGE:
#   ./build-openssl.sh              # Build with default version
#   ./build-openssl.sh 3.0.17       # Build specific version
#
# UPDATE PROCESS:
#   1. Check latest 3.0.x LTS version: https://github.com/openssl/openssl/releases
#   2. Get SHA256 from release page (click on .tar.gz, copy sha256)
#   3. Update OPENSSL_VERSION and OPENSSL_SHA256 in Dockerfile
#   4. Run: ./build-openssl.sh
#   5. Commit: git add libs/openssl VERSION.txt && git commit -m "Update OpenSSL to X.Y.Z"
#
# VERIFICATION:
#   Anyone can rebuild and verify checksums match the committed VERSION.txt
#

set -e

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "$SCRIPT_DIR/../.." && pwd)"
OUTPUT_DIR="$PROJECT_ROOT/libs/openssl/linux-x64"

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

echo "=============================================="
echo "OpenSSL Static Library Build for Kotlin/Native"
echo "=============================================="
echo "Container runtime: $CONTAINER_CMD"
echo "Output directory: $OUTPUT_DIR"
echo ""

# Build container image
echo "Building container image..."
$CONTAINER_CMD build -t openssl-kn-builder "$SCRIPT_DIR"

# Create output directories
mkdir -p "$OUTPUT_DIR/lib"

# Extract libraries only (headers are downloaded at build time by Gradle)
echo ""
echo "Extracting libraries..."
CONTAINER_ID=$($CONTAINER_CMD create openssl-kn-builder)
$CONTAINER_CMD cp "$CONTAINER_ID:/opt/openssl/lib/libssl.a" "$OUTPUT_DIR/lib/"
$CONTAINER_CMD cp "$CONTAINER_ID:/opt/openssl/lib/libcrypto.a" "$OUTPUT_DIR/lib/"
$CONTAINER_CMD cp "$CONTAINER_ID:/opt/openssl/VERSION" "$OUTPUT_DIR/"
$CONTAINER_CMD rm "$CONTAINER_ID"

# Verify glibc compatibility
echo ""
echo "Verifying glibc compatibility..."
MAX_GLIBC=$(objdump -T "$OUTPUT_DIR/lib/libssl.a" 2>/dev/null | grep -o 'GLIBC_[0-9.]*' | sort -V | tail -1 || echo "GLIBC_2.17")
echo "Maximum glibc required: $MAX_GLIBC"
if [[ "$MAX_GLIBC" > "GLIBC_2.19" ]]; then
    echo "WARNING: Library requires $MAX_GLIBC which is newer than Kotlin/Native's glibc 2.19!"
    exit 1
fi

# Show results
echo ""
echo "=============================================="
echo "BUILD SUCCESSFUL"
echo "=============================================="
echo ""
cat "$OUTPUT_DIR/VERSION"
echo ""
echo "Files:"
ls -lh "$OUTPUT_DIR/lib/"*.a
echo ""
echo "To use in build.gradle.kts:"
echo '  linkerOpts("-L$projectDir/libs/openssl/linux-x64/lib", "-lssl", "-lcrypto")'
echo ""
echo "To commit:"
echo "  git add libs/openssl/ && git commit -m 'Update OpenSSL static libraries'"
