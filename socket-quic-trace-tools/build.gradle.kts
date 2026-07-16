plugins {
    // No version: the Kotlin Gradle plugin jar is already on the build classpath (root applies the
    // multiplatform plugin, same jar) so the jvm plugin id must be requested version-less.
    id("org.jetbrains.kotlin.jvm")
    alias(libs.plugins.ktlint)
    alias(libs.plugins.maven.publish)
    signing
}

// :socket-quic-trace-tools — a JVM-only developer tool that deobfuscates a captured QUIC v1 trace
// against a ProGuard/R8 `mapping.txt`. Published so a consumer can retrace their OWN app's traces:
// they hold the mapping.txt, so the tool belongs in their hands (a JVM server/CLI dependency), not
// just ours.
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

val isMainBranchGithub = System.getenv("GITHUB_REF") == "refs/heads/main"

repositories {
    google() // com.android.tools:r8
    mavenCentral()
}

kotlin {
    jvmToolchain(21)
}

dependencies {
    // `api`: TraceDeobfuscator's surface exposes TraceEvent, so consumers need it transitively.
    api(project(":socket-quic"))
    // `implementation`: R8 is fully encapsulated in R8ClassResolver (never in a public signature).
    // It is still runtime-transitive, so a consumer must add `google()` to resolve it.
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

// --- Publishing ---
// Coordinates from socket-quic-trace-tools/gradle.properties (artifactName=socket-quic-trace-tools).
// Signing + Central upload engage only on a main-branch CI build supplying the in-memory PGP key;
// publishToMavenLocal needs none. Mirrors the sibling modules' publish setup.

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
