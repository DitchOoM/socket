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
    signing
}

apply(from = "gradle/setup.gradle.kts")

group = "com.ditchoom"
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
    mavenLocal() // For local buffer library testing
    google()
    mavenCentral()
    maven { setUrl("https://maven.pkg.jetbrains.space/kotlin/p/kotlin/kotlin-js-wrappers/") }
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

// BoringSSL for Linux TLS — shared with quiche (no symbol collision)
val boringsslCommit = libs.versions.boringsslCommit.get()
val boringsslBuildDir = layout.buildDirectory.dir("boringssl")

// --- BoringSSL build ---
//
// Replaces OpenSSL for Linux TCP TLS. Same API surface (ssl.h, err.h, x509.h).
// Shared with quiche — both link against the same BoringSSL, no symbol collision.
//
// OpenSSL code removed — all 18 API functions used in LinuxClientSocket.kt
// (SSL_CTX_new, SSL_connect, SSL_read, SSL_write, etc.) are identical in BoringSSL.
//

fun createBuildBoringSslTask(arch: String): TaskProvider<Task> {
    val taskName = "buildBoringssl${arch.replaceFirstChar { it.uppercase() }}"
    val outputDir = projectDir.resolve("libs/boringssl/linux-$arch")
    val markerFile = outputDir.resolve("lib/.built-$boringsslCommit")

    return tasks.register(taskName) {
        group = "build"
        description = "Build BoringSSL static libraries for Linux $arch"
        inputs.property("boringsslCommit", boringsslCommit)
        outputs.file(markerFile)
        onlyIf { !markerFile.exists() }

        doLast {
            val buildDir = boringsslBuildDir.get().asFile
            val sourceDir = File(buildDir, "boringssl")

            // Clone if not present — shallow clone of default branch
            // (BoringSSL API surface we use is stable across commits)
            if (!sourceDir.exists()) {
                buildDir.mkdirs()
                logger.lifecycle("Cloning BoringSSL...")
                val cloneResult =
                    ProcessBuilder(
                        "git",
                        "clone",
                        "--depth",
                        "1",
                        "https://github.com/google/boringssl.git",
                        sourceDir.name,
                    )
                        .directory(buildDir)
                        .inheritIO()
                        .start()
                        .waitFor()
                if (cloneResult != 0) throw GradleException("Failed to clone BoringSSL")
            }

            // CMake configure
            val cmakeBuildDir = File(sourceDir, "build-$arch")
            cmakeBuildDir.mkdirs()

            logger.lifecycle("Configuring BoringSSL for $arch...")

            val cmakeArgs =
                mutableListOf(
                    "cmake",
                    "-DCMAKE_BUILD_TYPE=Release",
                    "-DCMAKE_C_FLAGS=-fPIC",
                    "-DCMAKE_CXX_FLAGS=-fPIC",
                    "-DCMAKE_POSITION_INDEPENDENT_CODE=ON",
                    "-DOPENSSL_NO_ASM=1", // Avoids needing Go for ASM generation
                    "-DBUILD_SHARED_LIBS=OFF",
                    "-GUnix Makefiles",
                )

            // Cross-compilation for ARM64
            if (arch == "arm64" && System.getProperty("os.arch") != "aarch64") {
                cmakeArgs.addAll(
                    listOf(
                        "-DCMAKE_SYSTEM_NAME=Linux",
                        "-DCMAKE_SYSTEM_PROCESSOR=aarch64",
                        "-DCMAKE_C_COMPILER=aarch64-linux-gnu-gcc",
                        "-DCMAKE_CXX_COMPILER=aarch64-linux-gnu-g++",
                    ),
                )
            }

            cmakeArgs.add("..")

            val configResult =
                ProcessBuilder(cmakeArgs)
                    .directory(cmakeBuildDir)
                    .inheritIO()
                    .start()
                    .waitFor()
            if (configResult != 0) throw GradleException("BoringSSL cmake configure failed for $arch")

            // Build ssl and crypto targets only
            logger.lifecycle("Building BoringSSL (this may take a few minutes)...")
            val cpuCount = Runtime.getRuntime().availableProcessors()
            val makeProcess =
                ProcessBuilder("make", "-j$cpuCount", "ssl", "crypto")
                    .directory(cmakeBuildDir)
                    .redirectErrorStream(true)
                    .start()
            val makeOutput = makeProcess.inputStream.bufferedReader().readText()
            val makeResult = makeProcess.waitFor()
            if (makeResult != 0) {
                logger.error("BoringSSL make output:\n${makeOutput.takeLast(2000)}")
                throw GradleException("BoringSSL build failed for $arch (exit code $makeResult)")
            }

            // Copy libraries
            outputDir.resolve("lib").mkdirs()
            cmakeBuildDir.resolve("ssl/libssl.a").copyTo(outputDir.resolve("lib/libssl.a"), overwrite = true)
            cmakeBuildDir.resolve("crypto/libcrypto.a").copyTo(outputDir.resolve("lib/libcrypto.a"), overwrite = true)

            // Copy headers (same paths as OpenSSL: openssl/ssl.h, openssl/err.h, etc.)
            val includeOutputDir = outputDir.resolve("include")
            sourceDir.resolve("include").copyRecursively(includeOutputDir, overwrite = true)

            markerFile.writeText("BoringSSL $boringsslCommit built on ${System.currentTimeMillis()}")
            logger.lifecycle("BoringSSL built successfully for $arch")
        }
    }
}

val buildBoringSslX64 = createBuildBoringSslTask("x64")
val buildBoringSslArm64 = createBuildBoringSslTask("arm64")

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
    val boringsslDir = projectDir.resolve("libs/boringssl/linux-$arch")
    val boringsslLibDir = boringsslDir.resolve("lib")
    val boringsslIncDir = boringsslDir.resolve("include")
    val liburingDir = projectDir.resolve("libs/liburing/linux-$arch")
    val liburingLibDir = liburingDir.resolve("lib")
    val liburingIncludeDir = liburingDir.resolve("include")
    val buildBoringSslTask = if (arch == "x64") buildBoringSslX64 else buildBoringSslArm64
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
                        """libraryPaths.linux = ${boringsslLibDir.absolutePath} ${liburingLibDir.absolutePath}
staticLibraries.linux = libssl.a libcrypto.a liburing.a
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
                dependsOn(buildBoringSslTask)
                dependsOn(buildLiburingTask)
            }
        }
    }
    // Link against BoringSSL/OpenSSL and liburing
    binaries.all {
        linkerOpts(
            "-L${boringsslLibDir.absolutePath}",
            "-L${liburingLibDir.absolutePath}",
            "-lssl",
            "-lcrypto",
            "-luring",
            "-lpthread",
            "-ldl",
        )
    }
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
    }
    js {
        browser()
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
        // src/linuxMain is automatically shared by linuxX64 and linuxArm64
    }
}

android {
    compileSdk = 36
    sourceSets["main"].manifest.srcFile("src/androidMain/AndroidManifest.xml")
    defaultConfig {
        minSdk = 21
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
