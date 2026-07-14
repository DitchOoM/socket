import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.plugin.mpp.KotlinNativeTarget

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.library)
    alias(libs.plugins.ktlint)
    alias(libs.plugins.maven.publish)
    signing
}

val isMainBranchGithub = System.getenv("GITHUB_REF") == "refs/heads/main"
val isMacOS = org.jetbrains.kotlin.konan.target.HostManager.hostIsMac
val isLinux = org.jetbrains.kotlin.konan.target.HostManager.hostIsLinux

// The datagram trichotomy lives in buffer-flow under @ExperimentalDatagramApi (RFC Phase 0/1). Until
// it publishes to Maven Central as a real release, :socket-udp consumes the pinned local SNAPSHOT
// (published via `./gradlew :buffer-flow:publishToMavenLocal -Pversion=6.11.0-SNAPSHOT`). mavenLocal()
// is listed first so the snapshot wins; when 6.11.0 lands on Central this pin becomes a normal dep.
val bufferFlowVersion = "6.11.0-SNAPSHOT"

repositories {
    mavenLocal()
    google()
    mavenCentral()
}

// --- Native cinterop wiring (RFC Phase 3) ---
//
// Deliberately liburing-ONLY on Linux and Network.framework-ONLY on Apple: UDP has no TLS, so unlike
// root :socket's LinuxSockets.def (which bundles BoringSSL for K/N TLS) the UDP datapath needs neither
// OpenSSL nor BoringSSL. We reuse root :socket's already-built liburing static lib (the `.a` is
// TLS-free — exactly how :socket-quic-quiche gets io_uring transitively) instead of duplicating the
// download/build machinery.

// Configure the Linux io_uring cinterop (liburing static lib + POSIX socket/cmsg C shims, NO OpenSSL).
fun KotlinNativeTarget.configureLinuxUdpCinterop(arch: String) {
    val liburingDir = rootProject.projectDir.resolve("libs/liburing/linux-$arch")
    val liburingLibDir = liburingDir.resolve("lib")
    val liburingIncludeDir = liburingDir.resolve("include")
    val buildLiburingTask =
        rootProject.tasks.named(if (arch == "x64") "buildLiburingX64" else "buildLiburingArm64")

    val systemIncludeDirs =
        if (arch == "x64") {
            listOf("/usr/include", "/usr/include/x86_64-linux-gnu")
        } else {
            val crossRoot = "/usr/aarch64-linux-gnu"
            val crossInclude = "/usr/include/aarch64-linux-gnu"
            when {
                File(crossRoot).exists() -> listOf("$crossRoot/include")
                File(crossInclude).exists() -> listOf(crossInclude)
                else -> listOf("/usr/include/aarch64-linux-gnu")
            }
        }

    // Rewrite the base def's placeholder linkerOpts into static-library embedding (liburing.a), the
    // same technique root :socket uses for OpenSSL+liburing — minus everything TLS.
    val generatedDefFile = projectDir.resolve("build/generated/cinterop/UdpSockets-$arch.def")
    val generateDefTask =
        tasks.register("generateUdpSocketsDef${arch.replaceFirstChar { it.uppercase() }}") {
            inputs.file("src/nativeInterop/cinterop/UdpSockets.def")
            outputs.file(generatedDefFile)
            doLast {
                val base = file("src/nativeInterop/cinterop/UdpSockets.def").readText()
                val modified =
                    base.replace(
                        "linkerOpts.linux = -luring -lpthread",
                        """libraryPaths.linux = ${liburingLibDir.absolutePath}
staticLibraries.linux = liburing.a
linkerOpts.linux = -lpthread""",
                    )
                generatedDefFile.parentFile.mkdirs()
                generatedDefFile.writeText(modified)
            }
        }

    compilations["main"].cinterops {
        create("UdpSockets") {
            defFile(generatedDefFile)
            includeDirs(*(listOf(liburingIncludeDir.absolutePath) + systemIncludeDirs).toTypedArray())
            tasks.named(interopProcessingTaskName) {
                dependsOn(generateDefTask)
                dependsOn(buildLiburingTask)
            }
        }
    }
    binaries.all {
        linkerOpts("-L${liburingLibDir.absolutePath}", "-luring", "-lpthread")
    }
}

// Configure the Apple Network.framework cinterop (plain-UDP NWConnection + POSIX server helpers).
fun KotlinNativeTarget.configureNwUdpCinterop() {
    compilations["main"].cinterops {
        create("NwUdp") {
            defFile("src/nativeInterop/cinterop/NwUdp.def")
            includeDirs("src/nativeInterop/cinterop")
        }
    }
}

