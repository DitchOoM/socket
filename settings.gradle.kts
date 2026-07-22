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
    resolutionStrategy {
        eachPlugin {
            if (requested.id.id == "com.ditchoom.boringssl.provision") useVersion(provisionVersion)
        }
    }
}

rootProject.name = "socket"
include(":network-monitor")
include(":socket-quic")
include(":socket-testkit")
include(":socket-testsuite")
include(":socket-quic-quiche")
include(":socket-quic-default")
include(":socket-http3")
include(":socket-webtransport")
include(":socket-udp")
include(":socket-quic-trace-tools")

plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
    id("com.gradle.develocity") version ("4.3")
}
develocity {
    buildScan {
        uploadInBackground.set(System.getenv("CI") != null)
        termsOfUseUrl.set("https://gradle.com/help/legal-terms-of-use")
        termsOfUseAgree.set("yes")
    }
}
