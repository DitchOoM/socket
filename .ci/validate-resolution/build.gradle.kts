plugins {
    alias(libs.plugins.kotlin.multiplatform)
}

val socketVersion: String by project
val mavenRepoPath: String by project

repositories {
    maven(url = uri(file(mavenRepoPath)))
    mavenCentral()
}

kotlin {
    // JVM & JS
    jvm()
    js {
        browser()
        nodejs()
    }

    // Linux
    linuxX64()
    linuxArm64()

    // Apple – macOS
    macosArm64()
    macosX64()

    // Apple – iOS
    iosArm64()
    iosSimulatorArm64()
    iosX64()

    // Apple – tvOS
    tvosArm64()
    tvosSimulatorArm64()
    tvosX64()

    // Apple – watchOS
    watchosArm64()
    watchosSimulatorArm64()
    watchosX64()

    sourceSets {
        commonMain.dependencies {
            // Resolve every published module across every target. socket-quic and
            // socket-http3 are included so their per-host (Linux + Apple) root module
            // metadata must also be merged at publish time — otherwise resolving them
            // from an Apple target fails here. A socket-only check passed while
            // socket-quic's root module shipped Linux-only variants (its Apple klibs
            // existed on Central but weren't referenced by the root .module); this
            // catches that.
            implementation("com.ditchoom:socket:$socketVersion")
            implementation("com.ditchoom:socket-quic:$socketVersion")
            implementation("com.ditchoom:socket-http3:$socketVersion")
        }
    }
}

tasks.register("resolveAll") {
    description = "Resolves every resolvable configuration to verify Gradle module metadata"
    doLast {
        var resolved = 0
        configurations
            .filter { it.isCanBeResolved }
            .forEach { cfg ->
                try {
                    cfg.resolve()
                    resolved++
                } catch (e: Exception) {
                    throw GradleException("Failed to resolve configuration '${cfg.name}': ${e.message}", e)
                }
            }
        logger.lifecycle("Successfully resolved $resolved configurations")
    }
}
