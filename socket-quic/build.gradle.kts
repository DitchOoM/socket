import org.jetbrains.kotlin.gradle.dsl.JvmTarget

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
    mavenLocal()
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
    val markerFile = outputDir.resolve("lib/.built-$quicheVersion")

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
                    if (boringsslDir.resolve("lib/libssl.a").exists()) "ffi" else "ffi,boringssl-vendored",
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
    val markerFile = outputDir.resolve("lib/.built-$quicheVersion")

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

            // Use pre-built BoringSSL if available
            val boringsslDir = rootProject.projectDir.resolve("libs/boringssl/linux-$arch")
            val env = mutableMapOf<String, String>()
            // Note: avoid -C lto=thin / -C embed-bitcode=yes — they conflict with
            // pre-built BoringSSL objects that lack LTO bitcode.
            env["RUSTFLAGS"] = "-C opt-level=s -C codegen-units=1 -C strip=symbols"
            if (boringsslDir.resolve("lib/libssl.a").exists()) {
                env["QUICHE_BSSL_PATH"] = boringsslDir.absolutePath
                logger.lifecycle("Using pre-built BoringSSL from ${boringsslDir.absolutePath}")
            }
            if (os == "linux" && arch == "arm64" && System.getProperty("os.arch") != "aarch64") {
                env["CARGO_TARGET_AARCH64_UNKNOWN_LINUX_GNU_LINKER"] = "aarch64-linux-gnu-gcc"
            }

            val quicheFeatures =
                if (boringsslDir.resolve("lib/libssl.a").exists()) "ffi" else "ffi,boringssl-vendored"

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

// Register build tasks for the current host
val buildQuicheStaticX64 = if (isLinux) createBuildQuicheStaticTask("x64") else null
val buildQuicheStaticArm64 = if (isLinux) createBuildQuicheStaticTask("arm64") else null

