// Baseline-profile producer and first-request macrobenchmark for :benchmark-android-app.
//
// Runs on a Gradle-managed emulator (`pixel6Api34`, an AOSP API 34 image), so it needs no attached
// device and works on a GitHub-hosted runner with KVM. Profile generation:
//
//   ./gradlew :benchmark-android-app:generateReleaseBaselineProfile -PappleTargets=false
//
// The macrobenchmark that measures what the profile buys (first TCP round trip, QUIC handshake, first
// QUIC round trip; CompilationMode.None vs the baseline profile):
//
//   ./gradlew :benchmark-baselineprofile:pixel6Api34BenchmarkReleaseAndroidTest -PappleTargets=false \
//       -Pandroid.testInstrumentationRunnerArguments.class=com.ditchoom.socket.benchmark.baselineprofile.FirstRequestBenchmark
//
// Emulator numbers are regression signals only; see the device work queue for real-device runs.
plugins {
    // No version: AGP is already on the build classpath through the root project's com.android.library.
    id("com.android.test")
    kotlin("android")
    id("androidx.baselineprofile")
    alias(libs.plugins.ktlint)
}

repositories {
    google()
    mavenCentral()
}

android {
    namespace = "com.ditchoom.socket.benchmark.baselineprofile"
    compileSdk = 37

    defaultConfig {
        minSdk = 28
        targetSdk = 37
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        // An emulator is never a stable benchmark device; the numbers are signals, not results.
        testInstrumentationRunnerArguments["androidx.benchmark.suppressErrors"] = "EMULATOR"
    }

    targetProjectPath = ":benchmark-android-app"

    testOptions.managedDevices.localDevices.create("pixel6Api34") {
        device = "Pixel 6"
        apiLevel = 34
        systemImageSource = "aosp"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin {
    jvmToolchain(21)
    compilerOptions.jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
}

baselineProfile {
    managedDevices += "pixel6Api34"
    useConnectedDevices = false
}

dependencies {
    implementation("androidx.benchmark:benchmark-macro-junit4:${providers.gradleProperty("androidxBenchmarkVersion").get()}")
    implementation("androidx.test.ext:junit:1.3.0")
    implementation("androidx.test:runner:1.7.0")
    implementation("androidx.test.uiautomator:uiautomator:${providers.gradleProperty("androidxUiautomatorVersion").get()}")
}

ktlint {
    filter {
        exclude { it.file.path.contains("/build/") }
    }
}
