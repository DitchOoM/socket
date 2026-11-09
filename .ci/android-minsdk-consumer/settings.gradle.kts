// A standalone build (not part of the main Gradle build): a minimal Android APP with minSdk 24 that
// depends on every library module an Android user consumes, resolved from the PUBLISHED artifacts.
// See build.gradle.kts for what it guards.
pluginManagement {
    repositories {
        google()
        gradlePluginPortal()
        mavenCentral()
    }
    // AGP comes from the root catalog, so this check builds with the same plugin version the library
    // modules do and can never drift from it.
    val agpVersion =
        Regex("""(?m)^androidGradlePlugin\s*=\s*"([^"]+)"""")
            .find(file("../../gradle/libs.versions.toml").readText())
            ?.groupValues
            ?.get(1)
            ?: error("androidGradlePlugin not found in gradle/libs.versions.toml")
    resolutionStrategy {
        eachPlugin {
            if (requested.id.id == "com.android.application") useVersion(agpVersion)
        }
    }
}

rootProject.name = "android-minsdk-consumer"
