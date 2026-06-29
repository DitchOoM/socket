import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.security.KeyStore
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.util.Base64

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.library)
    alias(libs.plugins.ksp)
    alias(libs.plugins.ktlint)
    alias(libs.plugins.maven.publish)
    signing
}

val isMacOS = org.jetbrains.kotlin.konan.target.HostManager.hostIsMac
val isLinux = org.jetbrains.kotlin.konan.target.HostManager.hostIsLinux
val isRunningOnGithub = System.getenv("GITHUB_REPOSITORY")?.isNotBlank() == true
val isMainBranchGithub = System.getenv("GITHUB_REF") == "refs/heads/main"

// quiche version — centralized in gradle/libs.versions.toml
val quicheVersion = libs.versions.quiche.get()
val quicheSha256 = libs.versions.quicheSha256.get()
val quicheBuildDir = layout.buildDirectory.dir("quiche")

repositories {
    google()
    mavenCentral()
}

// Resolve cargo binary — checks PATH, then ~/.cargo/bin
val cargoBin: String by lazy {
    val pathCargo = ProcessBuilder("which", "cargo").start()
    if (pathCargo.waitFor() == 0) {
        pathCargo.inputStream
            .bufferedReader()
            .readText()
            .trim()
    } else {
        val home = System.getProperty("user.home")
        val fallback = "$home/.cargo/bin/cargo"
        if (file(fallback).exists()) {
            fallback
        } else {
            throw GradleException("cargo not found. Install Rust: curl --proto '=https' --tlsv1.2 -sSf https://sh.rustup.rs | sh")
        }
    }
}

// --- quiche build infrastructure ---

fun downloadQuicheSource(
    buildDir: File,
    version: String,
    @Suppress("UNUSED_PARAMETER") sha256: String,
): File {
    val sourceDir = File(buildDir, "quiche-$version")

    if (sourceDir.exists()) return sourceDir

    buildDir.mkdirs()

    // git clone with --recursive to include BoringSSL submodule
    // (GitHub tarballs don't include submodules)
    logger.lifecycle("Cloning quiche $version (with BoringSSL submodule)...")
    val result =
        ProcessBuilder(
            "git",
            "clone",
            "--recursive",
            "--depth",
            "1",
            "--branch",
            version,
            "https://github.com/cloudflare/quiche.git",
            sourceDir.name,
        ).directory(buildDir)
            .inheritIO()
            .start()
            .waitFor()

    if (result != 0) {
        throw GradleException("Failed to clone quiche $version")
    }

    return sourceDir
}

/**
 * Build quiche as a static library for K/Native cinterop (Linux).
 * Uses QUICHE_BSSL_PATH to share BoringSSL with the base module (future).
 */
fun createBuildQuicheStaticTask(arch: String): TaskProvider<Task> {
    val taskName = "buildQuicheStatic${arch.replaceFirstChar { it.uppercase() }}"
    val outputDir = projectDir.resolve("libs/quiche/linux-$arch")
    // `-qlog` suffix: the qlog feature was added to the cargo build, so a lib built before it (a stale
    // `.built-<ver>` marker with no suffix) must NOT satisfy this — bumping the marker forces a rebuild
    // with qlog on every host/CI runner that cached the old output.
    val markerFile = outputDir.resolve("lib/.built-$quicheVersion-qlog")

    val cargoTarget =
        if (arch == "x64") "x86_64-unknown-linux-gnu" else "aarch64-unknown-linux-gnu"

    return tasks.register(taskName) {
        group = "build"
        description = "Build quiche static library for Linux $arch"
        inputs.property("quicheVersion", quicheVersion)
        outputs.file(markerFile)
        onlyIf { !markerFile.exists() }

        doLast {
            val buildDir = quicheBuildDir.get().asFile
            val sourceDir = downloadQuicheSource(buildDir, quicheVersion, quicheSha256)

            logger.lifecycle("Building quiche $quicheVersion (static, $arch)...")

            // Use pre-built BoringSSL from the base module (avoids rebuilding inside quiche)
            val boringsslDir = rootProject.projectDir.resolve("libs/boringssl/linux-$arch")
            val env = mutableMapOf<String, String>()
            // Note: avoid -C lto=thin / -C embed-bitcode=yes here — they conflict with
            // pre-built BoringSSL objects that lack LTO bitcode.
            env["RUSTFLAGS"] = "-C opt-level=s -C codegen-units=1 -C strip=symbols"
            if (boringsslDir.resolve("lib/libssl.a").exists()) {
                env["QUICHE_BSSL_PATH"] = boringsslDir.absolutePath
                logger.lifecycle("Using pre-built BoringSSL from ${boringsslDir.absolutePath}")
            }
            if (arch == "arm64" && System.getProperty("os.arch") != "aarch64") {
                env["CARGO_TARGET_AARCH64_UNKNOWN_LINUX_GNU_LINKER"] = "aarch64-linux-gnu-gcc"
            }

            val cargoArgs =
                mutableListOf(
                    cargoBin,
                    "build",
                    "--release",
                    "--package",
                    "quiche",
                    "--target",
                    cargoTarget,
                    "--no-default-features",
                    "--features",
                    // qlog: env-gated diagnostics (QUIC_QLOG_DIR) need quiche_conn_set_qlog_path, which is
                    // #[cfg(feature = "qlog")] — without it the symbol is absent and the JNI/cinterop
                    // bindings don't link. Pulls serde_json/serde/qlog into the binary.
                    if (boringsslDir.resolve("lib/libssl.a").exists()) "ffi,qlog" else "ffi,boringssl-vendored,qlog",
                )

            val process =
                ProcessBuilder(cargoArgs)
                    .directory(sourceDir)
                    .also { pb -> pb.environment().putAll(env) }
                    .redirectErrorStream(true)
                    .start()
            // Stream cargo output through Gradle logger so it appears in CI logs
            process.inputStream.bufferedReader().forEachLine { logger.lifecycle(it) }
            val result = process.waitFor()

            if (result != 0) {
                throw GradleException("quiche cargo build failed for $arch (exit $result)")
            }

            // Copy static library
            outputDir.resolve("lib").mkdirs()
            outputDir.resolve("include").mkdirs()
            sourceDir.resolve("target/$cargoTarget/release/libquiche.a").copyTo(
                outputDir.resolve("lib/libquiche.a"),
                overwrite = true,
            )
            sourceDir.resolve("quiche/include/quiche.h").copyTo(
                outputDir.resolve("include/quiche.h"),
                overwrite = true,
            )

            markerFile.writeText("quiche $quicheVersion built on ${System.currentTimeMillis()}")
            logger.lifecycle("quiche $quicheVersion built successfully (static, $arch)")
        }
    }
}

/**
 * Build quiche as a shared library for JVM/Android (loaded via JNI or FFM).
 */
fun createBuildQuicheSharedTask(
    os: String,
    arch: String,
): TaskProvider<Task> {
    val taskName = "buildQuicheShared${os.replaceFirstChar { it.uppercase() }}${arch.replaceFirstChar { it.uppercase() }}"
    val outputDir = projectDir.resolve("libs/quiche/$os-$arch")
    // `-qlog` suffix: the qlog feature was added to the cargo build, so a lib built before it (a stale
    // `.built-<ver>` marker with no suffix) must NOT satisfy this — bumping the marker forces a rebuild
    // with qlog on every host/CI runner that cached the old output.
    val markerFile = outputDir.resolve("lib/.built-$quicheVersion-qlog")

    val cargoTarget =
        when ("$os-$arch") {
            "linux-x64" -> "x86_64-unknown-linux-gnu"
            "linux-arm64" -> "aarch64-unknown-linux-gnu"
            "macos-x64" -> "x86_64-apple-darwin"
            "macos-arm64" -> "aarch64-apple-darwin"
            else -> throw GradleException("Unsupported target: $os-$arch")
        }

    val libExt = if (os == "macos") "dylib" else "so"

    return tasks.register(taskName) {
        group = "build"
        description = "Build quiche shared library for $os-$arch"
        inputs.property("quicheVersion", quicheVersion)
        outputs.file(markerFile)
        onlyIf { !markerFile.exists() }

        doLast {
            val buildDir = quicheBuildDir.get().asFile
            val sourceDir = downloadQuicheSource(buildDir, quicheVersion, quicheSha256)

            logger.lifecycle("Building quiche $quicheVersion (shared, $os-$arch)...")

            // Use pre-built BoringSSL if available. Probe ONCE: this single decision determines
            // both whether libquiche.a vendors its own BoringSSL and whether the JNI shim must
            // link the external archives. Re-probing `libs/boringssl` independently in the shim
            // task races a concurrent :buildBoringssl* populating that dir mid-build — a vendored
            // libquiche.a then gets the external libcrypto.a linked alongside it, yielding
            // duplicate-symbol link errors (v3_utl.c.o, a2i_IPADDRESS, …). We record the choice
            // in a marker next to libquiche.a and have the shim read THAT, never the live dir.
            val boringsslDir = rootProject.projectDir.resolve("libs/boringssl/linux-$arch")
            val usesExternalBssl = boringsslDir.resolve("lib/libssl.a").exists()
            val env = mutableMapOf<String, String>()
            // Note: avoid -C lto=thin / -C embed-bitcode=yes — they conflict with
            // pre-built BoringSSL objects that lack LTO bitcode.
            env["RUSTFLAGS"] = "-C opt-level=s -C codegen-units=1 -C strip=symbols"
            if (usesExternalBssl) {
                env["QUICHE_BSSL_PATH"] = boringsslDir.absolutePath
                logger.lifecycle("Using pre-built BoringSSL from ${boringsslDir.absolutePath}")
            }
            if (os == "linux" && arch == "arm64" && System.getProperty("os.arch") != "aarch64") {
                env["CARGO_TARGET_AARCH64_UNKNOWN_LINUX_GNU_LINKER"] = "aarch64-linux-gnu-gcc"
            }

            // qlog: see the static-lib task — required for the QUIC_QLOG_DIR diagnostics binding.
            val quicheFeatures = if (usesExternalBssl) "ffi,qlog" else "ffi,boringssl-vendored,qlog"

            val process =
                ProcessBuilder(
                    cargoBin,
                    "build",
                    "--release",
                    "--package",
                    "quiche",
                    "--target",
                    cargoTarget,
                    "--no-default-features",
                    "--features",
                    quicheFeatures,
                ).directory(sourceDir)
                    .also { pb -> pb.environment().putAll(env) }
                    .redirectErrorStream(true)
                    .start()
            // Stream cargo output through Gradle logger so it appears in CI logs
            process.inputStream.bufferedReader().forEachLine { logger.lifecycle(it) }
            val result = process.waitFor()

            if (result != 0) {
                throw GradleException("quiche cargo build failed for $os-$arch (exit $result)")
            }

            outputDir.resolve("lib").mkdirs()
            val builtLib = sourceDir.resolve("target/$cargoTarget/release/libquiche.$libExt")
            val destLib = outputDir.resolve("lib/libquiche.$libExt")
            builtLib.copyTo(destLib, overwrite = true)

            // Also copy libquiche.a — the JVM JNI shim links against it for self-
            // contained packaging (the .dylib is for FFM on JDK 21+).
            val builtStatic = sourceDir.resolve("target/$cargoTarget/release/libquiche.a")
            if (builtStatic.exists()) {
                builtStatic.copyTo(outputDir.resolve("lib/libquiche.a"), overwrite = true)
            }
            // Record whether this libquiche.a was built against external BoringSSL (so it has
            // unresolved SSL_*/EVP_*/CRYPTO_* refs the shim must satisfy) or vendored its own
            // (already self-contained — the shim must NOT add the external archives, or ld sees
            // every BoringSSL object twice). The shim reads this marker, NOT the live boringssl
            // dir, so a concurrent :buildBoringssl* can't flip the decision out from under it.
            val externalMarker = outputDir.resolve("lib/.bssl-external")
            val vendoredMarker = outputDir.resolve("lib/.bssl-vendored")
            if (usesExternalBssl) {
                externalMarker.writeText("external\n")
                if (vendoredMarker.exists()) vendoredMarker.delete()
            } else {
                vendoredMarker.writeText("vendored\n")
                if (externalMarker.exists()) externalMarker.delete()
            }
            // Copy quiche.h for the JNI shim compile (Android task reads from libs/quiche/include).
            val headerSrc = sourceDir.resolve("quiche/include/quiche.h")
            val headerDest = projectDir.resolve("libs/quiche/include/quiche.h")
            if (headerSrc.exists() && !headerDest.exists()) {
                headerDest.parentFile.mkdirs()
                headerSrc.copyTo(headerDest, overwrite = true)
            }

            // macOS dylibs get cargo's default install_name (/usr/local/lib/...), which
            // breaks dyld once we extract from a JAR at runtime. Retarget to @rpath so
            // dependents resolve via the loader path.
            if (os == "macos") {
                ProcessBuilder(
                    "install_name_tool",
                    "-id",
                    "@rpath/libquiche.$libExt",
                    destLib.absolutePath,
                ).inheritIO().start().waitFor()
            }

            // Strip the shared library
            val stripCmd =
                if (os == "macos") {
                    listOf("strip", "-x", destLib.absolutePath)
                } else if (arch == "arm64" && System.getProperty("os.arch") != "aarch64") {
                    listOf("aarch64-linux-gnu-strip", "--strip-all", destLib.absolutePath)
                } else {
                    listOf("strip", "--strip-all", destLib.absolutePath)
                }
            ProcessBuilder(stripCmd).inheritIO().start().waitFor()

            markerFile.writeText("quiche $quicheVersion built on ${System.currentTimeMillis()}")

            val sizeKb = destLib.length() / 1024
            logger.lifecycle("quiche $quicheVersion built successfully (shared, $os-$arch, ${sizeKb}KB)")
        }
    }
}

