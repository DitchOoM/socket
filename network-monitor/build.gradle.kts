import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.plugin.mpp.KotlinNativeTarget
import java.io.File

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

repositories {
    google()
    mavenCentral()
}

// com.ditchoom:network-monitor — the whole of network awareness, standalone: the NetworkMonitor /
// NetworkState / NetworkId contract, EVERY implementation of it (JVM FFM routing socket, Android
// ConnectivityManager, JS, Linux netlink, Apple NWPathMonitor), `NetworkMonitor.default()` /
// `.processDefault()`, and the point-in-time `enumerateNetworkInterfaces()` scan that ICE host-candidate
// gathering reads. Extracted from root :socket so consumers (../webrtc ICE/WebRTC, QUIC auto-migration)
// can depend on network-awareness without TCP + TLS. :socket re-exports all of it through
// `api(project(":network-monitor"))`, so nothing downstream moved packages.
//
// WHY ONE MODULE AND NOT TWO (issue #269 proposed a third, native-only module): `expect`/`actual`
// cannot span a module boundary. Leaving the native monitors anywhere else leaves `default()` split
// from half its actuals and forces the monitors to be duplicated. Since the native monitors have to be
// wherever `default()` is, and `default()` has to be wherever the contract is, there is exactly one
// module.
//
// THE CINTEROP CONSTRAINT (this comment used to assert the wrong rule; the measurement below is what
// actually holds on Kotlin 2.4.0). It is NOT "a second cinterop-bearing project dependency to :socket
// evicts :socket's commonized LinuxSockets klib". Two probe modules, each a cinterop-bearing project
// dependency added to :socket's SHARED linuxMain / appleMain, differing ONLY in which C headers their
// .def declares:
//
//   linux/netlink.h + linux/rtnetlink.h  (ALSO declared by LinuxSockets.def)
//       -> :compileKotlinLinuxX64 FAILS — socket's own NLM_F_DUMP / NLMSG_DONE / RTM_NEWROUTE /
//          RTN_UNICAST go unresolved in LinuxNetworkMonitor.kt
//   sys/utsname.h                        (declared by nobody else)
//       -> :linkDebugTestLinuxX64 SUCCEEDS
//   Objective-C header transitively re-declaring <Network/Network.h> (as nw_helpers.h does)
//       -> :compileKotlinMacosArm64 and :linkDebugTestMacosArm64 both SUCCEED
//
// So the real constraint is HEADER OVERLAP between two cinterops on one classpath — not
// cinterop-bearing-ness, and not project-vs-external (this module already ships next to the
// cinterop-bearing external boringssl-canonical klib on linuxMain). Differing `package =` names do not
// help; the failing probe used its own package.
//
// The extraction satisfies that constraint BY CONSTRUCTION rather than by luck:
//   Linux — LinuxNetworkMonitor.kt was the only netlink user in :socket, so LinuxSockets.def dropped
//           linux/netlink.h + linux/rtnetlink.h and Netlink.def here is now their sole declarer. Its
//           headerFilter admits nothing else; everything non-netlink the monitor needs (socket(2),
//           getifaddrs, if_nametoindex, IFF_UP) comes from Kotlin/Native's own platform.posix /
//           platform.linux klibs, which are not cinterops of ours.
//   Apple — never overlapped (third probe above), but the split is still clean by ownership:
//           NetworkHelpers.def declares only our own nm_* helpers, and :socket's NWHelpers.def dropped
//           every one it used to own on this side (the four nw_helper_path_monitor_* AND
//           nw_helper_enumerate_interfaces) because it no longer has a caller for any of them.
//
// Before adding a header to either .def here, check it is not already declared by LinuxSockets.def or
// NWHelpers.def — and re-run those probes rather than citing this comment.

// Apple C bridge: the NWPathMonitor behind AppleNetworkMonitor and the getifaddrs scan behind
// enumerateNetworkInterfaces(). Header-only + framework linkage — no archive to build, so unlike
// :socket's Apple cinterop there is nothing to sequence against.
fun KotlinNativeTarget.configureNetworkHelpersCinterop() {
    compilations["main"].cinterops {
        create("NetworkHelpers") {
            defFile("src/nativeInterop/cinterop/NetworkHelpers.def")
            includeDirs("src/nativeInterop/cinterop")
        }
    }
}

