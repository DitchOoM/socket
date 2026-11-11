// Android Jetpack Microbenchmarks for the QUIC transport, run in a release-like build: the test APK is
// minified by R8 (full mode, AGP's default), so what is measured — and what has to load quiche's JNI
// natives by name — is the code an app that ships with R8 runs.
//
//   ./gradlew :benchmark-android:connectedReleaseAndroidTest -PappleTargets=false
//   ./gradlew :benchmark-android:connectedReleaseAndroidTest -PappleTargets=false -PbenchMinify=false
//   ./gradlew :benchmark-android:connectedReleaseAndroidTest -PappleTargets=false -PbenchDefaultProguardFile=false
//
// -PbenchMinify=false turns R8 off, for the with/without comparison. -PbenchDefaultProguardFile=false drops
// AGP's proguard-android-optimize.txt, leaving only the libraries' consumer rules: the configuration
// that proves :socket-quic-quiche's consumer-rules.pro keeps JNI working on its own, for an app that
// does not use AGP's default file.
//
// On an emulator, Microbenchmark refuses to report unless told to: CI passes
// -Pandroid.testInstrumentationRunnerArguments.androidx.benchmark.suppressErrors=EMULATOR, and its
// numbers are regression signals only. Real-device numbers are a separate, manual run.
//
// Results: build/outputs/connected_android_test_additional_output/releaseAndroidTest/connected/<device>/
// *-benchmarkData.json.
plugins {
    alias(libs.plugins.android.library)
    kotlin("android")
    id("androidx.benchmark")
    alias(libs.plugins.ktlint)
}

repositories {
    google()
    mavenCentral()
}

val minify = providers.gradleProperty("benchMinify").map(String::toBooleanStrict).getOrElse(true)
val defaultProguardFile = providers.gradleProperty("benchDefaultProguardFile").map(String::toBooleanStrict).getOrElse(true)
val androidxBenchmarkVersion = providers.gradleProperty("androidxBenchmarkVersion").get()

// Copies the committed test certificate pair (socket-quic-quiche/testcerts, valid to 2047) into a
// generated resource dir as certs/cert.crt and certs/cert.key, which the code reads as Java resources.
// Not :socket-quic-quiche's androidInstrumentedTest resources: that dir also receives certs its own
// tasks generate, which this module must neither depend on nor race.
abstract class CopyBenchmarkCerts : DefaultTask() {
    @get:InputFiles
    @get:PathSensitive(PathSensitivity.NAME_ONLY)
    abstract val certs: ConfigurableFileCollection

    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    @TaskAction
    fun copy() {
        val dir = outputDir.get().asFile.resolve("certs")
        dir.deleteRecursively()
        dir.mkdirs()
        certs.forEach { it.copyTo(dir.resolve(it.name)) }
    }
}

val testCerts = rootProject.files("socket-quic-quiche/testcerts/cert.crt", "socket-quic-quiche/testcerts/cert.key")

// Out of the repo-wide `connectedAndroidTest` / `connectedCheck`: android_integration.yaml's emulator
// lane runs those aggregates across every project, and benchmarks are not instrumented tests. They run
// only when asked for by name (the android-benchmark job, baseline-profile.yaml). AGP attaches each
// variant's connected task to the aggregates while it creates variants, after this script runs, so
// the detach waits for every project to finish evaluating.
gradle.projectsEvaluated {
    tasks
        .matching { it.name in setOf("connectedAndroidTest", "connectedCheck", "deviceAndroidTest", "deviceCheck") }
        .configureEach { setDependsOn(emptyList<Any>()) }
}

android {
    namespace = "com.ditchoom.socket.benchmark.android"
    compileSdk = 37

    defaultConfig {
        minSdk = 24
        testInstrumentationRunner = "androidx.benchmark.junit4.AndroidBenchmarkRunner"
    }

    // Benchmarks run against the release build type, which is the minified one.
    testBuildType = "release"
    buildTypes {
        getByName("release") {
            isMinifyEnabled = minify
            (this as com.android.build.api.dsl.LibraryBuildType).androidTest.enableMinification = minify
            // The test APK is the app here: it holds the library code, the dependencies and the
            // benchmarks, and these are the rules R8 shrinks it with, on top of every dependency's
            // consumer rules (:socket-quic-quiche's consumer-rules.pro among them).
            val rules =
                listOfNotNull(
                    getDefaultProguardFile("proguard-android-optimize.txt").takeIf { defaultProguardFile },
                    file("benchmark-proguard-rules.pro"),
                )
            proguardFiles(*rules.toTypedArray())
            testProguardFiles(*rules.toTypedArray())
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

androidComponents {
    onVariants { variant ->
        variant.deviceTests.values.forEach { test ->
            val copy =
                tasks.register<CopyBenchmarkCerts>(
                    "copyBenchmarkCerts${variant.name.replaceFirstChar { it.uppercase() }}${test.name.replaceFirstChar { it.uppercase() }}",
                ) {
                    certs.from(testCerts)
                }
            test.sources.resources?.addGeneratedSourceDirectory(copy, CopyBenchmarkCerts::outputDir)
        }
    }
}

kotlin {
    jvmToolchain(21)
    compilerOptions.jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
}

dependencies {
    androidTestImplementation(project(":socket-quic"))
    androidTestImplementation(project(":socket-quic-default"))
    androidTestImplementation(project(":socket-quic-quiche"))
    androidTestImplementation(libs.buffer)
    androidTestImplementation(libs.buffer.flow)
    androidTestImplementation(libs.kotlinx.coroutines.core)
    androidTestImplementation("androidx.benchmark:benchmark-junit4:$androidxBenchmarkVersion")
    androidTestImplementation("androidx.test:runner:1.7.0")
    androidTestImplementation("androidx.test.ext:junit:1.3.0")
}

ktlint {
    filter {
        exclude { it.file.path.contains("/build/") }
    }
}
