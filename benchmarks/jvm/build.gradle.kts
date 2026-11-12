import org.gradle.kotlin.dsl.support.serviceOf
import org.gradle.process.ExecOperations

// JVM microbenchmarks (kotlinx-benchmark over JMH) for the QUIC transport and the codec hot paths.
//
// Its own module, so no production or test classpath changes: nothing depends on this project, and
// it is never published. Run it with
//
//   ./gradlew :benchmark-jvm:benchmark -PappleTargets=false            (FFM backend: the JDK 21+ default)
//   ./gradlew :benchmark-jvm:benchmark -PappleTargets=false -PquicheJvmBackend=jni
//   ./gradlew :benchmark-jvm:smokeBenchmark -PappleTargets=false       (one short iteration of each)
//
// Results land in build/reports/benchmarks/<config>/<timestamp>/<target>.json (JMH JSON).
//
// Backend selection. :socket-quic-quiche ships a multi-release jar: the JDK 21 FFM loader lives under
// META-INF/versions/21 and shadows the base JNI loader.
//
//   -PquicheJvmBackend=ffm (default)  the jar as published: FFM on this JDK 21 run.
//   -PquicheJvmBackend=jni            the same classes with META-INF/versions/** stripped from the quiche
//                                     jar ONLY, so quiche runs JNI and everything else (buffer's own JDK 21
//                                     classes included) is unchanged: an A/B of the quiche backend alone.
//   -PbenchMultiRelease=false         -Djdk.util.jar.enableMultiRelease=false for the whole JVM: no jar's
//                                     versioned classes load (quiche AND buffer fall back to their base
//                                     code). That is what a consumer whose shrinker dropped META-INF/versions/**
//                                     runs, which ProGuard does; see the R8/ProGuard notes in the PR.
//
// Every QUIC benchmark asserts in its @Setup that the backend it measured is the one asked for
// (quiche.expectedJvmBackend), so a selection that silently stopped working fails the run instead of
// reporting one backend's numbers under the other's name.
plugins {
    kotlin("jvm")
    id("org.jetbrains.kotlinx.benchmark")
    alias(libs.plugins.ktlint)
}

repositories {
    google()
    mavenCentral()
}

kotlin {
    jvmToolchain(21)
}

val quicheBackend = providers.gradleProperty("quicheJvmBackend").getOrElse("ffm")
check(quicheBackend == "ffm" || quicheBackend == "jni") {
    "-PquicheJvmBackend must be 'ffm' or 'jni', was '$quicheBackend'"
}
val multiRelease = providers.gradleProperty("benchMultiRelease").map(String::toBooleanStrict).getOrElse(true)
// With multi-release off nothing can load the FFM loader, whatever the jar holds.
val expectedBackend = if (multiRelease) quicheBackend else "jni"

// The host's quiche natives (libquiche + the JNI shim), staged by :socket-quic-quiche under
// META-INF/native/<os>-<arch>/ — the layout its NativeLibLoader and FFM loader read from the classpath.
// The published jvm jar carries no natives (they ship in classifier jars), so the benchmark puts the
// staged directory on its runtime classpath, the same way :socket-quic-quiche:jvmTest does.
val quicheProject = project(":socket-quic-quiche")
val stagedQuicheNatives =
    files(quicheProject.layout.buildDirectory.dir("generated-native-resources/jvmMain"))
        .builtBy(":socket-quic-quiche:stageQuicheNativeResources")

dependencies {
    implementation(project(":"))
    implementation(project(":socket-quic"))
    implementation(project(":socket-quic-default"))
    implementation(project(":socket-quic-quiche"))
    implementation(project(":socket-http3"))
    implementation(libs.buffer)
    implementation(libs.buffer.codec)
    implementation(libs.buffer.flow)
    implementation(libs.kotlinx.coroutines.core)
    implementation("org.jetbrains.kotlinx:kotlinx-benchmark-runtime:${providers.gradleProperty("kotlinxBenchmarkVersion").get()}")
    runtimeOnly(stagedQuicheNatives)

    // The other stacks of the comparison benchmarks (NettyQuic*, NettyTcp*, Kwik*, KtorTcp*). This module is
    // never published and nothing depends on it, so these reach no consumer.
    val nettyVersion = providers.gradleProperty("nettyVersion").get()
    implementation("io.netty:netty-codec-classes-quic:$nettyVersion")
    implementation("io.netty:netty-handler:$nettyVersion")
    implementation("io.netty:netty-transport:$nettyVersion")
    for (classifier in listOf("osx-aarch_64", "osx-x86_64", "linux-x86_64", "linux-aarch_64")) {
        runtimeOnly("io.netty:netty-codec-native-quic:$nettyVersion:$classifier")
    }
    implementation("tech.kwik:kwik:${providers.gradleProperty("kwikVersion").get()}")
    implementation("io.ktor:ktor-network:${providers.gradleProperty("ktorVersion").get()}")
}

