import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.plugin.mpp.KotlinNativeTarget
import java.net.URI
import java.security.MessageDigest

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.library)
    alias(libs.plugins.ktlint)
    alias(libs.plugins.maven.publish)
    alias(libs.plugins.dokka)
    signing
}

apply(from = "gradle/setup.gradle.kts")

group = "com.ditchoom"
val isRunningOnGithub = System.getenv("GITHUB_REPOSITORY")?.isNotBlank() == true
val isMainBranchGithub = System.getenv("GITHUB_REF") == "refs/heads/main"
val isMacOS = System.getProperty("os.name").lowercase().contains("mac")

@Suppress("UNCHECKED_CAST")
val getNextVersion = project.extra["getNextVersion"] as (Boolean) -> Any
project.version = getNextVersion(!isRunningOnGithub).toString()

// Only print version info when not in quiet mode (avoids polluting CI output)
if (gradle.startParameter.logLevel != LogLevel.QUIET) {
    println("Version: ${project.version}\nisRunningOnGithub: $isRunningOnGithub\nisMainBranchGithub: $isMainBranchGithub")
}

repositories {
    google()
    mavenCentral()
    maven { setUrl("https://maven.pkg.jetbrains.space/kotlin/p/kotlin/kotlin-js-wrappers/") }
}

// Swift library paths
val swiftBuildDir = layout.buildDirectory.dir("swift")
val swiftHeaderDir = swiftBuildDir.map { it.dir("include") }
val swiftLibDir = swiftBuildDir.map { it.dir("lib") }

// Task to build Swift libraries (only on macOS)
val buildSwiftTask =
    tasks.register<Exec>("buildSwift") {
        onlyIf { isMacOS }
        workingDir = projectDir
        commandLine("./buildSwift.sh")
        outputs.dir(swiftBuildDir)
        inputs.files(fileTree("src/nativeInterop/cinterop/swift") { include("**/*.swift") })
    }

// Map our library subdirs to Swift platform names
fun swiftPlatformForLibSubdir(libSubdir: String): String =
    when (libSubdir) {
        "macos" -> "macosx"
        "ios" -> "iphoneos"
        "ios-simulator" -> "iphonesimulator"
        "tvos" -> "appletvos"
        "tvos-simulator" -> "appletvsimulator"
        "watchos" -> "watchos"
        "watchos-simulator" -> "watchsimulator"
        else -> "macosx"
    }

// Configure cinterop for Apple targets
fun KotlinNativeTarget.configureSocketWrapperCinterop(libSubdir: String) {
    val libPath =
        swiftLibDir
            .get()
            .dir(libSubdir)
            .asFile.absolutePath
    val swiftPlatform = swiftPlatformForLibSubdir(libSubdir)
    compilations["main"].cinterops {
        create("SocketWrapper") {
            defFile("src/nativeInterop/cinterop/SocketWrapper.def")
            includeDirs(swiftHeaderDir)
            // Embed the static library in the klib so consumers link it automatically
            extraOpts("-libraryPath", libPath)
            extraOpts("-staticLibrary", "libSocketWrapper.a")
            tasks.named(interopProcessingTaskName) {
                dependsOn(buildSwiftTask)
            }
        }
    }
    compilations["main"].compileTaskProvider.configure {
        dependsOn(buildSwiftTask)
    }
    // Link Swift runtime libraries (platform-specific) for all binaries
    // Note: libSocketWrapper.a is now embedded in the klib via -staticLibrary
    binaries.all {
        linkerOpts(
            "-L/Applications/Xcode.app/Contents/Developer/Toolchains/XcodeDefault.xctoolchain/usr/lib/swift/$swiftPlatform",
            "-L/usr/lib/swift",
        )
    }
}

// OpenSSL version for Linux builds - defined in gradle/libs.versions.toml
val opensslVersion = libs.versions.openssl.get()
val opensslSha256 = libs.versions.opensslSha256.get()
val opensslDownloadDir = layout.buildDirectory.dir("openssl")
val opensslIncludeDir = opensslDownloadDir.map { it.dir("openssl-$opensslVersion/include") }

