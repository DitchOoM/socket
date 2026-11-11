import java.util.zip.ZipFile
import java.util.zip.ZipInputStream

// A minimal app that exercises the library the way an application's first requests would — a TCP
// loopback round trip, then a QUIC handshake, stream round trips and stream-per-request — so that
// :benchmark-baselineprofile can record which library methods run, and measure first-request time with
// and without the resulting baseline profile. Never published.
//
// Regenerate the libraries' baseline profiles (Gradle-managed emulator, no device needed):
//
//   ./gradlew :benchmark-android-app:generateReleaseBaselineProfile :benchmark-android-app:distributeBaselineProfile \
//       -PappleTargets=false
//
// generateReleaseBaselineProfile writes the app-wide profile to src/release/generated/baselineProfiles/
// (every class the app ran); distributeBaselineProfile keeps the rules for this library's classes, splits them by the module each class
// is compiled into and writes each library's src/androidMain/baselineProfiles/baseline-prof.txt, which
// AGP packages into that library's AAR.
plugins {
    // No version: AGP is already on the build classpath through the root project's com.android.library.
    id("com.android.application")
    kotlin("android")
    id("androidx.baselineprofile")
    alias(libs.plugins.ktlint)
}

repositories {
    google()
    mavenCentral()
}

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
    namespace = "com.ditchoom.socket.benchmark.app"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.ditchoom.socket.benchmark.app"
        // 28, not :socket-quic-quiche's declared 24: its dependency com.ditchoom:buffer-crypto-android
        // declares minSdk 28, so an app below 28 fails manifest merging.
        minSdk = 28
        targetSdk = 37
        versionCode = 1
        versionName = "1"
    }

    buildTypes {
        getByName("release") {
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            // Signed with the debug key so the benchmark can install it; it is never distributed.
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

androidComponents {
    onVariants { variant ->
        val copy =
            tasks.register<CopyBenchmarkCerts>("copyBenchmarkCerts${variant.name.replaceFirstChar { it.uppercase() }}") {
                certs.from(testCerts)
            }
        variant.sources.resources?.addGeneratedSourceDirectory(copy, CopyBenchmarkCerts::outputDir)
    }
}

kotlin {
    jvmToolchain(21)
    compilerOptions.jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
}

baselineProfile {
    // Generated on demand, never as a side effect of assembling the app.
    automaticGenerationDuringBuild = false
    saveInSrc = true
    // No filter here: distributeBaselineProfile keeps exactly the rules whose class one of this
    // library's AARs compiles, so kotlinx, buffer and the app's own classes are dropped there.
}

dependencies {
    implementation(project(":"))
    implementation(project(":socket-quic"))
    implementation(project(":socket-quic-default"))
    implementation(project(":socket-quic-quiche"))
    implementation(libs.buffer)
    implementation(libs.buffer.flow)
    implementation(libs.kotlinx.coroutines.core)
    // Installs a shipped baseline profile at first launch on devices that do not get it from the store,
    // which is what lets the startup benchmark measure the profile at all.
    implementation("androidx.profileinstaller:profileinstaller:${providers.gradleProperty("androidxProfileInstallerVersion").get()}")
    baselineProfile(project(":benchmark-baselineprofile"))
}

ktlint {
    filter {
        exclude { it.file.path.contains("/build/") }
    }
}

// ── Splitting the app-wide profile into the libraries ───────────────────────────────────────────────
//
// A rule belongs to the library whose AAR holds its class. Classes are matched against each library's
// compiled release classes, so a package two modules share (com.ditchoom.socket.quic lives in both
// :socket-quic and :socket-quic-quiche) still splits correctly.
val libraryModules = listOf(":", ":network-monitor", ":socket-quic", ":socket-quic-default", ":socket-quic-quiche", ":socket-udp")

tasks.register("distributeBaselineProfile") {
    group = "baseline profile"
    description = "Splits src/release/generated/baselineProfiles/baseline-prof.txt into each library's src/androidMain/baselineProfiles."
    notCompatibleWithConfigurationCache("reads other projects' outputs; run by hand")
    val generated = layout.projectDirectory.file("src/release/generated/baselineProfiles/baseline-prof.txt")
    val aars = libraryModules.associateWith { path -> project(path).layout.buildDirectory.dir("outputs/aar") }
    dependsOn(libraryModules.map { "$it:bundleReleaseAar" })
    mustRunAfter("generateReleaseBaselineProfile")
    doLast {
        val profile = generated.asFile
        check(profile.isFile) { "No generated profile at $profile; run :benchmark-android-app:generateReleaseBaselineProfile first." }
        val classesByModule =
            aars.mapValues<String, Provider<Directory>, Set<String>> { (path, dir) ->
                val aar =
                    dir
                        .get()
                        .asFile
                        .listFiles { f -> f.name.endsWith("-release.aar") }
                        ?.singleOrNull()
                        ?: error("expected exactly one release AAR in ${dir.get()} for $path")
                ZipFile(aar).use { zip ->
                    val classesJar = zip.getEntry("classes.jar")
                    val names = mutableSetOf<String>()
                    ZipInputStream(zip.getInputStream(classesJar)).use { jar ->
                        generateSequence { jar.nextEntry }.forEach { e ->
                            if (e.name.endsWith(".class")) names += e.name.removeSuffix(".class")
                        }
                    }
                    names
                }
            }

        // A profile line is [flags]Lpkg/Class;[->method...]; the class is everything up to the first ';'.
        fun classOf(line: String): String = line.trimStart('H', 'S', 'P').removePrefix("L").substringBefore(';')
        val byModule = libraryModules.associateWith { mutableListOf<String>() }
        val unplaced = mutableListOf<String>()
        profile.readLines().filter { it.isNotBlank() && !it.startsWith("#") }.forEach { line ->
            val owner = classesByModule.entries.firstOrNull { classOf(line) in it.value }?.key
            if (owner != null) byModule.getValue(owner) += line else unplaced += line
        }
        for ((path, lines) in byModule) {
            val out = project(path).file("src/androidMain/baselineProfiles/baseline-prof.txt")
            if (lines.isEmpty()) {
                out.delete()
                continue
            }
            out.parentFile.mkdirs()
            out.writeText(
                "# Baseline profile for ${if (path == ":") ":socket" else path}, generated by " +
                    ":benchmark-android-app:generateReleaseBaselineProfile and split by\n" +
                    "# :benchmark-android-app:distributeBaselineProfile. Do not edit by hand; regenerate.\n" +
                    lines.sorted().joinToString("\n") + "\n",
            )
            logger.lifecycle("$path: ${lines.size} rules -> ${out.relativeTo(rootDir)}")
        }
        if (unplaced.isNotEmpty()) {
            logger.lifecycle("${unplaced.size} rules are for classes outside these modules (kotlinx, buffer, androidx, the app), dropped")
        }
    }
}