/**
 * Patch quiche 0.28's vendored-BoringSSL build.rs to handle the **arm64 iOS simulator**.
 *
 * Upstream `CMAKE_PARAMS_IOS` maps every `aarch64` iOS target to the `iphoneos` (device) SDK and only
 * sets a `-target …-simulator` ASM cflag for `x86_64`. So an `aarch64-apple-ios-sim` build compiles
 * BoringSSL's `.S` assembly with the DEVICE platform tag (`platform IOS`), and ld64 then rejects the
 * archive into an `iOS-simulator` test binary: "building for 'iOS-simulator', but linking in object
 * file built for 'iOS'". (The `.cc` TUs come out fine — only the hand-written asm is mis-tagged.)
 *
 * The fix keys off `CARGO_CFG_TARGET_ABI == "sim"` (set by rustc for `*-sim` triples) so the same
 * build.rs is correct for ALL targets: device arm64 (abi≠sim) is unchanged, x86_64 sim keeps its
 * existing handling, and arm64 sim now gets the `iphonesimulator` sysroot + simulator ASM target.
 * Idempotent (guarded by a marker comment); inert for macOS/Linux/Android, which don't take this arm.
 */
fun patchQuicheBuildRsForIosSim(sourceDir: File) {
    val buildRs = sourceDir.resolve("quiche/src/build.rs")
    if (!buildRs.exists()) return
    val text = buildRs.readText()
    if (text.contains("arm64-apple-ios-simulator")) return // already patched

    val original =
        """
        |            // Hack for Xcode 10.1.
        |            let target_cflag = if arch == "x86_64" {
        |                "-target x86_64-apple-ios-simulator"
        |            } else {
        |                ""
        |            };
        """.trimMargin()
    val replacement =
        """
        |            // Hack for Xcode 10.1 + arm64 iOS simulator (patched by socket build.gradle.kts —
        |            // see patchQuicheBuildRsForIosSim). Upstream only handles the x86_64 simulator.
        |            let is_ios_sim = std::env::var("CARGO_CFG_TARGET_ABI").map(|a| a == "sim").unwrap_or(false);
        |            if is_ios_sim && arch == "aarch64" {
        |                boringssl_cmake.define("CMAKE_OSX_SYSROOT", "iphonesimulator");
        |            }
        |            let target_cflag = if arch == "x86_64" {
        |                "-target x86_64-apple-ios-simulator"
        |            } else if is_ios_sim && arch == "aarch64" {
        |                "-target arm64-apple-ios-simulator"
        |            } else {
        |                ""
        |            };
        """.trimMargin()

    if (!text.contains(original)) {
        throw GradleException(
            "Could not patch quiche build.rs for the arm64 iOS simulator — the expected upstream block " +
                "was not found (quiche version changed?). Update patchQuicheBuildRsForIosSim.",
        )
    }
    buildRs.writeText(text.replace(original, replacement))
    logger.lifecycle("Patched quiche build.rs for arm64 iOS-simulator BoringSSL asm")
}

/**
 * Build quiche as a STATIC library (libquiche.a only) for a K/Native Apple target — the
 * quiche-on-Apple pivot's iOS datapath. macOS reuses [createBuildQuicheSharedTask] (it also copies the
 * .a alongside the dylib); iOS has no dylib step because the `cdylib`/dylib *link* fails on iOS while
 * K/N only needs the archive, so we use `cargo rustc --crate-type staticlib` (no dylib link attempted).
 *
 * Toolchain note (mirrors the SESSION-3 gotcha): a Homebrew `cargo` shadows rustup AND invokes the
 * `rustc` it finds on PATH, so an installed iOS target still fails `E0463 can't find crate for core`.
 * We resolve the rustup toolchain's bin dir via `rustup which rustc` and PREPEND it to the child PATH
 * so `rustc`/`core`/`std` resolve there. On a CI runner whose `cargo` is already rustup-managed
 * (dtolnay/rust-toolchain), `rustup which` simply confirms the same dir — harmless.
 */
fun createBuildQuicheAppleStaticTask(
    libSubdir: String,
    rustTarget: String,
): TaskProvider<Task> {
    val taskName = "buildQuicheStatic${libSubdir.split('-').joinToString("") { it.replaceFirstChar { c -> c.uppercase() } }}"
    val outputDir = projectDir.resolve("libs/quiche/$libSubdir")
    val markerFile = outputDir.resolve("lib/.built-$quicheVersion-qlog")

    return tasks.register(taskName) {
        group = "build"
        description = "Build quiche static library (libquiche.a) for Apple $libSubdir ($rustTarget)"
        inputs.property("quicheVersion", quicheVersion)
        outputs.file(markerFile)
        onlyIf { !markerFile.exists() }

        doLast {
            val buildDir = quicheBuildDir.get().asFile
            val sourceDir = downloadQuicheSource(buildDir, quicheVersion, quicheSha256)
            patchQuicheBuildRsForIosSim(sourceDir)

            logger.lifecycle("Building quiche $quicheVersion (static, $libSubdir / $rustTarget)...")

            val env = mutableMapOf<String, String>()
            env["RUSTFLAGS"] = "-C opt-level=s -C codegen-units=1 -C strip=symbols"

            // Resolve the rustup toolchain bin dir and prepend it so a Homebrew cargo can't shadow
            // rustc out from under an installed Apple target (E0463). Best-effort: if rustup is absent
            // (CI's rustup-managed cargo), keep the inherited PATH.
            val rustupRustc =
                runCatching {
                    val p = ProcessBuilder("rustup", "which", "rustc").redirectErrorStream(true).start()
                    val out = p.inputStream.bufferedReader().readText().trim()
                    if (p.waitFor() == 0 && out.isNotEmpty()) File(out).parentFile.absolutePath else null
                }.getOrNull()
            val basePath = System.getenv("PATH") ?: ""
            env["PATH"] = if (rustupRustc != null) "$rustupRustc${File.pathSeparator}$basePath" else basePath

            // Ensure the target is installed (no-op if already present); harmless if rustup is absent.
            runCatching {
                ProcessBuilder("rustup", "target", "add", rustTarget)
                    .also { pb -> pb.environment().putAll(env) }
                    .redirectErrorStream(true)
                    .start()
                    .also { it.inputStream.bufferedReader().forEachLine { line -> logger.lifecycle(line) } }
                    .waitFor()
            }

            // `cargo rustc --crate-type staticlib`: build ONLY the .a. A plain `cargo build` would also
            // try to link the dylib, which fails for iOS targets — and K/N never needs it.
            val process =
                ProcessBuilder(
                    cargoBin,
                    "rustc",
                    "--release",
                    "--package",
                    "quiche",
                    "--target",
                    rustTarget,
                    "--crate-type",
                    "staticlib",
                    "--no-default-features",
                    "--features",
                    "ffi,boringssl-vendored,qlog",
                ).directory(sourceDir)
                    .also { pb -> pb.environment().putAll(env) }
                    .redirectErrorStream(true)
                    .start()
            process.inputStream.bufferedReader().forEachLine { logger.lifecycle(it) }
            val result = process.waitFor()
            if (result != 0) throw GradleException("quiche cargo build failed for $libSubdir (exit $result)")

            outputDir.resolve("lib").mkdirs()
            outputDir.resolve("include").mkdirs()
            sourceDir.resolve("target/$rustTarget/release/libquiche.a").copyTo(
                outputDir.resolve("lib/libquiche.a"),
                overwrite = true,
            )
            sourceDir.resolve("quiche/include/quiche.h").copyTo(
                outputDir.resolve("include/quiche.h"),
                overwrite = true,
            )
            // Keep the shared module-level header in sync for the cinterop includeDirs.
            val headerDest = projectDir.resolve("libs/quiche/include/quiche.h")
            if (!headerDest.exists()) {
                headerDest.parentFile.mkdirs()
                sourceDir.resolve("quiche/include/quiche.h").copyTo(headerDest, overwrite = true)
            }

            markerFile.writeText("quiche $quicheVersion built on ${System.currentTimeMillis()}")
            logger.lifecycle("quiche $quicheVersion built successfully (static, $libSubdir)")
        }
    }
}

// Register build tasks for the current host
val buildQuicheStaticX64 = if (isLinux) createBuildQuicheStaticTask("x64") else null
val buildQuicheStaticArm64 = if (isLinux) createBuildQuicheStaticTask("arm64") else null

val buildQuicheSharedLinuxX64 = if (isLinux) createBuildQuicheSharedTask("linux", "x64") else null
val buildQuicheSharedLinuxArm64 = if (isLinux) createBuildQuicheSharedTask("linux", "arm64") else null
val buildQuicheSharedMacosX64 = if (isMacOS) createBuildQuicheSharedTask("macos", "x64") else null
val buildQuicheSharedMacosArm64 = if (isMacOS) createBuildQuicheSharedTask("macos", "arm64") else null

// iOS static libs for the quiche-on-Apple pivot (macOS .a comes from the shared tasks above). Device
// (aarch64-apple-ios), simulator-arm64 (…-ios-sim), and simulator-x64 (x86_64-apple-ios).
val buildQuicheStaticIosArm64 = if (isMacOS) createBuildQuicheAppleStaticTask("ios-arm64", "aarch64-apple-ios") else null
val buildQuicheStaticIosSimulatorArm64 =
    if (isMacOS) createBuildQuicheAppleStaticTask("ios-simulator-arm64", "aarch64-apple-ios-sim") else null
val buildQuicheStaticIosX64 = if (isMacOS) createBuildQuicheAppleStaticTask("ios-x64", "x86_64-apple-ios") else null

// Convenience task: build all quiche libraries for the current host
tasks.register("buildQuicheAll") {
    group = "build"
    description = "Build all quiche libraries (static + shared) for the current host OS"
    if (isLinux) {
        dependsOn(buildQuicheStaticX64!!, buildQuicheStaticArm64!!)
        dependsOn(buildQuicheSharedLinuxX64!!, buildQuicheSharedLinuxArm64!!)
    }
    if (isMacOS) {
        dependsOn(buildQuicheSharedMacosX64!!, buildQuicheSharedMacosArm64!!)
        dependsOn(buildQuicheStaticIosArm64!!, buildQuicheStaticIosSimulatorArm64!!, buildQuicheStaticIosX64!!)
    }
}

// Convenience task: build every Apple K/Native static lib (macOS .a from the shared tasks + the three
// iOS static libs). Referenced by build-apple.yaml so a CI runner prepares all five before the K/N
// link/test tasks (which gate their -force_load on the .a existing at configuration time).
if (isMacOS) {
    tasks.register("buildQuicheAppleStaticLibs") {
        group = "build"
        description = "Build all Apple K/Native quiche static libs (macOS + iOS)"
        dependsOn(buildQuicheSharedMacosArm64!!, buildQuicheSharedMacosX64!!)
        dependsOn(buildQuicheStaticIosArm64!!, buildQuicheStaticIosSimulatorArm64!!, buildQuicheStaticIosX64!!)
    }
}

// --- JVM JNI shim build (desktop macOS / Linux) ---
// Compiles src/jni/quiche_jni.c and links quiche statically so the resulting
// libquiche_jni.{dylib,so} is self-contained (no dyld dependency chain).
// FFM on JDK 21+ uses libquiche.{dylib,so} directly; this shim is the JDK 8–20
// fallback path loaded by NativeLibLoader.

