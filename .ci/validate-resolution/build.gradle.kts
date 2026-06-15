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
            //
            // The v6 QUIC split publishes three more modules with DIFFERENT target
            // sets — only socket-quic-default ships all targets, so only it can be
            // resolved from commonMain. socket-quic-quiche (jvm/linux only) and
            // socket-quic-nw (apple only) are pinned to their own source sets below;
            // putting either in commonMain would fail to resolve on the targets it
            // doesn't publish.
            implementation("com.ditchoom:socket:$socketVersion")
            implementation("com.ditchoom:socket-quic:$socketVersion")
            implementation("com.ditchoom:socket-quic-default:$socketVersion")
            implementation("com.ditchoom:socket-http3:$socketVersion")
        }

        // socket-quic-quiche publishes jvm + linuxX64 + linuxArm64 (+ android, not
        // a target here) — no js/apple/wasm. Resolve it only where it exists.
        jvmMain.dependencies {
            implementation("com.ditchoom:socket-quic-quiche:$socketVersion")
        }
        linuxX64Main.dependencies {
            implementation("com.ditchoom:socket-quic-quiche:$socketVersion")
        }
        linuxArm64Main.dependencies {
            implementation("com.ditchoom:socket-quic-quiche:$socketVersion")
        }

        // socket-quic-nw is apple-only — resolve it from the shared apple source set
        // so every macOS/iOS/tvOS/watchOS target pulls (and validates) its klibs.
        appleMain.dependencies {
            implementation("com.ditchoom:socket-quic-nw:$socketVersion")
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