// The comparison benchmarks (other stacks, plus this library's own matching shapes as the reference) run in
// their own configuration: the PR A/B lane and the nightly compare this library against itself and must not
// pay for, or be moved by, a third-party stack.
val comparisonOnly = ".*\\.(Netty|Kwik|Ktor).*"
val comparisonSet =
    ".*\\.(Netty|Kwik|Ktor).*|.*\\.(QuicLongLivedStreamRoundTrip|QuicStreamPerRequest|QuicBulkThroughput|TcpRoundTrip)\\..*"

benchmark {
    targets {
        register("main") {
            this as kotlinx.benchmark.gradle.JvmBenchmarkTarget
            jmhVersion = providers.gradleProperty("jmhVersion").get()
        }
    }
    configurations {
        named("main") {
            exclude(comparisonOnly)
            warmups = 5
            iterations = 10
            iterationTime = 1
            iterationTimeUnit = "s"
            reportFormat = "json"
            advanced("jvmForks", 2)
        }
        // The PR A/B lane (quic-bench.yaml `ab`): base and head each run this once per backend in one job,
        // so it trades the main config's second fork for wall-clock.
        register("ci") {
            exclude(comparisonOnly)
            warmups = 3
            iterations = 5
            iterationTime = 1
            iterationTimeUnit = "s"
            reportFormat = "json"
            advanced("jvmForks", 1)
        }
        // A short run that proves every benchmark still executes ("did I break a benchmark"). Its
        // numbers are not for comparison.
        register("smoke") {
            exclude(comparisonOnly)
            warmups = 1
            iterations = 1
            iterationTime = 1
            iterationTimeUnit = "s"
            reportFormat = "json"
            advanced("jvmForks", 1)
        }
        // `compareBenchmark`: this library against Netty QUIC/TCP, Kwik and Ktor on the same loopback shapes.
        // One fork per run: repeat the task (A, B, C, D, A, B, C, D, ...) rather than fork each benchmark twice
        // back to back, so a noisy minute on a shared machine lands on every library, not on one.
        register("compare") {
            include(comparisonSet)
            warmups = 5
            iterations = 10
            iterationTime = 1
            iterationTimeUnit = "s"
            reportFormat = "json"
            advanced("jvmForks", 1)
        }
        register("compareSmoke") {
            include(comparisonSet)
            warmups = 1
            iterations = 1
            iterationTime = 1
            iterationTimeUnit = "s"
            reportFormat = "json"
            advanced("jvmForks", 1)
        }
    }
}

// The JMH jar kotlinx-benchmark generates (build/benchmarks/main/jars/*-JMH.jar) bundles every dependency's
// classes, META-INF/versions/21 (the FFM loader) included, but its manifest says nothing about multi-release.
// A JVM then ignores the versioned classes and a run straight from that jar (`java -cp ... org.openjdk.jmh.Main`,
// the way a profiler is attached) measures JNI whatever backend was meant. The Gradle benchmark tasks never
// load this jar's copies (they run from the classpath, quiche's own multi-release jar first), so they were
// unaffected; the jar is made right for everything else, and assertQuicheBackend fails a run whose backend is
// not the one asked for (the default ask is ffm).
afterEvaluate {
    tasks.named<org.gradle.jvm.tasks.Jar>("mainBenchmarkJar") {
        manifest { attributes("Multi-Release" to "true") }
        // One of the comparison libraries' jars is signed (META-INF/PFOPENSO.*); copied into this fat jar its
        // signature no longer matches the merged manifest, and the JVM refuses the jar (SecurityException).
        exclude("META-INF/*.SF", "META-INF/*.DSA", "META-INF/*.RSA", "META-INF/*.EC")
    }
}

val certDir = quicheProject.layout.projectDirectory.dir("testcerts")

// The quiche jvm jar minus its META-INF/versions/** (the FFM loader and bindings): the JNI arm of the A/B.
// Picked off the resolved runtime classpath by name, so this project need not configure after
// :socket-quic-quiche to reach its jvmJar task.
fun File.isQuicheJvmJar() = name.startsWith("socket-quic-quiche-jvm") && name.endsWith(".jar")

