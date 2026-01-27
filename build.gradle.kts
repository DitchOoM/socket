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
project.version = getNextVersion(!isRunningOnGithub).toString()

// Only print version info when not in quiet mode (avoids polluting CI output)
if (gradle.startParameter.logLevel != LogLevel.QUIET) {
    println("Version: ${project.version}\nisRunningOnGithub: $isRunningOnGithub\nisMainBranchGithub: $isMainBranchGithub")
}

repositories {
    google()
    mavenCentral()
    maven { setUrl("https://maven.pkg.jetbrains.space/kotlin/p/kotlin/kotlin-js-wrappers/") }
}

// Swift library paths
val swiftBuildDir = layout.buildDirectory.dir("swift")
val swiftHeaderDir = swiftBuildDir.map { it.dir("include") }
val swiftLibDir = swiftBuildDir.map { it.dir("lib") }

// Task to build Swift libraries (only on macOS)
val buildSwiftTask =
    tasks.register<Exec>("buildSwift") {
        onlyIf { isMacOS }
        workingDir = projectDir
        commandLine("./buildSwift.sh")
        outputs.dir(swiftBuildDir)
        inputs.files(fileTree("src/nativeInterop/cinterop/swift") { include("**/*.swift") })
    }

// Map our library subdirs to Swift platform names
fun swiftPlatformForLibSubdir(libSubdir: String): String =
    when (libSubdir) {
        "macos" -> "macosx"
        "ios" -> "iphoneos"
        "ios-simulator" -> "iphonesimulator"
        "tvos" -> "appletvos"
        "tvos-simulator" -> "appletvsimulator"
        "watchos" -> "watchos"
        "watchos-simulator" -> "watchsimulator"
        else -> "macosx"
    }

// Configure cinterop for Apple targets
fun KotlinNativeTarget.configureSocketWrapperCinterop(libSubdir: String) {
    val libPath =
        swiftLibDir
            .get()
            .dir(libSubdir)
            .asFile.absolutePath
    val swiftPlatform = swiftPlatformForLibSubdir(libSubdir)
    compilations["main"].cinterops {
        create("SocketWrapper") {
            defFile("src/nativeInterop/cinterop/SocketWrapper.def")
            includeDirs(swiftHeaderDir)
            // Embed the static library in the klib so consumers link it automatically
            extraOpts("-libraryPath", libPath)
            extraOpts("-staticLibrary", "libSocketWrapper.a")
            tasks.named(interopProcessingTaskName) {
                dependsOn(buildSwiftTask)
            }
        }
    }
    compilations["main"].compileTaskProvider.configure {
        dependsOn(buildSwiftTask)
    }
    // Link Swift runtime libraries (platform-specific) for all binaries
    // Note: libSocketWrapper.a is now embedded in the klib via -staticLibrary
    binaries.all {
        linkerOpts(
            "-L/Applications/Xcode.app/Contents/Developer/Toolchains/XcodeDefault.xctoolchain/usr/lib/swift/$swiftPlatform",
            "-L/usr/lib/swift",
        )
    }
}

// OpenSSL version for Linux builds - defined in gradle/libs.versions.toml
val opensslVersion = libs.versions.openssl.get()
val opensslSha256 = libs.versions.opensslSha256.get()
val opensslBuildDir = layout.buildDirectory.dir("openssl")
val opensslIncludeDir = opensslBuildDir.map { it.dir("openssl-$opensslVersion/include") }

// OpenSSL configure options for minimal TLS build
val opensslConfigureOptions =
    listOf(
        "no-shared",
        "no-tests",
        "no-legacy",
        "no-engine",
        "no-comp",
        "no-dtls",
        "no-dtls1",
        "no-dtls1-method",
        "no-ssl3",
        "no-ssl3-method",
        "no-idea",
        "no-rc2",
        "no-rc4",
        "no-rc5",
        "no-des",
        "no-md4",
        "no-mdc2",
        "no-whirlpool",
        "no-psk",
        "no-srp",
        "no-gost",
        "no-cms",
        "no-ts",
        "no-ocsp",
        "no-srtp",
        "no-seed",
        "no-bf",
        "no-cast",
        "no-camellia",
        "no-aria",
        "no-sm2",
        "no-sm3",
        "no-sm4",
        "no-siphash",
    )