fun createBuildJvmJniShimTask(
    os: String,
    arch: String,
    buildShared: TaskProvider<Task>,
): TaskProvider<Task> {
    val taskName = "buildJvmJniShim${os.replaceFirstChar { it.uppercase() }}${arch.replaceFirstChar { it.uppercase() }}"
    val outputDir = projectDir.resolve("libs/quiche/$os-$arch/lib")
    val libExt = if (os == "macos") "dylib" else "so"
    val outputLib = outputDir.resolve("libquiche_jni.$libExt")
    val markerFile = outputDir.resolve(".jni-built-$quicheVersion")

    return tasks.register(taskName) {
        group = "build"
        description = "Build quiche JNI shim for $os-$arch (JDK 8–20 fallback path)"
        dependsOn(buildShared)
        inputs.property("quicheVersion", quicheVersion)
        val jniSourceFile = projectDir.resolve("src/jni/quiche_jni.c")
        inputs.file(jniSourceFile)
        outputs.files(outputLib, markerFile)
        // Rebuild when the marker is missing, the shim itself is gone, OR the JNI
        // source has been edited since the marker was written. The marker is keyed
        // only on quiche *version*, so without the source-mtime check an edit to
        // quiche_jni.c (e.g. #63 adding nSockAddr*) leaves a stale shim in place —
        // exactly what made macOS jvmTest fail with UnsatisfiedLinkError on a
        // version-matched-but-stale dylib. CI side-steps this by keying its native
        // cache on hashFiles(quiche_jni.c); local dev needs this guard.
        onlyIf {
            !markerFile.exists() ||
                !outputLib.exists() ||
                jniSourceFile.lastModified() > markerFile.lastModified()
        }

        doLast {
            val quicheStatic = outputDir.resolve("libquiche.a")
            if (!quicheStatic.exists()) {
                throw GradleException(
                    "libquiche.a missing at ${quicheStatic.absolutePath} — " +
                        "the shared build should have copied it alongside libquiche.$libExt",
                )
            }
            val javaHome =
                System.getenv("JAVA_HOME") ?: org.gradle.internal.jvm.Jvm
                    .current()
                    .javaHome.absolutePath
            val jdkIncludeOs = if (os == "macos") "darwin" else "linux"
            val quicheInclude = projectDir.resolve("libs/quiche/include")
            val jniSource = projectDir.resolve("src/jni/quiche_jni.c")

            val cmd = mutableListOf<String>()
            if (os == "macos") {
                cmd +=
                    listOf(
                        "clang",
                        "-dynamiclib",
                        "-O2",
                        // Keep the frame pointer so a JVM hs_err / core backtrace can unwind through
                        // the JNI shim reliably (the JVM's native stack walker doesn't use DWARF CFI).
                        // No codegen-meaningful effect on these tiny forwarders; buys traceable crashes.
                        "-fno-omit-frame-pointer",
                        "-Wall",
                        "-o",
                        outputLib.absolutePath,
                        jniSource.absolutePath,
                        "-I$javaHome/include",
                        "-I$javaHome/include/$jdkIncludeOs",
                        "-I${quicheInclude.absolutePath}",
                        // Pull every symbol from libquiche.a so the JNI dylib is self-contained
                        "-Wl,-force_load,${quicheStatic.absolutePath}",
                        "-Wl,-install_name,@rpath/libquiche_jni.$libExt",
                    )
            } else {
                // Whether libquiche.a needs the external BoringSSL archives is decided ONCE, at
                // quiche-build time, and recorded in `.bssl-external` next to libquiche.a (see the
                // shared-build task). Read THAT marker — never re-probe the live `libs/boringssl`
                // dir, which a concurrent :buildBoringssl* can populate after the cargo build chose
                // boringssl-vendored, causing the external libcrypto.a to be linked on top of the
                // already-vendored objects (duplicate-symbol ld failure: v3_utl.c.o, a2i_IPADDRESS…).
                val boringsslLibDir = rootProject.projectDir.resolve("libs/boringssl/linux-$arch/lib")
                // Primary signal: the marker the shared build wrote next to this libquiche.a.
                // Fallback (legacy artifact predating the marker, or a partial cache): ask the
                // archive itself — a vendored libquiche.a carries the BoringSSL objects (e.g.
                // v3_utl.c.o) as members, an external one does not. Either way the decision comes
                // from the artifact we're about to link, never the racy live `libs/boringssl` dir.
                val bsslMarker = outputDir.resolve(".bssl-external")
                val usedExternalBssl =
                    if (bsslMarker.exists()) {
                        true
                    } else if (outputDir.resolve(".bssl-vendored").exists()) {
                        false
                    } else {
                        // No marker — inspect the archive members. `ar t` lists object files;
                        // vendored builds include BoringSSL's, external builds don't.
                        val members =
                            ProcessBuilder("ar", "t", quicheStatic.absolutePath)
                                .redirectErrorStream(true)
                                .start()
                                .inputStream
                                .bufferedReader()
                                .readText()
                        !members.lineSequence().any { it.contains("v3_utl") || it.contains("boringssl") }
                    }
                val bsslArchives =
                    if (usedExternalBssl) {
                        listOf(
                            boringsslLibDir.resolve("libssl.a").absolutePath,
                            boringsslLibDir.resolve("libcrypto.a").absolutePath,
                        )
                    } else {
                        emptyList()
                    }
                // Pick the host or cross compiler based on the target arch.
                // Cross-build is needed when `-PquicEchoAllArches=true` is
                // set and the requested arch differs from the host — e.g.
                // building linux-arm64 on a linux-x64 CI runner. The
                // multi-arch quic-echo Docker image consumes the resulting
                // jar with both arches' natives inside.
                val hostArch =
                    if (System.getProperty("os.arch") == "aarch64") "arm64" else "x64"
                val cc =
                    if (arch == hostArch) {
                        "cc"
                    } else if (arch == "arm64") {
                        "aarch64-linux-gnu-gcc"
                    } else {
                        "x86_64-linux-gnu-gcc"
                    }
                cmd +=
                    listOf(
                        cc,
                        "-shared",
                        "-fPIC",
                        "-O2",
                        // See the macOS branch: keep frame pointers so a native crash (the intermittent
                        // JNI SIGABRT) unwinds cleanly through the shim in hs_err / a core dump.
                        "-fno-omit-frame-pointer",
                        "-Wall",
                        "-o",
                        outputLib.absolutePath,
                        jniSource.absolutePath,
                        "-I$javaHome/include",
                        "-I$javaHome/include/$jdkIncludeOs",
                        "-I${quicheInclude.absolutePath}",
                        "-Wl,--whole-archive",
                        quicheStatic.absolutePath,
                    ) + bsslArchives +
                    listOf(
                        "-Wl,--no-whole-archive",
                        "-lm",
                        "-ldl",
                        "-lpthread",
                        // ELF RUNPATH so the shim can find sibling libs if it ever needs them
                        "-Wl,-rpath,\$ORIGIN",
                    )
            }

            logger.lifecycle("Compiling JNI shim for $os-$arch...")
            val process =
                ProcessBuilder(cmd)
                    .redirectErrorStream(true)
                    .start()
            process.inputStream.bufferedReader().forEachLine { logger.lifecycle(it) }
            val result = process.waitFor()
            if (result != 0) throw GradleException("JNI shim compilation failed for $os-$arch (exit $result)")

            // `--strip-debug` (NOT `--strip-all`): drop DWARF debug sections to keep the shipped lib
            // small, but RETAIN the `.symtab` function-name symbols. Those are what turn a native
            // crash backtrace from `libquiche_jni.so+0x3f21` into `...(Java_..._nConnStreamSend+0x..)`
            // — the difference between diagnosing the intermittent JNI SIGABRT and guessing. The shim's
            // symbol table is a handful of nConn* entries (negligible size). macOS `strip -x` already
            // keeps global symbols.
            val stripCmd =
                if (os == "macos") {
                    listOf("strip", "-x", outputLib.absolutePath)
                } else {
                    listOf("strip", "--strip-debug", outputLib.absolutePath)
                }
            ProcessBuilder(stripCmd).inheritIO().start().waitFor()

            markerFile.writeText("quiche $quicheVersion JNI shim built on ${System.currentTimeMillis()}")
            val sizeKb = outputLib.length() / 1024
            logger.lifecycle("JNI shim built successfully ($os-$arch, ${sizeKb}KB)")
        }
    }
}

val jvmJniShimMacosX64 =
    if (isMacOS) createBuildJvmJniShimTask("macos", "x64", buildQuicheSharedMacosX64!!) else null
val jvmJniShimMacosArm64 =
    if (isMacOS) createBuildJvmJniShimTask("macos", "arm64", buildQuicheSharedMacosArm64!!) else null
val jvmJniShimLinuxX64 =
    if (isLinux) createBuildJvmJniShimTask("linux", "x64", buildQuicheSharedLinuxX64!!) else null
val jvmJniShimLinuxArm64 =
    if (isLinux) createBuildJvmJniShimTask("linux", "arm64", buildQuicheSharedLinuxArm64!!) else null

// Unified entry point referenced by the handoff doc — builds every native lib
// needed to exercise socket-quic on the current host's JVM (shared + JNI shim).
tasks.register("prepareQuicheNativeLib") {
    group = "build"
    description = "Build quiche shared lib + JNI shim for the current host OS/arch"
    if (isMacOS) {
        val hostArch = if (System.getProperty("os.arch") == "aarch64") "arm64" else "x64"
        dependsOn(
            if (hostArch == "arm64") jvmJniShimMacosArm64!! else jvmJniShimMacosX64!!,
        )
    }
    if (isLinux) {
        val hostArch = if (System.getProperty("os.arch") == "aarch64") "arm64" else "x64"
        dependsOn(
            if (hostArch == "arm64") jvmJniShimLinuxArm64!! else jvmJniShimLinuxX64!!,
        )
    }
}

// Stage built native libs into a directory added to the jvmTest runtime
// classpath (not to main resources — we don't want them in the base jvmJar).
// NativeLibLoader.getResourceAsStream("META-INF/native/...") picks them up
// from the classpath at test time; at publish time, they ship as per-host
// classifier JARs (see below).
val stagedNativeResourcesDir = layout.buildDirectory.dir("generated-native-resources/jvmMain")
val nativeLibsByPlatform =
    mapOf(
        "linux-x64" to listOf("libquiche.so", "libquiche_jni.so"),
        "linux-arm64" to listOf("libquiche.so", "libquiche_jni.so"),
        "macos-x64" to listOf("libquiche.dylib", "libquiche_jni.dylib"),
        "macos-arm64" to listOf("libquiche.dylib", "libquiche_jni.dylib"),
        // Windows ships only the JNI shim: quiche is statically linked into
        // quiche_jni.dll (boringssl-vendored + --whole-archive libquiche.a in
        // build-linux.yaml's MinGW cross-compile), so there is no separate
        // quiche.dll to extract — matching NativeLibLoader, which loads only
        // quiche_jni.dll on Windows. Staged into the jvmTest classpath only;
        // not in nativeClassifiers, so no windows publish artifact yet.
        "windows-x64" to listOf("quiche_jni.dll"),
    )

// `-PquicEchoAllArches=true` gates building the non-host-arch JNI shim
// during `stageQuicheNativeResources`. Off by default so local dev (which
// generally needs only the host arch) stays fast. CI sets it to true so
// the resulting `quicEchoJar` fat jar contains both linux-x64 AND
// linux-arm64 (or both macos arches) natives — required for the multi-arch
// `quic-echo` Docker image to run on both Linux x64 and Linux ARM64 CI
// jobs out of the same artifact.
val quicEchoAllArches =
    providers.gradleProperty("quicEchoAllArches").orNull == "true"

tasks.register<Copy>("stageQuicheNativeResources") {
    group = "build"
    description = "Copy built quiche native libs into jvmTest classpath (META-INF/native/<platform>/...)"
    into(stagedNativeResourcesDir)
    for ((platform, libs) in nativeLibsByPlatform) {
        val libDir = projectDir.resolve("libs/quiche/$platform/lib")
        into("META-INF/native/$platform") {
            from(libDir) { include(*libs.toTypedArray()) }
        }
    }

    // Single-arch (default) path: this Copy reads each JNI shim's output dir
    // (libs/quiche/<platform>/lib), so it must declare a dependency on the
    // host-arch shim build — `prepareQuicheNativeLib` builds exactly that.
    // Without it, Gradle flags an undeclared task-output dependency and fails
    // validation on local non-allArches runs (CI always sets quicEchoAllArches,
    // whose branch below already declares the deps, so CI never hit this).
    dependsOn("prepareQuicheNativeLib")

    // Multi-arch path: depend on both x64 + arm64 JNI shim builds so the
    // staged dir has natives for both arches by the time `Copy` runs. We
    // attach to `stageQuicheNativeResources` rather than `quicEchoJar`
    // directly so any task depending on staging (including jvmTest itself)
    // sees the same multi-arch set when the flag is on.
    if (quicEchoAllArches) {
        if (isLinux) {
            jvmJniShimLinuxX64?.let { dependsOn(it) }
            jvmJniShimLinuxArm64?.let { dependsOn(it) }
        }
        if (isMacOS) {
            jvmJniShimMacosX64?.let { dependsOn(it) }
            jvmJniShimMacosArm64?.let { dependsOn(it) }
        }
    }
}

