import java.security.KeyStore
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.util.Base64

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.ktlint)
    alias(libs.plugins.maven.publish)
    signing
}

// :socket-quic-nw is the Apple Network.framework QUIC backend (v6 Phase 2b.3): the system-QUIC
// [QuicEngine] (NetworkEngine) + its NWQuicHelpers cinterop, extracted out of the pure-API
// :socket-quic module. Apple targets only — there is no quiche, no JNI/FFM, no android/jvm/js/wasm
// here. It depends on :socket-quic (the QUIC API/SPI). The default-engine wrappers
// (withQuicConnection / withQuicServer) and the multiplatform `defaultQuicEngine` that selects this
// backend on Apple live in :socket-quic-default (Phase 2b.4).
//
// Apple targets are declared UNCONDITIONALLY (not host-gated): an Apple-only module must declare at
// least one target to configure at all, and ktlint must see the appleMain sources on every host.
// The cinterop/compile/link tasks only succeed on macOS — `configure` + `ktlintCheck` (the
// non-macOS validation gate for this phase) do not execute them.

val isMainBranchGithub = System.getenv("GITHUB_REF") == "refs/heads/main"

repositories {
    mavenLocal()
    google()
    mavenCentral()
}

// Apple Network.framework QUIC helper cinterop (system QUIC; no quiche). The .def carries its own
// `-framework Network/Foundation/Security` linkerOpts, so no BoringSSL / :socket root native dep is
// needed (unlike the quiche backend).
fun org.jetbrains.kotlin.gradle.plugin.mpp.KotlinNativeTarget.configureNWQuicHelpersCinterop() {
    compilations["main"].cinterops {
        create("NWQuicHelpers") {
            defFile("src/nativeInterop/cinterop/NWQuicHelpers.def")
            includeDirs("src/nativeInterop/cinterop")
        }
    }
}

kotlin {
    jvmToolchain(21)

    macosArm64 { configureNWQuicHelpersCinterop() }
    macosX64 { configureNWQuicHelpersCinterop() }
    iosArm64 { configureNWQuicHelpersCinterop() }
    iosSimulatorArm64 { configureNWQuicHelpersCinterop() }
    iosX64 { configureNWQuicHelpersCinterop() }
    tvosArm64 { configureNWQuicHelpersCinterop() }
    tvosSimulatorArm64 { configureNWQuicHelpersCinterop() }
    tvosX64 { configureNWQuicHelpersCinterop() }
    watchosArm64 { configureNWQuicHelpersCinterop() }
    watchosSimulatorArm64 { configureNWQuicHelpersCinterop() }
    watchosX64 { configureNWQuicHelpersCinterop() }

    applyDefaultHierarchyTemplate()
    sourceSets {
        commonMain.dependencies {
            api(project(":socket-quic"))
            implementation(libs.kotlinx.coroutines.core)
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
            implementation(libs.kotlinx.coroutines.core)
            implementation(libs.kotlinx.coroutines.test)
            // Cross-backend conformance suites (abstract *TestSuite + harness). The Apple* wrapper
            // subclasses in appleTest extend them; the suites themselves + the expect/actual harness
            // live in :socket-testsuite (shared with the quiche backend). The apple harness
            // actuals also moved there (PlatformActuals).
            implementation(project(":socket-testsuite"))
        }

        // Per-target Apple actuals that touch platform-width NSUInteger (see
        // FoundationInterop.kt). Added to each leaf source set rather than appleMain so they
        // never compile to commonized metadata — same arrangement as the root :socket module.
        val appleNativeImplDir = file("src/appleNativeImpl/kotlin")
        listOf(
            "macosArm64Main",
            "macosX64Main",
            "iosArm64Main",
            "iosSimulatorArm64Main",
            "iosX64Main",
            "tvosArm64Main",
            "tvosSimulatorArm64Main",
            "tvosX64Main",
            "watchosArm64Main",
            "watchosSimulatorArm64Main",
            "watchosX64Main",
        ).forEach { sourceSetName ->
            findByName(sourceSetName)?.kotlin?.srcDir(appleNativeImplDir)
        }
    }
}