// Helper function to download and verify OpenSSL source
fun downloadOpenSslSource(
    buildDir: File,
    version: String,
    sha256: String,
): File {
    val tarball = File(buildDir, "openssl-$version.tar.gz")
    val sourceDir = File(buildDir, "openssl-$version")

    if (sourceDir.exists()) return sourceDir

    buildDir.mkdirs()

    // Download if not present
    if (!tarball.exists()) {
        println("Downloading OpenSSL $version...")
        val url = URI("https://github.com/openssl/openssl/releases/download/openssl-$version/openssl-$version.tar.gz").toURL()
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
        throw GradleException("OpenSSL SHA256 mismatch: expected $sha256, got $actualSha256")
    }

    // Extract
    println("Extracting OpenSSL source...")
    ProcessBuilder("tar", "xzf", tarball.name)
        .directory(buildDir)
        .inheritIO()
        .start()
        .waitFor()

    return sourceDir
}

// Task to build OpenSSL static libraries for a specific architecture
fun createBuildOpenSslTask(arch: String): TaskProvider<Task> {
    val taskName = "buildOpenSsl${arch.replaceFirstChar { it.uppercase() }}"
    val opensslTarget = if (arch == "x64") "linux-x86_64" else "linux-aarch64"
    val outputDir = projectDir.resolve("libs/openssl/linux-$arch")
    val markerFile = outputDir.resolve("lib/.built-$opensslVersion")

    return tasks.register(taskName) {
        group = "build"
        description = "Build OpenSSL static libraries for Linux $arch"

        inputs.property("opensslVersion", opensslVersion)
        inputs.property("opensslSha256", opensslSha256)
        outputs.file(markerFile)

        onlyIf {
            !markerFile.exists()
        }

        doLast {
            val buildDir = opensslBuildDir.get().asFile
            val sourceDir = downloadOpenSslSource(buildDir, opensslVersion, opensslSha256)

            // Check for cross-compiler if needed
            val crossCompile =
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
                    "--cross-compile-prefix=aarch64-linux-gnu-"
                } else {
                    null
                }

            // Configure
            // Use C11 standard to avoid glibc 2.38+ C23 functions (e.g., __isoc23_strtol)
            // that aren't available in Kotlin/Native's older sysroot (glibc 2.19)
            logger.lifecycle("Configuring OpenSSL $opensslVersion for $arch...")
            val configureArgs =
                mutableListOf(
                    "./Configure",
                    opensslTarget,
                    "--prefix=/opt/openssl",
                    "--libdir=lib",
                )
            crossCompile?.let { configureArgs.add(it) }
            configureArgs.addAll(opensslConfigureOptions)

            val configureProcess =
                ProcessBuilder(configureArgs)
                    .directory(sourceDir)
                    .inheritIO()
            // Set CFLAGS to enforce C11 standard in environment
            configureProcess.environment()["CFLAGS"] = "-fPIC -std=gnu11"
            val configureResult = configureProcess.start().waitFor()

            if (configureResult != 0) {
                throw GradleException("OpenSSL configure failed")
            }

            // Build
            logger.lifecycle("Building OpenSSL (this may take a few minutes)...")
            val cpuCount = Runtime.getRuntime().availableProcessors()
            val makeResult =
                ProcessBuilder("make", "-j$cpuCount")
                    .directory(sourceDir)
                    .inheritIO()
                    .start()
                    .waitFor()

            if (makeResult != 0) {
                throw GradleException("OpenSSL build failed")
            }

            // Copy libraries
            outputDir.resolve("lib").mkdirs()
            sourceDir.resolve("libssl.a").copyTo(outputDir.resolve("lib/libssl.a"), overwrite = true)
            sourceDir.resolve("libcrypto.a").copyTo(outputDir.resolve("lib/libcrypto.a"), overwrite = true)

            // Write marker file
            markerFile.writeText("OpenSSL $opensslVersion built on ${System.currentTimeMillis()}")

            logger.lifecycle("OpenSSL $opensslVersion built successfully for $arch")
        }
    }
}

val buildOpenSslX64 = createBuildOpenSslTask("x64")
val buildOpenSslArm64 = createBuildOpenSslTask("arm64")

