import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.library)
    // KSP available but not applied — @ProtocolMessage is a marker annotation.
    // Consumers who want generated codecs can apply KSP + buffer-codec-processor themselves.
    // alias(libs.plugins.ksp)
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

fun org.jetbrains.kotlin.gradle.plugin.mpp.KotlinNativeTarget.configureNWQuicHelpersCinterop() {
    compilations["main"].cinterops {
        create("NWQuicHelpers") {
            defFile("src/nativeInterop/cinterop/NWQuicHelpers.def")
            includeDirs("src/nativeInterop/cinterop")
        }
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
                    // Use whole-archive to pull all quiche symbols, allow-multiple-definition
                    // to resolve BoringSSL/OpenSSL symbol collision with base module
                    linkerOpts(
                        "-L${quicheLibDir.absolutePath}",
                        "-Wl,--whole-archive",
                        "-lquiche",
                        "-Wl,--no-whole-archive",
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
                    linkerOpts(
                        "-L${quicheLibArm64.parentFile.absolutePath}",
                        "-lquiche",
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
        commonTest.dependencies {
            implementation(kotlin("test"))
            implementation(libs.kotlinx.coroutines.core)
            implementation(libs.kotlinx.coroutines.test)
        }
        val commonJvmMain by creating {
            dependsOn(commonMain.get())
        }
        jvmMain.get().dependsOn(commonJvmMain)
        androidMain.get().dependsOn(commonJvmMain)
        val commonJvmTest by creating {
            dependsOn(commonTest.get())
        }
        jvmTest.get().dependsOn(commonJvmTest)
        val androidUnitTest by getting {
            dependencies {
                implementation(kotlin("test"))
            }
        }
        val androidInstrumentedTest by getting {
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

// KSP codec processor wiring (disabled — see alias above)
// kotlin.targets.configureEach {
//     if (name != "metadata") {
//         dependencies.add("ksp${name.replaceFirstChar { it.uppercase() }}", libs.buffer.codec.processor)
//         dependencies.add("ksp${name.replaceFirstChar { it.uppercase() }}", libs.buffer.codec)
//     }
// }

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

android {
    compileSdk = 36
    sourceSets["main"].manifest.srcFile("src/androidMain/AndroidManifest.xml")
    sourceSets["main"].jniLibs.srcDirs("src/androidMain/jniLibs")
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

// Configure JVM JAR after evaluation (task created by KMP plugin)
afterEvaluate {
    tasks.named<Jar>("jvmJar") {
        duplicatesStrategy = DuplicatesStrategy.EXCLUDE
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
        // Bundle quiche native libraries (shared lib + JNI shim, included if built)
        val nativeLibs =
            mapOf(
                "linux-x64" to listOf("libquiche.so", "libquiche_jni.so"),
                "linux-arm64" to listOf("libquiche.so", "libquiche_jni.so"),
                "macos-x64" to listOf("libquiche.dylib", "libquiche_jni.dylib"),
                "macos-arm64" to listOf("libquiche.dylib", "libquiche_jni.dylib"),
            )
        for ((platform, libs) in nativeLibs) {
            val libDir = projectDir.resolve("libs/quiche/$platform/lib")
            val existingLibs = libs.filter { libDir.resolve(it).exists() }
            if (existingLibs.isNotEmpty()) {
                into("META-INF/native/$platform") {
                    from(libDir) { include(*existingLibs.toTypedArray()) }
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
