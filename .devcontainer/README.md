# Dev container — canonical Linux build toolchain

[`Dockerfile`](./Dockerfile) is the **single source of truth** for the native
toolchain this project's build needs (Rust → quiche → BoringSSL, `cargo-ndk`,
cross-compilers, `liburing`, JDK 17 + 21). It mirrors the steps in
[`.github/workflows/build-linux.yaml`](../.github/workflows/build-linux.yaml) so
local dev, Codespaces, and (later) CI can share one definition.

> **CI is intentionally unchanged.** `build-linux.yaml` still installs its own
> toolchain today. This image is the canonical list to keep it in sync against,
> and a future change can have CI build/run inside this image — one place to edit
> for both GitHub and local. Editing the toolchain? Update this `Dockerfile`.

## Run it

Apple silicon (native `linux/arm64`, no Docker Desktop) — Apple's `container` CLI:

```sh
container build -t ditchoom-socket -f .devcontainer/Dockerfile .
container run --rm -it -v "$PWD":/workspace -w /workspace ditchoom-socket \
  ./gradlew :socket-quic:jvmTest jsNodeTest
```

Docker (any host):

```sh
docker build -t ditchoom-socket -f .devcontainer/Dockerfile .
docker run --rm -it -v "$PWD":/workspace -w /workspace ditchoom-socket \
  ./gradlew :socket-quic:jvmTest jsNodeTest
```

VS Code / Codespaces: "Reopen in Container" picks up `devcontainer.json`
(JDK 21 default, cached `~/.gradle` / `~/.konan` / cargo registry volumes).

`JAVA_HOME` defaults to **JDK 21** (required to compile the FFM/Panama quiche
bindings). JDK 17 is at `/opt/java-17` for the JNI-on-JDK<21 test path.

## What builds where (architecture note)

| Target | linux/arm64 (Apple `container`, native) | linux/amd64 |
| --- | --- | --- |
| JVM (JNI + FFM), Kotlin/Native Linux, Kotlin/JS (`jsNodeTest`) | ✅ native | ✅ |
| Android JNI (`buildAndroidJni*`, `cargo-ndk`) | ⚠️ NDK host tools are **x86_64-only** | ✅ |
| Apple targets (macOS/iOS) | ❌ needs a macOS host (Xcode / Network.framework) | ❌ |

Android NDK ships no `linux-aarch64` host toolchain, so on a native arm64
container Android JNI builds won't run. For Android work, use an amd64 image
(`--platform linux/amd64`, emulated on Apple silicon) or build Android on an
x86_64 host / CI. Everything else builds natively on arm64.

Browser-based Kotlin/JS tests (`jsBrowserTest`) additionally need Chrome/Chromium
on `PATH`; `jsNodeTest` uses the Node that Gradle provisions.
