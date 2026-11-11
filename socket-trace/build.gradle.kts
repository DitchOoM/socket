import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.library)
    alias(libs.plugins.ktlint)
    alias(libs.plugins.maven.publish)
    signing
}

// :socket-trace — the trace MODEL: TraceSink / TraceEvent (+ TracePath, TracePathStats, the migration
// enums) and the v1 trace line codec, carved out of :socket-testkit so that production modules
// (:socket-quic and everything above it) can expose trace capture WITHOUT inheriting the test and
// field-walk harness. Declarations keep their original package (com.ditchoom.socket.testkit.trace /
// .osnet), so source and JVM binary compatibility hold for existing users; :socket-testkit re-exports
// this module with `api`, so testkit users see no change.
//
// Depends only on root `:socket` for the connectivity vocabulary the trace carries (NetworkState /
// NetworkId / Liveness). No QUIC dependency.

// K/N targets: Apple on a macOS host, Linux on a Linux host, unless `-PappleTargets=false` /
// `-PlinuxTargets=false` (see the root build.gradle.kts).
val appleTargets =
    org.jetbrains.kotlin.konan.target.HostManager.hostIsMac &&
        providers.gradleProperty("appleTargets").map(String::toBooleanStrict).getOrElse(true)
val linuxTargets =
    org.jetbrains.kotlin.konan.target.HostManager.hostIsLinux &&
        providers.gradleProperty("linuxTargets").map(String::toBooleanStrict).getOrElse(true)
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

    if (appleTargets) {
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

    if (linuxTargets) {
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
            }
        }
    }
}

android {
    compileSdk = 37
    (this as com.android.build.api.dsl.LibraryExtension)
        .sourceSets
        .getByName("main")
        .manifest
        .srcFile("src/androidMain/AndroidManifest.xml")
    defaultConfig {
        minSdk = 21
    }
    namespace = "com.ditchoom.socket.trace"

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
// Coordinates from socket-trace/gradle.properties (artifactName=socket-trace). Signing + Central
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
