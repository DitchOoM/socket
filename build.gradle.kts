import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.plugin.mpp.KotlinNativeTarget
import java.net.URI
import java.security.MessageDigest

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.library)
    alias(libs.plugins.ktlint)
    alias(libs.plugins.maven.publish)
    alias(libs.plugins.dokka)
    id("com.ditchoom.boringssl.provision") version "0.0.1-SNAPSHOT"
    signing
}

apply(from = "gradle/setup.gradle.kts")

group = "com.ditchoom"

// Canonical BoringSSL owner bundle (commit 44b3df6f, quiche 0.29.2 / boring-sys 4.22.0 ABI).
// Consumed via the boringssl-kmp provision plugin; localDist points at the locally built
// :boringssl-build dist so no network fetch happens during the migration window. The dev
// bundle here is byte-identical to the archive the :boringssl-canonical owner klib embeds.
boringssl {
    version = "0.0.1-dev"
    localDist = file("/home/rbehera/git/boringssl-kmp/boringssl-build/build/dist")
}
val isRunningOnGithub = System.getenv("GITHUB_REPOSITORY")?.isNotBlank() == true
val isMainBranchGithub = System.getenv("GITHUB_REF") == "refs/heads/main"
val isMacOS = org.jetbrains.kotlin.konan.target.HostManager.hostIsMac
val isLinux = org.jetbrains.kotlin.konan.target.HostManager.hostIsLinux

@Suppress("UNCHECKED_CAST")
val getNextVersion = project.extra["getNextVersion"] as (Boolean) -> Any
// Only compute version if not explicitly provided via -Pversion
if (!project.hasProperty("version") || project.version == "unspecified") {
    project.version = getNextVersion(!isRunningOnGithub).toString()
}

// Only print version info when not in quiet mode (avoids polluting CI output)
if (gradle.startParameter.logLevel != LogLevel.QUIET) {
    println("Version: ${project.version}\nisRunningOnGithub: $isRunningOnGithub\nisMainBranchGithub: $isMainBranchGithub")
}

repositories {
    mavenLocal()
    google()
    mavenCentral()
}

// Configure cinterop for NW helpers (C bridge for dispatch_data_t → NSData, connection creation, WS support)
fun KotlinNativeTarget.configureNWHelpersCinterop() {
    compilations["main"].cinterops {
        create("NWHelpers") {
            defFile("src/nativeInterop/cinterop/NWHelpers.def")
            includeDirs("src/nativeInterop/cinterop")
        }
    }
}

// BoringSSL now comes from the canonical boringssl-kmp bundle via the provision plugin
// (see the boringssl { } block above). The old google/boringssl clone+cmake tasks are gone:
// the :boringssl-canonical owner klib is the SINGLE archive owner; every socket cinterop that
// touches BoringSSL is now EXTERNAL (headers-only) against commit 44b3df6f.

// liburing version for Linux builds - defined in gradle/libs.versions.toml
val liburingVersion = libs.versions.liburing.get()
val liburingSha256 = libs.versions.liburingSha256.get()
val liburingBuildDir = layout.buildDirectory.dir("liburing")

// Helper function to download and verify liburing source
fun downloadLiburingSource(
    buildDir: File,
    version: String,
    sha256: String,
): File {
    val tarball = File(buildDir, "liburing-$version.tar.gz")
    val sourceDir = File(buildDir, "liburing-liburing-$version")

    if (sourceDir.exists()) return sourceDir

    buildDir.mkdirs()

    // Download if not present
    if (!tarball.exists()) {
        println("Downloading liburing $version...")
        val url = URI("https://github.com/axboe/liburing/archive/refs/tags/liburing-$version.tar.gz").toURL()
        url.openStream().use { input ->
            tarball.outputStream().use { output ->
                input.copyTo(output)
            }
        }
    }

    // Verify SHA256
    val digest = MessageDigest.getInstance("SHA-256")
    tarball.inputStream().use { input ->
        val buffer = ByteArray(8192)
        var read: Int
        while (input.read(buffer).also { read = it } != -1) {
            digest.update(buffer, 0, read)
        }
    }
    val actualSha256 = digest.digest().joinToString("") { byte -> "%02x".format(byte) }
    if (actualSha256 != sha256) {
        tarball.delete()
        throw GradleException("liburing SHA256 mismatch: expected $sha256, got $actualSha256")
    }

    // Extract
    println("Extracting liburing source...")
    ProcessBuilder("tar", "xzf", tarball.name)
        .directory(buildDir)
        .inheritIO()
        .start()
        .waitFor()

    return sourceDir
}

