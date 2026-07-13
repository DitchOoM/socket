import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.library)
    alias(libs.plugins.ktlint)
    alias(libs.plugins.maven.publish)
    signing
}

val isMainBranchGithub = System.getenv("GITHUB_REF") == "refs/heads/main"

// The datagram trichotomy lives in buffer-flow under @ExperimentalDatagramApi (RFC Phase 0/1). Until
// it publishes to Maven Central as a real release, :socket-udp consumes the pinned local SNAPSHOT
// (published via `./gradlew :buffer-flow:publishToMavenLocal -Pversion=6.11.0-SNAPSHOT`). mavenLocal()
// is listed first so the snapshot wins; when 6.11.0 lands on Central this pin becomes a normal dep.
val bufferFlowVersion = "6.11.0-SNAPSHOT"

repositories {
    mavenLocal()
    google()
    mavenCentral()
}

// :socket-udp — a first-class UDP datagram substrate (RFC_UDP_MODULE.md).
//
// Deliberately a SEPARATE published module, not folded into root :socket: a consumer (../webrtc,
// or quiche's datapath in a later phase) can depend on UDP without dragging in TCP + TLS. It provides
// the buffer-flow datagram trichotomy (DatagramChannel / DatagramSource / DatagramSink + SocketAddress)
// on real sockets.
//
// Phase 2 ships JVM + Android only (NIO DatagramChannel). Native (Linux io_uring + Apple), Node dgram,
// and the QUIC cutover land in later phases; targets are added per-phase so each phase stays green.
kotlin {
    jvmToolchain(21)

    androidTarget {
        compilerOptions.jvmTarget.set(JvmTarget.JVM_17)
        publishLibraryVariants("release")
    }
    jvm {
        // Java 8 bytecode to match the base :socket module — the production surface is pure java.nio
        // (DatagramChannel/Selector), no post-8 java.* API on the shipped path (JDK-version-specific
        // socket options are resolved reflectively via DatagramChannel.supportedOptions()).
        compilerOptions.jvmTarget.set(JvmTarget.JVM_1_8)
    }

    applyDefaultHierarchyTemplate()
    sourceSets {
        commonMain.dependencies {
            api("com.ditchoom:buffer-flow:$bufferFlowVersion")
            api("com.ditchoom:buffer:$bufferFlowVersion")
            implementation(libs.kotlinx.coroutines.core)
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
            implementation(libs.kotlinx.coroutines.core)
            implementation(libs.kotlinx.coroutines.test)
        }

        // Shared JVM/Android implementation (NIO DatagramChannel), mirroring root :socket's manually
        // created commonJvmMain — the default hierarchy template has no such intermediate set.
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
    namespace = "com.ditchoom.socket.udp"

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
// Coordinates from socket-udp/gradle.properties (artifactName=socket-udp). Signing + Central upload
// engage only on a main-branch CI build supplying the in-memory PGP key; publishToMavenLocal needs none.

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