// Task to download and extract OpenSSL headers (avoids committing 40k lines of headers)
val downloadOpenSslHeaders by tasks.registering {
    val outputDir = opensslDownloadDir.get().asFile
    val tarball = File(outputDir, "openssl-$opensslVersion.tar.gz")
    val markerFile = File(outputDir, "openssl-$opensslVersion/.extracted")

    outputs.file(markerFile)

    doLast {
        if (markerFile.exists()) {
            return@doLast
        }

        outputDir.mkdirs()

        // Download if not present
        if (!tarball.exists()) {
            logger.lifecycle("Downloading OpenSSL $opensslVersion headers...")
            val url = URI("https://github.com/openssl/openssl/releases/download/openssl-$opensslVersion/openssl-$opensslVersion.tar.gz").toURL()
            url.openStream().use { input: java.io.InputStream ->
                tarball.outputStream().use { output ->
                    input.copyTo(output)
                }
            }
        }

        // Verify SHA256
        val digest = MessageDigest.getInstance("SHA-256")
        tarball.inputStream().use { input: java.io.InputStream ->
            val buffer = ByteArray(8192)
            var read: Int
            while (input.read(buffer).also { read = it } != -1) {
                digest.update(buffer, 0, read)
            }
        }
        val actualSha256 = digest.digest().joinToString("") { byte -> "%02x".format(byte) }
        if (actualSha256 != opensslSha256) {
            tarball.delete()
            throw GradleException("OpenSSL SHA256 mismatch: expected $opensslSha256, got $actualSha256")
        }

        // Extract only the include directory
        logger.lifecycle("Extracting OpenSSL headers...")
        project.exec {
            workingDir = outputDir
            commandLine("tar", "xzf", tarball.name, "openssl-$opensslVersion/include")
        }

        markerFile.writeText("extracted")
    }
}

// Configure cinterop for Linux targets with static OpenSSL
// Requires: sudo apt install liburing-dev
// For ARM64 cross-compilation: sudo apt install gcc-aarch64-linux-gnu libc6-dev-arm64-cross
// OpenSSL static libraries are pre-built and committed in libs/openssl/
// To rebuild OpenSSL: ./buildSrc/openssl/build-openssl.sh
fun KotlinNativeTarget.configureLinuxCinterop(arch: String) {
    val opensslLibDir = projectDir.resolve("libs/openssl/linux-$arch/lib")

    // Determine include/lib paths based on architecture and available cross-compilation tools
    val (systemIncludeDirs, systemLibDir) = if (arch == "x64") {
        listOf("/usr/include", "/usr/include/x86_64-linux-gnu") to "/usr/lib/x86_64-linux-gnu"
    } else {
        // ARM64: check for cross-compilation sysroot or native paths
        val crossRoot = "/usr/aarch64-linux-gnu"
        if (File(crossRoot).exists()) {
            listOf("$crossRoot/include", "/usr/include") to "$crossRoot/lib"
        } else {
            listOf("/usr/include", "/usr/include/aarch64-linux-gnu") to "/usr/lib/aarch64-linux-gnu"
        }
    }

    compilations["main"].cinterops {
        create("LinuxSockets") {
            defFile("src/nativeInterop/cinterop/LinuxSockets.def")
            // Use system headers for liburing, downloaded headers for OpenSSL
            val allIncludes = systemIncludeDirs + opensslIncludeDir.get().asFile.absolutePath
            includeDirs(*allIncludes.toTypedArray())
            tasks.named(interopProcessingTaskName) {
                dependsOn(downloadOpenSslHeaders)
            }
        }
    }
    // Link against static OpenSSL (glibc 2.17 compatible) and system liburing
    binaries.all {
        linkerOpts(
            "-L${opensslLibDir.absolutePath}",
            "-L$systemLibDir",
            "-L/usr/lib",
            "-lssl",
            "-lcrypto",
            "-luring",
            "-lpthread",
            "-ldl",
        )
    }
}