val quicheJvmJar = configurations.runtimeClasspath.map { cp -> cp.filter { it.isQuicheJvmJar() } }
val quicheJniOnlyJar =
    tasks.register<Jar>("quicheJniOnlyJar") {
        description = "The :socket-quic-quiche jvm jar without its JDK 21 (FFM) classes, for -PquicheJvmBackend=jni."
        archiveFileName.set("socket-quic-quiche-jni-only.jar")
        destinationDirectory.set(layout.buildDirectory.dir("quiche-jni-only"))
        from(quicheJvmJar.map { jars -> jars.map { zipTree(it) } }) { exclude("META-INF/versions/**") }
        manifest { attributes("Multi-Release" to "false") }
    }

// M connections to one in-process server, each on an application thread of its own: round trip, stream
// per request and bulk at each M (QuicMultiConnectionLoad). JMH has no parameterized thread count, so this
// is a plain program. -PloadArgs="<counts> <warm-up s> <measured s> [phases]", default "1,4,16,64 2 5".
tasks.register<JavaExec>("multiConnectionLoad") {
    description = "Aggregate QUIC throughput and per-connection latency at 1/4/16/64 connections."
    group = "benchmark"
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass.set("com.ditchoom.socket.benchmark.QuicMultiConnectionLoadKt")
    args(
        providers
            .gradleProperty("loadArgs")
            .getOrElse("1,4,16,64 2 5")
            .split(' ')
            .filter { it.isNotBlank() },
    )
    jvmArgs(
        "--enable-native-access=ALL-UNNAMED",
        "--add-opens",
        "java.base/java.nio=ALL-UNNAMED",
        "-Dquiche.expectedJvmBackend=$expectedBackend",
        "-Dbench.certDir=${certDir.asFile.absolutePath}",
    )
    if (!multiRelease) jvmArgs("-Djdk.util.jar.enableMultiRelease=false")
    if (quicheBackend == "jni") {
        dependsOn(quicheJniOnlyJar)
        val replacement = quicheJniOnlyJar.flatMap { it.archiveFile }
        classpath = files(replacement) + classpath.filter { !it.isQuicheJvmJar() }
    }
    javaLauncher.set(javaToolchains.launcherFor { languageVersion.set(JavaLanguageVersion.of(21)) })
    outputs.upToDateWhen { false }
}

// The run tasks are JavaExec; JMH forks inherit the host VM's input arguments, so these reach every
// measured fork.
tasks.withType<JavaExec>().matching { it.name.endsWith("Benchmark") }.configureEach {
    jvmArgs(
        "--enable-native-access=ALL-UNNAMED",
        "--add-opens",
        "java.base/java.nio=ALL-UNNAMED",
        "-Dquiche.expectedJvmBackend=$expectedBackend",
        "-Dbench.certDir=${certDir.asFile.absolutePath}",
    )
    if (!multiRelease) jvmArgs("-Djdk.util.jar.enableMultiRelease=false")
    if (quicheBackend == "jni") {
        dependsOn(quicheJniOnlyJar)
        val replacement = quicheJniOnlyJar.flatMap { it.archiveFile }
        classpath = files(replacement) + classpath.filter { !it.isQuicheJvmJar() }
    }
    // Re-run on every invocation: a benchmark result is a measurement, not a cacheable output.
    outputs.upToDateWhen { false }
}

ktlint {
    filter {
        exclude { it.file.path.contains("/build/") }
    }
}

// ── What a shrunk desktop consumer runs ─────────────────────────────────────────────────────────────
//
// `:benchmark-jvm:shrunkConsumerProbe` builds a fat jar the way an application would (every dependency
// unpacked, Multi-Release kept), shrinks it with R8 (class-file mode) and with ProGuard, and runs all
// three: each prints the quiche backend it loaded and a 64-byte round-trip p50/p99 (ShrunkConsumerProbe).
// Evidence for the FFM-under-shrinking question, not a gate: nothing depends on it.
val r8Tool = configurations.create("r8Tool")
val proguardTool = configurations.create("proguardTool")

dependencies {
    r8Tool(libs.r8)
    proguardTool("com.guardsquare:proguard-base:${providers.gradleProperty("proguardVersion").get()}")
}

fun File.isComparisonJar() = listOf("netty-", "kwik-", "agent15-", "hkdf-", "ktor-").any { name.startsWith(it) } || "siphash" in name

