plugins {
    // No version: the Kotlin Gradle plugin jar is already on the build classpath (root applies the
    // multiplatform plugin, same jar) so the jvm plugin id must be requested version-less.
    id("org.jetbrains.kotlin.jvm")
    alias(libs.plugins.ktlint)
}

// :socket-quic-trace-tools — a JVM-only developer tool (NOT a published library, no maven-publish)
// that deobfuscates a captured QUIC v1 trace against an Android-release R8/ProGuard `mapping.txt`.
//
// Why a standalone module and not buildSrc: it depends on `com.android.tools:r8` for retrace, and
// R8/asm MUST NOT land on the Gradle plugin/buildSrc classpath — AGP already bundles its own R8
// there and the two would collide. Isolating the dep in this module's runtime classpath keeps it
// clear of the plugin classpath while still letting us reuse the typed `TraceEvent` model from
// :socket-quic (so deobfuscation rewrites the State.name / Error.type tokens on the typed event,
// not by blind string surgery).
//
// What it covers: any trace whose class-name tokens were obfuscated by ProGuard/R8 — an Android/ART
// release AND a JVM app that runs ProGuard or R8 over its own build. Both emit the same mapping.txt
// grammar (R8's is deliberately ProGuard-compatible) and R8's retrace reads either, so nothing here
// is Android/dex-specific. Kotlin/Native and JS have no ProGuard/R8 mapping, so their traces stay
// readable and pass through untouched.
//
// Why the TOOL is JVM-only: retrace is a JVM library and deobfuscation is an offline dev-machine
// step regardless of which target produced the trace. The capture side stays multiplatform (it
// already emits qualified FQNs on every platform).

repositories {
    google() // com.android.tools:r8
    mavenCentral()
}

kotlin {
    jvmToolchain(21)
}

dependencies {
    implementation(project(":socket-quic"))
    implementation(libs.r8)
    testImplementation(kotlin("test"))
}

tasks.test {
    useJUnitPlatform()
}

// A thin Gradle wrapper over the retrace CLI. Runs the tool with R8 isolated on THIS task's
// runtime classpath. Wire it up with -P properties, e.g.:
//   ./gradlew :socket-quic-trace-tools:retraceQuicTrace \
//       -PtraceIn=captured.trace -Pmapping=mapping.txt -PtraceOut=captured.deobf.trace
tasks.register<JavaExec>("retraceQuicTrace") {
    group = "trace"
    description = "Deobfuscate a captured QUIC v1 trace against an R8/ProGuard mapping.txt."
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("com.ditchoom.socket.quic.trace.tools.RetraceCliKt")
    // Forward the -P properties as CLI args when present (empty otherwise → the CLI prints usage).
    val traceIn = providers.gradleProperty("traceIn")
    val mapping = providers.gradleProperty("mapping")
    val traceOut = providers.gradleProperty("traceOut")
    argumentProviders.add {
        buildList {
            if (traceIn.isPresent) add(traceIn.get())
            if (mapping.isPresent) add(mapping.get())
            if (traceOut.isPresent) add(traceOut.get())
        }
    }
}