// :socket-udp — a first-class UDP datagram substrate (RFC_UDP_MODULE.md).
//
// Deliberately a SEPARATE published module, not folded into root :socket: a consumer (../webrtc,
// or quiche's datapath in a later phase) can depend on UDP without dragging in TCP + TLS. It provides
// the buffer-flow datagram trichotomy (DatagramChannel / DatagramSource / DatagramSink + SocketAddress)
// on real sockets.
//
// Phase 2 shipped JVM + Android (NIO DatagramChannel). Phase 3 adds Linux io_uring + Apple
// Network.framework native actuals. Node dgram and the QUIC cutover land in later phases.
kotlin {
    jvmToolchain(21)

    androidTarget {
        compilerOptions.jvmTarget.set(JvmTarget.JVM_17)
        publishLibraryVariants("release")
    }
    jvm {
        // Java 8 bytecode to match the base :socket module — the production surface is pure java.nio
        // (DatagramChannel/Selector), no post-8 java.* API on the shipped path (JDK-version-specific
        // socket options are resolved reflectively via DatagramChannel.supportedOptions()).
        compilerOptions.jvmTarget.set(JvmTarget.JVM_1_8)
    }

    // Node.js `dgram` UDP (RFC Phase 4). The single JS target compiles for both runtimes; the
    // production actual guards on `global.window` and throws UnsupportedOperationException in the
    // browser (parity with root :socket's TCP), while `require('dgram')` is only reached at runtime
    // on Node (dynamic require, never webpack-bundled). Only jsNodeTest runs the conformance suite —
    // the browser has no raw UDP, so jsBrowserTest is disabled to avoid a headless-browser dependency.
    js {
        nodejs {
            testTask {
                useMocha { timeout = "30s" }
            }
        }
        browser {
            testTask { enabled = false }
        }
    }

    // Linux io_uring UDP (K/N). ARM64 must be cross-compiled from x64 (no prebuilt K/N linux-aarch64
    // compiler); both targets are always registered on Linux x64 for source-set resolution.
    if (isLinux) {
        linuxX64 { configureLinuxUdpCinterop("x64") }
        linuxArm64 { configureLinuxUdpCinterop("arm64") }
    }

    // Apple Network.framework UDP (K/N). Mirrors :socket-quic-quiche's five targets (no tvos/watchos —
    // buffer-flow's Apple klibs and the NW UDP client path target the mac/ios set).
    if (isMacOS) {
        macosArm64 { configureNwUdpCinterop() }
        macosX64 { configureNwUdpCinterop() }
        iosArm64 { configureNwUdpCinterop() }
        iosSimulatorArm64 { configureNwUdpCinterop() }
        iosX64 { configureNwUdpCinterop() }
    }

    applyDefaultHierarchyTemplate()
    sourceSets {
        commonMain.dependencies {
            api("com.ditchoom:buffer-flow:$bufferFlowVersion")
            api("com.ditchoom:buffer:$bufferFlowVersion")
            // buffer-codec: SocketAddressCodec (Codec<SocketAddress>) is the type-safe sockaddr SPI the
            // QUIC cutover consumes — encode a resolved SocketAddress into native C-sockaddr bytes for
            // quiche's recv_info/send_info FFI. api (not implementation): the Codec type is in its
            // public surface.
            api("com.ditchoom:buffer-codec:$bufferFlowVersion")
            implementation(libs.kotlinx.coroutines.core)
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
            implementation(libs.kotlinx.coroutines.core)
            implementation(libs.kotlinx.coroutines.test)
        }

        // Shared JVM/Android implementation (NIO DatagramChannel), mirroring root :socket's manually
        // created commonJvmMain — the default hierarchy template has no such intermediate set.
        val commonJvmMain by creating {
            dependsOn(commonMain.get())
        }
        jvmMain.get().dependsOn(commonJvmMain)
        androidMain.get().dependsOn(commonJvmMain)

        val commonJvmTest by creating {
            dependsOn(commonTest.get())
        }
        jvmTest.get().dependsOn(commonJvmTest)
        val androidUnitTest by getting
        androidUnitTest.dependsOn(commonJvmTest)
        androidUnitTest.dependencies {
            implementation(kotlin("test"))
            implementation(libs.kotlinx.coroutines.test)
        }
    }
}

android {
    compileSdk = 36
    (this as com.android.build.api.dsl.LibraryExtension)
        .sourceSets
        .getByName("main")
        .manifest
        .srcFile("src/androidMain/AndroidManifest.xml")
    defaultConfig {
        minSdk = 21
    }
    namespace = "com.ditchoom.socket.udp"

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

// --- Publishing ---
// Coordinates from socket-udp/gradle.properties (artifactName=socket-udp). Signing + Central upload
// engage only on a main-branch CI build supplying the in-memory PGP key; publishToMavenLocal needs none.

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
