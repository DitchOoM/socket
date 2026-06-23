import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.library)
    alias(libs.plugins.ktlint)
    alias(libs.plugins.maven.publish)
    signing
}

val isMacOS = org.jetbrains.kotlin.konan.target.HostManager.hostIsMac
val isLinux = org.jetbrains.kotlin.konan.target.HostManager.hostIsLinux
val isMainBranchGithub = System.getenv("GITHUB_REF") == "refs/heads/main"

repositories {
    mavenLocal()
    google()
    mavenCentral()
}

// WebTransport (RFC 9220 + draft-ietf-webtrans-http3) as a top-level transport (v6 Phase 4).
//
// ONE neutral API, two honest backings (MAJOR_API_REDESIGN.md §5/§9):
//   - jvm / android / native  → Extended CONNECT over :socket-http3 (real QUIC on the classpath)
//   - browser (js / wasmJs)    → the platform's own `WebTransport` object; the browser does HTTP/3
//                                internally, so the browser source set pulls NEITHER :socket-http3
//                                NOR :socket-quic. "Honesty by construction": raw QUIC is simply not
//                                on the browser classpath, so there are no throwing stubs.
//
// The split is enforced by the source-set graph: the `http3Main` intermediate source set
// (jvm + android + native) is the only place that depends on :socket-http3; `browserMain`
// (js + wasmJs) depends on neither. `webTransportSupport()` / `networkCapabilities()` are
// `expect` in commonMain with one `actual` in each of those two shared source sets.
//
// Target matrix mirrors :socket-http3 exactly (host-gated): Apple targets only on macOS,
// Linux-native only on Linux — both modules must expose the same per-host target set or the
// :socket-http3 dependency fails to resolve.
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
        // The linuxX64Test binary stands up a real QUIC server + client (via :socket-testsuite's
        // WebTransportTestSuite → withHttp3Server / connectMultiplexed), which calls into quiche, so the
        // test binary must whole-archive libquiche.a — exactly like :socket-http3's and :socket-quic's
        // native binaries. Without it the binary hits `undefined symbol: quiche_config_new` at runtime.
        linuxX64 {
            val quicheLibDir = project(":socket-quic-quiche").projectDir.resolve("libs/quiche/linux-x64/lib")
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
            val quicheLibDir = project(":socket-quic-quiche").projectDir.resolve("libs/quiche/linux-arm64/lib")
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
            // The neutral API speaks only buffer types (the Phase-3a byte trichotomy) + coroutines.
            // No :socket-http3 / :socket-quic here — those would leak QUIC onto the browser classpath.
            api(libs.buffer)
            api(libs.buffer.flow)
            implementation(libs.kotlinx.coroutines.core)
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
            implementation(libs.kotlinx.coroutines.core)
            implementation(libs.kotlinx.coroutines.test)
        }

        // --- The split (see the module comment above) ---
        // `http3Main`: jvm + android + native. The WebTransport-over-HTTP/3 backing; the only
        // source set that sees :socket-http3. `actual fun webTransportSupport()` here is a
        // `Multiplexed` (many sessions over one held H3 connection — the native-only power).
        val http3Main by creating {
            dependsOn(commonMain.get())
            dependencies {
                // implementation, not api: socket-http3 types stay an implementation detail behind
                // the neutral WebTransportSession interface — they must not surface in the public API.
                implementation(project(":socket-http3"))
                implementation(libs.kotlinx.coroutines.core)
            }
        }
        // `browserMain`: js + wasmJs. Wraps the native `WebTransport` object directly; depends on
        // neither :socket-http3 nor :socket-quic. `actual fun webTransportSupport()` here is a plain
        // (non-Multiplexed) WebTransportSupport — the browser gives one session per `new WebTransport`.
        val browserMain by creating {
            dependsOn(commonMain.get())
        }

        jvmMain.get().dependsOn(http3Main)
        androidMain.get().dependsOn(http3Main)
        // nativeMain is the default-template parent of appleMain/linuxMain; routing it through
        // http3Main gives every native target the socket-http3 backing in one edge.
        named("nativeMain").get().dependsOn(http3Main)

        jsMain.get().dependsOn(browserMain)
        wasmJsMain.get().dependsOn(browserMain)

        // --- WebTransport conformance (the :socket-testsuite WebTransportTestSuite) ---
        // Attached ONLY to the http3-backed test source sets (jvm + native), never commonTest: the
        // suite drives the native Multiplexed provider + the public withHttp3Server, neither of which
        // exists on browser — and :socket-testsuite has no js/wasmJs targets, so a commonTest edge
        // would break the browser test compilations. The Jvm*/Linux* subclasses live in those source
        // sets and supply testTlsConfig + wrapTestBody.
        jvmTest.dependencies {
            implementation(project(":socket-testsuite"))
        }
        if (isLinux) {
            named("linuxX64Test") {
                dependencies {
                    implementation(project(":socket-testsuite"))
                }
            }
        }
        if (isMacOS) {
            // `appleTest` (default-template parent of macos/ios/tvos/watchos Test) gets the suite once;
            // the Apple QUIC server backing comes transitively through nativeMain → socket-http3 →
            // socket-quic-default → socket-quic-nw (Network.framework), so no cinterop/linker opts here.
            named("appleTest") {
                dependencies {
                    implementation(project(":socket-testsuite"))
                }
            }
        }
        // Android instrumented (on-device) WebTransport conformance. Android runs the same quiche backing
        // as JVM; the quiche JNI `.so` is merged into the test APK transitively from :socket-quic-quiche's
        // androidMain/jniLibs. androidInstrumentedTest does NOT dependsOn commonTest, so the suite +
        // coroutines-test must be pulled in explicitly here (mirrors :socket-quic-quiche).
        val androidInstrumentedTest by getting {
            dependencies {
                implementation(kotlin("test"))
                implementation(libs.kotlinx.coroutines.test)
                implementation("androidx.test:runner:1.7.0")
                implementation("androidx.test.ext:junit:1.3.0")
                implementation(project(":socket-testsuite"))
            }
        }
    }
}

