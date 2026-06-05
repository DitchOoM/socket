import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.library)
    alias(libs.plugins.ksp)
    alias(libs.plugins.ktlint)
    alias(libs.plugins.maven.publish)
    signing
}

val isMacOS = org.jetbrains.kotlin.konan.target.HostManager.hostIsMac
val isLinux = org.jetbrains.kotlin.konan.target.HostManager.hostIsLinux
val isRunningOnGithub = System.getenv("GITHUB_REPOSITORY")?.isNotBlank() == true
val isMainBranchGithub = System.getenv("GITHUB_REF") == "refs/heads/main"

repositories {
    mavenLocal()
    google()
    mavenCentral()
}

// HTTP/3 layer on top of :socket-quic — the frame + QPACK codecs, the stream reader,
// and the Http3Connection client. Pure Kotlin (no platform actuals of its own); it
// rides on :socket-quic's multiplatform QuicScope/QuicByteStream.
//
// Targets mirror :socket-quic's host-gated matrix exactly: because commonMain
// `api`-depends on :socket-quic, both modules must expose the SAME target set per host
// or dependency resolution fails. So Apple targets are declared only on macOS and the
// Linux-native targets only on Linux — the same `if (isMacOS)` / `if (isLinux)` guards
// :socket-quic uses. KSP-for-main (for future declarative codecs) stays deferred until a
// @ProtocolMessage codec actually exists; the wiring to copy is on origin/feature/socket-http3.
kotlin {
    jvmToolchain(21)

    androidTarget {
        compilerOptions.jvmTarget.set(JvmTarget.JVM_17)
        publishLibraryVariants("release")
    }
    jvm()
    js {
        browser()
        nodejs()
    }
    wasmJs {
        // Pure-Kotlin codecs compile here; QUIC itself is unimplemented on wasmJs in
        // :socket-quic (gap-tested), so the live interop/loopback suites have no wasmJs
        // concrete subclass and don't run — only the deterministic codec tests do.
        browser()
        nodejs()
    }

    if (isMacOS) {
        // No cinterop of its own — Apple QUIC (Network.framework) is provided transitively
        // by :socket-quic. These targets just compile the pure-Kotlin H3 layer.
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
        // linuxX64/linuxArm64: codecs are pure common Kotlin, so the full common suite runs
        // here. The live interop GET also opens a real QUIC connection, which calls into
        // quiche — so the test binary must link libquiche.a (whole-archive), exactly like
        // :socket-quic's own native binaries do (BoringSSL is contributed transitively by the
        // base :socket: cinterop's staticLibraries). Without it the binary hits
        // `undefined symbol: quiche_config_new` at runtime.
        linuxX64 {
            val quicheLibDir = project(":socket-quic").projectDir.resolve("libs/quiche/linux-x64/lib")
            if (quicheLibDir.resolve("libquiche.a").exists()) {
                binaries.all {
                    linkerOpts(
                        "-L${quicheLibDir.absolutePath}",
                        "-Wl,-Bstatic",
                        "-Wl,--whole-archive",
                        "-lquiche",
                        "-Wl,--no-whole-archive",
                        "-Wl,-Bdynamic",
                        "-lpthread",
                        "-ldl",
                        "-lm",
                        "-lstdc++",
                        "--unresolved-symbols=ignore-in-object-files",
                        "--allow-multiple-definition",
                    )
                }
            }
        }
        linuxArm64 {
            val quicheLibDir = project(":socket-quic").projectDir.resolve("libs/quiche/linux-arm64/lib")
            if (quicheLibDir.resolve("libquiche.a").exists()) {
                binaries.all {
                    linkerOpts(
                        "-L${quicheLibDir.absolutePath}",
                        "-Wl,-Bstatic",
                        "-Wl,--whole-archive",
                        "-lquiche",
                        "-Wl,--no-whole-archive",
                        "-Wl,-Bdynamic",
                        "-lpthread",
                        "-ldl",
                        "-lm",
                        "-lstdc++",
                        "--unresolved-symbols=ignore-in-object-files",
                        "--allow-multiple-definition",
                    )
                }
            }
        }
    }

    applyDefaultHierarchyTemplate()
    sourceSets {
        commonMain.dependencies {
            api(project(":socket-quic"))
            api(libs.buffer)
            api(libs.buffer.flow)
            api(libs.buffer.codec)
            implementation(libs.kotlinx.coroutines.core)
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
            implementation(libs.kotlinx.coroutines.core)
            implementation(libs.kotlinx.coroutines.test)
        }
    }
}

android {
    compileSdk = 36
    // AGP 9 + legacy-DSL opt-out: reach the source set via the new DSL interface (the
    // `sourceSets[...]` Kotlin accessor casts to the removed old API). Mirrors :socket-quic.
    (this as com.android.build.api.dsl.LibraryExtension).sourceSets.getByName("main").apply {
        manifest.srcFile("src/androidMain/AndroidManifest.xml")
    }
    defaultConfig {
        minSdk = 24
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    namespace = "com.ditchoom.socket.http3"

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

// The live HTTP/3 interop test (Http3PublicEndpointInteropTests) needs :socket-quic's quiche
// native lib on the JVM test classpath — on JDK 21+ FFM loads `libquiche.so` as a classloader
// resource under META-INF/native/<platform>/. :socket-quic stages those natives onto its own
// jvmTest classpath but doesn't export them to dependents, so reuse the same staged dir here and
// depend on its staging task. (Scripted unit tests don't need this — only the gated interop GET,
// which otherwise skips with "no native lib could be loaded".)
afterEvaluate {
    val quicProject = project(":socket-quic")
    val stagedNatives = quicProject.layout.buildDirectory.dir("generated-native-resources/jvmMain")
    tasks.named<org.gradle.api.tasks.testing.Test>("jvmTest").configure {
        dependsOn(quicProject.tasks.named("stageQuicheNativeResources"))
        classpath += files(stagedNatives)
    }
}

// --- Publishing ---
// Coordinates come from socket-http3/gradle.properties (artifactName=socket-http3); the rest of
// the POM mirrors :socket-quic. Signing + Maven Central upload only engage on a main-branch CI
// build that supplies the in-memory PGP key; `publishToMavenLocal` works locally without keys.

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

// KSP-for-main: run the buffer-codec processor once on the common metadata compilation so the
// generated declarative codecs land in commonMain and every target compilation sees the same
// symbols (the KSP2 common-multiplatform shape; per-target runs would scatter sources). Mirrors
// buffer's own buffer-codec-test wiring.
dependencies {
    add("kspCommonMainMetadata", libs.buffer.codec.processor)
    add("kspCommonMainMetadata", libs.buffer.codec)
}

kotlin.sourceSets.named("commonMain") {
    kotlin.srcDir("build/generated/ksp/metadata/commonMain/kotlin")
}

tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompilationTask<*>>().configureEach {
    if (name != "kspCommonMainKotlinMetadata") {
        dependsOn("kspCommonMainKotlinMetadata")
    }
}

// The ktlint commonMain tasks read the KSP-generated srcDir (even though they filter it out of the
// lint pass), so Gradle reports an implicit-dependency validation error without this. Declare it
// explicitly (mirrors buffer's buffer-codec-test).
tasks
    .matching {
        it.name == "runKtlintCheckOverCommonMainSourceSet" ||
            it.name == "runKtlintFormatOverCommonMainSourceSet"
    }.configureEach {
        dependsOn("kspCommonMainKotlinMetadata")
    }
