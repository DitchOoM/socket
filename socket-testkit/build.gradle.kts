import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.library)
    alias(libs.plugins.ktlint)
    alias(libs.plugins.maven.publish)
    signing
}

// :socket-testkit — the transport-NEUTRAL test/replay core (RFC_UNIFIED_NETWORK_TEST_HARNESS.md §3).
//
// A published, pure-Kotlin module with NO QUIC dependency, so any KMP consumer (or any of the three
// transport stacks — TCP `:socket`, UDP `:socket-udp`, QUIC `:socket-quic*`) can reuse the neutral
// recorder/replay machinery without dragging quiche in. P0 lands the trace model here (TraceSink /
// TraceEvent / the v1 line codec), de-namespaced under `com.ditchoom.socket.testkit.trace`; the
// quiche-backed `QuicTraceRecorder` (one module downstream) keeps projecting quiche's PathKey /
// QuicPathStats onto these neutral types. Later phases add FaultSchedule + the neutral Tier-A pipe.
//
// Depends only on root `:socket` for the connectivity vocabulary the trace already carries
// (NetworkAvailability / NetworkId / Liveness); those move to `:network-monitor` in P5, at which
// point this dependency narrows.

val isMacOS = org.jetbrains.kotlin.konan.target.HostManager.hostIsMac
val isLinux = org.jetbrains.kotlin.konan.target.HostManager.hostIsLinux
val isMainBranchGithub = System.getenv("GITHUB_REF") == "refs/heads/main"

repositories {
    google()
    mavenCentral()
}

kotlin {
    jvmToolchain(21)

    androidTarget {
        compilerOptions.jvmTarget.set(JvmTarget.JVM_17)
        publishLibraryVariants("release")
    }
    jvm {
        compilerOptions.jvmTarget.set(JvmTarget.JVM_1_8)
    }
    js {
        browser()
        nodejs()
    }
    wasmJs {
        browser()
        nodejs()
    }

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

    if (isLinux) {
        linuxX64()
        linuxArm64()
    }

    applyDefaultHierarchyTemplate()
    sourceSets {
        commonMain.dependencies {
            // api: TraceEvent's replayable variants carry root's connectivity types (NetworkId /
            // NetworkAvailability / Liveness) in their public surface, so consumers need them
            // transitively. No QUIC dependency — that is the whole point of the split.
            api(project(":"))
        }
        commonTest {
            dependencies {
                implementation(kotlin("test"))
                implementation(libs.kotlinx.coroutines.test)
            }
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
    namespace = "com.ditchoom.socket.testkit"

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
// Coordinates from socket-testkit/gradle.properties (artifactName=socket-testkit). Signing + Central
// upload engage only on a main-branch CI build supplying the in-memory PGP key; publishToMavenLocal
// needs none. Mirrors the sibling modules' publish setup.

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