android {
    compileSdk = 36
    (this as com.android.build.api.dsl.LibraryExtension).sourceSets.getByName("main").apply {
        manifest.srcFile("src/androidMain/AndroidManifest.xml")
    }
    defaultConfig {
        minSdk = 24
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    namespace = "com.ditchoom.socket.webtransport"

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

// The WebTransport conformance suite (:socket-testsuite's WebTransportTestSuite) stands up a real
// QUIC server + client, which on JDK 21+ loads quiche via FFM as a classloader resource under
// META-INF/native/<platform>/. :socket-quic-quiche stages those natives onto its OWN jvmTest classpath
// but doesn't export them to dependents (they're not in the base jvmJar), so reuse the same staged dir
// here and depend on its staging task — exactly as :socket-http3 does for its loopback/interop tests.
// Without it the tests fail with "no native lib could be loaded" instead of running.
afterEvaluate {
    val quicProject = project(":socket-quic-quiche")
    val stagedNatives = quicProject.layout.buildDirectory.dir("generated-native-resources/jvmMain")
    tasks.named<org.gradle.api.tasks.testing.Test>("jvmTest").configure {
        dependsOn(quicProject.tasks.named("stageQuicheNativeResources"))
        classpath += files(stagedNatives)
    }
}

// --- Apple QUIC server identity (PKCS#12) for the WebTransport conformance suite ---
// Network.framework's QUIC listener needs a `sec_identity_t` it can only build from a PKCS#12 blob
// (loose PEM cert+key won't do — see QuicTlsConfig.pkcs12Path). The committed testcerts/cert.{crt,key}
// (the long-lived quiche example identity, CN=quic.tech) feed an openssl export into testcerts/cert.p12
// (passphrase `testpass`, the same convention :socket-quic-nw uses). The p12 is gitignored and
// regenerated on demand; the Apple K/N test tasks depend on it. JVM/Linux ignore it (PEM-only servers).
if (isMacOS) {
    val generateWebTransportTestP12 =
        tasks.register("generateWebTransportTestP12") {
            group = "verification"
            description = "Generate testcerts/cert.p12 from the committed PEM cert+key for the Apple WebTransport tests."
            val certDir = projectDir.resolve("testcerts")
            val crt = certDir.resolve("cert.crt")
            val key = certDir.resolve("cert.key")
            inputs.files(crt, key)
            outputs.file(certDir.resolve("cert.p12"))
            doLast {
                val process =
                    ProcessBuilder(
                        "openssl",
                        "pkcs12",
                        "-export",
                        "-out",
                        certDir.resolve("cert.p12").absolutePath,
                        "-inkey",
                        key.absolutePath,
                        "-in",
                        crt.absolutePath,
                        "-passout",
                        "pass:testpass",
                    ).redirectErrorStream(true).start()
                val output = process.inputStream.bufferedReader().readText()
                if (process.waitFor() != 0) {
                    throw GradleException("openssl pkcs12 export failed for cert.p12 (rc != 0):\n$output")
                }
            }
        }

    // Apple K/N test tasks read testcerts/cert.p12 at runtime.
    tasks
        .matching { it.name.matches(Regex("(macos|ios|tvos|watchos)\\w*Test")) }
        .configureEach { dependsOn(generateWebTransportTestP12) }
}

// --- Publishing ---
// Coordinates come from socket-webtransport/gradle.properties (artifactName=socket-webtransport);
// the POM mirrors :socket-http3. Signing + Maven Central upload only engage on a main-branch CI
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
