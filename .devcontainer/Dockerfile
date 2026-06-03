# Canonical Linux build toolchain for com.ditchoom:socket (incl. socket-quic).
#
# This is the single source of truth for the native toolchain the build needs —
# Rust → quiche → BoringSSL, cargo-ndk, cross-compilers, liburing, JDK 17+21.
# It mirrors .github/workflows/build-linux.yaml so local dev, Codespaces, and a
# future CI base image all share one definition. CI workflows are NOT changed by
# adding this file (see .devcontainer/README.md).
#
# Multi-arch: builds natively on linux/amd64 and linux/arm64 (e.g. Apple's
# `container` CLI on Apple silicon). Caveat: the Android NDK ships x86_64-only
# Linux host tools, so Android JNI builds require an amd64 image — see README.
FROM ubuntu:24.04

ARG DEBIAN_FRONTEND=noninteractive

# --- Base build toolchain (mirrors build-linux.yaml "Install ... toolchains") ---
RUN apt-get update && apt-get install -y --no-install-recommends \
        build-essential perl pkg-config cmake nasm \
        git curl wget ca-certificates gnupg unzip zip xz-utils \
        gcc-aarch64-linux-gnu g++-aarch64-linux-gnu \
        gcc-mingw-w64-x86-64 g++-mingw-w64-x86-64 \
        liburing-dev \
    && rm -rf /var/lib/apt/lists/*

# --- JDK 17 + 21 (Temurin). Build/toolchain runs on 21 (FFM bindings); 17 lets
#     the JNI test step run on a JDK < 21 launcher, as build-linux.yaml does. ---
RUN mkdir -p /etc/apt/keyrings \
    && curl -fsSL https://packages.adoptium.net/artifactory/api/gpg/key/public \
        | gpg --dearmor -o /etc/apt/keyrings/adoptium.gpg \
    && echo "deb [signed-by=/etc/apt/keyrings/adoptium.gpg] https://packages.adoptium.net/artifactory/deb $(. /etc/os-release && echo $VERSION_CODENAME) main" \
        > /etc/apt/sources.list.d/adoptium.list \
    && apt-get update && apt-get install -y --no-install-recommends temurin-17-jdk temurin-21-jdk \
    && rm -rf /var/lib/apt/lists/* \
    # Arch-independent symlinks so JAVA_HOME is stable across amd64/arm64.
    && ln -s /usr/lib/jvm/temurin-17-jdk-* /opt/java-17 \
    && ln -s /usr/lib/jvm/temurin-21-jdk-* /opt/java-21

# Default to JDK 21 (required to compile the FFM/Panama quiche bindings).
ENV JAVA_HOME=/opt/java-21
ENV PATH=${JAVA_HOME}/bin:${PATH}

# --- Rust stable + cross targets + cargo-ndk (mirrors build-linux.yaml) ---
ENV RUSTUP_HOME=/usr/local/rustup \
    CARGO_HOME=/usr/local/cargo \
    PATH=/usr/local/cargo/bin:${PATH}
RUN curl --proto '=https' --tlsv1.2 -sSf https://sh.rustup.rs \
        | sh -s -- -y --default-toolchain stable --profile minimal \
    && rustup target add \
        aarch64-unknown-linux-gnu x86_64-pc-windows-gnu \
        aarch64-linux-android armv7-linux-androideabi \
        x86_64-linux-android i686-linux-android \
    && cargo install cargo-ndk --quiet \
    && chmod -R a+rwX /usr/local/rustup /usr/local/cargo

# --- Android SDK + NDK (compileSdk 36, minSdk 21/24). cmdline-tools + sdkmanager
#     are Java-based (arch-independent); the NDK host toolchain is x86_64-only. ---
ENV ANDROID_HOME=/opt/android-sdk \
    ANDROID_SDK_ROOT=/opt/android-sdk
ARG ANDROID_NDK_VERSION=27.2.12479018
RUN mkdir -p ${ANDROID_HOME}/cmdline-tools \
    && cd ${ANDROID_HOME}/cmdline-tools \
    && curl -fsSL -o tools.zip https://dl.google.com/android/repository/commandlinetools-linux-11076708_latest.zip \
    && unzip -q tools.zip && rm tools.zip && mv cmdline-tools latest \
    && yes | ${ANDROID_HOME}/cmdline-tools/latest/bin/sdkmanager --licenses > /dev/null \
    && ${ANDROID_HOME}/cmdline-tools/latest/bin/sdkmanager \
        "platform-tools" "platforms;android-36" "build-tools;36.0.0" "ndk;${ANDROID_NDK_VERSION}" > /dev/null
ENV ANDROID_NDK_HOME=${ANDROID_HOME}/ndk/${ANDROID_NDK_VERSION}
ENV PATH=${PATH}:${ANDROID_HOME}/platform-tools:${ANDROID_HOME}/cmdline-tools/latest/bin

# Kotlin/JS tests: Gradle provisions its own Node; browser tests additionally
# need a Chrome/Chromium on PATH (install per host if you run jsBrowserTest).

WORKDIR /workspace