// Task to build liburing static library for a specific architecture
fun createBuildLiburingTask(arch: String): TaskProvider<Task> {
    val taskName = "buildLiburing${arch.replaceFirstChar { it.uppercase() }}"
    val outputDir = projectDir.resolve("libs/liburing/linux-$arch")
    val markerFile = outputDir.resolve("lib/.built-$liburingVersion")

    return tasks.register(taskName) {
        group = "build"
        description = "Build liburing static library for Linux $arch"

        inputs.property("liburingVersion", liburingVersion)
        inputs.property("liburingSha256", liburingSha256)
        outputs.file(markerFile)

        onlyIf {
            !markerFile.exists()
        }

        doLast {
            val buildDir = liburingBuildDir.get().asFile
            val sourceDir = downloadLiburingSource(buildDir, liburingVersion, liburingSha256)

            // Determine compiler for cross-compilation
            val (cc, cxx) =
                if (arch == "arm64" && System.getProperty("os.arch") != "aarch64") {
                    val compiler = "aarch64-linux-gnu-gcc"
                    val result = ProcessBuilder("which", compiler).start().waitFor()
                    if (result != 0) {
                        throw GradleException(
                            """
                            Cross-compiler not found for ARM64. Install with:
                              sudo apt install gcc-aarch64-linux-gnu
                            """.trimIndent(),
                        )
                    }
                    "aarch64-linux-gnu-gcc" to "aarch64-linux-gnu-g++"
                } else {
                    "gcc" to "g++"
                }

            // Clean any previous build artifacts to avoid mixing architectures
            logger.lifecycle("Cleaning previous liburing build...")
            ProcessBuilder("make", "clean")
                .directory(sourceDir)
                .inheritIO()
                .start()
                .waitFor()

            // Configure liburing
            logger.lifecycle("Configuring liburing $liburingVersion for $arch...")
            val configureArgs =
                mutableListOf(
                    "./configure",
                    "--cc=$cc",
                    "--cxx=$cxx",
                    "--prefix=${outputDir.absolutePath}",
                )

            val configureResult =
                ProcessBuilder(configureArgs)
                    .directory(sourceDir)
                    .inheritIO()
                    .start()
                    .waitFor()

            if (configureResult != 0) {
                throw GradleException("liburing configure failed")
            }

            // Build only the static library (not shared library or tests)
            logger.lifecycle("Building liburing...")
            val cpuCount = Runtime.getRuntime().availableProcessors()
            val makeResult =
                ProcessBuilder("make", "-j$cpuCount", "-C", "src", "liburing.a")
                    .directory(sourceDir)
                    .inheritIO()
                    .start()
                    .waitFor()

            if (makeResult != 0) {
                throw GradleException("liburing build failed")
            }

            // Copy library and headers
            outputDir.resolve("lib").mkdirs()
            outputDir.resolve("include").mkdirs()

            // Copy static library
            sourceDir.resolve("src/liburing.a").copyTo(
                outputDir.resolve("lib/liburing.a"),
                overwrite = true,
            )

            // Copy headers
            sourceDir.resolve("src/include/liburing.h").copyTo(
                outputDir.resolve("include/liburing.h"),
                overwrite = true,
            )
            val liburingIncludeDir = sourceDir.resolve("src/include/liburing")
            if (liburingIncludeDir.exists()) {
                liburingIncludeDir.copyRecursively(
                    outputDir.resolve("include/liburing"),
                    overwrite = true,
                )
            }

            // Write marker file
            markerFile.writeText("liburing $liburingVersion built on ${System.currentTimeMillis()}")

            logger.lifecycle("liburing $liburingVersion built successfully for $arch")
        }
    }
}

val buildLiburingX64 = createBuildLiburingTask("x64")
val buildLiburingArm64 = createBuildLiburingTask("arm64")

