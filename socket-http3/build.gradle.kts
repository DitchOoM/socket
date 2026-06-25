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
            api(project(":socket-quic"))
            // v6 Phase 2b.5: the withQuicConnection/withQuicServer entrypoints that WithHttp3* call
            // live in :socket-quic-default (which selects the per-platform engine: quiche on
            // jvm/android/linux, Network.framework on Apple, Unsupported on js/wasm). It transitively
            // contributes the quiche backend (+ root :socket BoringSSL) on jvm/linux. implementation,
            // not api: withQuic* are called internally; no :socket-quic-default type is in http3's API.
            implementation(project(":socket-quic-default"))
            api(libs.buffer)
            api(libs.buffer.flow)
            api(libs.buffer.codec)
            implementation(libs.kotlinx.coroutines.core)
        }
        commonTest {
            // The HTTP/3 loopback suite + its in-process server and hand-rolled frame codec live in a
            // standalone srcDir (not plain src/commonTest) so the on-device androidInstrumentedTest set —
            // which does NOT dependsOn commonTest — can compile the SAME abstract suite without dragging in
            // the rest of commonTest (the fuzzers, codec unit tests, gated interop). Mirrors the
            // browserInterop pattern in :socket-webtransport. The other commonTest files that use
            // HandwrittenHttp3FrameCodec still see it here (same compilation).
            kotlin.srcDir("src/loopbackShared/kotlin")
            dependencies {
                implementation(kotlin("test"))
                implementation(libs.kotlinx.coroutines.core)
                implementation(libs.kotlinx.coroutines.test)
            }
        }
        // Android instrumented (on-device) HTTP/3 loopback conformance — the 27-test parity gap vs
        // JVM/Linux/Apple. Android runs the same quiche backing as the JVM; the quiche JNI `.so` merges
        // into the test APK transitively from :socket-quic-quiche's androidMain/jniLibs (via
        // :socket-quic-default). The shared suite compiles in through the srcDir; coroutines-test + the
        // AndroidJUnit runner are explicit because androidInstrumentedTest does NOT dependsOn commonTest.
        val androidInstrumentedTest by getting {
            kotlin.srcDir("src/loopbackShared/kotlin")
            dependencies {
                implementation(kotlin("test"))
                implementation(libs.kotlinx.coroutines.core)
                implementation(libs.kotlinx.coroutines.test)
                implementation("androidx.test:runner:1.7.0")
                implementation("androidx.test.ext:junit:1.3.0")
            }
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
    val quicProject = project(":socket-quic-quiche")
    val stagedNatives = quicProject.layout.buildDirectory.dir("generated-native-resources/jvmMain")
    tasks.named<org.gradle.api.tasks.testing.Test>("jvmTest").configure {
        dependsOn(quicProject.tasks.named("stageQuicheNativeResources"))
        classpath += files(stagedNatives)
    }
}