// Configure cinterop for Linux targets with static OpenSSL
// Requires: sudo apt install liburing-dev build-essential perl
// For ARM64 cross-compilation: sudo apt install gcc-aarch64-linux-gnu
// OpenSSL is built automatically by Gradle when needed
fun KotlinNativeTarget.configureLinuxCinterop(arch: String) {
    val opensslLibDir = projectDir.resolve("libs/openssl/linux-$arch/lib")
    val buildOpenSslTask = if (arch == "x64") buildOpenSslX64 else buildOpenSslArm64

    // Determine include/lib paths based on architecture and available cross-compilation tools
    val (systemIncludeDirs, systemLibDir) =
        if (arch == "x64") {
            listOf("/usr/include", "/usr/include/x86_64-linux-gnu") to "/usr/lib/x86_64-linux-gnu"
        } else {
            // ARM64: check for cross-compilation sysroot or native paths
            val crossRoot = "/usr/aarch64-linux-gnu"
            if (File(crossRoot).exists()) {
                listOf("$crossRoot/include", "/usr/include") to "$crossRoot/lib"
            } else {
                listOf("/usr/include", "/usr/include/aarch64-linux-gnu") to "/usr/lib/aarch64-linux-gnu"
            }
        }

    compilations["main"].cinterops {
        create("LinuxSockets") {
            defFile("src/nativeInterop/cinterop/LinuxSockets.def")
            // Use system headers for liburing, build directory headers for OpenSSL
            val allIncludes = systemIncludeDirs + opensslIncludeDir.get().asFile.absolutePath
            includeDirs(*allIncludes.toTypedArray())
            tasks.named(interopProcessingTaskName) {
                dependsOn(buildOpenSslTask)
            }
        }
    }
    // Link against static OpenSSL and system liburing
    binaries.all {
        linkerOpts(
            "-L${opensslLibDir.absolutePath}",
            "-L$systemLibDir",
            "-L/usr/lib",
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
        nodejs()
    }

    // Apple targets with Network.framework zero-copy socket implementation
    if (isMacOS) {
        // macOS
        macosArm64 { configureSocketWrapperCinterop("macos") }
        macosX64 { configureSocketWrapperCinterop("macos") }

        // iOS
        iosArm64 { configureSocketWrapperCinterop("ios") }
        iosSimulatorArm64 { configureSocketWrapperCinterop("ios-simulator") }
        iosX64 { configureSocketWrapperCinterop("ios-simulator") }

        // tvOS
        tvosArm64 { configureSocketWrapperCinterop("tvos") }
        tvosSimulatorArm64 { configureSocketWrapperCinterop("tvos-simulator") }
        tvosX64 { configureSocketWrapperCinterop("tvos-simulator") }

        // watchOS (watchosArm32 not supported by buffer library)
        watchosArm64 { configureSocketWrapperCinterop("watchos") }
        watchosSimulatorArm64 { configureSocketWrapperCinterop("watchos-simulator") }
        watchosX64 { configureSocketWrapperCinterop("watchos-simulator") }
    }

    // Linux targets with io_uring async I/O and statically-linked OpenSSL
    // Always register targets (for Dokka), but only configure cinterop on Linux
    linuxX64 {
        if (isLinux) {
            // x64 requires either native x64 machine or cross-compilation headers
            val osArch = System.getProperty("os.arch")
            val isNativeX64 = osArch == "amd64" || osArch == "x86_64"
            val hasX64CrossCompileHeaders = File("/usr/include/x86_64-linux-gnu").exists()

            println("linuxX64 config: os.arch=$osArch, isNativeX64=$isNativeX64, hasX64CrossCompileHeaders=$hasX64CrossCompileHeaders")

            if (isNativeX64 || hasX64CrossCompileHeaders) {
                println("  Calling configureLinuxCinterop(x64)...")
                configureLinuxCinterop("x64")
                println("  configureLinuxCinterop(x64) completed")
                println("  Binaries configured: ${binaries.toList().map { it.name }}")
            }
        }
    }
    linuxArm64 {
        if (isLinux) {
            // ARM64 requires either native ARM64 machine or cross-compilation headers
            // On x64, install: sudo apt install gcc-aarch64-linux-gnu libc6-dev-arm64-cross
            val osArch = System.getProperty("os.arch")
            // JVM may report "aarch64" or "arm64" depending on platform
            val isNativeArm64 = osArch == "aarch64" || osArch == "arm64"
            val crossInclude1 = File("/usr/include/aarch64-linux-gnu").exists()
            val crossInclude2 = File("/usr/aarch64-linux-gnu/include").exists()
            val hasCrossCompileHeaders = crossInclude1 || crossInclude2
            val canBuild = isNativeArm64 || hasCrossCompileHeaders

            println("linuxArm64 config: os.arch=$osArch, isNativeArm64=$isNativeArm64")
            println("  crossInclude1(/usr/include/aarch64-linux-gnu)=$crossInclude1")
            println("  crossInclude2(/usr/aarch64-linux-gnu/include)=$crossInclude2")
            println("  hasCrossCompileHeaders=$hasCrossCompileHeaders")
            println("  canBuild=$canBuild")

            if (canBuild) {
                println("  Calling configureLinuxCinterop(arm64)...")
                configureLinuxCinterop("arm64")
                println("  configureLinuxCinterop(arm64) completed")
                println("  Binaries configured: ${binaries.toList().map { it.name }}")
            }
        }
    }

    applyDefaultHierarchyTemplate()
    sourceSets {
        commonMain.dependencies {
            implementation(libs.buffer)
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

        // Add shared Apple implementation to all Apple target source sets
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