afterEvaluate {
    tasks.named<Test>("jvmTest").configure {
        dependsOn("prepareQuicheNativeLib", "stageQuicheNativeResources")
        // Put staged natives on the test runtime classpath so NativeLibLoader
        // can extract them as classloader resources.
        classpath += files(stagedNativeResourcesDir)

        // Forward `nw.plain.*` system properties to the forked worker JVM (CLI `-D` does NOT propagate
        // to test workers). Used by the manually-launched QuichePlainProbeClient anti-amplification
        // probe (-Dnw.plain.client=true); a no-op for ordinary test runs.
        for ((k, v) in System.getProperties()) {
            val key = k.toString()
            if (key.startsWith("nw.plain.")) systemProperty(key, v.toString())
        }

        // --- Heap-corruption / UAF guard ---------------------------------------
        // `-PquicMallocCheck` runs the forked test workers under glibc's malloc
        // consistency checks. The worst QUIC bug class (the 3.2.12 JNI stream-
        // command use-after-free) reproduces under these, NOT ASAN — libquiche.a
        // is non-instrumented. MALLOC_CHECK_=3 aborts on the first detected heap
        // inconsistency (double-free, overflow); MALLOC_PERTURB_ poisons freed
        // memory so a UAF reads garbage and is more likely to trip a check. Set
        // here (on the Test task) rather than via shell env so the *worker* JVM
        // gets it regardless of Gradle daemon reuse. CI pairs this with a filter
        // to the recv-path + soak suites (see build-linux.yaml).
        if (providers.gradleProperty("quicMallocCheck").isPresent) {
            environment("MALLOC_CHECK_", "3")
            environment("MALLOC_PERTURB_", "165")
            environment("GLIBC_TUNABLES", "glibc.malloc.check=3")
        }

        // --- Native-crash diagnostics ------------------------------------------
        // When hunting the intermittent JNI SIGABRT, mirror the JVM fatal-error log
        // (hs_err: the crashing thread's native + Java/Kotlin stack, the signal, the
        // glibc abort reason) straight to stderr so it lands in the LIVE CI job log —
        // the one output that survives even when a killed/timed-out job fails to
        // upload artifacts. `-XX:+ErrorFileToStderr` and a file path are mutually
        // exclusive, so we choose the log; the core dump (workflow-enabled) is the
        // artifact for deep inspection. CI sets ORG_GRADLE_PROJECT_quicCrashDiag=1 so
        // every jvmTest worker in the job gets this; local runs are unaffected.
        if (providers.gradleProperty("quicCrashDiag").isPresent) {
            jvmArgs("-XX:+ErrorFileToStderr")
        }

        // --- Backend selector ---------------------------------------------------
        // By default this task resolves `loadQuicheApi()` to the base commonJvmMain
        // JNI loader: the java21/FFM compilation output is deliberately NOT on the
        // Test runtime classpath, so the base `QuicheApiLoaderKt` (→ JniQuicheApi)
        // is the only one present. That means `:socket-quic:jvmTest` exercises the
        // *JNI* backend on every JDK — including the JDK 21 CI job — NOT FFM.
        //
        // Passing `-PquicheJvmBackend=ffm` prepends the java21 (FFM) compilation
        // output ahead of the base output, so its `QuicheApiLoaderKt` shadows the
        // base one — the same multi-release-JAR class shadowing that production
        // JDK 21+ consumers get, reproduced here via classpath order. FFM then
        // loads the pure `libquiche.{so,dylib}` via Panama (falling back to JNI
        // only if that lib is absent). Requires a JDK 21+ launcher (FFM bindings
        // are JVM-21 bytecode). This is what gives CI an FFM-backed test run.
        if (providers.gradleProperty("quicheJvmBackend").orNull == "ffm") {
            dependsOn("compileJava21KotlinJvm")
            val ffmOutput =
                kotlin
                    .jvm()
                    .compilations["java21"]
                    .output.allOutputs
            classpath = files(ffmOutput) + classpath
        }

        // --- Launcher selector --------------------------------------------------
        // The build/toolchain is JDK 21 (required to compile the FFM bindings),
        // but the JNI backend (base loader + tests are JVM-8 bytecode) runs on
        // older JVMs too. `-PjvmTestLauncher=17` points *this Test task* at a
        // JDK 17 launcher so CI can exercise JNI on the runtime that Android /
        // legacy-JVM consumers actually use, while the rest of the build stays on
        // 21. The java21/FFM classes aren't on the JNI classpath, so there is no
        // UnsupportedClassVersionError. Do not combine with `quicheJvmBackend=ffm`.
        providers.gradleProperty("jvmTestLauncher").orNull?.let { version ->
            val launcherVersion = version.toInt()
            val toolchains = project.extensions.getByType<JavaToolchainService>()
            javaLauncher.set(
                toolchains.launcherFor {
                    languageVersion.set(JavaLanguageVersion.of(launcherVersion))
                },
            )
            if (launcherVersion < 21) {
                // `--enable-native-access` (added globally above for FFM/Panama) is
                // pointless for the JNI backend and some older JVMs reject the
                // unknown flag outright, which would kill the test worker at startup.
                // Strip it; --add-opens java.base/java.nio stays (valid + harmless).
                jvmArgs = jvmArgs.orEmpty().filterNot { it.contains("enable-native-access") }
            }
        }
    }
}

// --- Coverage-guided fuzzing (Jazzer) ----------------------------------------
// `quicHeaderFuzz` drives HeaderInfoFuzzer (src/jvmTest) — the quiche_header_info
// parse that runs on every received datagram before any connection exists — under
// Jazzer/libFuzzer. Jazzer is runtime-only here: the target uses the `byte[]`
// entry-point form so nothing in jvmTest compiles against Jazzer; the driver is
// pulled into a dedicated, non-test configuration resolved only by this task.
//
// NOTE on coverage signal: the real parser is native quiche, which JVM Jazzer
// cannot instrument, so libFuzzer's coverage feedback only sees the thin Kotlin
// forwarder. This is primarily a *crash/robustness* fuzzer — libFuzzer's signal
// handlers turn a native SIGSEGV/SIGABRT into a saved `crash-*` repro — and pairs
// with the MALLOC_CHECK_ CI lane that catches the heap-corruption class. Deeper
// coverage-guided fuzzing of the parser itself would need an ASAN libFuzzer build
// of quiche (a separate, larger effort).
val jazzerConfig =
    configurations.create("jazzer") {
        isCanBeConsumed = false
        isCanBeResolved = true
    }
dependencies { add("jazzer", libs.jazzer) }

// Seed corpus + crash/interesting-input output live under the module (corpus is
// committed so CI starts warm; the work dir is build/ and git-ignored).
val quicFuzzCorpusDir = projectDir.resolve("fuzz/corpus/header-info")
val quicFuzzWorkDir = layout.buildDirectory.dir("fuzz/header-info")

tasks.register<JavaExec>("quicHeaderFuzz") {
    group = "verification"
    description = "Coverage-guided Jazzer fuzzing of quiche_header_info (HeaderInfoFuzzer). " +
        "Configure runtime with -PquicFuzzSeconds=<n> (default 60)."
    dependsOn("jvmTestClasses", "prepareQuicheNativeLib", "stageQuicheNativeResources")

    // Build the classpath from the jvm *test compilation* (compiled main+test output +
    // all runtime deps) plus the staged native libs NativeLibLoader extracts — i.e.
    // exactly what jvmTest runs with, but WITHOUT referencing the jvmTest *task*, so
    // launching the fuzzer doesn't first execute the entire jvmTest suite. The Jazzer
    // driver itself comes from the dedicated `jazzer` configuration.
    val jvmTestCompilation = kotlin.jvm().compilations["test"]
    classpath =
        files(
            jvmTestCompilation.output.allOutputs,
            jvmTestCompilation.runtimeDependencyFiles,
            stagedNativeResourcesDir,
        ) + jazzerConfig

    mainClass.set("com.code_intelligence.jazzer.Jazzer")

    val maxSeconds = providers.gradleProperty("quicFuzzSeconds").orElse("60")
    val corpusDir = quicFuzzCorpusDir
    val workDir = quicFuzzWorkDir

    doFirst {
        corpusDir.mkdirs()
        val work = workDir.get().asFile
        work.resolve("corpus").mkdirs()
    }

    // libFuzzer writes crash-*/oom-*/timeout-* repro files to artifact_prefix, and
    // new interesting inputs only to the FIRST positional corpus dir. We make that a
    // gitignored build/ dir so the committed seed corpus (passed second, read-only in
    // practice) stays pristine across local runs.
    argumentProviders.add {
        val work = workDir.get().asFile
        listOf(
            "--target_class=com.ditchoom.socket.quic.fuzz.HeaderInfoFuzzer",
            "--instrumentation_includes=com.ditchoom.**",
            "-print_final_stats=1",
            "-artifact_prefix=${work.absolutePath}/",
            "-max_total_time=${maxSeconds.get()}",
            work.resolve("corpus").absolutePath,
            corpusDir.absolutePath,
        )
    }
}

// --- Native ASAN + coverage-guided fuzzing (cargo-fuzz) ----------------------
// The counterpart the Jazzer task above (and fuzz/README.md) call for: cargo-fuzz builds quiche itself
// with SanitizerCoverage + AddressSanitizer, so libFuzzer gets REAL edge coverage of the Rust parser and
// ASAN catches the heap-overflow / use-after-free class. Locally: cov climbed to ~162 and the corpus grew
// from 12 seeds to 47 in 30s — the coverage feedback the JVM lane can't produce.
//
// Prereqs (NOT auto-installed — this is opt-in, like the Android NDK tasks): a nightly Rust toolchain and
// `cargo install cargo-fuzz`. Reuses the SAME committed seed corpus as the Jazzer target; new inputs and
// crash repros go under build/ (gitignored). quiche is pinned to the shipped version via fuzz/native/Cargo.toml.
//
// Targets (fuzz/native/fuzz_targets/):
//   - header_info — quiche::Header::from_slice (public-header parse on every datagram)
//   - conn_recv   — quiche::accept + Connection::recv (the full server recv state machine; pre-auth surface)
//
// By default both ASAN+SanCov-instrument quiche's RUST. `-PquicFuzzBoringSslAsan` additionally ASAN-builds
// the vendored BoringSSL C so a memory bug in the crypto path is caught too — it needs a clang toolchain
// whose LLVM matches the Rust toolchain's ASAN runtime (gcc's libasan won't coexist with cargo-fuzz's
// LLVM ASAN). That path is opt-in and deliberately kept OUT of the gating CI lane until verified on a
// clang host. Local verification of THIS change covered the Rust-ASAN path only (no clang available).
fun registerNativeFuzz(
    taskName: String,
    target: String,
    workSubdir: String,
) = tasks.register<Exec>(taskName) {
    group = "verification"
    description = "ASAN + coverage-guided cargo-fuzz of quiche ($target). Requires nightly Rust + cargo-fuzz. " +
        "-PquicFuzzSeconds=<n> (default 60); -PquicFuzzBoringSslAsan to also ASAN-build BoringSSL (needs clang)."
    workingDir = projectDir
    environment("ASAN_OPTIONS", "detect_leaks=0") // recv/parse keep no long-lived state; LSAN noise only
    if (providers.gradleProperty("quicFuzzBoringSslAsan").isPresent) {
        // clang (not gcc) so BoringSSL's ASAN runtime is the same LLVM one cargo-fuzz links for the Rust
        // side; fuzzer-no-link adds the SanitizerCoverage libFuzzer consumes without its own main.
        environment("CC", "clang")
        environment("CXX", "clang++")
        environment("CFLAGS", "-fsanitize=address,fuzzer-no-link -g1")
        environment("CXXFLAGS", "-fsanitize=address,fuzzer-no-link -g1")
    }
    doFirst {
        val maxSeconds = providers.gradleProperty("quicFuzzSeconds").orElse("60").get()
        val seedCorpus = projectDir.resolve("fuzz/corpus/header-info")
        val workCorpus =
            layout.buildDirectory
                .dir("fuzz-native/$workSubdir")
                .get()
                .asFile
        workCorpus.mkdirs()
        // First positional corpus dir receives new inputs (gitignored build/); the committed seed is
        // passed second so coverage starts warm without mutating the checked-in vectors.
        commandLine(
            "cargo",
            "+nightly",
            "fuzz",
            "run",
            "--fuzz-dir",
            "fuzz/native",
            target,
            workCorpus.absolutePath,
            seedCorpus.absolutePath,
            "--",
            "-print_final_stats=1",
            "-max_total_time=$maxSeconds",
        )
    }
}

registerNativeFuzz("quicHeaderFuzzNative", "header_info", "header-info-corpus")
registerNativeFuzz("quicConnRecvFuzzNative", "conn_recv", "conn-recv-corpus")

// --- Android JNI native library build ---
// Builds quiche via cargo-ndk and compiles the JNI shim with NDK clang.
// Requires: cargo, cargo-ndk, Android NDK, Rust Android targets
//   rustup target add x86_64-linux-android aarch64-linux-android armv7-linux-androideabi
//   cargo install cargo-ndk

// Resolve Android NDK — checks env vars then common install locations
val androidNdkDir: File? by lazy {
    val envNdk = System.getenv("ANDROID_NDK_HOME")
    if (envNdk != null && File(envNdk).exists()) return@lazy File(envNdk)
    val sdkRoot = System.getenv("ANDROID_SDK_ROOT") ?: System.getenv("ANDROID_HOME")
    if (sdkRoot != null) {
        val ndkDir = File(sdkRoot, "ndk")
        if (ndkDir.exists()) return@lazy ndkDir.listFiles()?.filter { it.isDirectory }?.maxByOrNull { it.name }
    }
    val homeNdk = File(System.getProperty("user.home"), "Android/ndk")
    if (homeNdk.exists()) return@lazy homeNdk.listFiles()?.filter { it.isDirectory }?.maxByOrNull { it.name }
    null
}

data class AndroidAbi(
    val abi: String,
    val rustTarget: String,
    val ndkClangPrefix: String,
)

val androidAbis =
    listOf(
        AndroidAbi("arm64-v8a", "aarch64-linux-android", "aarch64-linux-android24"),
        AndroidAbi("armeabi-v7a", "armv7-linux-androideabi", "armv7a-linux-androideabi24"),
        AndroidAbi("x86_64", "x86_64-linux-android", "x86_64-linux-android24"),
    )

