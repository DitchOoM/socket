import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.plugin.mpp.KotlinNativeTarget

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.library)
    alias(libs.plugins.ktlint)
    alias(libs.plugins.maven.publish)
    signing
}

apply(from = "gradle/setup.gradle.kts")

group = "com.ditchoom"
val isRunningOnGithub = System.getenv("GITHUB_REPOSITORY")?.isNotBlank() == true
val isMainBranchGithub = System.getenv("GITHUB_REF") == "refs/heads/main"
val isMacOS = System.getProperty("os.name").lowercase().contains("mac")
val isLinux = System.getProperty("os.name").lowercase().contains("linux")

@Suppress("UNCHECKED_CAST")
val getNextVersion = project.extra["getNextVersion"] as (Boolean) -> Any
project.version = getNextVersion(!isRunningOnGithub).toString()

println("Version: ${project.version}\nisRunningOnGithub: $isRunningOnGithub\nisMainBranchGithub: $isMainBranchGithub")

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
            extraOpts("-libraryPath", libPath)
            tasks.named(interopProcessingTaskName) {
                dependsOn(buildSwiftTask)
            }
        }
    }
    compilations["main"].compileTaskProvider.configure {
        dependsOn(buildSwiftTask)
    }
    // Link the Swift static library for all binaries (main and test)
    binaries.all {
        // Link our Swift wrapper library
        linkerOpts("-L$libPath", "-lSocketWrapper")
        // Link Swift runtime libraries (platform-specific)
        linkerOpts(
            "-L/Applications/Xcode.app/Contents/Developer/Toolchains/XcodeDefault.xctoolchain/usr/lib/swift/$swiftPlatform",
            "-L/usr/lib/swift",
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

    // Linux targets with io_uring socket implementation
    // Always register targets so source sets exist for ktlint, but only configure
    // cinterop on Linux (since it requires Linux headers like io_uring and OpenSSL)
    fun KotlinNativeTarget.configureLinuxSocketsCinterop() {
        if (isLinux) {
            compilations["main"].cinterops {
                create("LinuxSockets") {
                    defFile("src/nativeInterop/cinterop/LinuxSockets.def")
                }
            }
        }
    }

    linuxX64 { configureLinuxSocketsCinterop() }
    linuxArm64 { configureLinuxSocketsCinterop() }

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

        // Add shared Linux implementation to all Linux target source sets
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
