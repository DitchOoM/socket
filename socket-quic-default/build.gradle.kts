import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.library)
    alias(libs.plugins.ktlint)
    alias(libs.plugins.maven.publish)
    signing
}

// :socket-quic-default is the multiplatform default-engine bundle (v6 Phase 2b.4). It owns the
// public lifecycle entrypoints (withQuicConnection / withQuicServer / withQuicMux) and the
// `expect val defaultQuicEngine` that selects a backend per platform:
//   jvm / android / linux → :socket-quic-quiche (QuicheEngine)
//   apple                 → :socket-quic-nw     (NetworkEngine)
//   js / wasmJs           → :socket-quic        (UnsupportedQuicEngine)
// The backend modules expose their engine as public SPI; this module wires them together so a
// consumer can `implementation(":socket-quic-default")` and call withQuic* on any target.

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
            api(project(":socket-quic"))
            implementation(libs.kotlinx.coroutines.core)
        }
        commonTest {
            dependencies {
                implementation(kotlin("test"))
                implementation(libs.kotlinx.coroutines.core)
                implementation(libs.kotlinx.coroutines.test)
            }
        }
        // Shared jvm+android source set: one defaultQuicEngine = QuicheEngine actual + the
        // :socket-quic-quiche backend dependency for both JVM-family targets.
        val commonJvmMain by creating {
            dependsOn(commonMain.get())
            dependencies {
                api(project(":socket-quic-quiche"))
            }
        }
        jvmMain.get().dependsOn(commonJvmMain)
        androidMain.get().dependsOn(commonJvmMain)
        androidMain.dependencies {
            // buffer-android references kotlinx.atomicfu.AtomicFU at runtime without declaring it;
            // provide it so Android consumers of the QUIC StateFlow path don't NoClassDefFoundError.
            implementation("org.jetbrains.kotlinx:atomicfu:0.33.0")
        }
        if (isLinux) {
            val linuxMain by getting {
                dependencies {
                    api(project(":socket-quic-quiche"))
                }
            }
        }
        if (isMacOS) {
            val appleMain by getting {
                dependencies {
                    api(project(":socket-quic-nw"))
                }
            }
        }
    }
}

tasks.withType<Test> {
    jvmArgs("--enable-native-access=ALL-UNNAMED")
    jvmArgs("--add-opens", "java.base/java.nio=ALL-UNNAMED")
}

tasks.withType<org.gradle.api.tasks.testing.AbstractTestTask>().configureEach {
    testLogging {
        events("failed", "skipped")
        showExceptions = true
        showCauses = true
        showStackTraces = true
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }
}

android {
    compileSdk = 36
    defaultConfig {
        minSdk = 24
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    namespace = "com.ditchoom.socket.quic.defaults"

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

// --- Publishing ---

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