kotlin {
    // Ensure consistent JDK version across all developer machines and CI
    jvmToolchain(21)

    androidTarget {
        publishLibraryVariants("release")
        compilerOptions.jvmTarget.set(JvmTarget.JVM_17)
    }
    jvm {
        compilerOptions.jvmTarget.set(JvmTarget.JVM_1_8)
    }
    js {
        browser()
        nodejs()
    }

    // Apple targets with Network.framework zero-copy socket implementation
    if (isMacOS) {
        // macOS
        macosArm64 { configureSocketWrapperCinterop("macos") }
        macosX64 { configureSocketWrapperCinterop("macos") }

        // iOS
        iosArm64 { configureSocketWrapperCinterop("ios") }
        iosSimulatorArm64 { configureSocketWrapperCinterop("ios-simulator") }
        iosX64 { configureSocketWrapperCinterop("ios-simulator") }

        // tvOS
        tvosArm64 { configureSocketWrapperCinterop("tvos") }
        tvosSimulatorArm64 { configureSocketWrapperCinterop("tvos-simulator") }
        tvosX64 { configureSocketWrapperCinterop("tvos-simulator") }

        // watchOS (watchosArm32 not supported by buffer library)
        watchosArm64 { configureSocketWrapperCinterop("watchos") }
        watchosSimulatorArm64 { configureSocketWrapperCinterop("watchos-simulator") }
        watchosX64 { configureSocketWrapperCinterop("watchos-simulator") }
    }

    // Linux targets with io_uring async I/O and statically-linked OpenSSL
    linuxX64 {
        configureLinuxCinterop("x64")
    }
    // ARM64 requires either native ARM64 machine or cross-compilation headers
    // On x64, install: sudo apt install gcc-aarch64-linux-gnu libc6-dev-arm64-cross
    val canBuildArm64 = File("/usr/include/aarch64-linux-gnu").exists() ||
        File("/usr/aarch64-linux-gnu/include").exists() ||
        System.getProperty("os.arch") == "aarch64"
    if (canBuildArm64) {
        linuxArm64 {
            configureLinuxCinterop("arm64")
        }
    }

    applyDefaultHierarchyTemplate()
    sourceSets {
        commonMain.dependencies {
            implementation(libs.buffer)
            implementation(libs.kotlinx.coroutines.core)
        }
        androidMain.dependencies {
            implementation(libs.androidx.core.ktx)
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
            implementation(libs.kotlinx.coroutines.core)
            implementation(libs.kotlinx.coroutines.test)
        }
        jsMain.dependencies {
            implementation(libs.kotlin.js)
        }
        jsTest.dependencies {
            implementation(kotlin("test-js"))
            implementation(npm("tcp-port-used", "1.0.2"))
        }
        val androidInstrumentedTest by getting {
            dependencies {
                implementation(kotlin("test"))
            }
        }
        val androidUnitTest by getting {
            dependencies {
                implementation(kotlin("test"))
            }
        }
        val commonJvmMain by creating {
            dependsOn(commonMain.get())
        }
        jvmMain.get().dependsOn(commonJvmMain)
        androidMain.get().dependsOn(commonJvmMain)
        val commonJvmTest by creating {
            dependsOn(commonTest.get())
        }
        jvmTest.get().dependsOn(commonJvmTest)
        jvmTest.dependencies {
            implementation(libs.kotlinx.coroutines.debug)
        }
        androidUnitTest.dependsOn(commonJvmTest)

        // Add shared Apple implementation to all Apple target source sets
        if (isMacOS) {
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

        // Linux implementation using io_uring for async I/O
        val linuxNativeImplDir = file("src/linuxNativeImpl/kotlin")
        listOf(
            "linuxX64Main",
            "linuxArm64Main",
        ).forEach { sourceSetName ->
            findByName(sourceSetName)?.kotlin?.srcDir(linuxNativeImplDir)
        }
    }
}

android {
    compileSdk = 36
    sourceSets["main"].manifest.srcFile("src/androidMain/AndroidManifest.xml")
    defaultConfig {
        minSdk = 21
    }
    namespace = "$group.${rootProject.name}"

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    publishing {
        singleVariant("release") {
            withSourcesJar()
            withJavadocJar()
        }
    }
}

val javadocJar: TaskProvider<Jar> by tasks.registering(Jar::class) {
    archiveClassifier.set("javadoc")
}

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

val signingInMemoryKey = project.findProperty("signingInMemoryKey")
val signingInMemoryKeyPassword = project.findProperty("signingInMemoryKeyPassword")
val shouldSignAndPublish = isMainBranchGithub && signingInMemoryKey is String && signingInMemoryKeyPassword is String

if (shouldSignAndPublish) {
    signing {
        useInMemoryPgpKeys(
            signingInMemoryKey as String,
            signingInMemoryKeyPassword as String,
        )
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
    }
}

tasks.register("nextVersion") {
    println(getNextVersion(false))
}

dokka {
    dokkaSourceSets.configureEach {
        externalDocumentationLinks.register("kotlin-stdlib") {
            url("https://kotlinlang.org/api/latest/jvm/stdlib/")
            packageListUrl("https://kotlinlang.org/api/latest/jvm/stdlib/package-list")
        }
        externalDocumentationLinks.register("kotlinx-coroutines") {
            url("https://kotlinlang.org/api/kotlinx.coroutines/")
            packageListUrl("https://kotlinlang.org/api/kotlinx.coroutines/package-list")
        }
        reportUndocumented.set(false)
    }

    // Suppress duplicate Apple source sets - keep only macosArm64 as representative
    val suppressedAppleTargets =
        listOf(
            "macosX64",
            "iosArm64",
            "iosSimulatorArm64",
            "iosX64",
            "tvosArm64",
            "tvosSimulatorArm64",
            "tvosX64",
            "watchosArm64",
            "watchosSimulatorArm64",
            "watchosX64",
        )
    dokkaSourceSets {
        suppressedAppleTargets.forEach { target ->
            findByName("${target}Main")?.suppress?.set(true)
        }
    }
}

// Copy Dokka output to Docusaurus static directory
tasks.register<Copy>("copyDokkaToDocusaurus") {
    description = "Generate and copy API documentation to Docusaurus"
    group = "documentation"
    dependsOn("dokkaGenerateHtml")

    from(layout.buildDirectory.dir("dokka/html"))
    into(layout.projectDirectory.dir("docs/static/api"))
}
