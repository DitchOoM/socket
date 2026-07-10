import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.library)
    alias(libs.plugins.ktlint)
}

// :socket-testsuite holds the stack's cross-module conformance suites (the abstract `*TestSuite`
// classes) plus the shared test harness, in MAIN source sets so consumers can extend them from their
// own per-platform test source sets. Kotlin expect/actual can't cross module boundaries, so the
// harness `expect`s and their per-platform `actual`s live here together (commonJvm/linux/apple). Not
// published; consumed only as `testImplementation`. Two families today:
//   - QUIC backend conformance (the `Quic*TestSuite`s): extended by the quiche backend
//     (:socket-quic-quiche, jvm/android/linux) and the Network.framework backend (:socket-quic-nw,
//     apple). These drive only the public default-engine entrypoints (withQuicConnection/Server).
//   - WebTransport conformance (`WebTransportTestSuite`): extended by :socket-webtransport's native
//     test source sets. Drives the PUBLIC h3 server (withHttp3Server) + the PUBLIC neutral client
//     (webTransportSupport()) — never any socket-http3 codec internals, so socket-http3's white-box
//     tests (the internal-codec Http3LoopbackServer) deliberately stay in :socket-http3's own
//     commonTest rather than moving here.

val isMacOS = org.jetbrains.kotlin.konan.target.HostManager.hostIsMac
val isLinux = org.jetbrains.kotlin.konan.target.HostManager.hostIsLinux

repositories {
    google()
    mavenCentral()
}

kotlin {
    jvmToolchain(21)

    androidTarget {
        compilerOptions.jvmTarget.set(JvmTarget.JVM_17)
    }
    jvm {
        compilerOptions.jvmTarget.set(JvmTarget.JVM_1_8)
    }

    // Linux quiche-backend consumers (host-gated like :socket-quic-quiche).
    if (isLinux) {
        linuxX64()
        linuxArm64()
    }

    // Apple Network.framework-backend consumers (host-gated like :socket-quic-nw).
    if (isMacOS) {
        macosArm64()
        macosX64()
        iosArm64()
        iosSimulatorArm64()
        iosX64()
        tvosArm64()
        tvosSimulatorArm64()
        tvosX64()
        watchosArm64()
        watchosSimulatorArm64()
        watchosX64()
    }

    applyDefaultHierarchyTemplate()
    sourceSets {
        commonMain.dependencies {
            // The suites drive QUIC through the public default-engine entrypoints
            // (withQuicConnection/withQuicServer), which live in :socket-quic-default; it
            // transitively exposes the :socket-quic SPI. No cycle: the backends' *main* code never
            // depends on this test-support module (only their *test* source sets do).
            api(project(":socket-quic-default"))
            // WebTransport conformance suite: the PUBLIC h3 server (withHttp3Server) lives in
            // :socket-http3, and the PUBLIC neutral client (webTransportSupport()) in
            // :socket-webtransport. socket-webtransport's http3Main pulls socket-http3 as
            // `implementation` (not exposed transitively), so socket-http3 is named explicitly here.
            // Both build a superset of this module's targets (jvm/android/linux/apple), so they resolve
            // on every target. Still test-scope-only for consumers → no main-code cycle.
            api(project(":socket-http3"))
            api(project(":socket-webtransport"))
            api(libs.buffer)
            api(libs.buffer.flow)
            api(libs.kotlinx.coroutines.core)
            api(libs.kotlinx.coroutines.test)
            // kotlin.test in a MAIN source set: the umbrella `kotlin("test")` doesn't pull the
            // common `@Test` annotation artifact the way a test source set does, so add it explicitly.
            api(kotlin("test"))
            api(kotlin("test-annotations-common"))
        }

        // jvm + android share the JVM-flavoured harness actuals (mirrors :socket-quic-quiche).
        val commonJvmMain by creating {
            dependsOn(commonMain.get())
            dependencies {
                // kotlin.test.Test on JVM aliases org.junit.Test; a MAIN source set doesn't get the
                // framework auto-wired (that's test-source-set magic), so add junit explicitly. Native
                // (linux/apple) has kotlin.test bundled in the platform libs.
                api(kotlin("test-junit"))
            }
        }
        jvmMain.get().dependsOn(commonJvmMain)
        androidMain.get().dependsOn(commonJvmMain)
        androidMain.dependencies {
            // buffer-android doesn't declare atomicfu in its metadata; the QUIC StateFlow path needs
            // it at runtime on Android (see :socket-quic-quiche for the full note).
            implementation("org.jetbrains.kotlinx:atomicfu:0.33.0")
        }
    }
}

android {
    compileSdk = 36
    defaultConfig {
        minSdk = 24
    }
    namespace = "com.ditchoom.socket.quic.testsuite"
}

// ─── controllerJar ────────────────────────────────────────────────────
// Runnable fat jar of the W6 harness control-plane controller (jvmMain
// `HarnessController` — RFC_DETERMINISTIC_SIMULATION §7), modeled on
// :socket-quic-quiche's quicEchoJar. Output:
// test-harness/controller/harness-controller.jar, where the controller
// service's Dockerfile COPYs it. Wired as a dependsOn of the root
// `harnessUp` so `docker compose up` always sees a jar that matches the
// current source.
//
// Why a fat jar and not a thin jar + classpath script: Docker runs a single
// `java -jar` with zero host knowledge (same rationale as quicEchoJar).
tasks.register<Jar>("controllerJar") {
    group = "build"
    description =
        "Build a runnable fat jar of the harness controller for test-harness/controller. " +
        "Output: test-harness/controller/harness-controller.jar"

    val mainCompilation = kotlin.jvm().compilations.getByName("main")
    dependsOn(mainCompilation.compileTaskProvider)
    // runtimeDependencyFiles carries the buildable project-dependency jars;
    // depending on the FileCollection itself makes Gradle build them first.
    dependsOn(mainCompilation.runtimeDependencyFiles)

    archiveBaseName.set("harness-controller")
    archiveVersion.set("")
    destinationDirectory.set(rootProject.projectDir.resolve("test-harness/controller"))

    manifest {
        attributes(
            "Main-Class" to "com.ditchoom.socket.testsuite.controller.HarnessControllerKt",
            // Dep jars (e.g. socket-quic-quiche) are multi-release; preserve that.
            "Multi-Release" to "true",
        )
    }

    from(mainCompilation.output.allOutputs)
    // Unpack every runtime dep jar so the result is `java -jar`-able. Duplicate
    // META-INF entries would break the jar; EXCLUDE keeps the first occurrence.
    from({
        mainCompilation.runtimeDependencyFiles.files
            .filter { it.isFile && it.name.endsWith(".jar") }
            .map { zipTree(it) }
    })

    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    // Drop signing metadata from upstream jars — fat-jar invalidates signatures.
    exclude("META-INF/*.SF", "META-INF/*.DSA", "META-INF/*.RSA", "META-INF/INDEX.LIST", "module-info.class")
}