val consumerDir = layout.buildDirectory.dir("shrunk-consumer")
val consumerFatJar =
    tasks.register<Jar>("consumerFatJar") {
        description = "A fat jar of ShrunkConsumerProbe and every runtime dependency, Multi-Release kept."
        archiveFileName.set("consumer-fat.jar")
        destinationDirectory.set(consumerDir)
        from(sourceSets.main.map { it.output })
        from(
            configurations.runtimeClasspath.map { cp ->
                // The comparison libraries are not part of what a consumer ships.
                cp.filter { it.name.endsWith(".jar") && !it.isComparisonJar() }.map { zipTree(it) }
            },
        )
        from(stagedQuicheNatives)
        duplicatesStrategy = DuplicatesStrategy.EXCLUDE
        exclude(
            "META-INF/*.SF",
            "META-INF/*.DSA",
            "META-INF/*.RSA",
            "META-INF/INDEX.LIST",
            "module-info.class",
            "META-INF/versions/*/module-info.class",
        )
        manifest {
            attributes("Main-Class" to "com.ditchoom.socket.benchmark.ShrunkConsumerProbeKt", "Multi-Release" to "true")
        }
    }

val shrinkRules = layout.projectDirectory.file("shrunk-consumer.pro")
val javaHome =
    javaToolchains.launcherFor { languageVersion.set(JavaLanguageVersion.of(21)) }.map { it.metadata.installationPath.asFile }

val consumerR8Jar =
    tasks.register<JavaExec>("consumerR8Jar") {
        // The shrinker arguments live in an argument provider Gradle does not fingerprint; always re-shrink.
        outputs.upToDateWhen { false }
        description = "consumer-fat.jar shrunk by R8 in class-file mode (full mode, the R8 default)."
        val out = consumerDir.map { it.file("consumer-r8.jar") }
        inputs.files(consumerFatJar, shrinkRules)
        outputs.file(out)
        classpath = r8Tool
        mainClass.set("com.android.tools.r8.R8")
        argumentProviders.add(
            CommandLineArgumentProvider {
                listOf(
                    "--classfile",
                    "--release",
                    "--output",
                    out.get().asFile.path,
                    "--pg-conf",
                    shrinkRules.asFile.path,
                    "--lib",
                    javaHome.get().path,
                    consumerFatJar
                        .get()
                        .archiveFile
                        .get()
                        .asFile.path,
                )
            },
        )
    }

val consumerProguardJar =
    tasks.register<JavaExec>("consumerProguardJar") {
        // The shrinker arguments live in an argument provider Gradle does not fingerprint; always re-shrink.
        outputs.upToDateWhen { false }
        description = "consumer-fat.jar shrunk by ProGuard."
        val out = consumerDir.map { it.file("consumer-proguard.jar") }
        inputs.files(consumerFatJar, shrinkRules)
        outputs.file(out)
        classpath = proguardTool
        mainClass.set("proguard.ProGuard")
        argumentProviders.add(
            CommandLineArgumentProvider {
                listOf(
                    "-injars",
                    consumerFatJar
                        .get()
                        .archiveFile
                        .get()
                        .asFile.path,
                    "-outjars",
                    out.get().asFile.path,
                    "-libraryjars",
                    "${javaHome.get().path}/jmods(!**.jar;!module-info.class)",
                    "-include",
                    shrinkRules.asFile.path,
                    // ProGuard 7.10's optimizer emits a class that fails verification here (VerifyError,
                    // "Bad type on operand stack", in an obfuscated coroutine path), so the ProGuard arm
                    // shrinks and obfuscates without optimizing. The META-INF/versions question is
                    // unaffected: ProGuard drops that tree before any optimization runs.
                    "-dontoptimize",
                )
            },
        )
    }

tasks.register("shrunkConsumerProbe") {
    description = "Runs ShrunkConsumerProbe from the fat, R8 and ProGuard jars and prints which quiche backend each loaded."
    dependsOn(consumerFatJar, consumerR8Jar, consumerProguardJar)
    val jars =
        listOf(
            "fat (unshrunk)" to consumerDir.map { it.file("consumer-fat.jar") },
            "r8" to consumerDir.map { it.file("consumer-r8.jar") },
            "proguard" to consumerDir.map { it.file("consumer-proguard.jar") },
        )
    val java = javaHome.map { File(it, "bin/java").path }
    val certs = certDir.asFile.absolutePath
    val exec = project.serviceOf<ExecOperations>()
    notCompatibleWithConfigurationCache("a one-off measurement, run by hand")
    doLast {
        for ((label, jar) in jars) {
            exec.exec {
                // --add-opens: buffer's base (pre-JDK 21) tier reads java.nio.Buffer.address reflectively, and a
                // jar that lost its JDK 21 tier runs the base tier.
                commandLine(
                    java.get(),
                    "--enable-native-access=ALL-UNNAMED",
                    "--add-opens",
                    "java.base/java.nio=ALL-UNNAMED",
                    "-jar",
                    jar.get().asFile.path,
                    certs,
                    label,
                )
                isIgnoreExitValue = true
            }
        }
    }
}
