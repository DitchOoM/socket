import org.jetbrains.kotlin.konan.target.HostManager

// Consumer-smoke: a REAL downstream consumer of the PUBLISHED com.ditchoom artifacts. Unlike
// `.ci/validate-resolution` (which only RESOLVES the dependency graph and checks module-metadata
// shape), this project COMPILES consumer code against the published API, LINKS Kotlin/Native
// binaries (so a missing static lib — e.g. an undistributed libquiche.a — surfaces as the
// link-time undefined-symbol error it really is, instead of slipping through resolution), and RUNS
// a behavioural HTTP/3 loopback on the JVM (catching native-load / JNI / runtime breakage that
// resolution never exercises). It is the artifact-shape safety net the source-built CI lanes can't see.
//
// Parameterised like validate-resolution: -PsocketVersion + -PmavenRepoPath point it at the
// just-built merged maven-local repo in CI; both default to a local publishToMavenLocal run.
plugins {
    alias(libs.plugins.kotlin.multiplatform)
}

val socketVersion = (findProperty("socketVersion") as String?) ?: "3.6.6-SNAPSHOT"
val mavenRepoPath = findProperty("mavenRepoPath") as String?

val isMacOS = HostManager.hostIsMac
val isLinux = HostManager.hostIsLinux

repositories {
    // The artifacts under test: an explicit merged maven-local repo in CI, else the developer's
    // local publishToMavenLocal cache.
    if (mavenRepoPath != null) {
        maven(url = uri(file(mavenRepoPath)))
    } else {
        mavenLocal()
    }
    google()
    mavenCentral()
}

kotlin {
    jvmToolchain(21)
    jvm {
        // Surface the loopback smoke's println markers and give the JNI quiche backend (used on
        // JDK<21; FFM on 21+) the java.nio open it needs on JDK 16+.
        testRuns.all {
            executionTask.configure {
                testLogging { showStandardStreams = true }
                jvmArgs("--add-opens", "java.base/java.nio=ALL-UNNAMED")
            }
        }
    }

    // Apple targets only build on macOS; the Linux-native target only on Linux — the same host
    // gating :socket-quic / :socket-http3 use, so the consumer's target set matches what each host
    // actually published.
    if (isMacOS) {
        macosArm64()
        iosSimulatorArm64()
    }
    if (isLinux) {
        linuxX64()
    }

    sourceSets {
        commonMain.dependencies {
            // The published library surface a real consumer touches. Compiling commonMain against
            // these on every declared target is already stronger than resolution: it catches an API
            // shape that resolves but won't compile.
            implementation("com.ditchoom:socket:$socketVersion")
            implementation("com.ditchoom:socket-quic:$socketVersion")
            implementation("com.ditchoom:socket-http3:$socketVersion")
            implementation("com.ditchoom:buffer:6.0.0")
            implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.11.0")
        }

        // kotlin.test + coroutines-test go in commonTest so EVERY test source set inherits them —
        // notably nativeTest, whose ApiLinkTest is the Kotlin/Native LINK gate. With them only in
        // jvmTest, compileTestKotlin{LinuxX64,MacosArm64,IosSimulatorArm64} failed with unresolved
        // 'test'/'runTest' BEFORE reaching the linker, so the link gate never actually exercised the
        // K/N link (it caught a missing-dep, not the undistributed-libquiche.a it is built to catch).
        commonTest.dependencies {
            implementation(kotlin("test"))
            implementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.11.0")
            // W7: the published test-support artifact, consumed exactly as a downstream project
            // would — testImplementation-scope from the merged repo under validation. In commonTest
            // (not jvmTest) on purpose: the K/N TestBinaries link gates then also compile/link
            // against the published socket-testsuite klibs (jvm/linux/apple), which is what catches
            // a socket-testsuite left out of the validate-artifacts merge loop (the #188 class of
            // bug) at link time. The behavioural harness smoke itself runs in jvmTest.
            implementation("com.ditchoom:socket-testsuite:$socketVersion")
        }

        jvmTest.dependencies {
            // The macOS quiche natives ship as a per-host classifier jar (META-INF/native/<platform>/)
            // that CI's validate-artifacts step injects into the merged socket-quic-quiche-jvm jar. For
            // a LOCAL run the SNAPSHOT publication doesn't auto-attach it, so add the locally-built
            // classifier jar to the runtime classpath (no-op in CI, where the natives are already in the jar).
            val localNativeJar =
                file(
                    "${rootDir}/../../socket-quic-quiche/build/libs/" +
                        "socket-quic-quiche-$socketVersion-macos-aarch64.jar",
                )
            if (mavenRepoPath == null && localNativeJar.exists()) {
                runtimeOnly(files(localNativeJar))
            }
        }
    }
}
