import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.security.KeyStore
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.util.Base64
import java.util.Properties

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
    jvm {
        // Java 8 bytecode (v52) to match the base :socket / :socket-quic-quiche modules. The WebTransport
        // layer is pure-Kotlin commonMain + http3Main (the latter also compiles to native), so there is
        // no java.* API; targeting 21 (the toolchain default) needlessly threw UnsupportedClassVersionError
        // for JDK<21 consumers. The android variant keeps JVM_17 above (dexed, unaffected).
        compilerOptions.jvmTarget.set(JvmTarget.JVM_1_8)
        // -Xjdk-release=8 gates the API surface to Java 8 (the toolchain is 21), turning a future post-8
        // java.* call into a compile error rather than a runtime NoSuchMethodError on JDK 8/11. Scoped to
        // the SHIPPED main compilation; jvmTest runs on JDK 21 and is not published. See :socket-http3.
        compilations.named("main").configure {
            compileTaskProvider.configure { compilerOptions.freeCompilerArgs.add("-Xjdk-release=8") }
        }
    }
    js {
        browser {
            // The non-gated surface smoke (src/browserSmoke) is node-only — exclude it from the browser
            // task so jsBrowserTest stays Chrome-free (the interop test below is the browser path).
            testTask {
                filter.excludeTestsMatching("com.ditchoom.socket.webtransport.WebTransportSurfaceSmokeTest")
            }
            // The browser WebTransport interop test runs in real headless Chrome via Karma. Opt-in only
            // (`-PwtBrowserInterop`) so a default `jsBrowserTest` / CI `check` never requires Chrome.
            if (project.hasProperty("wtBrowserInterop")) {
                testTask {
                    useKarma { useChromeHeadless() }
                }
            }
        }
        nodejs()
    }
    wasmJs {
        // The shared browser interop test (src/browserInterop, compiled for js AND wasmJs) returns the
        // wasmJs `Promise<JsAny?>` from GlobalScope.promise, which is gated behind ExperimentalWasmJsInterop.
        // It can't carry the wasmJs-only `@OptIn` (js wouldn't resolve it), so opt in at the target instead;
        // production wasmJs sources already file-opt-in, so this only covers the shared test.
        compilerOptions {
            optIn.add("kotlin.js.ExperimentalWasmJsInterop")
        }
        browser {
            // The non-gated surface smoke (src/browserSmoke) is node-only — exclude it from the browser
            // task so wasmJsBrowserTest stays Chrome-free (the interop test below is the browser path).
            testTask {
                filter.excludeTestsMatching("com.ditchoom.socket.webtransport.WebTransportSurfaceSmokeTest")
            }
            // Same opt-in as js: run the wasmJs browser WebTransport interop test in real headless Chrome
            // via Karma under `-PwtBrowserInterop`, so the wasmJs reset → neutral-exception mapping is
            // Chrome-verified rather than merely compile-checked. A default `wasmJsBrowserTest` / CI `check`
            // never requires Chrome.
            if (project.hasProperty("wtBrowserInterop")) {
                testTask {
                    useKarma { useChromeHeadless() }
                }
            }
        }
        nodejs()
    }

    if (isMacOS) {
        // Apple QUIC is now quiche (the quiche-on-Apple pivot), provided transitively via
        // :socket-http3 → :socket-quic-default → :socket-quic-quiche. The WebTransport conformance suite
        // stands up a real in-process QUIC server+client (calls into quiche), so the macOS/iOS TEST binary
        // must -force_load libquiche.a — exactly as the linux block below whole-archives it. binaries.all
        // affects the test.kexe; the published klib links nothing. tvOS/watchOS have no quiche target.
        val linkQuiche: org.jetbrains.kotlin.gradle.plugin.mpp.KotlinNativeTarget.(String) -> Unit = { libSubdir ->
            val a = project(":socket-quic-quiche").projectDir.resolve("libs/quiche/$libSubdir/lib/libquiche.a")
            if (a.exists()) {
                binaries.all {
                    linkerOpts(
                        "-force_load",
                        a.absolutePath,
                        "-framework",
                        "Security",
                        "-framework",
                        "CoreFoundation",
                        "-framework",
                        "Network",
                        "-framework",
                        "Foundation",
                        "-lc++",
                    )
                }
            }
        }
        macosArm64 { linkQuiche("macos-arm64") }
        macosX64 { linkQuiche("macos-x64") }
        iosArm64 { linkQuiche("ios-arm64") }
        iosSimulatorArm64 { linkQuiche("ios-simulator-arm64") }
        iosX64 { linkQuiche("ios-x64") }
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
            // Attach to the SHARED `linuxTest` source set (default-hierarchy parent of linuxX64Test +
            // linuxArm64Test) so the LinuxWebTransportTest conformance subclass — and the cross-backend
            // exception-parity test it inherits — builds and runs on BOTH linux arches, not just x64.
            // (linuxArm64 has no native QUIC harness host in CI, but the binary cross-links on x64 and
            // runs on real arm64 Linux, e.g. via Apple's `container`.)
            named("linuxTest") {
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
        // Forward `wt.*` system properties to the forked test worker JVM (CLI `-D` does NOT propagate
        // to test workers). Used by the manually-launched BrowserInteropServer harness
        // (-Dwt.interop.server=true) for browser interop validation; a no-op for ordinary test runs.
        for ((k, v) in System.getProperties()) {
            val key = k.toString()
            if (key.startsWith("wt.")) systemProperty(key, v.toString())
        }
    }
}

