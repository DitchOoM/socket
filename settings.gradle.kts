pluginManagement {
    repositories {
        // The com.ditchoom.boringssl.provision plugin now ships to the Gradle Plugin Portal + Maven
        // Central (stable), so it resolves through gradlePluginPortal()/mavenCentral() below at the
        // pinned version (see provisionVersion). This scoped mavenLocal is retained only for local dev
        // against an unreleased -SNAPSHOT publish (e.g. :boringssl-provision:publishToMavenLocal on
        // alien1): its content filter admits ONLY the com.ditchoom.boringssl.* groups, so an unrelated
        // ~/.m2 artifact can never shadow a real Portal/Central dependency (a bare mavenLocal() would).
        // It is a no-op for the stable default resolve; drop it entirely if -SNAPSHOT dev is not needed.
        mavenLocal {
            content { includeGroupByRegex("com\\.ditchoom\\.boringssl.*") }
        }
        google()
        gradlePluginPortal()
        mavenCentral()
    }
    // Pin the provision plugin version from a property so the same build drives a local -SNAPSHOT dev
    // publish + the published stable release. Default is the current stable (resolved from the Portal).
    val provisionVersion = providers.gradleProperty("boringsslPluginVersion").orNull ?: "0.0.6"
    // The benchmark plugins' versions live in gradle.properties, not gradle/libs.versions.toml: that
    // file is hashed into the konan, BoringSSL, quiche and cargo cache keys, so a benchmark-only bump
    // there would rebuild every native cache on CI for nothing.
    val kotlinxBenchmarkVersion = providers.gradleProperty("kotlinxBenchmarkVersion").get()
    val androidxBenchmarkVersion = providers.gradleProperty("androidxBenchmarkVersion").get()
    // The module that implements each plugin this build requests. A plugin resolves straight from its
    // module, never through its marker: a marker's POM names a jar that does not exist, Gradle keeps
    // that "not found" answer for only 24 hours, and so every build on a Gradle cache older than a day
    // asks the repository for the jar again; for a plugin the portal forwards to Maven Central
    // (maven-publish, KSP, boringssl-provision), Central answers that request, 429 included.
    // .github/scripts/plugins-from-cache.sh checks that the build's plugins resolve with the network closed.
    val pluginModules =
        mapOf(
            "org.gradle.toolchains.foojay-resolver-convention" to "org.gradle.toolchains:foojay-resolver",
            "com.gradle.develocity" to "com.gradle:develocity-gradle-plugin",
            "org.jetbrains.kotlin.multiplatform" to "org.jetbrains.kotlin:kotlin-gradle-plugin",
            "org.jetbrains.kotlin.jvm" to "org.jetbrains.kotlin:kotlin-gradle-plugin",
            "com.android.library" to "com.android.tools.build:gradle",
            "com.android.application" to "com.android.tools.build:gradle",
            "com.android.test" to "com.android.tools.build:gradle",
            "org.jlleitschuh.gradle.ktlint" to "org.jlleitschuh.gradle:ktlint-gradle",
            "com.vanniktech.maven.publish" to "com.vanniktech:gradle-maven-publish-plugin",
            "org.jetbrains.dokka" to "org.jetbrains.dokka:dokka-gradle-plugin",
            "com.google.devtools.ksp" to "com.google.devtools.ksp:symbol-processing-gradle-plugin",
            "com.ditchoom.boringssl.provision" to "com.ditchoom.boringssl:boringssl-provision",
            "org.jetbrains.kotlinx.benchmark" to "org.jetbrains.kotlinx:kotlinx-benchmark-plugin",
            "androidx.benchmark" to "androidx.benchmark:benchmark-gradle-plugin",
            "androidx.baselineprofile" to "androidx.benchmark:benchmark-baseline-profile-gradle-plugin",
        )
    resolutionStrategy {
        eachPlugin {
            val id = requested.id.id
            val version =
                when (id) {
                    "com.ditchoom.boringssl.provision" -> provisionVersion
                    "org.jetbrains.kotlinx.benchmark" -> kotlinxBenchmarkVersion
                    "androidx.benchmark", "androidx.baselineprofile" -> androidxBenchmarkVersion
                    else -> requested.version
                }
            // A request without a version is a core plugin or one already on the classpath: nothing to resolve.
            if (version != null) {
                val module =
                    pluginModules[id]
                        ?: throw GradleException(
                            "Plugin '$id' has no entry in settings.gradle.kts pluginModules; add the module its marker names.",
                        )
                useModule("$module:$version")
            }
        }
    }
}

rootProject.name = "socket"
include(":network-monitor")
include(":socket-quic")
include(":socket-trace")
include(":socket-testkit")
include(":socket-testsuite")
include(":socket-quic-quiche")
include(":socket-quic-default")
include(":socket-http3")
include(":socket-webtransport")
include(":socket-udp")
include(":socket-quic-trace-tools")
// Hand-driven iOS handoff probe (not published, not in CI). See ios-probe/build.gradle.kts.
include(":ios-probe")
// Benchmarks: never published, never on a production or test classpath. Each module's build file says what it measures.
include(":benchmark-jvm")
project(":benchmark-jvm").projectDir = file("benchmarks/jvm")
include(":benchmark-android")
project(":benchmark-android").projectDir = file("benchmarks/android-micro")
include(":benchmark-android-app")
project(":benchmark-android-app").projectDir = file("benchmarks/android-app")
include(":benchmark-baselineprofile")
project(":benchmark-baselineprofile").projectDir = file("benchmarks/baselineprofile")

plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
    id("com.gradle.develocity") version ("4.3")
}
develocity {
    buildScan {
        uploadInBackground.set(System.getenv("CI") != null)
        publishing.onlyIf { System.getenv("CI") != null }
        termsOfUseUrl.set("https://gradle.com/help/legal-terms-of-use")
        termsOfUseAgree.set("yes")
    }
}