fun createBuildAndroidJniTask(abi: AndroidAbi): TaskProvider<Task>? {
    val ndk = androidNdkDir ?: return null
    val outputDir = projectDir.resolve("src/androidMain/jniLibs/${abi.abi}")
    val outputLib = outputDir.resolve("libquiche_jni.so")

    return tasks.register("buildAndroidJni${abi.abi.replace("-", "").replaceFirstChar { it.uppercase() }}") {
        group = "build"
        description = "Build quiche + JNI shim for Android ${abi.abi}"
        // Track the C source + quiche version as inputs and the .so itself as the
        // output. Gradle's content-hashed up-to-date check then rebuilds whenever
        // quiche_jni.c changes — NOT a version-keyed `.built-<ver>` marker, which
        // (the old bug) survived every C-source edit at the same quiche version and
        // shipped a stale .so missing newly-added JNI symbols (e.g.
        // nConnDgramMaxWritableLen) → UnsatisfiedLinkError at runtime. This mirrors
        // the host JNI-shim task. Do NOT reintroduce a marker + onlyIf gate.
        inputs.property("quicheVersion", quicheVersion)
        inputs.file("src/jni/quiche_jni.c")
        outputs.file(outputLib)

        doLast {
            // 1. Get quiche source (reuses shared clone)
            val buildDir = quicheBuildDir.get().asFile
            val sourceDir = downloadQuicheSource(buildDir, quicheVersion, quicheSha256)

            // 2. Build quiche for Android via cargo ndk
            logger.lifecycle("Building quiche for Android ${abi.abi}...")
            val env = mutableMapOf<String, String>()
            env["ANDROID_NDK_HOME"] = ndk.absolutePath
            env["RUSTFLAGS"] = "-C opt-level=s -C codegen-units=1 -C strip=symbols"

            val cargoResult =
                ProcessBuilder(
                    cargoBin,
                    "ndk",
                    "--target",
                    abi.abi,
                    "--platform",
                    "24",
                    "--",
                    "build",
                    "--release",
                    "--package",
                    "quiche",
                    "--no-default-features",
                    "--features",
                    // qlog: see the static-lib task — required for the QUIC_QLOG_DIR diagnostics binding.
                    "ffi,boringssl-vendored,qlog",
                ).directory(sourceDir)
                    .also { pb -> pb.environment().putAll(env) }
                    .redirectErrorStream(true)
                    .start()
                    .also { it.inputStream.bufferedReader().forEachLine { line -> logger.lifecycle(line) } }
                    .waitFor()
            if (cargoResult != 0) throw GradleException("cargo ndk build failed for ${abi.abi}")

            // 3. Find the static library
            val quicheLib = sourceDir.resolve("target/${abi.rustTarget}/release/libquiche.a")
            if (!quicheLib.exists()) throw GradleException("libquiche.a not found at ${quicheLib.absolutePath}")

            // 4. Compile JNI shim with NDK clang
            outputDir.mkdirs()
            val ndkHost = if (org.jetbrains.kotlin.konan.target.HostManager.hostIsMac) "darwin-x86_64" else "linux-x86_64"
            val ndkToolchain = ndk.resolve("toolchains/llvm/prebuilt/$ndkHost")
            val ndkClang = ndkToolchain.resolve("bin/${abi.ndkClangPrefix}-clang")
            val llvmStrip = ndkToolchain.resolve("bin/llvm-strip")
            val javaHome =
                System.getenv("JAVA_HOME") ?: org.gradle.internal.jvm.Jvm
                    .current()
                    .javaHome.absolutePath

            logger.lifecycle("Compiling JNI shim for ${abi.abi}...")
            val clangResult =
                ProcessBuilder(
                    ndkClang.absolutePath,
                    "-shared",
                    "-fPIC",
                    "-Wall",
                    "-o",
                    outputLib.absolutePath,
                    projectDir.resolve("src/jni/quiche_jni.c").absolutePath,
                    "-I$javaHome/include",
                    // jni_md.h lives in a host-platform-specific subdir of $JAVA_HOME/include.
                    // Android NDK's toolchain doesn't provide jni.h, so we source it from the
                    // host JDK running Gradle.
                    "-I$javaHome/include/${if (org.gradle.internal.os.OperatingSystem
                            .current()
                            .isMacOsX
                    ) {
                        "darwin"
                    } else {
                        "linux"
                    }}",
                    "-I${projectDir.resolve("libs/quiche/include").absolutePath}",
                    "-Wl,--whole-archive",
                    quicheLib.absolutePath,
                    "-Wl,--no-whole-archive",
                    "-lm",
                    "-ldl",
                    "-llog",
                ).redirectErrorStream(true)
                    .start()
                    .also { it.inputStream.bufferedReader().forEachLine { line -> logger.lifecycle(line) } }
                    .waitFor()
            if (clangResult != 0) throw GradleException("JNI shim compilation failed for ${abi.abi}")

            ProcessBuilder(llvmStrip.absolutePath, "--strip-all", outputLib.absolutePath).start().waitFor()

            logger.lifecycle("Built: ${outputLib.absolutePath} (${outputLib.length() / 1024}KB)")
        }
    }
}

val androidJniTasks = androidAbis.mapNotNull { createBuildAndroidJniTask(it) }

if (androidJniTasks.isNotEmpty()) {
    tasks.register("buildAndroidNativeLibs") {
        group = "build"
        description = "Build quiche JNI shims for all Android ABIs"
        dependsOn(androidJniTasks)
    }

    // Ensure JNI shims are built before any connected Android test and
    // before Gradle's own jniLib-folder merge (Gradle 8.14 strict mode
    // flags mergeJniLibFolders as having an implicit dependency on the
    // buildAndroidJni* task outputs via src/androidMain/jniLibs/<abi>/).
    afterEvaluate {
        tasks
            .matching {
                (it.name.startsWith("connected") && it.name.contains("AndroidTest")) ||
                    (it.name.startsWith("merge") && it.name.endsWith("JniLibFolders"))
            }.configureEach {
                dependsOn(androidJniTasks)
            }
    }
}

// --- QUIC test server for Android instrumented tests ---
// Starts a QUIC echo server on the host so the emulator can connect via adb reverse.

afterEvaluate {
    val quicTestServerPidFile =
        layout.buildDirectory
            .file("quic-test-server.pid")
            .get()
            .asFile

    tasks.register("startQuicTestServer") {
        group = "verification"
        description = "Start a QUIC echo server on localhost:4433 for Android instrumented tests"
        dependsOn("compileTestKotlinJvm", "jvmTestProcessResources")

        doLast {
            if (quicTestServerPidFile.exists()) {
                val oldPid = quicTestServerPidFile.readText().trim()
                ProcessBuilder("kill", oldPid).start().waitFor()
                quicTestServerPidFile.delete()
            }

            val testClasspath =
                kotlin
                    .jvm()
                    .compilations["test"]
                    .runtimeDependencyFiles.files +
                    kotlin
                        .jvm()
                        .compilations["test"]
                        .output.allOutputs.files +
                    kotlin
                        .jvm()
                        .compilations["main"]
                        .output.allOutputs.files +
                    kotlin
                        .jvm()
                        .compilations["java21"]
                        .output.allOutputs.files

            val classpathStr = testClasspath.joinToString(File.pathSeparator)
            val javaExec =
                org.gradle.internal.jvm.Jvm
                    .current()
                    .javaExecutable.absolutePath
            val certDir = projectDir.resolve("testcerts")

            val process =
                ProcessBuilder(
                    javaExec,
                    "--enable-native-access=ALL-UNNAMED",
                    "--add-opens",
                    "java.base/java.nio=ALL-UNNAMED",
                    "-cp",
                    classpathStr,
                    "com.ditchoom.socket.quic.QuicEchoTestServerKt",
                    certDir.resolve("cert.crt").absolutePath,
                    certDir.resolve("cert.key").absolutePath,
                ).redirectErrorStream(true)
                    .start()

            quicTestServerPidFile.parentFile.mkdirs()
            quicTestServerPidFile.writeText(process.pid().toString())

            // Wait for server to be ready (looks for "READY" on stdout)
            val reader = process.inputStream.bufferedReader()
            val deadline = System.currentTimeMillis() + 10_000
            while (System.currentTimeMillis() < deadline) {
                if (reader.ready()) {
                    val line = reader.readLine() ?: break
                    logger.lifecycle("[quic-server] $line")
                    if (line.contains("READY")) break
                }
                Thread.sleep(100)
            }
            if (!process.isAlive) throw GradleException("QUIC test server failed to start")

            // Set up adb reverse so emulator localhost:4433 → host:4433
            ProcessBuilder("adb", "reverse", "tcp:4433", "tcp:4433")
                .redirectErrorStream(true)
                .start()
                .waitFor()

            logger.lifecycle("QUIC test server running (PID ${process.pid()}), adb reverse configured")
        }
    }

    tasks.register("stopQuicTestServer") {
        group = "verification"
        description = "Stop the QUIC echo test server"
        doLast {
            if (quicTestServerPidFile.exists()) {
                val pid = quicTestServerPidFile.readText().trim()
                ProcessBuilder("kill", pid).start().waitFor()
                quicTestServerPidFile.delete()
                logger.lifecycle("Stopped QUIC test server (PID $pid)")
            }
        }
    }

    // --- QUIC echo fat-jar for the test-harness Docker container ---
    // Produces a single self-contained jar (Main-Class = QuicEchoTestServerKt)
    // that wraps every JVM test runtime dep + the compiled test classes + the
    // host quiche JNI natives, then copies it to test-harness/quic-echo/quic-echo.jar
    // so the Dockerfile's COPY picks it up.
    //
    // The harness orchestrator wires this as a `dependsOn` of `harnessUp`
    // (see root build.gradle.kts) so `docker compose up` always sees a fresh
    // jar that matches the current source.
    //
    // Why a fat jar and not a thin jar + Gradle classpath script: Docker can
    // run a single `java -jar` with zero host knowledge; the alternative
    // (mounting Gradle's classpath cache into the container) would couple
    // the image to the build environment.
    tasks.register<Jar>("quicEchoJar") {
        group = "build"
        description =
            "Build a runnable fat jar of QuicEchoTestServer for test-harness/quic-echo. " +
            "Output: test-harness/quic-echo/quic-echo.jar"
        dependsOn("compileTestKotlinJvm", "jvmTestProcessResources", "prepareQuicheNativeLib", "stageQuicheNativeResources")

        archiveBaseName.set("quic-echo")
        archiveVersion.set("")
        destinationDirectory.set(rootProject.projectDir.resolve("test-harness/quic-echo"))

        manifest {
            attributes(
                "Main-Class" to "com.ditchoom.socket.quic.QuicEchoTestServerKt",
                "Multi-Release" to "true",
            )
        }

        // Bundle every dep: kotlin stdlib, kotlinx-coroutines, ditchoom-buffer,
        // the socket-quic jvmMain + commonJvmMain + commonMain classes, plus
        // the compiled jvmTest classes (where QuicEchoTestServer lives). Then
        // overlay the staged quiche natives (META-INF/native/<os>-<arch>/).
        val jvm = kotlin.jvm()
        val testCompilation = jvm.compilations["test"]
        val mainCompilation = jvm.compilations["main"]
        val java21Compilation = jvm.compilations["java21"]

        from(testCompilation.output.allOutputs)
        from(mainCompilation.output.allOutputs)
        // Multi-release: ship the JDK-21 FFM bindings under META-INF/versions/21
        into("META-INF/versions/21") {
            from(java21Compilation.output.allOutputs)
        }
        // The staged-natives task drops files under stagedNativeResourcesDir
        // already prefixed with META-INF/native/<os>-<arch>/, so a plain `from`
        // preserves that layout inside the jar.
        from(stagedNativeResourcesDir)

        // Unpack every dep jar so the resulting fat jar is `java -jar`-able.
        // Duplicate META-INF entries (manifests, signatures, services) would
        // otherwise break the jar; EXCLUDE picks the first occurrence.
        from({
            testCompilation.runtimeDependencyFiles.files
                .filter { it.isFile && it.name.endsWith(".jar") }
                .map { zipTree(it) }
        })

        duplicatesStrategy = DuplicatesStrategy.EXCLUDE
        // Drop signing metadata from upstream jars — fat-jar invalidates signatures.
        exclude("META-INF/*.SF", "META-INF/*.DSA", "META-INF/*.RSA", "META-INF/INDEX.LIST", "module-info.class")

        // Side effect: also stage the self-signed cert/key alongside the jar so
        // the Dockerfile's COPY picks them up. Kept next to the jar so the
        // Dockerfile context is a single, self-contained directory.
        doLast {
            val certDir = projectDir.resolve("testcerts")
            val outDir = rootProject.projectDir.resolve("test-harness/quic-echo/testcerts")
            outDir.mkdirs()
            certDir.resolve("cert.crt").copyTo(outDir.resolve("cert.crt"), overwrite = true)
            certDir.resolve("cert.key").copyTo(outDir.resolve("cert.key"), overwrite = true)
        }
    }

    // --- Network control server for migration tests ---
    val netCtrlPidFile =
        layout.buildDirectory
            .file("network-control-server.pid")
            .get()
            .asFile

    tasks.register("startNetworkControlServer") {
        group = "verification"
        description = "Start the network control server on localhost:9998 for migration tests"
        dependsOn("compileTestKotlinJvm", "jvmTestProcessResources")

        doLast {
            if (netCtrlPidFile.exists()) {
                val oldPid = netCtrlPidFile.readText().trim()
                ProcessBuilder("kill", oldPid).start().waitFor()
                netCtrlPidFile.delete()
            }

            val testClasspath =
                kotlin
                    .jvm()
                    .compilations["test"]
                    .runtimeDependencyFiles.files +
                    kotlin
                        .jvm()
                        .compilations["test"]
                        .output.allOutputs.files +
                    kotlin
                        .jvm()
                        .compilations["main"]
                        .output.allOutputs.files +
                    kotlin
                        .jvm()
                        .compilations["java21"]
                        .output.allOutputs.files

            val classpathStr = testClasspath.joinToString(File.pathSeparator)
            val javaExec =
                org.gradle.internal.jvm.Jvm
                    .current()
                    .javaExecutable.absolutePath

            val process =
                ProcessBuilder(
                    javaExec,
                    "--enable-native-access=ALL-UNNAMED",
                    "--add-opens",
                    "java.base/java.nio=ALL-UNNAMED",
                    "-cp",
                    classpathStr,
                    "com.ditchoom.socket.quic.netctrl.NetworkControlServerKt",
                ).redirectErrorStream(true)
                    .start()

            netCtrlPidFile.parentFile.mkdirs()
            netCtrlPidFile.writeText(process.pid().toString())

            val reader = process.inputStream.bufferedReader()
            val deadline = System.currentTimeMillis() + 10_000
            while (System.currentTimeMillis() < deadline) {
                if (reader.ready()) {
                    val line = reader.readLine() ?: break
                    logger.lifecycle("[net-ctrl] $line")
                    if (line.contains("READY")) break
                }
                Thread.sleep(100)
            }
            if (!process.isAlive) throw GradleException("Network control server failed to start")

            ProcessBuilder("adb", "reverse", "tcp:9998", "tcp:9998")
                .redirectErrorStream(true)
                .start()
                .waitFor()

            logger.lifecycle("Network control server running (PID ${process.pid()}), adb reverse configured")
        }
    }

    tasks.register("stopNetworkControlServer") {
        group = "verification"
        description = "Stop the network control server"
        doLast {
            if (netCtrlPidFile.exists()) {
                val pid = netCtrlPidFile.readText().trim()
                ProcessBuilder("kill", pid).start().waitFor()
                netCtrlPidFile.delete()
                logger.lifecycle("Stopped network control server (PID $pid)")
            }
        }
    }

    tasks.register("androidQuicIntegrationTest") {
        group = "verification"
        description = "Build native libs, start servers, run Android instrumented tests, stop servers"
        dependsOn("startQuicTestServer", "startNetworkControlServer")
        finalizedBy("stopQuicTestServer", "stopNetworkControlServer")
        doLast {
            val result =
                ProcessBuilder(
                    "${rootProject.projectDir}/gradlew",
                    ":socket-quic:connectedDebugAndroidTest",
                ).directory(rootProject.projectDir)
                    .inheritIO()
                    .start()
                    .waitFor()
            if (result != 0) throw GradleException("Android instrumented tests failed")
        }
    }
}

