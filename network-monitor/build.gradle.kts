import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.library)
    alias(libs.plugins.ktlint)
    alias(libs.plugins.maven.publish)
    signing
}

val isMainBranchGithub = System.getenv("GITHUB_REF") == "refs/heads/main"
val isMacOS = org.jetbrains.kotlin.konan.target.HostManager.hostIsMac
val isLinux = org.jetbrains.kotlin.konan.target.HostManager.hostIsLinux

repositories {
    google()
    mavenCentral()
}

// com.ditchoom:network-monitor — the portable NetworkMonitor / NetworkId contract plus the JVM
// (FFM routing socket), Android (ConnectivityManager) and JS monitors, extracted from root :socket so
// consumers (../webrtc ICE/WebRTC, QUIC auto-migration) can depend on network-awareness without
// TCP + TLS.
//
// Deliberately CINTEROP-FREE (cinterop-issues/06): the native Linux (netlink) and Apple (NWPathMonitor)
// monitors stay in :socket, reusing :socket's own LinuxSockets / NWHelpers cinterop. Adding a second
// cinterop-bearing project dependency to :socket evicts :socket's own commonized LinuxSockets klib
// (memset/errno unresolved on linuxX64). Keeping this module cinterop-free never perturbs the
// commonizer. `NetworkMonitor.default()` / `.processDefault()` therefore live in :socket (extensions on
// the companion), where the native monitors can be constructed directly; this module keeps only the
// contract, the process-default override seam (installProcessDefault), and the portable monitors.
kotlin {
    // Match the target set of :socket exactly — :socket depends on this module and compiles every one
    // of these targets, so the dependency must publish an artifact for each.
    jvmToolchain(21)

    androidTarget {
        publishLibraryVariants("release")
        compilerOptions.jvmTarget.set(JvmTarget.JVM_17)
    }
    jvm {
        compilerOptions.jvmTarget.set(JvmTarget.JVM_1_8)
        // Java 21 compilation for the FFM-based reactive network monitor (routing sockets).
        // Shipped under META-INF/versions/21 (multi-release JAR) so it only loads on JDK 21+;
        // the Java-8 base compilation keeps the polling fallback (see JvmNetworkMonitorSelector.kt).
        compilations.create("java21") {
            compilerOptions.configure {
                jvmTarget.set(JvmTarget.JVM_21)
            }
            defaultSourceSet {
                kotlin.srcDir("src/jvm21Main/kotlin")
                dependencies {
                    // Reach the base compilation's output for NetworkMonitor, NetworkAvailability,
                    // PollingNetworkMonitor (Windows fallback), etc.
                    implementation(
                        this@jvm
                            .compilations
                            .getByName("main")
                            .output.classesDirs,
                    )
                    implementation(libs.kotlinx.coroutines.core)
                }
            }
        }
    }
    js {
        browser()
        nodejs {
            testTask {
                useMocha {
                    timeout = "30s"
                }
            }
        }
    }
    wasmJs {
        browser()
        nodejs()
    }

    // Apple targets — pure-Kotlin contract only (no NWHelpers cinterop; the native AppleNetworkMonitor
    // stays in :socket). Registered on macOS hosts so :socket's Apple compilations resolve this dep.
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

    // Linux targets — pure-Kotlin contract only (no LinuxSockets cinterop; the native
    // LinuxNetworkMonitor stays in :socket). ARM64 is cross-registered on x64 for source-set resolution.
    if (isLinux) {
        linuxX64()
        linuxArm64()
    }

    applyDefaultHierarchyTemplate()
    sourceSets {
        commonMain.dependencies {
            // StateFlow is on the public NetworkMonitor surface → api.
            api(libs.kotlinx.coroutines.core)
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
            implementation(libs.kotlinx.coroutines.core)
            implementation(libs.kotlinx.coroutines.test)
        }
        jsMain.dependencies {
            implementation(libs.kotlin.js)
        }
        jsTest.dependencies {
            implementation(kotlin("test-js"))
        }

        // Shared JVM/Android implementation (PollingNetworkMonitor, JvmNetworkId), mirroring root
        // :socket's manually created commonJvmMain — the default hierarchy template has no such set.
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

// Put the java21 (FFM network-monitor) compilation output on the JVM test classpath so
// NetlinkNetworkMonitorTest can reference NetlinkNetworkMonitor at both compile and runtime.
// compileDependencyFiles lets the test resolve the class; the afterEvaluate below appends it to the
// actual jvmTest runtime classpath (appended — the base polling selector still wins for
// NetworkMonitor.default() in a normal run; the FFM classes are exercised via direct instantiation).
kotlin.jvm().compilations.getByName("test") {
    compileDependencyFiles +=
        kotlin
            .jvm()
            .compilations["java21"]
            .output.classesDirs
}
afterEvaluate {
    tasks.named<Test>("jvmTest") {
        dependsOn("compileJava21KotlinJvm")
        classpath +=
            kotlin
                .jvm()
                .compilations["java21"]
                .output.classesDirs
    }
}

// Multi-release JAR: ship the JDK 21 FFM network-monitor bindings under META-INF/versions/21.
// Wrapped in afterEvaluate because jvmJar is created by the KMP plugin.
afterEvaluate {
    tasks.named<Jar>("jvmJar") {
        manifest {
            attributes("Multi-Release" to "true")
        }
        into("META-INF/versions/21") {
            from(
                kotlin
                    .jvm()
                    .compilations["java21"]
                    .output.allOutputs,
            )
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
    namespace = "com.ditchoom.networkmonitor"

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
// Coordinates from network-monitor/gradle.properties (artifactName=network-monitor). Signing + Central
// upload engage only on a main-branch CI build supplying the in-memory PGP key; publishToMavenLocal
// needs none.

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
