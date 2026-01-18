#!/bin/bash
set -e

# Build Swift wrapper for Network.framework
# This script compiles the Swift code to static libraries for each Apple platform
# and generates the Objective-C header for Kotlin/Native cinterop

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
SWIFT_SRC="$SCRIPT_DIR/src/nativeInterop/cinterop/swift"
BUILD_DIR="$SCRIPT_DIR/build/swift"
HEADER_DIR="$BUILD_DIR/include"
LIB_DIR="$BUILD_DIR/lib"

# Clean and create directories
rm -rf "$BUILD_DIR"
mkdir -p "$HEADER_DIR" "$LIB_DIR"

echo "Building Swift Socket Wrapper..."

# Function to build for a specific platform
build_platform() {
    local PLATFORM_NAME=$1
    local SDK=$2
    local TARGET=$3

    echo "  Building for $PLATFORM_NAME ($TARGET)..."

    local PLATFORM_DIR="$BUILD_DIR/$PLATFORM_NAME"
    mkdir -p "$PLATFORM_DIR"

    # Compile Swift to object file and generate header
    xcrun -sdk $SDK swiftc \
        -emit-object \
        -emit-objc-header \
        -emit-objc-header-path "$HEADER_DIR/SocketWrapper-Swift.h" \
        -module-name SocketWrapper \
        -target $TARGET \
        -O \
        -whole-module-optimization \
        "$SWIFT_SRC/SocketWrapper.swift" \
        -o "$PLATFORM_DIR/SocketWrapper.o"

    # Create static library
    xcrun -sdk $SDK ar rcs "$PLATFORM_DIR/libSocketWrapper.a" "$PLATFORM_DIR/SocketWrapper.o"

    echo "    Created $PLATFORM_DIR/libSocketWrapper.a"
}

# Build for macOS (arm64 and x86_64)
echo "Building for macOS..."
build_platform "macos-arm64" "macosx" "arm64-apple-macos11.0"
build_platform "macos-x86_64" "macosx" "x86_64-apple-macos11.0"

# Create universal macOS library
echo "Creating universal macOS library..."
mkdir -p "$LIB_DIR/macos"
lipo -create \
    "$BUILD_DIR/macos-arm64/libSocketWrapper.a" \
    "$BUILD_DIR/macos-x86_64/libSocketWrapper.a" \
    -output "$LIB_DIR/macos/libSocketWrapper.a"

# Build for iOS device (arm64)
echo "Building for iOS device..."
build_platform "ios-arm64" "iphoneos" "arm64-apple-ios13.0"
mkdir -p "$LIB_DIR/ios"
cp "$BUILD_DIR/ios-arm64/libSocketWrapper.a" "$LIB_DIR/ios/"

# Build for iOS Simulator (arm64 and x86_64)
echo "Building for iOS Simulator..."
build_platform "ios-simulator-arm64" "iphonesimulator" "arm64-apple-ios13.0-simulator"
build_platform "ios-simulator-x86_64" "iphonesimulator" "x86_64-apple-ios13.0-simulator"

# Create universal iOS Simulator library
echo "Creating universal iOS Simulator library..."
mkdir -p "$LIB_DIR/ios-simulator"
lipo -create \
    "$BUILD_DIR/ios-simulator-arm64/libSocketWrapper.a" \
    "$BUILD_DIR/ios-simulator-x86_64/libSocketWrapper.a" \
    -output "$LIB_DIR/ios-simulator/libSocketWrapper.a"

# Build for tvOS device
echo "Building for tvOS..."
build_platform "tvos-arm64" "appletvos" "arm64-apple-tvos13.0"
mkdir -p "$LIB_DIR/tvos"
cp "$BUILD_DIR/tvos-arm64/libSocketWrapper.a" "$LIB_DIR/tvos/"

# Build for tvOS Simulator
echo "Building for tvOS Simulator..."
build_platform "tvos-simulator-arm64" "appletvsimulator" "arm64-apple-tvos13.0-simulator"
build_platform "tvos-simulator-x86_64" "appletvsimulator" "x86_64-apple-tvos13.0-simulator"

mkdir -p "$LIB_DIR/tvos-simulator"
lipo -create \
    "$BUILD_DIR/tvos-simulator-arm64/libSocketWrapper.a" \
    "$BUILD_DIR/tvos-simulator-x86_64/libSocketWrapper.a" \
    -output "$LIB_DIR/tvos-simulator/libSocketWrapper.a"

# Build for watchOS device (arm64)
echo "Building for watchOS..."
build_platform "watchos-arm64" "watchos" "arm64-apple-watchos6.0"
mkdir -p "$LIB_DIR/watchos"
cp "$BUILD_DIR/watchos-arm64/libSocketWrapper.a" "$LIB_DIR/watchos/"

# Build for watchOS Simulator
echo "Building for watchOS Simulator..."
build_platform "watchos-simulator-arm64" "watchsimulator" "arm64-apple-watchos6.0-simulator"
build_platform "watchos-simulator-x86_64" "watchsimulator" "x86_64-apple-watchos6.0-simulator"

mkdir -p "$LIB_DIR/watchos-simulator"
lipo -create \
    "$BUILD_DIR/watchos-simulator-arm64/libSocketWrapper.a" \
    "$BUILD_DIR/watchos-simulator-x86_64/libSocketWrapper.a" \
    -output "$LIB_DIR/watchos-simulator/libSocketWrapper.a"

echo ""
echo "Build complete!"
echo "Header: $HEADER_DIR/SocketWrapper-Swift.h"
echo "Libraries:"
ls -la "$LIB_DIR"/*/libSocketWrapper.a 2>/dev/null || echo "  (run from project root to see paths)"
