import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.library)
    alias(libs.plugins.ktlint)
}

// :socket-quic-testsuite holds the cross-backend QUIC conformance suites (the abstract `*TestSuite`
// classes) plus the shared test harness, in MAIN source sets so BOTH backends — the quiche backend
// (:socket-quic-quiche, jvm/android/linux) and the Network.framework backend (:socket-quic-nw,
// apple) — can extend them from their own per-platform test source sets. Kotlin expect/actual can't
// cross module boundaries, so the harness `expect`s and their per-platform `actual`s live here
// together (commonJvm/linux/apple). Not published; consumed only as `testImplementation`.

val isMacOS = org.jetbrains.kotlin.konan.target.HostManager.hostIsMac
val isLinux = org.jetbrains.kotlin.konan.target.HostManager.hostIsLinux

repositories {
    mavenLocal()
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