// --- QuicHarnessConfig codegen ---
// socket-quic's commonTest is a separate source set from the root project's
// commonTest (so the root's generated HarnessConfig isn't visible here).
// Mirror just the values we need by reading the root's harness.env, then
// emitting a tiny `QuicHarnessConfig` object into a generated commonTest
// source dir. Single source of truth stays at test-harness/harness.env.
val quicHarnessEnvFile = rootProject.projectDir.resolve("test-harness/harness.env")
val quicHarnessCaCertFile = rootProject.projectDir.resolve("test-harness/tls/certs/ca.crt")
val quicHarnessGeneratedDir = layout.buildDirectory.dir("generated/harness/commonTest")
val generateQuicHarnessConfig =
    tasks.register("generateQuicHarnessConfig") {
        group = "verification"
        description = "Generate QuicHarnessConfig.kt from test-harness/harness.env"
        inputs.file(quicHarnessEnvFile)
        // The harness CA is generated by test-harness/tls/gen-certs.sh and may be
        // absent (e.g. Linux/JVM runs that don't need pinning). inputs.files()
        // tolerates the missing file and tracks its content when present.
        inputs.files(quicHarnessCaCertFile)
        outputs.dir(quicHarnessGeneratedDir)
        doLast {
            val env =
                quicHarnessEnvFile
                    .readLines()
                    .filter { it.isNotBlank() && !it.trimStart().startsWith("#") }
                    .associate { line ->
                        val i = line.indexOf('=')
                        require(i > 0) { "Malformed harness.env line: '$line'" }
                        line.substring(0, i).trim() to line.substring(i + 1).trim()
                    }
            val host = env["HARNESS_HOST"] ?: error("Missing HARNESS_HOST in $quicHarnessEnvFile")
            // QUIC_ECHO_PORT may be commented out until the orchestrator merges the
            // Phase-4 env snippet — fall back to 14433 (the value from the spec) so
            // the codegen doesn't fail standalone. Tests use isHarnessAvailable() to
            // skip if the harness isn't actually up.
            val port = env["QUIC_ECHO_PORT"] ?: "14433"
            // Embed the harness CA (PEM) so the cross-platform test can pin it as a
            // trust anchor on Apple (Network.framework rejects the private CA on the
            // QUIC path otherwise — issue #81). Null when certs aren't generated.
            val caCertPem = quicHarnessCaCertFile.takeIf { it.exists() }?.readText()?.trim()
            val pkgDir =
                quicHarnessGeneratedDir
                    .get()
                    .asFile
                    .resolve("com/ditchoom/socket/quic/harness")
            pkgDir.mkdirs()
            pkgDir.resolve("QuicHarnessConfig.kt").writeText(
                buildString {
                    append("// Generated from test-harness/harness.env — DO NOT EDIT.\n")
                    append("@file:Suppress(\"ktlint:standard:property-naming\")\n\n")
                    append("package com.ditchoom.socket.quic.harness\n\n")
                    append("/**\n")
                    append(" * QUIC echo endpoint exposed by the local docker-compose harness.\n")
                    append(" * Single source of truth: edit test-harness/harness.env and re-run\n")
                    append(" * `:socket-quic:generateQuicHarnessConfig` (auto-fired by every test task).\n")
                    append(" */\n")
                    append("internal object QuicHarnessConfig {\n")
                    append("    const val host: String = \"$host\"\n")
                    append("    const val quicEchoPort: Int = $port\n")
                    append("    /** ALPN advertised by the harness QUIC echo server (QuicEchoTestServer). */\n")
                    append("    const val alpn: String = \"test\"\n")
                    append("    /**\n")
                    append("     * Harness CA in PEM, for pinning as a trust anchor where the platform's\n")
                    append("     * default trust rejects the private CA (Apple QUIC — issue #81). Null when\n")
                    append("     * the cert matrix hasn't been generated (test-harness/tls/gen-certs.sh).\n")
                    append("     */\n")
                    if (caCertPem != null) {
                        // Raw triple-quoted PEM. The consumer parses by BEGIN/END
                        // markers and ignores surrounding whitespace, so no escaping
                        // or trimIndent is needed (base64 bodies contain no `"` or `$`).
                        append("    val caCertPem: String? =\n")
                        append("        \"\"\"")
                        append(caCertPem)
                        append("\"\"\"\n")
                    } else {
                        append("    val caCertPem: String? = null\n")
                    }
                    append("}\n")
                },
            )
        }
    }

kotlin {
    jvmToolchain(21)

    androidTarget {
        compilerOptions.jvmTarget.set(JvmTarget.JVM_17)
    }
    jvm {
        compilerOptions.jvmTarget.set(JvmTarget.JVM_1_8)
        // Java 21 compilation for FFM (Foreign Function & Memory) quiche bindings
        compilations.create("java21") {
            compilerOptions.configure {
                jvmTarget.set(JvmTarget.JVM_21)
            }
            defaultSourceSet {
                kotlin.srcDir("src/jvm21Main/kotlin")
                dependencies {
                    implementation(
                        this@jvm
                            .compilations
                            .getByName("main")
                            .output
                            .classesDirs,
                    )
                    // The FFM bindings reference the QUIC SPI types (QuicStreamId, QuicError,
                    // MaxDatagramSize, …) that live in the :socket-quic dependency. The `main`
                    // compilation gets them transitively, but this separate java21 compilation
                    // only inherited main's *output*, not its dependencies — so add them here.
                    implementation(project(":socket-quic"))
                    implementation(project(":"))
                    implementation(libs.kotlinx.coroutines.core)
                }
            }
        }
    }

    if (isLinux) {
        linuxX64 {
            compilations["main"].cinterops {
                create("Quiche") {
                    defFile("src/nativeInterop/cinterop/Quiche.def")
                    includeDirs("libs/quiche/include")
                }
                // BoringSSL X.509 bindings for the W3C serverCertificateHashes cert-constraint parser
                // (PinnedLeafFields.linux.kt). Headers come from the base :socket: module's BoringSSL
                // build; the symbols are already linked into this binary via that module's cinterop
                // (staticLibraries.linux = libcrypto.a), so no extra linkerOpts are added here.
                create("BoringSslX509") {
                    defFile("src/nativeInterop/cinterop/BoringSslX509.def")
                    includeDirs(
                        rootProject.projectDir.resolve("libs/boringssl/linux-x64/include").absolutePath,
                        "/usr/include",
                        "/usr/include/x86_64-linux-gnu",
                    )
                    tasks.named(interopProcessingTaskName) {
                        dependsOn(rootProject.tasks.named("buildBoringsslX64"))
                    }
                }
            }
            // Link against quiche — prefer static if no OpenSSL conflict, else shared
            val quicheLibDir = projectDir.resolve("libs/quiche/linux-x64/lib")
            val quicheShared = quicheLibDir.resolve("libquiche_jni.so") // self-contained, no OpenSSL conflict
            val quicheStatic = quicheLibDir.resolve("libquiche.a")
            if (quicheStatic.exists()) {
                binaries.all {
                    // -Bstatic around -lquiche forces ld.lld to pick libquiche.a, not the sibling
                    // libquiche.so (which has unresolved SSL_* refs and fails --no-allow-shlib-undefined).
                    // whole-archive pulls in all quiche symbols. BSSL archives are transitively
                    // contributed by the base :socket: module's cinterop (staticLibraries.linux =
                    // libssl.a libcrypto.a in LinuxSockets.def); we don't link them again here to
                    // avoid duplicate work. allow-multiple-definition covers any residual overlap.
                    linkerOpts(
                        "-L${quicheLibDir.absolutePath}",
                        "-Wl,-Bstatic",
                        "-Wl,--whole-archive",
                        "-lquiche",
                        "-Wl,--no-whole-archive",
                        "-Wl,-Bdynamic",
                        "-lpthread",
                        "-ldl",
                        "-lm",
                        "-lstdc++",
                        "--unresolved-symbols=ignore-in-object-files",
                        "--allow-multiple-definition",
                        "-Wl,--defsym=QUICHE_BSSL=1", // marker to indicate quiche BoringSSL is linked
                    )
                }
            }
        }
        linuxArm64 {
            compilations["main"].cinterops {
                create("Quiche") {
                    defFile("src/nativeInterop/cinterop/Quiche.def")
                    includeDirs("libs/quiche/include")
                }
                // See linuxX64: BoringSSL X.509 bindings for the cert-constraint parser. Symbols are
                // contributed transitively by the base :socket: module's arm64 cinterop.
                create("BoringSslX509") {
                    defFile("src/nativeInterop/cinterop/BoringSslX509.def")
                    includeDirs(
                        rootProject.projectDir.resolve("libs/boringssl/linux-arm64/include").absolutePath,
                        "/usr/aarch64-linux-gnu/include",
                        "/usr/include/aarch64-linux-gnu",
                    )
                    tasks.named(interopProcessingTaskName) {
                        dependsOn(rootProject.tasks.named("buildBoringsslArm64"))
                    }
                }
            }
            val quicheLibArm64 = projectDir.resolve("libs/quiche/linux-arm64/lib/libquiche.a")
            if (quicheLibArm64.exists()) {
                binaries.all {
                    // Mirror linuxX64: -Bstatic forces static quiche; BSSL comes from the base
                    // :socket: module's cinterop (staticLibraries.linux = libssl.a libcrypto.a).
                    linkerOpts(
                        "-L${quicheLibArm64.parentFile.absolutePath}",
                        "-Wl,-Bstatic",
                        "-Wl,--whole-archive",
                        "-lquiche",
                        "-Wl,--no-whole-archive",
                        "-Wl,-Bdynamic",
                        "-lpthread",
                        "-ldl",
                        "-lm",
                        "-lstdc++",
                        "--unresolved-symbols=ignore-in-object-files",
                        "--allow-multiple-definition",
                    )
                }
            }
        }
    }

    // quiche-on-Apple pivot: the Apple QUIC backend is Cloudflare quiche (K/N cinterop into a
    // self-contained libquiche.a with vendored BoringSSL) over a POSIX UDP datapath — replacing the
    // Network.framework system-QUIC backend (the deleted :socket-quic-nw). macOS .a comes from the
    // shared cargo tasks; iOS .a from createBuildQuicheAppleStaticTask. See quiche-on-apple-pivot.
    if (isMacOS) {
        // One configurator for every Apple target: the Quiche + BoringSslSha256 cinterops and the
        // -force_load of that target's self-contained libquiche.a.
        val configureQuicheApple: org.jetbrains.kotlin.gradle.plugin.mpp.KotlinNativeTarget.(String) -> Unit = { libSubdir ->
            compilations["main"].cinterops {
                create("Quiche") {
                    defFile("src/nativeInterop/cinterop/Quiche.def")
                    includeDirs("libs/quiche/include")
                }
                // SHA-256 for the W3C serverCertificateHashes leaf-cert pin. Binds BoringSSL's SHA256
                // already vendored into (and force-loaded from) libquiche.a — collision-free on Apple
                // since quiche's BoringSSL is the only crypto lib present. Inline C prototype, no header
                // (the Apple cinterop path resolved no system include). See BoringSslSha256.def.
                create("BoringSslSha256") {
                    defFile("src/nativeInterop/cinterop/BoringSslSha256.def")
                }
            }
            val quicheStatic = projectDir.resolve("libs/quiche/$libSubdir/lib/libquiche.a")
            if (quicheStatic.exists()) {
                binaries.all {
                    // libquiche.a is self-contained (vendored BoringSSL — see the .bssl-vendored marker
                    // next to it), so no external crypto archives. Apple ld64 differs from GNU ld: use
                    // -force_load (not --whole-archive) to pull every quiche object. Link the libraries
                    // Rust std + BoringSSL reference on Darwin: Security/CoreFoundation frameworks plus
                    // libc++ for BoringSSL's C++ TUs.
                    linkerOpts(
                        "-force_load",
                        quicheStatic.absolutePath,
                        "-framework",
                        "Security",
                        "-framework",
                        "CoreFoundation",
                        "-lc++",
                    )
                }
            }
        }
        macosArm64 { configureQuicheApple("macos-arm64") }
        macosX64 { configureQuicheApple("macos-x64") }
        iosArm64 { configureQuicheApple("ios-arm64") }
        iosSimulatorArm64 { configureQuicheApple("ios-simulator-arm64") }
        iosX64 { configureQuicheApple("ios-x64") }
    }

    applyDefaultHierarchyTemplate()
    sourceSets {
        commonMain.dependencies {
            api(project(":"))
            api(project(":socket-quic"))
            implementation(libs.kotlinx.coroutines.core)
        }
        commonTest {
            kotlin.srcDir(generateQuicHarnessConfig.map { quicHarnessGeneratedDir })
            dependencies {
                implementation(kotlin("test"))
                implementation(libs.kotlinx.coroutines.core)
                implementation(libs.kotlinx.coroutines.test)
                // Test-scope back-edge (v6 Phase 2b.4): withQuicConnection/withQuicServer/withQuicMux
                // moved to :socket-quic-default (it owns the public default-engine facade). The quiche
                // engine-behavior suites stay HERE (near the engine they exercise) and drive it through
                // the real public entrypoint via this test-only dependency. No production cycle:
                // default→quiche-main is the only main-scope edge; quiche-test→default is test-scope.
                implementation(project(":socket-quic-default"))
                // Cross-backend conformance suites (abstract *TestSuite + harness). The Jvm*/Linux*
                // wrapper subclasses live in this module's per-platform test source sets; the shared
                // suites they extend live here. Also consumed by :socket-quic-nw (apple).
                implementation(project(":socket-testsuite"))
            }
        }
        val commonJvmMain by creating {
            dependsOn(commonMain.get())
            dependencies {
                // SHA-256 for W3C serverCertificateHashes leaf-cert pinning (sha256Into actual). Pure-JCA
                // on JVM/Android — no native lib. Scoped to JVM/Android ONLY (not commonMain): on Linux,
                // buffer-crypto would link a SECOND static BoringSSL alongside quiche's → duplicate
                // SHA256_* symbols → SIGSEGV. Linux uses the already-linked BoringSSL via cinterop instead
                // (see Sha256.linux.kt). Even with no call, a commonMain dep would pull buffer-crypto's
                // libcrypto.a onto the Linux link line, so the scoping is load-bearing, not cosmetic.
                implementation(libs.buffer.crypto)
            }
        }
        jvmMain.get().dependsOn(commonJvmMain)
        androidMain.get().dependsOn(commonJvmMain)
        androidMain.dependencies {
            // com.ditchoom:buffer-android:5.2.0 references kotlinx.atomicfu.AtomicFU at runtime but
            // doesn't declare the atomicfu dependency in its module metadata, so it isn't on a
            // consumer's runtime classpath — any Android app exercising the QUIC connect/StateFlow
            // path crashes with NoClassDefFoundError: kotlinx.atomicfu.AtomicFU. (JVM is unaffected:
            // coroutines/buffer JVM artifacts are atomicfu-transformed.) Provide the runtime atomicfu
            // so Android consumers — and the instrumented tests, which silently skipped on this for
            // ages via their connect-failure catch — actually work. Version matches coroutines 1.10.2.
            implementation("org.jetbrains.kotlinx:atomicfu:0.33.0")
        }
        val commonJvmTest by creating {
            dependsOn(commonTest.get())
            kotlin.srcDir("src/sharedJvmTestProtocol/kotlin")
        }
        jvmTest.get().dependsOn(commonJvmTest)
        val androidUnitTest by getting {
            dependencies {
                implementation(kotlin("test"))
            }
        }
        val androidInstrumentedTest by getting {
            kotlin.srcDir("src/sharedJvmTestProtocol/kotlin")
            dependencies {
                implementation(kotlin("test"))
                implementation(libs.kotlinx.coroutines.test)
                implementation("androidx.test:runner:1.7.0")
                implementation("androidx.test.ext:junit:1.3.0")
                // androidInstrumentedTest does NOT dependsOn commonTest, so the test-scope back-edge to
                // :socket-quic-default (for withQuic*) must be declared here too. See commonTest note.
                implementation(project(":socket-quic-default"))
                // Same reason: the Android* wrappers extend the cross-backend suites, which moved to
                // :socket-testsuite — and this source set doesn't inherit commonTest's deps.
                implementation(project(":socket-testsuite"))
            }
        }
        androidUnitTest.dependsOn(commonJvmTest)
    }
}

