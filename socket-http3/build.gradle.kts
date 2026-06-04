import org.jetbrains.kotlin.gradle.tasks.KotlinCompilationTask

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.ksp)
    alias(libs.plugins.ktlint)
}

group = "com.ditchoom"

repositories {
    mavenLocal()
    mavenCentral()
}

// Minimal Phase-1 module: HTTP/3 codec foundation on top of :socket-quic.
// Targets are limited to jvm + linuxX64 while the foundation is validated;
// the full KMP target set + publishing config land once the codec layer is proven.
kotlin {
    jvmToolchain(21)
    jvm()
    linuxX64()

    applyDefaultHierarchyTemplate()
    sourceSets {
        commonMain.dependencies {
            api(project(":socket-quic"))
            api(libs.buffer)
            api(libs.buffer.flow)
            api(libs.buffer.codec)
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
        }
    }
}

// --- buffer-codec KSP for PRODUCTION commonMain across every target ---
// Run KSP once on the common-metadata compilation so generated codecs land in
// build/generated/ksp/metadata/commonMain/kotlin and every target compilation
// (jvm, linuxX64, …) sees the same generated symbols. This mirrors the proven
// pattern in ../buffer's buffer-codec-test module. Both the processor AND
// buffer-codec (the annotation + runtime types) are KSP dependencies.
dependencies {
    add("kspCommonMainMetadata", libs.buffer.codec.processor)
    add("kspCommonMainMetadata", libs.buffer.codec)
}

kotlin.sourceSets.named("commonMain") {
    kotlin.srcDir("build/generated/ksp/metadata/commonMain/kotlin")
}

// Every non-metadata compilation depends on the single metadata KSP run so all
// targets compile against the generated codecs.
tasks.withType<KotlinCompilationTask<*>>().configureEach {
    if (name != "kspCommonMainKotlinMetadata") {
        dependsOn("kspCommonMainKotlinMetadata")
    }
}

ktlint {
    // commonMain includes the KSP output srcDir; the glob alone doesn't match the
    // absolute generated path, so also use a path predicate (matches ../buffer's setup).
    filter {
        exclude("**/generated/**")
        exclude("**/build/**")
        exclude {
            it.file.path.contains("/generated/") || it.file.path.contains("/build/")
        }
    }
}

// ktlint reads the commonMain srcDir (which includes the KSP output dir), so the
// check/format tasks must run after KSP generation or Gradle flags an implicit
// dependency on a directory written by kspCommonMainKotlinMetadata.
tasks
    .matching {
        it.name == "runKtlintCheckOverCommonMainSourceSet" ||
            it.name == "runKtlintFormatOverCommonMainSourceSet"
    }.configureEach {
        dependsOn("kspCommonMainKotlinMetadata")
    }