// Netlink (linux/netlink.h + linux/rtnetlink.h) bridge for LinuxNetworkMonitor. Pure UAPI headers plus
// one static-inline bind shim — no libraries to link, no archives to build.
//
// The system include dirs mirror :socket's configureLinuxCinterop: the netlink UAPI headers ship in
// linux-libc-dev under /usr/include, and linuxArm64 is CROSS-compiled from x64 (Kotlin/Native has no
// linux-aarch64 compiler), so on that target they must be read from the aarch64 sysroot instead.
fun KotlinNativeTarget.configureNetlinkCinterop(arch: String) {
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
    compilations["main"].cinterops {
        create("Netlink") {
            defFile("src/nativeInterop/cinterop/Netlink.def")
            includeDirs(*(listOf("src/nativeInterop/cinterop") + systemIncludeDirs).toTypedArray())
        }
    }
}

kotlin {
    // Match the target set of :socket exactly — :socket depends on this module and compiles every one
    // of these targets, so the dependency must publish an artifact for each.
    jvmToolchain(21)

    androidTarget {
        publishLibraryVariants("release")
        compilerOptions.jvmTarget.set(JvmTarget.JVM_17)
    }
    jvm {
        compilerOptions.jvmTarget.set(JvmTarget.JVM_1_8)
        // Java 21 compilation for the FFM-based reactive network monitor (routing sockets).
        // Shipped under META-INF/versions/21 (multi-release JAR) so it only loads on JDK 21+;
        // the Java-8 base compilation keeps the polling fallback (see JvmNetworkMonitorSelector.kt).
        compilations.create("java21") {
            compilerOptions.configure {
                jvmTarget.set(JvmTarget.JVM_21)
            }
            defaultSourceSet {
                kotlin.srcDir("src/jvm21Main/kotlin")
                dependencies {
                    // Reach the base compilation's output for NetworkMonitor, NetworkAvailability,
                    // PollingNetworkMonitor (Windows fallback), etc.
                    implementation(
                        this@jvm
                            .compilations
                            .getByName("main")
                            .output.classesDirs,
                    )
                    implementation(libs.kotlinx.coroutines.core)
                }
            }
        }
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

    // Apple targets — AppleNetworkMonitor over NWPathMonitor plus the getifaddrs interface scan, via
    // this module's own NetworkHelpers cinterop. Registered on macOS hosts so :socket's Apple
    // compilations resolve this dep.
    if (isMacOS) {
        macosArm64 { configureNetworkHelpersCinterop() }
        macosX64 { configureNetworkHelpersCinterop() }
        iosArm64 { configureNetworkHelpersCinterop() }
        iosSimulatorArm64 { configureNetworkHelpersCinterop() }
        iosX64 { configureNetworkHelpersCinterop() }
        tvosArm64 { configureNetworkHelpersCinterop() }
        tvosSimulatorArm64 { configureNetworkHelpersCinterop() }
        tvosX64 { configureNetworkHelpersCinterop() }
        watchosArm64 { configureNetworkHelpersCinterop() }
        watchosSimulatorArm64 { configureNetworkHelpersCinterop() }
        watchosX64 { configureNetworkHelpersCinterop() }
    }

    // Linux targets — LinuxNetworkMonitor over netlink, via this module's own Netlink cinterop.
    // ARM64 is cross-registered on x64 for source-set resolution.
    if (isLinux) {
        linuxX64 { configureNetlinkCinterop("x64") }
        linuxArm64 { configureNetlinkCinterop("arm64") }
    }

    applyDefaultHierarchyTemplate()
    sourceSets {
        commonMain.dependencies {
            // StateFlow is on the public NetworkMonitor surface → api.
            api(libs.kotlinx.coroutines.core)
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
            implementation(libs.kotlinx.coroutines.core)
            implementation(libs.kotlinx.coroutines.test)
        }
        androidMain.dependencies {
            // App Startup: NetworkMonitorInitializer (contributed to androidx.startup's
            // InitializationProvider by src/androidMain/AndroidManifest.xml) captures the application
            // Context at process start, so NetworkMonitor.default() is reactive on Android without the
            // app remembering installAndroidContext(). `implementation`, not `api`: consumers never
            // compile against androidx.startup, they only need it on the runtime classpath, and its own
            // consumer proguard.txt keeps `* extends Initializer` so R8 cannot strip ours.
            implementation(libs.androidx.startup.runtime)
        }
        jsMain.dependencies {
            implementation(libs.kotlin.js)
        }
        jsTest.dependencies {
            implementation(kotlin("test-js"))
        }

        // Shared JVM/Android implementation (PollingNetworkMonitor, JvmNetworkId), mirroring root
        // :socket's manually created commonJvmMain — the default hierarchy template has no such set.
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
            // Robolectric: run the Android monitor against the real framework classes on the host JVM.
            // Without it the only signal for AndroidNetworkMonitor / NetworkMonitorInitializer is the
            // emulator lane — one API-29 job that also boots an AVD and builds the quiche NDK libs, and
            // that cannot reach the API<23 networkHandle branch or the stripped-permission path at all.
            implementation(libs.robolectric)
        }

        // Apple implementation, added to every Apple target's MAIN source set by srcDir rather than
        // written into the shared appleMain — exactly as root :socket does it, and for the same reason:
        // appleMain is also compiled as common metadata (compileAppleMainKotlinMetadata), which cannot
        // resolve the per-target NWPathMonitor cinterop. Per-target srcDirs are only ever compiled by a
        // real target, where the cinterop klib exists.
        //
        // appleTest needs no such treatment: it is compiled once per target (never as metadata) and is
        // associated with that target's main compilation, so it sees both the cinterop and the
        // `internal` mappers (appleNetworkState / appleNetworkId) it asserts on.
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

            // Wi-Fi RSSI (LinkQuality) is macOS-only among Apple platforms: CoreWLAN is the one PUBLIC
            // signal-strength API, and it exists only there. Same per-target-srcDir technique as
            // appleNativeImpl (these are only ever compiled by a real target, never as shared
            // metadata), with exactly one of the two twins per target: macOS gets the CoreWLAN sampler
            // (platform.CoreWLAN ships in Kotlin/Native's macOS platform klibs — no cinterop needed),
            // every other Apple target gets the honest-absence twin that declares
            // LinkQualityResolution.None and never fabricates a reading.
            val appleRssiMacosDir = file("src/appleRssiMacosImpl/kotlin")
            val appleRssiDefaultDir = file("src/appleRssiDefaultImpl/kotlin")
            listOf("macosArm64Main", "macosX64Main").forEach { sourceSetName ->
                findByName(sourceSetName)?.kotlin?.srcDir(appleRssiMacosDir)
            }
            listOf(
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
                findByName(sourceSetName)?.kotlin?.srcDir(appleRssiDefaultDir)
            }
        }

        // Linux uses the standard KMP hierarchy: src/linuxMain / src/linuxTest are shared by linuxX64
        // and linuxArm64 with no extra wiring.
    }
}