// KSP codec processor — wired for JVM and Android test compilations only.
// Generates codecs for @ProtocolMessage types in commonJvmTest (NetCtrlProtocol).
dependencies {
    add("kspJvmTest", libs.buffer.codec.processor)
}
// Android KSP configurations are registered after evaluation
afterEvaluate {
    val kspConfigs = configurations.names.filter { it.startsWith("ksp") && it.contains("Android", ignoreCase = true) }
    kspConfigs.filter { it.contains("Test", ignoreCase = true) }.forEach { configName ->
        dependencies.add(
            configName,
            libs.buffer.codec.processor
                .get(),
        )
    }
}

// Enable FFM native access for JVM tests (quiche uses Panama on JDK 21+)
// --add-opens needed for JNI fallback path (direct ByteBuffer address access)
tasks.withType<Test> {
    jvmArgs("--enable-native-access=ALL-UNNAMED")
    jvmArgs("--add-opens", "java.base/java.nio=ALL-UNNAMED")
}

// Ensure the QUIC harness-config codegen runs before any test task — same
// pattern the root project uses for its HarnessConfig generator. Targets
// every AbstractTestTask (jvmTest, linuxX64Test, kotlinNodeTest, …).
tasks.withType<org.gradle.api.tasks.testing.AbstractTestTask>().configureEach {
    dependsOn(generateQuicHarnessConfig)
    // Per-test progress lines via a TestListener — these print at LIFECYCLE
    // level regardless of testLogging.events gating, which surfaces a hung test
    // immediately in a CI log (last "TEST START" line wins). Bounded volume
    // (one line per start + one per finish, regardless of test duration).
    // testLogging in the root build.gradle.kts only applies to root-project
    // test tasks; this subproject's :socket-quic:jvmTest etc. need their own.
    addTestListener(
        object : org.gradle.api.tasks.testing.TestListener {
            override fun beforeSuite(suite: org.gradle.api.tasks.testing.TestDescriptor) = Unit

            override fun afterSuite(
                suite: org.gradle.api.tasks.testing.TestDescriptor,
                result: org.gradle.api.tasks.testing.TestResult,
            ) = Unit

            override fun beforeTest(testDescriptor: org.gradle.api.tasks.testing.TestDescriptor) {
                logger.lifecycle("TEST START ${testDescriptor.className}.${testDescriptor.name}")
            }

            override fun afterTest(
                testDescriptor: org.gradle.api.tasks.testing.TestDescriptor,
                result: org.gradle.api.tasks.testing.TestResult,
            ) {
                logger.lifecycle(
                    "TEST ${result.resultType} ${testDescriptor.className}.${testDescriptor.name} " +
                        "(${result.endTime - result.startTime}ms)",
                )
            }
        },
    )
    // Same testLogging the root project applies — re-stated here because that
    // root-level configureEach doesn't cross project boundaries.
    testLogging {
        events("failed", "skipped")
        showExceptions = true
        showCauses = true
        showStackTraces = true
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }
}
// Compilation tasks that index commonTest sources also need the generated
// file present before they run; without this they fail to resolve
// QuicHarnessConfig references in QuicHarnessIntegrationTests.
tasks.matching { it.name.startsWith("compileTestKotlin") || it.name.contains("TestKotlin") }.configureEach {
    dependsOn(generateQuicHarnessConfig)
}