val buildQuicheSharedLinuxX64 = if (isLinux) createBuildQuicheSharedTask("linux", "x64") else null
val buildQuicheSharedLinuxArm64 = if (isLinux) createBuildQuicheSharedTask("linux", "arm64") else null
val buildQuicheSharedMacosX64 = if (isMacOS) createBuildQuicheSharedTask("macos", "x64") else null
val buildQuicheSharedMacosArm64 = if (isMacOS) createBuildQuicheSharedTask("macos", "arm64") else null

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
                // When the cargo build used QUICHE_BSSL_PATH (shared BoringSSL from base socket
                // module), libquiche.a has unresolved SSL_*/EVP_*/CRYPTO_* refs. Link those
                // archives inside the same --whole-archive pair — mirrors build-linux.yaml.
                val boringsslLibDir = rootProject.projectDir.resolve("libs/boringssl/linux-$arch/lib")
                val bsslArchives =
                    if (boringsslLibDir.resolve("libssl.a").exists()) {
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

            val stripCmd =
                if (os == "macos") {
                    listOf("strip", "-x", outputLib.absolutePath)
                } else {
                    listOf("strip", "--strip-all", outputLib.absolutePath)
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
    val markerFile = outputDir.resolve(".built-$quicheVersion")

    return tasks.register("buildAndroidJni${abi.abi.replace("-", "").replaceFirstChar { it.uppercase() }}") {
        group = "build"
        description = "Build quiche + JNI shim for Android ${abi.abi}"
        inputs.property("quicheVersion", quicheVersion)
        inputs.file("src/jni/quiche_jni.c")
        outputs.file(markerFile)
        onlyIf { !markerFile.exists() }

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
                    "ffi,boringssl-vendored",
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

            markerFile.writeText("quiche $quicheVersion JNI shim for ${abi.abi} built on ${System.currentTimeMillis()}")
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

fun org.jetbrains.kotlin.gradle.plugin.mpp.KotlinNativeTarget.configureNWQuicHelpersCinterop() {
    compilations["main"].cinterops {
        create("NWQuicHelpers") {
            defFile("src/nativeInterop/cinterop/NWQuicHelpers.def")
            includeDirs("src/nativeInterop/cinterop")
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
                }
            }
        }
    }
    js {
        browser() // QUIC unsupported in browser (no raw UDP)
        nodejs() // QUIC via quiche FFI (koffi) on Node.js
    }
    wasmJs {
        browser() // QUIC unsupported in browser (no raw UDP/WASM QUIC)
        nodejs()
    }

    if (isMacOS) {
        macosArm64 { configureNWQuicHelpersCinterop() }
        macosX64 { configureNWQuicHelpersCinterop() }
        iosArm64 { configureNWQuicHelpersCinterop() }
        iosSimulatorArm64 { configureNWQuicHelpersCinterop() }
        iosX64 { configureNWQuicHelpersCinterop() }
        tvosArm64 { configureNWQuicHelpersCinterop() }
        tvosSimulatorArm64 { configureNWQuicHelpersCinterop() }
        tvosX64 { configureNWQuicHelpersCinterop() }
        watchosArm64 { configureNWQuicHelpersCinterop() }
        watchosSimulatorArm64 { configureNWQuicHelpersCinterop() }
        watchosX64 { configureNWQuicHelpersCinterop() }
    }

    if (isLinux) {
        linuxX64 {
            compilations["main"].cinterops {
                create("Quiche") {
                    defFile("src/nativeInterop/cinterop/Quiche.def")
                    includeDirs("libs/quiche/include")
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

    applyDefaultHierarchyTemplate()
    sourceSets {
        commonMain.dependencies {
            api(project(":"))
            implementation(libs.kotlinx.coroutines.core)
        }
        commonTest {
            kotlin.srcDir(generateQuicHarnessConfig.map { quicHarnessGeneratedDir })
            dependencies {
                implementation(kotlin("test"))
                implementation(libs.kotlinx.coroutines.core)
                implementation(libs.kotlinx.coroutines.test)
            }
        }
        val commonJvmMain by creating {
            dependsOn(commonMain.get())
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
            implementation("org.jetbrains.kotlinx:atomicfu:0.27.0")
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
                implementation("androidx.test:runner:1.6.2")
                implementation("androidx.test.ext:junit:1.2.1")
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

kotlin {
    sourceSets {

        if (isMacOS) {
            val appleNativeImplDir = file("src/appleNativeImpl/kotlin")
            listOf(
                "macosArm64Main",
                "macosX64Main",
                "iosArm64Main",
                "iosSimulatorArm64Main",
                "iosX64Main",
                "tvosArm64Main",
                "tvosSimulatorArm64Main",
                "tvosX64Main",
                "watchosArm64Main",
                "watchosSimulatorArm64Main",
                "watchosX64Main",
            ).forEach { sourceSetName ->
                findByName(sourceSetName)?.kotlin?.srcDir(appleNativeImplDir)
            }
        }
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
    namespace = "com.ditchoom.socket.quic"

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

// iOS-simulator QUIC harness (issue #81). KGP's default `simctl spawn --standalone`
// runs tests outside launchd_sim's network services, which breaks Network.framework
// QUIC (raw-socket TCP is unaffected). When CI supplies a booted device via
// `-PiosSimulatorDevice=<udid>` (after `xcrun simctl boot`), run the iOS simulator
// test task inside that booted simulator (standalone=false) and export
// QUIC_SIM_BOOTED so QuicHarnessIntegrationTests un-skips there. Without the
// property, KGP's auto-boot + `--standalone` behavior is unchanged, so
// `./gradlew check` still works locally with no manual boot (the QUIC harness then
// self-skips on the simulator). tvOS/watchOS are intentionally left as-is for now.
providers.gradleProperty("iosSimulatorDevice").orNull?.takeIf { it.isNotBlank() }?.let { simDevice ->
    tasks
        .withType<org.jetbrains.kotlin.gradle.targets.native.tasks.KotlinNativeSimulatorTest>()
        .matching { it.name == "iosSimulatorArm64Test" }
        .configureEach {
            standalone.set(false)
            device.set(simDevice)
            // `simctl spawn` only forwards env vars to the child when they carry the
            // SIMCTL_CHILD_ prefix (which it strips), so the test process sees
            // QUIC_SIM_BOOTED=1. A bare QUIC_SIM_BOOTED would stay on xcrun and never reach it.
            environment("SIMCTL_CHILD_QUIC_SIM_BOOTED", "1")
        }
}