tasks.withType<org.gradle.api.tasks.testing.AbstractTestTask>().configureEach {
    addTestListener(
        object : org.gradle.api.tasks.testing.TestListener {
            override fun beforeSuite(suite: org.gradle.api.tasks.testing.TestDescriptor) = Unit

            override fun afterSuite(
                suite: org.gradle.api.tasks.testing.TestDescriptor,
                result: org.gradle.api.tasks.testing.TestResult,
            ) = Unit

            override fun beforeTest(testDescriptor: org.gradle.api.tasks.testing.TestDescriptor) {
                logger.lifecycle("TEST START ${testDescriptor.className}.${testDescriptor.name}")
            }

            override fun afterTest(
                testDescriptor: org.gradle.api.tasks.testing.TestDescriptor,
                result: org.gradle.api.tasks.testing.TestResult,
            ) {
                logger.lifecycle(
                    "TEST ${result.resultType} ${testDescriptor.className}.${testDescriptor.name} " +
                        "(${result.endTime - result.startTime}ms)",
                )
            }
        },
    )
    testLogging {
        events("failed", "skipped")
        showExceptions = true
        showCauses = true
        showStackTraces = true
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
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

// --- Self-signed `localhost` + p12 test identities for the Apple QUIC server tests ---
// The Apple K/N test tasks read testcerts/*.{crt,key,p12}; generate them so appleTest can run on
// macOS. (The quiche test-cert pipeline lives in :socket-quic-quiche; this is the Apple-only half.)
val generateLocalhostCert =
    tasks.register("generateLocalhostCert") {
        group = "verification"
        description = "Generate the short-lived self-signed localhost cert+key for the Apple QUIC CA-pinning tests."
        val certDir = projectDir.resolve("testcerts")
        outputs.files(certDir.resolve("localhost.crt"), certDir.resolve("localhost.key"))
        doLast {
            val javaHome = File(System.getProperty("java.home"))
            val isWindows = System.getProperty("os.name").startsWith("Windows", ignoreCase = true)
            val keytool = javaHome.resolve(if (isWindows) "bin/keytool.exe" else "bin/keytool").absolutePath
            val tmpP12 = temporaryDir.resolve("localhost.p12")
            tmpP12.delete()

            fun keytool(vararg args: String): Process = ProcessBuilder(listOf(keytool) + args).redirectErrorStream(false).start()

            val gen =
                keytool(
                    "-genkeypair",
                    "-alias",
                    "localhost",
                    "-keyalg",
                    "RSA",
                    "-keysize",
                    "2048",
                    "-sigalg",
                    "SHA256withRSA",
                    "-validity",
                    "397",
                    "-dname",
                    "CN=localhost",
                    "-ext",
                    "san=dns:localhost,ip:127.0.0.1",
                    "-ext",
                    "eku=serverAuth",
                    "-ext",
                    "bc:critical=ca:true",
                    "-keystore",
                    tmpP12.absolutePath,
                    "-storetype",
                    "PKCS12",
                    "-storepass",
                    "testpass",
                    "-keypass",
                    "testpass",
                )
            val genErr = gen.errorStream.bufferedReader().readText()
            if (gen.waitFor() != 0) throw GradleException("keytool -genkeypair failed:\n$genErr")

            val exp = keytool("-exportcert", "-rfc", "-alias", "localhost", "-keystore", tmpP12.absolutePath, "-storepass", "testpass")
            val certPem = exp.inputStream.bufferedReader().readText()
            val expErr = exp.errorStream.bufferedReader().readText()
            if (exp.waitFor() != 0) throw GradleException("keytool -exportcert failed:\n$expErr")

            val ks = KeyStore.getInstance("PKCS12")
            tmpP12.inputStream().use { ks.load(it, "testpass".toCharArray()) }
            val key = ks.getKey("localhost", "testpass".toCharArray()) as PrivateKey
            val b64 = Base64.getMimeEncoder(64, "\n".toByteArray()).encodeToString(key.encoded)
            val keyPem = "-----BEGIN PRIVATE KEY-----\n$b64\n-----END PRIVATE KEY-----\n"

            certDir.mkdirs()
            certDir.resolve("localhost.crt").writeText(certPem)
            certDir.resolve("localhost.key").writeText(keyPem)
        }
    }

// --- W3C `serverCertificateHashes` constraint fixtures for the Apple QUIC tests (Phase 4) ---
// Mirror of :socket-quic-quiche's matrix (see that build for the rationale) — one compliant EC
// P-256 leaf plus one violator per constraint branch, written here into the Apple-only testcerts/
// dir (with `.sha256` so the Kotlin/Native Apple test reads the expected pin from a file). The p12
// step below packages each into the sec_identity_t the Network.framework listener presents.
data class PinnedFixtureSpec(
    val name: String,
    val keyAlg: String,
    val keyParam: List<String>,
    val sigAlg: String,
    val startDate: String?,
    val validityDays: Int,
)

val pinnedFixtureSpecs =
    listOf(
        PinnedFixtureSpec("pinned", "EC", listOf("-groupname", "secp256r1"), "SHA256withECDSA", null, 13),
        PinnedFixtureSpec("pinned-expired", "EC", listOf("-groupname", "secp256r1"), "SHA256withECDSA", "-2d", 1),
        PinnedFixtureSpec("pinned-toolong", "EC", listOf("-groupname", "secp256r1"), "SHA256withECDSA", null, 15),
        PinnedFixtureSpec("pinned-rsa", "RSA", listOf("-keysize", "2048"), "SHA256withRSA", null, 13),
    )
val pinnedFixtureExts = listOf("crt", "key", "sha256")

fun pinnedFixturesFresh(dir: File): Boolean {
    val allPresent =
        pinnedFixtureSpecs.all { s -> pinnedFixtureExts.all { ext -> dir.resolve("${s.name}.$ext").exists() } }
    if (!allPresent) return false
    return try {
        val cert =
            dir.resolve("pinned.crt").inputStream().use {
                CertificateFactory.getInstance("X.509").generateCertificate(it) as X509Certificate
            }
        cert.notAfter.time - System.currentTimeMillis() > 3L * 24 * 60 * 60 * 1000
    } catch (_: Exception) {
        false
    }
}

val generatePinnedW3cCerts =
    tasks.register("generatePinnedW3cCerts") {
        group = "verification"
        description = "Generate the W3C serverCertificateHashes constraint fixtures for the Apple QUIC tests."
        val certDir = projectDir.resolve("testcerts")
        outputs.files(pinnedFixtureSpecs.flatMap { s -> pinnedFixtureExts.map { certDir.resolve("${s.name}.$it") } })
        outputs.upToDateWhen { pinnedFixturesFresh(certDir) }
        doLast {
            val javaHome = File(System.getProperty("java.home"))
            val isWindows = System.getProperty("os.name").startsWith("Windows", ignoreCase = true)
            val keytool = javaHome.resolve(if (isWindows) "bin/keytool.exe" else "bin/keytool").absolutePath

            fun keytool(vararg args: String): Process = ProcessBuilder(listOf(keytool) + args).redirectErrorStream(false).start()
            certDir.mkdirs()
            for (spec in pinnedFixtureSpecs) {
                val tmpP12 = temporaryDir.resolve("${spec.name}.p12").also { it.delete() }
                val genArgs =
                    buildList {
                        addAll(listOf("-genkeypair", "-alias", spec.name, "-keyalg", spec.keyAlg))
                        addAll(spec.keyParam)
                        addAll(listOf("-sigalg", spec.sigAlg))
                        spec.startDate?.let { addAll(listOf("-startdate", it)) }
                        addAll(listOf("-validity", spec.validityDays.toString()))
                        addAll(listOf("-dname", "CN=localhost"))
                        addAll(listOf("-ext", "san=dns:localhost,ip:127.0.0.1"))
                        addAll(listOf("-ext", "eku=serverAuth"))
                        addAll(
                            listOf(
                                "-keystore",
                                tmpP12.absolutePath,
                                "-storetype",
                                "PKCS12",
                                "-storepass",
                                "testpass",
                                "-keypass",
                                "testpass",
                            ),
                        )
                    }
                val gen = keytool(*genArgs.toTypedArray())
                val genErr = gen.errorStream.bufferedReader().readText()
                if (gen.waitFor() != 0) throw GradleException("keytool -genkeypair failed for ${spec.name}:\n$genErr")

                val exp = keytool("-exportcert", "-rfc", "-alias", spec.name, "-keystore", tmpP12.absolutePath, "-storepass", "testpass")
                val certPem = exp.inputStream.bufferedReader().readText()
                val expErr = exp.errorStream.bufferedReader().readText()
                if (exp.waitFor() != 0) throw GradleException("keytool -exportcert failed for ${spec.name}:\n$expErr")

                val ks = KeyStore.getInstance("PKCS12")
                tmpP12.inputStream().use { ks.load(it, "testpass".toCharArray()) }
                val key = ks.getKey(spec.name, "testpass".toCharArray()) as PrivateKey
                val b64 = Base64.getMimeEncoder(64, "\n".toByteArray()).encodeToString(key.encoded)
                val keyPem = "-----BEGIN PRIVATE KEY-----\n$b64\n-----END PRIVATE KEY-----\n"

                val x509 = CertificateFactory.getInstance("X.509").generateCertificate(certPem.byteInputStream()) as X509Certificate
                val sha256Hex = MessageDigest.getInstance("SHA-256").digest(x509.encoded).joinToString("") { "%02x".format(it) }

                certDir.resolve("${spec.name}.crt").writeText(certPem)
                certDir.resolve("${spec.name}.key").writeText(keyPem)
                certDir.resolve("${spec.name}.sha256").writeText(sha256Hex)
            }
        }
    }

val generateTestP12 =
    tasks.register("generateTestP12") {
        group = "verification"
        description = "Generate testcerts/*.p12 from the PEM cert+key pairs for the Apple QUIC server tests."
        dependsOn(generateLocalhostCert, generatePinnedW3cCerts)
        val certDir = projectDir.resolve("testcerts")
        val identities = listOf("cert", "localhost") + pinnedFixtureSpecs.map { it.name }
        inputs.files(identities.flatMap { listOf(certDir.resolve("$it.crt"), certDir.resolve("$it.key")) })
        outputs.files(identities.map { certDir.resolve("$it.p12") })
        doLast {
            for (name in identities) {
                val process =
                    ProcessBuilder(
                        "openssl",
                        "pkcs12",
                        "-export",
                        "-out",
                        certDir.resolve("$name.p12").absolutePath,
                        "-inkey",
                        certDir.resolve("$name.key").absolutePath,
                        "-in",
                        certDir.resolve("$name.crt").absolutePath,
                        "-passout",
                        "pass:testpass",
                    ).redirectErrorStream(true).start()
                val output = process.inputStream.bufferedReader().readText()
                if (process.waitFor() != 0) {
                    throw GradleException("openssl pkcs12 export failed for $name (rc != 0):\n$output")
                }
            }
        }
    }

// Apple K/N test tasks read testcerts/*.{crt,key,p12} at runtime.
tasks
    .matching { it.name.matches(Regex("(macos|ios|tvos|watchos)\\w*Test")) }
    .configureEach { dependsOn(generateTestP12) }