android {
    compileSdk = 36
    // AGP 9 + legacy-DSL opt-out: the `sourceSets[...]` Kotlin accessor casts to the
    // removed old API. Reach the source set via the new DSL interface instead.
    (this as com.android.build.api.dsl.LibraryExtension).sourceSets.getByName("main").apply {
        manifest.srcFile("src/androidMain/AndroidManifest.xml")
        jniLibs.srcDirs("src/androidMain/jniLibs")
    }
    defaultConfig {
        minSdk = 24
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    namespace = "com.ditchoom.socket.quic.quiche"

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

// Add Java 21 compilation output to test classpath so FFM tests can reference the bindings
kotlin
    .jvm()
    .compilations
    .getByName("test") {
        val java21Output =
            kotlin
                .jvm()
                .compilations["java21"]
                .output
                .classesDirs
        compileDependencyFiles += java21Output
        runtimeDependencyFiles += java21Output
    }

// Configure JVM JAR after evaluation (task created by KMP plugin).
// Base jvmJar is code-only — native libs ship in classifier JARs below, so
// consumers pull only the natives matching their host. The exclude() below
// strips natives that would otherwise get picked up from
// src/jvmMain/resources/META-INF/native/ (where CI's build-linux.yaml writes
// its JNI shims today — leaving them there for the jvmTest classpath).
afterEvaluate {
    tasks.named<Jar>("jvmJar") {
        duplicatesStrategy = DuplicatesStrategy.EXCLUDE
        exclude("META-INF/native/**")
        // Multi-release JAR: JDK 21+ gets FFM quiche bindings
        manifest {
            attributes("Multi-Release" to "true")
        }
        into("META-INF/versions/21") {
            from(
                kotlin
                    .jvm()
                    .compilations["java21"]
                    .output
                    .allOutputs,
            )
        }
    }
}

// --- Per-host native classifier JARs ---
// Consumers add the matching classifier as a runtime-only dep, e.g.:
//   runtimeOnly("com.ditchoom:socket-quic:3.x:macos-aarch64@jar")
// This is the same pattern netty-tcnative-boringssl-static uses.

// Gradle classifier naming convention uses the "os-arch" strings that Maven
// ecosystems expect (aarch64 not arm64, x86_64 not x64). Map our internal
// $os-$arch keys to those.
data class NativeClassifier(
    val key: String, // internal — matches libs/quiche/<key>/lib
    val classifier: String, // maven classifier — consumer-facing
)

val nativeClassifiers =
    listOf(
        NativeClassifier("macos-arm64", "macos-aarch64"),
        NativeClassifier("macos-x64", "macos-x86_64"),
        NativeClassifier("linux-x64", "linux-x86_64"),
        NativeClassifier("linux-arm64", "linux-aarch64"),
    )

val nativeClassifierJars =
    nativeClassifiers.map { (key, classifier) ->
        val libs = nativeLibsByPlatform.getValue(key)
        val libDir = projectDir.resolve("libs/quiche/$key/lib")
        val taskSuffix = classifier.split("-").joinToString("") { it.replaceFirstChar { c -> c.uppercase() } }
        tasks.register<Jar>("jvmNativesJar$taskSuffix") {
            group = "build"
            description = "Package quiche natives for $classifier (consumed as runtime classifier JAR)"
            archiveClassifier.set(classifier)
            archiveBaseName.set(artifactName)
            destinationDirectory.set(layout.buildDirectory.dir("libs"))
            into("META-INF/native/$key") {
                from(libDir) { include(*libs.toTypedArray()) }
            }
            // Only produce the jar if the native lib was built for this platform.
            onlyIf { libs.all { libDir.resolve(it).exists() } }
        } to classifier
    }

// Register each classifier JAR as an additional artifact on the jvm Maven
// publication so publishToMavenLocal / publishToMavenCentral emit them
// alongside the base socket-quic-jvm artifact. Only attach if the platform's
// native libs exist on the host — a Mac build attaches macos-* classifiers;
// Linux CI attaches linux-*. A release assembly step should merge platforms.
afterEvaluate {
    publishing {
        publications.withType<MavenPublication>().matching { it.name == "jvm" }.configureEach {
            nativeClassifiers.forEach { (key, classifier) ->
                val libDir = projectDir.resolve("libs/quiche/$key/lib")
                val libs = nativeLibsByPlatform.getValue(key)
                val allPresent = libs.all { libDir.resolve(it).exists() }
                if (allPresent) {
                    val jarTask = nativeClassifierJars.first { it.second == classifier }.first
                    artifact(jarTask) {
                        this.classifier = classifier
                    }
                }
            }
        }
    }
}

// --- Publishing ---

val publishedGroupId: String by project
val libraryName: String by project
val artifactName: String by project
val libraryDescription: String by project
val siteUrl: String by project
val gitUrl: String by project
val licenseName: String by project
val licenseUrl: String by project
val developerOrg: String by project
val developerName: String by project
val developerEmail: String by project
val developerId: String by project

project.group = publishedGroupId
project.version = rootProject.version

val signingInMemoryKey = project.findProperty("signingInMemoryKey")
val signingInMemoryKeyPassword = project.findProperty("signingInMemoryKeyPassword")
val shouldSignAndPublish = isMainBranchGithub && signingInMemoryKey is String && signingInMemoryKeyPassword is String

if (shouldSignAndPublish) {
    signing {
        useInMemoryPgpKeys(signingInMemoryKey as String, signingInMemoryKeyPassword as String)
        sign(publishing.publications)
    }
}

mavenPublishing {
    if (shouldSignAndPublish) {
        publishToMavenCentral()
        signAllPublications()
    }

    coordinates(publishedGroupId, artifactName, project.version.toString())

    pom {
        name.set(libraryName)
        description.set(libraryDescription)
        url.set(siteUrl)
        licenses {
            license {
                name.set(licenseName)
                url.set(licenseUrl)
            }
        }
        developers {
            developer {
                id.set(developerId)
                name.set(developerName)
                email.set(developerEmail)
            }
        }
        organization {
            name.set(developerOrg)
        }
        scm {
            connection.set(gitUrl)
            developerConnection.set(gitUrl)
            url.set(siteUrl)
        }
    }
}

ktlint {
    verbose.set(true)
    outputToConsole.set(true)
    android.set(true)
    filter {
        exclude("**/generated/**")
        exclude("**/build/**")
    }
}

// (The iOS-simulator QUIC harness booted-mode wiring — issue #81 — lives in :socket-quic-nw, the
// module whose appleTest runs the Apple QUIC suites. This module has no Apple target.)

// --- Self-signed `localhost` test identity, GENERATED at build time (issues #112 / #99) ---
// The QUIC CA-pinning tests (QuicServerTestSuite.pinnedCorrectCaAnchor.../pinnedWrongCaAnchor...)
// do a REAL TLS chain validation, so the localhost identity must satisfy BOTH stacks at once:
//   * Apple's SecTrust rejects any TLS cert whose validity exceeds 398 days
//     (errSecCertificateNotStandardsCompliant) — so it MUST be short-lived, which means it can't
//     be committed "forever" (a 100-year cert passed on quiche but hung every Apple handshake).
//   * quiche/BoringSSL only accepts a self-signed cert as a pinned trust ANCHOR when it is CA:TRUE.
// Generating it fresh (397-day, CA:TRUE, serverAuth EKU, SAN localhost+127.0.0.1) keeps it
// perpetually valid on every platform with ZERO committed expiry. We use `keytool` — it ships with
// the JDK the build already requires, so it's portable to the Windows jvmTest runner (unlike
// openssl, which isn't guaranteed there) — plus a pure-JVM PKCS#8 key export. The PEM pair then
// feeds the openssl p12 step below, which only runs on macOS for the Apple server.
//
// Written to every dir a consumer reads (each test source set has its own copy; all git-ignored):
//   testcerts/                        → Apple K/N + Linux (cwd-relative) + the p12 source below
//   src/jvmTest/resources/certs/      → JVM (classpath, incl. the Windows runner)
//   src/androidInstrumentedTest/...   → Android instrumented tests (classpath)
val localhostCertDirs =
    listOf(
        projectDir.resolve("testcerts"),
        projectDir.resolve("src/jvmTest/resources/certs"),
        projectDir.resolve("src/androidInstrumentedTest/resources/certs"),
    )
val generateLocalhostCert =
    tasks.register("generateLocalhostCert") {
        group = "verification"
        description = "Generate the short-lived self-signed localhost cert+key the QUIC CA-pinning tests pin."
        outputs.files(localhostCertDirs.flatMap { listOf(it.resolve("localhost.crt"), it.resolve("localhost.key")) })
        // No declared inputs: regenerate only when an output is missing (fresh/clean checkout),
        // otherwise stay up-to-date — so one dev session reuses a single cert and incremental
        // builds don't churn. CI starts clean, so CI always mints a fresh (never-expiring) cert.
        doLast {
            val javaHome = File(System.getProperty("java.home"))
            val isWindows = System.getProperty("os.name").startsWith("Windows", ignoreCase = true)
            val keytool = javaHome.resolve(if (isWindows) "bin/keytool.exe" else "bin/keytool").absolutePath
            val tmpP12 = temporaryDir.resolve("localhost.p12")
            tmpP12.delete()

            fun keytool(vararg args: String): Process = ProcessBuilder(listOf(keytool) + args).redirectErrorStream(false).start()

            val gen =
                keytool(
                    "-genkeypair",
                    "-alias",
                    "localhost",
                    "-keyalg",
                    "RSA",
                    "-keysize",
                    "2048",
                    "-sigalg",
                    "SHA256withRSA",
                    "-validity",
                    "397",
                    "-dname",
                    "CN=localhost",
                    "-ext",
                    "san=dns:localhost,ip:127.0.0.1",
                    "-ext",
                    "eku=serverAuth",
                    "-ext",
                    "bc:critical=ca:true",
                    "-keystore",
                    tmpP12.absolutePath,
                    "-storetype",
                    "PKCS12",
                    "-storepass",
                    "testpass",
                    "-keypass",
                    "testpass",
                )
            val genErr = gen.errorStream.bufferedReader().readText()
            if (gen.waitFor() != 0) throw GradleException("keytool -genkeypair failed:\n$genErr")

            // Cert → PEM via keytool; private key → PKCS#8 PEM via the JDK KeyStore (no openssl).
            val exp = keytool("-exportcert", "-rfc", "-alias", "localhost", "-keystore", tmpP12.absolutePath, "-storepass", "testpass")
            val certPem = exp.inputStream.bufferedReader().readText()
            val expErr = exp.errorStream.bufferedReader().readText()
            if (exp.waitFor() != 0) throw GradleException("keytool -exportcert failed:\n$expErr")

            val ks = KeyStore.getInstance("PKCS12")
            tmpP12.inputStream().use { ks.load(it, "testpass".toCharArray()) }
            val key = ks.getKey("localhost", "testpass".toCharArray()) as PrivateKey
            val b64 = Base64.getMimeEncoder(64, "\n".toByteArray()).encodeToString(key.encoded)
            val keyPem = "-----BEGIN PRIVATE KEY-----\n$b64\n-----END PRIVATE KEY-----\n"

            localhostCertDirs.forEach { dir ->
                dir.mkdirs()
                dir.resolve("localhost.crt").writeText(certPem)
                dir.resolve("localhost.key").writeText(keyPem)
            }
        }
    }

// --- W3C `serverCertificateHashes` constraint fixtures, GENERATED at build time (Phase 4) ---
// The serverCertificateHashes pinning verifier additionally enforces the W3C leaf-cert constraints
// (validity ≤ 14 days, currently valid, ECDSA P-256) once the leaf hash matches. The existing
// `cert.crt` (RSA-2048, 2020–2047) violates BOTH ≤14d AND P-256, so it can't be the *accept*
// fixture once enforcement lands. We mint a small matrix instead — one compliant leaf plus one
// dedicated violator per constraint branch — so each backend's native X.509 parser can be proven
// against an isolated failure mode (the day-precision boundaries live in the deterministic
// `checkServerCertificatePinConstraints` unit tests, not here):
//   pinned          → EC P-256, 13-day, currently valid           → the ACCEPT fixture.
//   pinned-expired  → EC P-256, started 2d ago, 1-day validity    → NotTemporallyValid (expired).
//   pinned-toolong  → EC P-256, 15-day validity                   → ValidityPeriodTooLong (> 14d).
//   pinned-rsa      → RSA-2048, 13-day, currently valid           → UnsupportedPublicKey (not EC).
// Each is emitted as `<name>.crt` + `<name>.key` + `<name>.sha256` (lowercase hex of the leaf DER,
// computed here via java.security MessageDigest — an impl independent of the verifier under test —
// so the Kotlin/Native tests, which have no java.security, can read the expected pin from a file
// instead of hard-coding a hash that would drift every regeneration). All dirs are git-ignored.
data class PinnedFixtureSpec(
    val name: String,
    val keyAlg: String,
    val keyParam: List<String>,
    val sigAlg: String,
    val startDate: String?,
    val validityDays: Int,
)

val pinnedFixtureSpecs =
    listOf(
        PinnedFixtureSpec("pinned", "EC", listOf("-groupname", "secp256r1"), "SHA256withECDSA", null, 13),
        PinnedFixtureSpec("pinned-expired", "EC", listOf("-groupname", "secp256r1"), "SHA256withECDSA", "-2d", 1),
        PinnedFixtureSpec("pinned-toolong", "EC", listOf("-groupname", "secp256r1"), "SHA256withECDSA", null, 15),
        PinnedFixtureSpec("pinned-rsa", "RSA", listOf("-keysize", "2048"), "SHA256withRSA", null, 13),
    )

// Mint each fixture's PEM cert + PKCS#8 key + `.sha256` into [dir]. Pure JVM (keytool + java.security),
// so it runs on every host including the Windows jvmTest runner.
fun generatePinnedFixturesInto(
    dir: File,
    keytool: String,
    tmpRoot: File,
) {
    fun keytool(vararg args: String): Process = ProcessBuilder(listOf(keytool) + args).redirectErrorStream(false).start()
    dir.mkdirs()
    for (spec in pinnedFixtureSpecs) {
        val tmpP12 = tmpRoot.resolve("${spec.name}.p12").also { it.delete() }
        val genArgs =
            buildList {
                addAll(listOf("-genkeypair", "-alias", spec.name, "-keyalg", spec.keyAlg))
                addAll(spec.keyParam)
                addAll(listOf("-sigalg", spec.sigAlg))
                spec.startDate?.let { addAll(listOf("-startdate", it)) }
                addAll(listOf("-validity", spec.validityDays.toString()))
                addAll(listOf("-dname", "CN=localhost"))
                addAll(listOf("-ext", "san=dns:localhost,ip:127.0.0.1"))
                addAll(listOf("-ext", "eku=serverAuth"))
                addAll(listOf("-keystore", tmpP12.absolutePath, "-storetype", "PKCS12", "-storepass", "testpass", "-keypass", "testpass"))
            }
        val gen = keytool(*genArgs.toTypedArray())
        val genErr = gen.errorStream.bufferedReader().readText()
        if (gen.waitFor() != 0) throw GradleException("keytool -genkeypair failed for ${spec.name}:\n$genErr")

        val exp = keytool("-exportcert", "-rfc", "-alias", spec.name, "-keystore", tmpP12.absolutePath, "-storepass", "testpass")
        val certPem = exp.inputStream.bufferedReader().readText()
        val expErr = exp.errorStream.bufferedReader().readText()
        if (exp.waitFor() != 0) throw GradleException("keytool -exportcert failed for ${spec.name}:\n$expErr")

        val ks = KeyStore.getInstance("PKCS12")
        tmpP12.inputStream().use { ks.load(it, "testpass".toCharArray()) }
        val key = ks.getKey(spec.name, "testpass".toCharArray()) as PrivateKey
        val b64 = Base64.getMimeEncoder(64, "\n".toByteArray()).encodeToString(key.encoded)
        val keyPem = "-----BEGIN PRIVATE KEY-----\n$b64\n-----END PRIVATE KEY-----\n"

        val x509 = CertificateFactory.getInstance("X.509").generateCertificate(certPem.byteInputStream()) as X509Certificate
        val sha256Hex = MessageDigest.getInstance("SHA-256").digest(x509.encoded).joinToString("") { "%02x".format(it) }

        dir.resolve("${spec.name}.crt").writeText(certPem)
        dir.resolve("${spec.name}.key").writeText(keyPem)
        dir.resolve("${spec.name}.sha256").writeText(sha256Hex)
    }
}

// True when the `pinned` accept fixture (and, regenerated alongside it, `pinned-rsa`) is still
// comfortably valid — it and pinned-rsa are the only currently-valid-by-design fixtures, so their
// e2e accept / UnsupportedPublicKey tests time-bomb if the leaf expires. Regenerate when any output
// is missing or `pinned` has < 3 days of validity left. CI starts clean so always mints fresh.
fun pinnedFixturesFresh(dir: File): Boolean {
    val pinnedCrt = dir.resolve("pinned.crt")
    val allPresent =
        pinnedFixtureSpecs.all { s ->
            listOf("crt", "key", "sha256").all { ext -> dir.resolve("${s.name}.$ext").exists() }
        }
    if (!allPresent) return false
    return try {
        val cert = pinnedCrt.inputStream().use { CertificateFactory.getInstance("X.509").generateCertificate(it) as X509Certificate }
        cert.notAfter.time - System.currentTimeMillis() > 3L * 24 * 60 * 60 * 1000
    } catch (_: Exception) {
        false
    }
}

val pinnedFixtureExts = listOf("crt", "key", "sha256")
val generatePinnedW3cCerts =
    tasks.register("generatePinnedW3cCerts") {
        group = "verification"
        description = "Generate the W3C serverCertificateHashes constraint fixtures (compliant + per-branch violators)."
        outputs.files(
            localhostCertDirs.flatMap { dir -> pinnedFixtureSpecs.flatMap { s -> pinnedFixtureExts.map { dir.resolve("${s.name}.$it") } } },
        )
        outputs.upToDateWhen { localhostCertDirs.all { pinnedFixturesFresh(it) } }
        doLast {
            val javaHome = File(System.getProperty("java.home"))
            val isWindows = System.getProperty("os.name").startsWith("Windows", ignoreCase = true)
            val keytool = javaHome.resolve(if (isWindows) "bin/keytool.exe" else "bin/keytool").absolutePath
            localhostCertDirs.forEach { generatePinnedFixturesInto(it, keytool, temporaryDir) }
        }
    }

// --- PKCS#12 test identities for the Apple QUIC server tests (issues #112 + #99) ---
// Network.framework's QUIC listener needs a sec_identity_t, which SecPKCS12Import builds from a
// PKCS#12 bundle. Generate a `.p12` for each PEM cert+key using the system openssl (LibreSSL — its
// default p12 encoding is one SecPKCS12Import reads reliably; this is why we do NOT reuse keytool's
// PKCS12, whose newer PBE algorithms SecPKCS12Import may reject). Apple builds only on macOS, where
// openssl is always present, so this never runs on the Windows jvmTest runner. Git-ignored; the
// Apple K/N test tasks depend on it so the p12s exist (cwd-relative testcerts/*.p12) at runtime.
//   - cert.p12      → the committed quic.tech example identity used by most Apple server tests.
//   - localhost.p12 → the generated localhost identity the CA-pinning tests need.

// Wire generateLocalhostCert ahead of everything that reads localhost.* — the test tasks
// themselves (cwd-relative reads on JVM/Linux/Apple K/N) and the resource-processing tasks that
// copy it onto the JVM/Android test classpath. Each match only adds a dependsOn, harmless for any
// task that doesn't actually read the cert. generateTestP12 already chains via dependsOn above.
tasks
    .matching {
        it.name.matches(Regex("(jvm|linuxX64|macos|ios|tvos|watchos)\\w*Test")) ||
            (it.name.contains("ProcessResources", ignoreCase = true) && it.name.contains("Test", ignoreCase = true)) ||
            it.name.matches(Regex("process\\w*(AndroidTest|UnitTest)\\w*Resources"))
    }.configureEach { dependsOn(generateLocalhostCert, generatePinnedW3cCerts) }