// Configure cinterop for Linux targets with BoringSSL (or OpenSSL fallback) and liburing
// Requires: sudo apt install build-essential cmake
// For ARM64 cross-compilation: sudo apt install gcc-aarch64-linux-gnu
fun KotlinNativeTarget.configureLinuxCinterop(arch: String) {
    // BoringSSL headers come from the canonical bundle via the provision plugin. No archive is
    // embedded here (EXTERNAL mode): the :boringssl-canonical owner klib is the single archive owner.
    // boringsslDir(triple) returns <cache>/<version>/<triple>/{include,lib}.
    val boringsslDir = boringssl.boringsslDir(if (arch == "x64") "linuxX64" else "linuxArm64")
    val boringsslIncDir = boringsslDir.resolve("include")
    val liburingDir = projectDir.resolve("libs/liburing/linux-$arch")
    val liburingLibDir = liburingDir.resolve("lib")
    val liburingIncludeDir = liburingDir.resolve("include")
    val buildLiburingTask = if (arch == "x64") buildLiburingX64 else buildLiburingArm64

    // System include paths for standard headers (not liburing - we build that ourselves)
    val systemIncludeDirs =
        if (arch == "x64") {
            listOf("/usr/include", "/usr/include/x86_64-linux-gnu")
        } else {
            // ARM64 cross-compilation from x64
            val crossRoot = "/usr/aarch64-linux-gnu"
            val crossInclude = "/usr/include/aarch64-linux-gnu"
            when {
                File(crossRoot).exists() -> listOf("$crossRoot/include")
                File(crossInclude).exists() -> listOf(crossInclude)
                else -> listOf("/usr/include/aarch64-linux-gnu")
            }
        }

    // Generate .def file with library paths for static libraries
    val generatedDefFile = projectDir.resolve("build/generated/cinterop/LinuxSockets-$arch.def")

    val generateDefTask =
        tasks.register("generateLinuxSocketsDef${arch.replaceFirstChar { it.uppercase() }}") {
            inputs.file("src/nativeInterop/cinterop/LinuxSockets.def")
            outputs.file(generatedDefFile)
            doLast {
                val baseDefContent = file("src/nativeInterop/cinterop/LinuxSockets.def").readText()
                // Add libraryPaths and staticLibraries directives
                // getentropy is referenced by OpenSSL but requires glibc 2.25+. Since io_uring
                // requires Linux 5.1+ (which ships glibc 2.29+), getentropy is available at runtime.
                // Use --unresolved-symbols=ignore-in-object-files to allow linking.
                val modifiedContent =
                    baseDefContent.replace(
                        "linkerOpts.linux = -lssl -lcrypto -luring -lpthread -ldl",
                        """libraryPaths.linux = ${liburingLibDir.absolutePath}
staticLibraries.linux = liburing.a
linkerOpts.linux = -lpthread -ldl --unresolved-symbols=ignore-in-object-files""",
                    )
                generatedDefFile.parentFile.mkdirs()
                generatedDefFile.writeText(modifiedContent)
            }
        }

    compilations["main"].cinterops {
        create("LinuxSockets") {
            defFile(generatedDefFile)
            // Include: system headers, our built liburing headers, and BoringSSL/OpenSSL headers
            // BoringSSL headers FIRST — must shadow system OpenSSL headers
            // to avoid cinterop picking up system's SSL_ctrl macro
            val allIncludes =
                listOf(boringsslIncDir.absolutePath) +
                    liburingIncludeDir.absolutePath +
                    systemIncludeDirs
            includeDirs(*allIncludes.toTypedArray())

            tasks.named(interopProcessingTaskName) {
                dependsOn(generateDefTask)
                dependsOn(buildLiburingTask)
            }
        }
    }
    // No binaries.all linkerOpts block here: the old -L...boringssl -lssl -lcrypto was a SECOND
    // BoringSSL owner (duplicate-symbol hazard). BoringSSL symbols now resolve from the single
    // :boringssl-canonical owner klib (added as `api` to linuxMain). liburing.a stays embedded via the
    // cinterop def (staticLibraries.linux = liburing.a) and the pthread/dl floor rides the def's
    // linkerOpts.linux, so both still propagate to the final link.
}