// Put the java21 (FFM network-monitor) compilation output on the JVM test classpath so
// NetlinkNetworkMonitorTest can reference NetlinkNetworkMonitor at both compile and runtime.
// compileDependencyFiles lets the test resolve the class; the afterEvaluate below appends it to the
// actual jvmTest runtime classpath (appended — the base polling selector still wins for
// NetworkMonitor.default() in a normal run; the FFM classes are exercised via direct instantiation).
kotlin.jvm().compilations.getByName("test") {
    compileDependencyFiles +=
        kotlin
            .jvm()
            .compilations["java21"]
            .output.classesDirs
}
afterEvaluate {
    tasks.named<Test>("jvmTest") {
        dependsOn("compileJava21KotlinJvm")
        classpath +=
            kotlin
                .jvm()
                .compilations["java21"]
                .output.classesDirs
    }
}

// netns JVM route-resolution probe support (test-harness/netns): dump the jvmTest runtime classpath and
// the JDK 21 toolchain `java` launcher to build/netns/ so run-netns-tests.sh can exec NetnsJvmProbe
// inside a rootless network namespace (`unshare -rnm`) — the JVM twin of the native NetnsRouteResolution
// test. The classpath already carries the java21 (FFM) output (appended to jvmTest above), so the probe
// reaches both the commonJvmMain JvmNetworkId path and NetlinkNetworkMonitor. JDK 21 is required for the
// FFM classes to load; the toolchain launcher below is that exact JVM. See the harness script for how it
// is consumed (it self-skips when these files are absent, keeping native-only runs working).
val netnsJava21Launcher =
    javaToolchains.launcherFor {
        languageVersion.set(JavaLanguageVersion.of(21))
    }