// The Apple WebTransport conformance suite now runs on the quiche backend (the quiche-on-Apple pivot),
// which loads a loose PEM cert+key directly from the committed testcerts/cert.{crt,key} — exactly like
// JVM/Linux. The old PKCS#12 export (an NW `sec_identity_t` requirement) and the NW<->quiche cross-impl
// interop jar are both gone (Apple IS quiche now, so there are no two implementations to cross-test).

// --- Browser WebTransport interop (opt-in: `-PwtBrowserInterop`) ---
// Real headless Chrome (via Karma) drives the production browserMain WebTransport wrapper against an
// externally-launched withHttp3Server (the BrowserInteropServer harness, JVM/quiche) presenting the
// W3C serverCertificateHashes `pinned` fixture for the local browser-interop lane (real Chrome ↔ the
// quiche JVM BrowserInteropServer). Chrome accepts a self-signed leaf only via serverCertificateHashes,
// which requires an EC P-256 leaf with ≤14-day validity — so it's generated fresh (a committed one is a
// guaranteed expiry flake) into this module's gitignored testcerts/. Relocated here from the deleted
// :socket-quic-nw (which produced it for the now-removed NW cross-impl cells); the browser lane operator
// runs `:socket-webtransport:generateWebTransportPinnedCert` before launching BrowserInteropServer.
val generateWebTransportPinnedCert =
    tasks.register("generateWebTransportPinnedCert") {
        group = "verification"
        description = "Generate the W3C serverCertificateHashes `pinned` EC P-256 fixture (pinned.{crt,key,sha256})."
        val certDir = projectDir.resolve("testcerts")
        val exts = listOf("crt", "key", "sha256")
        outputs.files(exts.map { certDir.resolve("pinned.$it") })
        // Up-to-date only while the existing leaf still has >3 days left (short-lived by design).
        outputs.upToDateWhen {
            if (!exts.all { certDir.resolve("pinned.$it").exists() }) {
                false
            } else {
                try {
                    val cert =
                        certDir.resolve("pinned.crt").inputStream().use {
                            CertificateFactory.getInstance("X.509").generateCertificate(it) as X509Certificate
                        }
                    cert.notAfter.time - System.currentTimeMillis() > 3L * 24 * 60 * 60 * 1000
                } catch (_: Exception) {
                    false
                }
            }
        }
        doLast {
            val javaHome = File(System.getProperty("java.home"))
            val isWindows = System.getProperty("os.name").startsWith("Windows", ignoreCase = true)
            val keytool = javaHome.resolve(if (isWindows) "bin/keytool.exe" else "bin/keytool").absolutePath
            certDir.mkdirs()
            val tmpP12 = temporaryDir.resolve("pinned.p12").also { it.delete() }
            val gen =
                ProcessBuilder(
                    keytool,
                    "-genkeypair",
                    "-alias",
                    "pinned",
                    "-keyalg",
                    "EC",
                    "-groupname",
                    "secp256r1",
                    "-sigalg",
                    "SHA256withECDSA",
                    "-validity",
                    "13",
                    "-dname",
                    "CN=localhost",
                    "-ext",
                    "san=dns:localhost,ip:127.0.0.1",
                    "-ext",
                    "eku=serverAuth",
                    "-keystore",
                    tmpP12.absolutePath,
                    "-storetype",
                    "PKCS12",
                    "-storepass",
                    "testpass",
                    "-keypass",
                    "testpass",
                ).redirectErrorStream(false).start()
            val genErr = gen.errorStream.bufferedReader().readText()
            if (gen.waitFor() != 0) throw GradleException("keytool -genkeypair failed for pinned:\n$genErr")

            val exp =
                ProcessBuilder(
                    keytool,
                    "-exportcert",
                    "-rfc",
                    "-alias",
                    "pinned",
                    "-keystore",
                    tmpP12.absolutePath,
                    "-storepass",
                    "testpass",
                ).start()
            val certPem = exp.inputStream.bufferedReader().readText()
            val expErr = exp.errorStream.bufferedReader().readText()
            if (exp.waitFor() != 0) throw GradleException("keytool -exportcert failed for pinned:\n$expErr")

            val ks = KeyStore.getInstance("PKCS12")
            tmpP12.inputStream().use { ks.load(it, "testpass".toCharArray()) }
            val key = ks.getKey("pinned", "testpass".toCharArray()) as PrivateKey
            val b64 = Base64.getMimeEncoder(64, "\n".toByteArray()).encodeToString(key.encoded)
            val keyPem = "-----BEGIN PRIVATE KEY-----\n$b64\n-----END PRIVATE KEY-----\n"
            val x509 = CertificateFactory.getInstance("X.509").generateCertificate(certPem.byteInputStream()) as X509Certificate
            val sha256Hex = MessageDigest.getInstance("SHA-256").digest(x509.encoded).joinToString("") { "%02x".format(it) }
            certDir.resolve("pinned.crt").writeText(certPem)
            certDir.resolve("pinned.key").writeText(keyPem)
            certDir.resolve("pinned.sha256").writeText(sha256Hex)
        }
    }