kotlin {
    // Ensure consistent JDK version across all developer machines and CI
    jvmToolchain(21)

    androidTarget {
        publishLibraryVariants("release")
        compilerOptions.jvmTarget.set(JvmTarget.JVM_17)
    }
    jvm {
        compilerOptions.jvmTarget.set(JvmTarget.JVM_1_8)
        // The JDK 21 FFM reactive network monitor (multi-release JAR under META-INF/versions/21) moved
        // to the cinterop-free com.ditchoom:network-monitor module along with the rest of the portable
        // NetworkMonitor implementation; :socket keeps only the native Linux/Apple monitors + default().
    }
    js {
        browser {
            testTask {
                // The base TCP socket has no raw-socket surface in a browser:
                // Socket.kt's browser actuals throw UnsupportedOperationException
                // ("Sockets are not supported in the browser"). These suites spin up
                // real TCP echo servers and/or connect over TCP (and the Node-specific
                // error-mapping suites exercise Node internals), so they can only run
                // under Node. They stay green in jsNodeTest; the WebSocket/WebTransport
                // surface is what the browser actually provides (networkCapabilities()).
                listOf(
                    "ClientCancellationTests",
                    "DataIntegrityTests",
                    "ErrorHandlingTests",
                    "ExceptionIntegrationTests",
                    "NodeBufferPoolTests",
                    "PartialBufferWriteTests",
                    "ResourceCleanupTests",
                    "ServerCancellationTests",
                    "SimpleSocketTests",
                    "WrapNodeErrorTests",
                    // The read-timeout contract suite spins up an in-process TCP SilentPeer, so it too
                    // can only run under Node (note the `.harness.` subpackage — the prefix below still
                    // resolves to its fully-qualified name).
                    "harness.ReadTimeoutContractTests",
                    // The write-timeout contract suite spins up an in-process TCP NonDrainingPeer, and the
                    // Node backpressure suite drives a raw `net.createServer` — both are Node-only
                    // (`net` has no browser surface; the contract suite early-returns via
                    // nonDrainingPeerIsReliable()=false, but exclude it for parity with the read suite).
                    "harness.WriteTimeoutContractTests",
                    "NodeWriteBackpressureTests",
                ).forEach { filter.excludeTestsMatching("com.ditchoom.socket.$it") }
            }
        }
        nodejs {
            testTask {
                useMocha {
                    timeout = "30s"
                }
            }
        }
    }
    wasmJs {
        browser()
        nodejs()
    }

    // Apple targets with Network.framework zero-copy socket implementation
    if (isMacOS) {
        // macOS
        macosArm64 { configureNWHelpersCinterop() }
        macosX64 { configureNWHelpersCinterop() }

        // iOS
        iosArm64 { configureNWHelpersCinterop() }
        iosSimulatorArm64 { configureNWHelpersCinterop() }
        iosX64 { configureNWHelpersCinterop() }

        // tvOS
        tvosArm64 { configureNWHelpersCinterop() }
        tvosSimulatorArm64 { configureNWHelpersCinterop() }
        tvosX64 { configureNWHelpersCinterop() }

        // watchOS (watchosArm32 not supported by buffer library)
        watchosArm64 { configureNWHelpersCinterop() }
        watchosSimulatorArm64 { configureNWHelpersCinterop() }
        watchosX64 { configureNWHelpersCinterop() }
    }

    // Linux targets with io_uring async I/O and statically-linked OpenSSL
    // NOTE: Kotlin/Native doesn't have a prebuilt compiler for linux-aarch64,
    // so ARM64 must be cross-compiled from x64. Both targets are always registered
    // on Linux x64 for proper source set resolution.
    if (isLinux) {
        // x64 target - always available on Linux x64
        linuxX64 {
            configureLinuxCinterop("x64")
        }

        linuxArm64 {
            configureLinuxCinterop("arm64")
        }
    }

    applyDefaultHierarchyTemplate()
    sourceSets {
        commonMain.dependencies {
            // Network-awareness (NetworkMonitor / NetworkId) extracted to its own published, cinterop-free
            // module (cinterop-issues/06) so ../webrtc and other consumers can use it standalone. api: it
            // is part of :socket's public surface (TransportConfig.networkId, ReconnectingConnection,
            // NetworkMonitor.default()/processDefault()). The native Linux/Apple monitors stay here.
            api(project(":network-monitor"))
            implementation(libs.buffer)
            api(libs.buffer.flow)
            api(libs.buffer.codec)
            implementation(libs.kotlinx.coroutines.core)
        }
        androidMain.dependencies {
            implementation(libs.androidx.core.ktx)
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
            implementation(libs.kotlinx.coroutines.core)
            implementation(libs.kotlinx.coroutines.test)
            // Tier-A fault-injection vocabulary (FaultSchedule/ImpairmentEngine). testkit does
            // api(project(":")), so this is a test→testkit→main chain, not a cycle (main has no
            // back-edge to test). RFC_UNIFIED_NETWORK_TEST_HARNESS.md §3: TCP Tier-A builds on testkit.
            implementation(project(":socket-testkit"))
        }
        jsMain.dependencies {
            implementation(libs.kotlin.js)
        }
        jsTest.dependencies {
            implementation(kotlin("test-js"))
            implementation(npm("tcp-port-used", "1.0.2"))
        }
        val androidInstrumentedTest by getting {
            dependencies {
                implementation(kotlin("test"))
                implementation(libs.kotlinx.coroutines.test)
                implementation("androidx.test:runner:1.7.0")
                implementation("androidx.test.ext:junit:1.3.0")
            }
        }
        val androidUnitTest by getting {
            dependencies {
                implementation(kotlin("test"))
            }
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
        jvmTest.dependencies {
            implementation(libs.kotlinx.coroutines.debug)
        }
        androidUnitTest.dependsOn(commonJvmTest)
        androidUnitTest.dependencies {
            implementation(libs.kotlinx.coroutines.debug)
        }

        // Add shared Apple implementation to all Apple target source sets.
        // Cannot use appleMain directly because compileAppleMainKotlinMetadata
        // can't resolve Apple-specific types (NSDataBuffer, NSData, etc.) from dependencies.
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

        // Linux implementation uses standard KMP hierarchy:
        // src/linuxMain is automatically shared by linuxX64 and linuxArm64.
        // The canonical BoringSSL owner klib is added as `api` here so it (a) contributes the SINGLE
        // embedded libcrypto.a/libssl.a to every linux K/N final link, and (b) propagates transitively
        // to downstream modules that depend on project(":") (e.g. :socket-quic-quiche).
        if (isLinux) {
            val linuxMain by getting {
                dependencies {
                    api("com.ditchoom.boringssl:boringssl-canonical:0.0.1-SNAPSHOT")
                }
            }
        }
    }
}

android {
    compileSdk = 36
    // AGP 9 + legacy-DSL opt-out: the `sourceSets[...]` Kotlin accessor casts to the
    // removed old API. Reach the source set via the new DSL interface instead.
    (this as com.android.build.api.dsl.LibraryExtension)
        .sourceSets
        .getByName("main")
        .manifest
        .srcFile("src/androidMain/AndroidManifest.xml")
    defaultConfig {
        minSdk = 21
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    namespace = "$group.${rootProject.name}"

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    publishing {
        singleVariant("release") {
            withSourcesJar()
            withJavadocJar()
        }
    }
}

val javadocJar: TaskProvider<Jar> by tasks.registering(Jar::class) {
    archiveClassifier.set("javadoc")
}

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

val signingInMemoryKey = project.findProperty("signingInMemoryKey")
val signingInMemoryKeyPassword = project.findProperty("signingInMemoryKeyPassword")
val shouldSignAndPublish = isMainBranchGithub && signingInMemoryKey is String && signingInMemoryKeyPassword is String

if (shouldSignAndPublish) {
    signing {
        useInMemoryPgpKeys(
            signingInMemoryKey as String,
            signingInMemoryKeyPassword as String,
        )
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
    }
}

tasks.register("nextVersion") {
    println(getNextVersion(false))
}

dokka {
    dokkaSourceSets.configureEach {
        externalDocumentationLinks.register("kotlin-stdlib") {
            url("https://kotlinlang.org/api/latest/jvm/stdlib/")
            packageListUrl("https://kotlinlang.org/api/latest/jvm/stdlib/package-list")
        }
        externalDocumentationLinks.register("kotlinx-coroutines") {
            url("https://kotlinlang.org/api/kotlinx.coroutines/")
            packageListUrl("https://kotlinlang.org/api/kotlinx.coroutines/package-list")
        }
        reportUndocumented.set(false)
    }

    // Suppress duplicate Apple source sets - keep only macosArm64 as representative
    val suppressedAppleTargets =
        listOf(
            "macosX64",
            "iosArm64",
            "iosSimulatorArm64",
            "iosX64",
            "tvosArm64",
            "tvosSimulatorArm64",
            "tvosX64",
            "watchosArm64",
            "watchosSimulatorArm64",
            "watchosX64",
        )
    dokkaSourceSets {
        suppressedAppleTargets.forEach { target ->
            findByName("${target}Main")?.suppress?.set(true)
        }
    }
}

// Copy Dokka output to Docusaurus static directory
tasks.register<Copy>("copyDokkaToDocusaurus") {
    description = "Generate and copy API documentation to Docusaurus"
    group = "documentation"
    dependsOn("dokkaGenerateHtml")

    from(layout.buildDirectory.dir("dokka/html"))
    into(layout.projectDirectory.dir("docs/static/api"))
}

// ──────────────────────────────────────────────────────────────────────
// Test harness (local Docker Compose). Phase 1 — echo + http only.
// Design: TESTING_STRATEGY.md  ·  Stack: test-harness/README.md
// ──────────────────────────────────────────────────────────────────────

val harnessDir = layout.projectDirectory.dir("test-harness")
val harnessEnvFile = harnessDir.file("harness.env")
val harnessGeneratedDir = layout.buildDirectory.dir("generated/harness/commonTest")

// Reads test-harness/harness.env and writes a generated Kotlin object
// onto commonTest's source path. One object, zero expect/actual — visible
// identically on JVM, K/N, Apple, JS, and wasmJs (none of which need to
// hand-roll a system-property reader). Edit harness.env, run this task,
// every target sees the new value.
val generateHarnessConfig by tasks.registering {
    group = "verification"
    description = "Generate HarnessConfig.kt from test-harness/harness.env"
    val envFile = harnessEnvFile
    val outDir = harnessGeneratedDir
    inputs.file(envFile)
    outputs.dir(outDir)
    doLast {
        val env =
            envFile.asFile
                .readLines()
                .filter { it.isNotBlank() && !it.trimStart().startsWith("#") }
                .associate { line ->
                    val i = line.indexOf('=')
                    require(i > 0) { "Malformed harness.env line: '$line'" }
                    line.substring(0, i).trim() to line.substring(i + 1).trim()
                }

        fun req(key: String): String = env[key] ?: error("Missing $key in $envFile")
        val pkgDir = outDir.get().asFile.resolve("com/ditchoom/socket/harness")
        pkgDir.mkdirs()
        pkgDir.resolve("HarnessConfig.kt").writeText(
            buildString {
                append("// Generated from test-harness/harness.env — DO NOT EDIT.\n")
                // Suppress ktlint's SCREAMING_SNAKE_CASE rule: we deliberately use camelCase
                // for object members so call sites read like normal properties.
                append("@file:Suppress(\"ktlint:standard:property-naming\")\n\n")
                append("package com.ditchoom.socket.harness\n\n")
                append("/**\n")
                append(" * Endpoints for the local docker-compose test harness.\n")
                append(" * Single source of truth: edit test-harness/harness.env and re-run\n")
                append(" * `:generateHarnessConfig` (auto-fired by every test task).\n")
                append(" */\n")
                append("internal object HarnessConfig {\n")
                append("    const val host: String = \"${req("HARNESS_HOST")}\"\n")
                append("    const val echoPort: Int = ${req("ECHO_PORT")}\n")
                append("    const val httpPort: Int = ${req("HTTP_PORT")}\n")
                append("    const val tlsValidPort: Int = ${req("TLS_VALID_PORT")}\n")
                append("    const val tlsSelfSignedPort: Int = ${req("TLS_SELF_SIGNED_PORT")}\n")
                append("    const val tlsExpiredPort: Int = ${req("TLS_EXPIRED_PORT")}\n")
                append("    const val tlsWrongHostPort: Int = ${req("TLS_WRONG_HOST_PORT")}\n")
                append("    const val tlsUntrustedPort: Int = ${req("TLS_UNTRUSTED_PORT")}\n")
                append("    const val tlsTls13Port: Int = ${req("TLS_TLS13_PORT")}\n")
                append("    const val netemBlackholeHost: String = \"${req("NETEM_BLACKHOLE_HOST")}\"\n")
                append("    const val netemBlackholePort: Int = ${req("NETEM_BLACKHOLE_PORT")}\n")
                append("    const val toxiproxyApiPort: Int = ${req("TOXIPROXY_API_PORT")}\n")
                append("    const val toxiproxyEchoPort: Int = ${req("TOXIPROXY_ECHO_PORT")}\n")
                append("    const val toxiproxyHttpPort: Int = ${req("TOXIPROXY_HTTP_PORT")}\n")
                append("    const val toxiproxyTlsPort: Int = ${req("TOXIPROXY_TLS_PORT")}\n")
                append("    const val rstPort: Int = ${req("RST_PORT")}\n")
                append("}\n")
            },
        )
    }
}

kotlin.sourceSets.named("commonTest") {
    kotlin.srcDir(generateHarnessConfig.map { harnessGeneratedDir })
}

tasks.withType<org.gradle.api.tasks.testing.AbstractTestTask>().configureEach {
    dependsOn(generateHarnessConfig)
    // Surface assertion messages and exception traces in the gradle test
    // output. Default `events("failed")` only logs "FAILED" + the top-frame
    // class name (e.g. `AssertionError at Assert.java:89`) which makes CI
    // failures un-actionable without downloading the HTML report.
    testLogging {
        events("failed", "skipped")
        showExceptions = true
        showCauses = true
        showStackTraces = true
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }
}

// Pin JVM tests to a single sequential fork. The JVM test surface uses
// process-wide mutable globals (`useAsyncChannels`, `useNioBlocking` in
// `commonJvmMain/Socket.kt`) that the Simple{Nio,NioNonBlocking,Async}-
// SocketTests classes flip via @BeforeTest/@AfterTest save-and-restore.
// That isolation only works inside a single fork running tests
// sequentially; multiple forks (or in-fork parallelism) would race on the
// shared globals and silently exercise the wrong client implementation —
// the tests would still pass because they're impl-agnostic, but the
// discriminating coverage would be lost.
//
// If you need to enable parallel JVM tests, first migrate those globals
// to a CoroutineContext.Element or a per-call factory (see the long
// comment in Socket.kt).
tasks.withType<org.gradle.api.tasks.testing.Test>().configureEach {
    maxParallelForks = 1
    setForkEvery(0L)
}

// ─── harnessUp / harnessDown ──────────────────────────────────────────
// Both tolerant: missing Docker, Windows host, or HARNESS_DISABLED=true
// → silent no-op. Tests then short-circuit at runtime via
// isHarnessAvailable() and the suite stays green.

fun runHarnessCmd(args: List<String>): Int {
    if (System.getenv("HARNESS_DISABLED")?.lowercase() == "true") {
        logger.lifecycle("harness: HARNESS_DISABLED=true — skipping `${args.joinToString(" ")}`")
        return 0
    }
    if (org.gradle.internal.os.OperatingSystem
            .current()
            .isWindows
    ) {
        logger.lifecycle(
            "harness: Windows host — skipping `${args.joinToString(" ")}`; " +
                "run on Linux/macOS (or WSL) for harness coverage",
        )
        return 0
    }
    return try {
        val proc =
            ProcessBuilder(args)
                .directory(harnessDir.asFile)
                .redirectErrorStream(true)
                .start()
        proc.inputStream.bufferedReader().forEachLine { logger.lifecycle("harness: $it") }
        proc.waitFor()
    } catch (e: Exception) {
        logger.lifecycle("harness: skipped — ${e.message}")
        0
    }
}

// Idempotent cert-matrix generation (TESTING_STRATEGY.md §2b). The script writes
// to test-harness/tls/certs/, which is gitignored. Runs once; subsequent calls
// short-circuit on the `.generated` marker file. Wired before `harnessUp` so
// `docker compose up` sees the cert files on the mounted volume.
val generateHarnessCerts by tasks.registering {
    group = "verification"
    description = "Generate the harness TLS cert matrix (./test-harness/tls/gen-certs.sh)."
    val script = harnessDir.file("tls/gen-certs.sh")
    val marker = harnessDir.file("tls/certs/.generated")
    inputs.file(script)
    outputs.file(marker)
    doLast {
        if (org.gradle.internal.os.OperatingSystem
                .current()
                .isWindows
        ) {
            logger.lifecycle("harness: Windows host — skipping cert generation (need bash + openssl)")
            return@doLast
        }
        try {
            val proc =
                ProcessBuilder("bash", script.asFile.absolutePath)
                    .directory(harnessDir.asFile)
                    .redirectErrorStream(true)
                    .start()
            proc.inputStream.bufferedReader().forEachLine { logger.lifecycle("harness: $it") }
            val rc = proc.waitFor()
            if (rc != 0) {
                logger.lifecycle("harness: gen-certs.sh returned $rc — TLS tests will skip via isHarnessAvailable()")
            }
        } catch (e: Exception) {
            logger.lifecycle("harness: cert generation skipped — ${e.message}")
        }
    }
}

val harnessUp by tasks.registering {
    group = "verification"
    description = "Start the local test harness (docker compose up --wait). No-op if docker unavailable."
    dependsOn(generateHarnessCerts)
    // Phase 4 — make sure the quic-echo image's input artefact (a fat jar of
    // QuicEchoTestServer + its test deps + the host's quiche native libs) is
    // current before `docker compose up` reads it. Subproject task is wrapped
    // in `tasks.named` so the dependency edge is resolved lazily — keeps the
    // root build script orderable against the subproject's afterEvaluate.
    dependsOn(project(":socket-quic-quiche").tasks.named("quicEchoJar"))
    // W6 — same treatment for the harness controller image's input artefact
    // (a fat jar of :socket-testsuite's jvmMain HarnessController; see
    // test-harness/controller/Dockerfile and RFC_DETERMINISTIC_SIMULATION §7).
    dependsOn(project(":socket-testsuite").tasks.named("controllerJar"))
    // P1 (unified-harness) — same for the udp-toxi relay image's input artefact
    // (a fat jar of :socket-testsuite's jvmMain UdpToxiServer; see
    // test-harness/udp-toxi/Dockerfile and RFC_UNIFIED_NETWORK_TEST_HARNESS §5).
    dependsOn(project(":socket-testsuite").tasks.named("udpToxiJar"))
    doLast {
        // --build: `up` alone never rebuilds an existing image, so a changed
        // controllerJar/quicEchoJar would silently keep serving a stale build on
        // dev machines (CI runners start imageless, so they build regardless).
        // The compose build cache makes this a no-op when the inputs are unchanged.
        val rc = runHarnessCmd(listOf("docker", "compose", "up", "-d", "--wait", "--build"))
        if (rc != 0) {
            logger.lifecycle(
                "harness: `docker compose up` returned $rc — tests will skip harness scenarios " +
                    "via isHarnessAvailable()",
            )
        }
    }
}

val harnessDown by tasks.registering {
    group = "verification"
    description = "Stop the local test harness (docker compose down -v). No-op if docker unavailable."
    doLast { runHarnessCmd(listOf("docker", "compose", "down", "-v")) }
}

// Wrap both the root module's test tasks AND :socket-quic's matching test
// tasks with the harness lifecycle. Phase 4 adds quic-echo to the compose
// stack, so :socket-quic:jvmTest's new QuicHarnessIntegrationTests need it
// running. The exit gate is intentionally two separate gradle invocations
// (HANDOFF.md Phase 4) so the root and socket-quic test tasks don't run
// concurrently — JVM+native workload contention causes the in-process QUIC
// tests' 10 s timeouts to miss on resource-constrained runners.
//
// jsNodeTest (Phase 5): Node has full socket access (`net.Socket`), so
// isHarnessAvailable() returns true when the harness is up and Node-targeted
// harness tests exercise the same conformance suites. Browser/wasmJs stays
// WEBSOCKETS_ONLY and skips via the early-return path (HANDOFF.md §7.4) — no
// special-case needed; on Windows CI the harnessUp task is a documented no-op
// and the tests skip cleanly through isHarnessAvailable().
listOf("jvmTest", "linuxX64Test", "jsNodeTest").forEach { name ->
    tasks.matching { it.name == name }.configureEach {
        dependsOn(harnessUp)
        finalizedBy(harnessDown)
    }
    project(":socket-quic-quiche").tasks.matching { it.name == name }.configureEach {
        dependsOn(harnessUp)
        finalizedBy(harnessDown)
    }
    // W6 — :socket-testsuite's own validation tests (NetworkHarnessTestSuite)
    // exercise the controller + toxiproxy; give them the same lifecycle. With
    // the harness unavailable they skip cleanly via withNetworkHarness's
    // skip-on-unreachable, so this stays green everywhere.
    project(":socket-testsuite").tasks.matching { it.name == name }.configureEach {
        dependsOn(harnessUp)
        finalizedBy(harnessDown)
    }
}

// :socket-testsuite ONLY: also give the macOS-native lanes the harness window, so
// NetworkHarnessTests runs its live scenarios on developer Macs (Docker Desktop)
// instead of racing whichever other task holds the stack. Deliberately NOT extended
// to the root/:socket-quic-quiche macos tasks: their TLS harness tests require the
// harness CA in the macOS keychain (never injected on dev machines — only Linux CI
// injects it), so a guaranteed-live harness would turn their skip into a guaranteed
// -9808 trust failure. withNetworkHarness scenarios have no such precondition.
// On CI macOS runners docker is absent and harnessUp is a documented no-op.
listOf("macosArm64Test", "macosX64Test").forEach { name ->
    project(":socket-testsuite").tasks.matching { it.name == name }.configureEach {
        dependsOn(harnessUp)
        finalizedBy(harnessDown)
    }
}