afterEvaluate {
    tasks.register("netnsJvmProbeClasspath") {
        description = "Dumps the jvmTest runtime classpath + JDK21 java launcher for the netns JVM probe."
        // The probe runs off the compiled jvmTest classes + java21 FFM output + all runtime deps.
        dependsOn("compileTestKotlinJvm", "compileJava21KotlinJvm")
        val jvmTest = tasks.named<Test>("jvmTest")
        val outDir = layout.buildDirectory.dir("netns")
        val launcher = netnsJava21Launcher
        outputs.dir(outDir)
        doLast {
            val dir = outDir.get().asFile
            dir.mkdirs()
            // Resolve the FINAL jvmTest classpath (afterEvaluate appended the java21 output to it).
            dir.resolve("jvm-test-classpath.txt").writeText(jvmTest.get().classpath.asPath)
            val javaExe = launcher.get().executablePath.asFile
            dir.resolve("java21-launcher.txt").writeText(javaExe.absolutePath)
        }
    }
}

// Multi-release JAR: ship the JDK 21 FFM network-monitor bindings under META-INF/versions/21.
// Wrapped in afterEvaluate because jvmJar is created by the KMP plugin.
afterEvaluate {
    tasks.named<Jar>("jvmJar") {
        manifest {
            attributes("Multi-Release" to "true")
        }
        into("META-INF/versions/21") {
            from(
                kotlin
                    .jvm()
                    .compilations["java21"]
                    .output.allOutputs,
            )
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
        // 23 (M), not 21. AndroidNetworkMonitor needs ConnectivityManager.getActiveNetwork() (API 23) to
        // seed its state, and Network.getNetworkHandle() (also 23) to give a NetworkId.Link a real
        // per-link identity. Declaring 21 while calling both was simply a latent NoSuchMethodError on
        // API 21/22, and the obvious guarded fallback onto the deprecated activeNetworkInfo is untestable
        // with any infrastructure this repo has: Robolectric 4.16.1 rejects @Config(sdk = 21) and 22 with
        // "API level N is not available", and the emulator lanes run 29 and 35. Below 23 a NetworkState
        // could never carry a real identity anyway, so an untestable branch would buy a configuration
        // that cannot produce a useful answer (RFC_NETWORK_REACHABILITY §8.1).
        minSdk = 23
    }
    namespace = "com.ditchoom.networkmonitor"

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    // Robolectric needs the merged manifest + resources on the unit-test classpath; without
    // isIncludeAndroidResources it cannot resolve the application under test. returnDefaultValues keeps
    // any framework call we have NOT shadowed from throwing "not mocked" and masking the real assertion.
    testOptions {
        unitTests {
            isIncludeAndroidResources = true
            isReturnDefaultValues = true
        }
    }

    publishing {
        singleVariant("release") {
            withSourcesJar()
            withJavadocJar()
        }
    }
}

// --- Publishing ---
// Coordinates from network-monitor/gradle.properties (artifactName=network-monitor). Signing + Central
// upload engage only on a main-branch CI build supplying the in-memory PGP key; publishToMavenLocal
// needs none.

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