// `pinned` EC P-256 leaf — the only self-signed path a browser accepts (W3C serverCertificateHashes).
// Non-gated public-API surface smoke (src/browserSmoke/kotlin), shared by jsTest + wasmJsTest. Unlike the
// interop test it needs no server and no Chrome, so it ships in every default build — but it's excluded
// from the browser test tasks (see the js/wasmJs `browser { testTask { filter… } }` above) so it runs on
// the node tasks only and the browser tasks stay Chrome-free.
for (smokeTestSourceSet in listOf("jsTest", "wasmJsTest")) {
    kotlin.sourceSets.named(smokeTestSourceSet) {
        kotlin.srcDir("src/browserSmoke/kotlin")
    }
}

// The harness binds an ephemeral port and writes build/wt-interop/config.properties (url + leaf hash);
// `generateBrowserInteropConfig` bakes that into BrowserInteropConfig.kt so the js/wasmJs tests compile
// against the actual port (no fixed ports, no runtime Karma fetch). The interop test source set
// (src/browserInterop/kotlin) is shared by both jsTest and wasmJsTest. Orchestration:
// scripts/browser-interop.sh starts the server, runs jsBrowserTest + wasmJsBrowserTest, then stops it.
// Gated so default builds/CI never require Chrome.
if (project.hasProperty("wtBrowserInterop")) {
    val interopConfigDir = layout.buildDirectory.dir("generated/wt-interop/kotlin")
    val configProps = layout.buildDirectory.file("wt-interop/config.properties")
    val generateBrowserInteropConfig =
        tasks.register("generateBrowserInteropConfig") {
            description = "Generate BrowserInteropConfig.kt from the running interop server's config.properties."
            val outDir = interopConfigDir
            val propsFile = configProps
            outputs.dir(outDir)
            outputs.upToDateWhen { false } // input is a runtime-written file; never cache
            doLast {
                val props = Properties()
                val f = propsFile.get().asFile
                if (f.exists()) f.inputStream().use { props.load(it) }
                val url = props.getProperty("url", "")
                val hash = props.getProperty("certSha256", "")
                val pkgDir = outDir.get().dir("com/ditchoom/socket/webtransport").asFile
                pkgDir.mkdirs()
                pkgDir.resolve("BrowserInteropConfig.kt").writeText(
                    """
                    package com.ditchoom.socket.webtransport

                    internal object BrowserInteropConfig {
                        const val URL: String = "$url"
                        const val CERT_SHA256_HEX: String = "$hash"
                    }
                    """.trimIndent() + "\n",
                )
            }
        }
    // The interop test (src/browserInterop/kotlin) is platform-neutral browser code — it speaks only the
    // neutral WebTransport API + buffer types (allocateNative is the one allocator js and wasmJs share), so
    // the SAME source set compiles into both the jsTest and wasmJsTest binaries. Adding it as a srcDir to
    // each (rather than an expect/actual source-set hierarchy) keeps the wiring symmetric with how jsTest
    // already consumed it. Both also pick up the generated BrowserInteropConfig (port + leaf hash).
    for (testSourceSet in listOf("jsTest", "wasmJsTest")) {
        kotlin.sourceSets.named(testSourceSet) {
            kotlin.srcDir("src/browserInterop/kotlin")
            kotlin.srcDir(interopConfigDir)
            dependencies {
                implementation(kotlin("test"))
                implementation(libs.kotlinx.coroutines.core)
            }
        }
    }
    tasks.named("compileTestKotlinJs") { dependsOn(generateBrowserInteropConfig) }
    tasks.named("compileTestKotlinWasmJs") { dependsOn(generateBrowserInteropConfig) }
    // ktlint over the js/wasmJs test source sets reads the generated srcDir (even though the file is
    // excluded from linting), so declare the producer dependency to avoid Gradle's implicit-dependency
    // validation error.
    tasks
        .matching {
            it.name in
                setOf(
                    "runKtlintCheckOverJsTestSourceSet",
                    "runKtlintFormatOverJsTestSourceSet",
                    "runKtlintCheckOverWasmJsTestSourceSet",
                    "runKtlintFormatOverWasmJsTestSourceSet",
                )
        }.configureEach { dependsOn(generateBrowserInteropConfig) }
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
        // The string globs above match each file's path RELATIVE TO ITS SOURCE ROOT, so a generated
        // srcDir rooted under build/ (the wtBrowserInterop BrowserInteropConfig.kt) slips through — its
        // relative path has no "build"/"generated" segment. Exclude it by filename, which the
        // relative-path matcher does see.
        exclude("**/BrowserInteropConfig.kt")
    }
}