// --- Apple QUIC server identity (PKCS#12) for the Http3 loopback suite ---
// On Apple the loopback server is Network.framework's QUIC listener (provided transitively through
// socket-quic-default → :socket-quic-nw), and NW can only build its `sec_identity_t` from a PKCS#12
// blob (loose PEM cert+key won't do — see QuicTlsConfig.pkcs12Path). The committed testcerts/cert.{crt,key}
// feed an openssl export into testcerts/cert.p12 (passphrase `testpass`, the same convention :socket-quic-nw
// and :socket-webtransport use). The p12 is gitignored and regenerated on demand; the Apple K/N test tasks
// depend on it. JVM/Linux ignore it (their quiche server reads the PEM cert+key directly).
if (isMacOS) {
    val generateHttp3TestP12 =
        tasks.register("generateHttp3TestP12") {
            group = "verification"
            description = "Generate testcerts/cert.p12 from the committed PEM cert+key for the Apple Http3 loopback tests."
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
        .configureEach { dependsOn(generateHttp3TestP12) }
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

// Publishing tasks (`sourcesJar`, per-target `<target>SourcesJar`, and the Dokka-backed
// `javadocJar`) package commonMain sources, which now include the KSP-generated srcDir — so they
// read `kspCommonMainKotlinMetadata`'s output and Gradle reports the same implicit-dependency
// validation error at publish time. Match by name (type-agnostic: the KMP source Jars are the base
// `jvm.tasks.Jar`, which `withType<bundling.Jar>` misses) like the ktlint wiring above.
// (buffer-codec-test doesn't need this: it isn't a published module, so it has no source Jars.)
tasks
    .matching {
        it.name == "sourcesJar" ||
            it.name.endsWith("SourcesJar") ||
            it.name == "javadocJar" ||
            it.name.endsWith("JavadocJar")
    }.configureEach {
        dependsOn("kspCommonMainKotlinMetadata")
    }

// --- Coverage-guided fuzzing (Jazzer) ----------------------------------------
// `http3CodecFuzz` drives Http3CodecFuzzer (src/jvmTest) — the hand-rolled HTTP/3 + QPACK *decoder*,
// the module's real RFC-9114/9204 risk surface — under Jazzer/libFuzzer. The code under test is pure
// Kotlin, so unlike :socket-quic-quiche's native header-info fuzzer this gets REAL edge coverage of the
// parser, not just crash signals. Jazzer is runtime-only: the target uses the `byte[]` entry-point form
// so nothing in jvmTest compiles against Jazzer; the driver comes from the dedicated `jazzer`
// configuration. The committed seed corpus (fuzz/corpus/http3-codec) starts every run warm with the
// Phase-0 crafted vectors; new inputs and crash repros go under build/ (gitignored).
//
// NOTE: this task is staged-but-dormant in CI until buffer 5.13.2 publishes to Central (the whole
// redesign branch is buffer-blocked); run it locally with -PquicFuzzSeconds=<n> (default 60).
val http3JazzerConfig =
    configurations.create("jazzer") {
        isCanBeConsumed = false
        isCanBeResolved = true
    }
dependencies { add("jazzer", libs.jazzer) }

val http3FuzzCorpusDir = projectDir.resolve("fuzz/corpus/http3-codec")
val http3FuzzWorkDir = layout.buildDirectory.dir("fuzz/http3-codec")

tasks.register<JavaExec>("http3CodecFuzz") {
    group = "verification"
    description = "Coverage-guided Jazzer fuzzing of the HTTP/3 + QPACK decoder (Http3CodecFuzzer). " +
        "Configure runtime with -PquicFuzzSeconds=<n> (default 60)."
    dependsOn("jvmTestClasses")

    // Build the classpath from the jvm test compilation (compiled main+test output + all runtime
    // deps) WITHOUT referencing the jvmTest *task*, so launching the fuzzer doesn't first run the
    // whole suite. The Jazzer driver itself comes from the dedicated `jazzer` configuration.
    val jvmTestCompilation = kotlin.jvm().compilations["test"]
    classpath =
        files(
            jvmTestCompilation.output.allOutputs,
            jvmTestCompilation.runtimeDependencyFiles,
        ) + http3JazzerConfig

    mainClass.set("com.code_intelligence.jazzer.Jazzer")

    val maxSeconds = providers.gradleProperty("quicFuzzSeconds").orElse("60")
    val corpusDir = http3FuzzCorpusDir
    val workDir = http3FuzzWorkDir

    doFirst {
        corpusDir.mkdirs()
        workDir
            .get()
            .asFile
            .resolve("corpus")
            .mkdirs()
    }

    // libFuzzer writes crash-*/oom-*/timeout-* repro files to artifact_prefix, and new interesting
    // inputs only to the FIRST positional corpus dir. We make that a gitignored build/ dir so the
    // committed seed corpus (passed second) stays pristine across local runs.
    argumentProviders.add {
        val work = workDir.get().asFile
        listOf(
            "--target_class=com.ditchoom.socket.http3.fuzz.Http3CodecFuzzer",
            "--instrumentation_includes=com.ditchoom.socket.http3.**",
            "-print_final_stats=1",
            "-artifact_prefix=${work.absolutePath}/",
            "-max_total_time=${maxSeconds.get()}",
            work.resolve("corpus").absolutePath,
            corpusDir.absolutePath,
        )
    }
}

// `http3RoundTripFuzz` is the encoder-side twin: it drives Http3RoundTripFuzzer (src/jvmTest), which
// feeds the same Jazzer byte[] into the shared Http3FuzzGenerators to build a STRUCTURALLY VALID frame /
// QPACK header list, then asserts `encode → decode` returns it unchanged. Where http3CodecFuzz hunts
// untyped decoder crashes, this hunts wire-format bijection breaks (encode/decode disagreement, lossy
// round-trips). Same dormancy + corpus discipline as above; separate committed seed corpus.
val http3RoundTripCorpusDir = projectDir.resolve("fuzz/corpus/http3-roundtrip")
val http3RoundTripWorkDir = layout.buildDirectory.dir("fuzz/http3-roundtrip")

tasks.register<JavaExec>("http3RoundTripFuzz") {
    group = "verification"
    description = "Coverage-guided Jazzer fuzzing of the HTTP/3 + QPACK encode→decode round-trip " +
        "(Http3RoundTripFuzzer). Configure runtime with -PquicFuzzSeconds=<n> (default 60)."
    dependsOn("jvmTestClasses")

    val jvmTestCompilation = kotlin.jvm().compilations["test"]
    classpath =
        files(
            jvmTestCompilation.output.allOutputs,
            jvmTestCompilation.runtimeDependencyFiles,
        ) + http3JazzerConfig

    mainClass.set("com.code_intelligence.jazzer.Jazzer")

    val maxSeconds = providers.gradleProperty("quicFuzzSeconds").orElse("60")
    val corpusDir = http3RoundTripCorpusDir
    val workDir = http3RoundTripWorkDir

    doFirst {
        corpusDir.mkdirs()
        workDir
            .get()
            .asFile
            .resolve("corpus")
            .mkdirs()
    }

    argumentProviders.add {
        val work = workDir.get().asFile
        listOf(
            "--target_class=com.ditchoom.socket.http3.fuzz.Http3RoundTripFuzzer",
            "--instrumentation_includes=com.ditchoom.socket.http3.**",
            "-print_final_stats=1",
            "-artifact_prefix=${work.absolutePath}/",
            "-max_total_time=${maxSeconds.get()}",
            work.resolve("corpus").absolutePath,
            corpusDir.absolutePath,
        )
    }
}

// --- h3spec conformance server (Phase 2 STEP 2) ------------------------------------------------
// `h3specServer` runs the PRODUCTION withHttp3Server (H3SpecServerMain, src/jvmTest) so the external
// h3spec client (github.com/kazu-yamamoto/h3spec) can probe it and externally prove the Phase-0/STEP-1
// typed Http3Violation error codes — the inverse of the aioquic docker-interop, where WE are the client.
// The QUIC transport needs :socket-quic-quiche's native lib on the classpath, so this stages it exactly
// like the jvmTest task does (the afterEvaluate block above). Driven by socket-http3/h3spec/run-h3spec.sh.
//
// NOTE: this is staged-but-dormant — it is wired but not run by CI (the build-linux `run-h3spec` input
// defaults false, and the whole redesign branch is buffer-blocked until buffer 5.13.2 publishes to
// Central). Run it locally via socket-http3/h3spec/run-h3spec.sh once Docker + a built quiche native exist.
afterEvaluate {
    val quicProject = project(":socket-quic-quiche")
    val stagedNatives = quicProject.layout.buildDirectory.dir("generated-native-resources/jvmMain")
    tasks.register<JavaExec>("h3specServer") {
        group = "verification"
        description = "Run the production withHttp3Server (H3SpecServerMain) for the external h3spec " +
            "conformance client. Configure via env H3SPEC_PORT/H3SPEC_CERT/H3SPEC_KEY/H3SPEC_QPACK_CAPACITY."
        dependsOn("jvmTestClasses")
        dependsOn(quicProject.tasks.named("stageQuicheNativeResources"))

        // Reuse the jvm test compilation's output + runtime deps (which include withHttp3Server and the
        // QuicTlsConfig types), plus the staged quiche native resources the FFM backend loads at runtime —
        // mirroring how the jvmTest task is wired above. Built WITHOUT referencing the jvmTest *task*, so
        // launching the server doesn't first run the whole suite.
        val jvmTestCompilation = kotlin.jvm().compilations["test"]
        classpath =
            files(
                jvmTestCompilation.output.allOutputs,
                jvmTestCompilation.runtimeDependencyFiles,
                stagedNatives,
            )
        mainClass.set("com.ditchoom.socket.http3.h3spec.H3SpecServerMain")
        // Default cert paths (testcerts/cert.{crt,key}) resolve relative to the module dir.
        workingDir = projectDir
    }
}
