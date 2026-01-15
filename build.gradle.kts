import com.vanniktech.maven.publish.SonatypeHost
import groovy.util.Node
import groovy.xml.XmlParser
import org.apache.tools.ant.taskdefs.condition.Os
import java.net.URL

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.kotlin.cocoapods)
    alias(libs.plugins.android.library)
    alias(libs.plugins.maven.publish)
    alias(libs.plugins.ktlint)
}

val isRunningOnGithub = System.getenv("GITHUB_REPOSITORY")?.isNotBlank() == true
val isMainBranchGithub = System.getenv("GITHUB_REF") == "refs/heads/main"
val isMacOS = Os.isFamily(Os.FAMILY_MAC)
val isLinux = Os.isFamily(Os.FAMILY_UNIX) && !isMacOS

group = "com.ditchoom"
val libraryVersion = getNextVersion(!isRunningOnGithub).toString()
version = libraryVersion

println(
    "Version: $libraryVersion\nisRunningOnGithub: $isRunningOnGithub\nisMainBranchGithub: $isMainBranchGithub\n" +
        "OS: macOS=$isMacOS linux=$isLinux",
)

repositories {
    google()
    mavenCentral()
}

kotlin {
    jvmToolchain(21)

    androidTarget {
        publishLibraryVariants("release")
    }
    jvm()
    js {
        browser()
        nodejs()
    }

    // WASM target
    @OptIn(org.jetbrains.kotlin.gradle.ExperimentalWasmDsl::class)
    wasmJs {
        browser()
        nodejs()
    }

    // Apple targets
    macosX64()
    macosArm64()
    iosArm64()
    iosX64()
    iosSimulatorArm64()
    watchosArm64()
    watchosX64()
    watchosSimulatorArm64()
    tvosArm64()
    tvosX64()
    tvosSimulatorArm64()

    // Linux targets
    linuxX64()
    linuxArm64()

    applyDefaultHierarchyTemplate()

    cocoapods {
        ios.deploymentTarget = "13.0"
        osx.deploymentTarget = "11.0"
        watchos.deploymentTarget = "6.0"
        tvos.deploymentTarget = "13.0"
        pod("SocketWrapper") {
            source =
                git("https://github.com/DitchOoM/apple-socket-wrapper.git") {
                    tag = "0.1.3"
                }
            extraOpts += listOf("-compiler-option", "-fmodules")
        }
        version = "0.1.3"
    }

    sourceSets {
        commonMain.dependencies {
            api(libs.buffer)
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
            implementation(libs.kotlin.wrappers.js)
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

        // Linux source sets
        val linuxMain by creating {
            dependsOn(commonMain.get())
        }
        linuxX64Main.get().dependsOn(linuxMain)
        linuxArm64Main.get().dependsOn(linuxMain)

        val linuxTest by creating {
            dependsOn(commonTest.get())
        }
        linuxX64Test.get().dependsOn(linuxTest)
        linuxArm64Test.get().dependsOn(linuxTest)
    }
}

android {
    compileSdk = 35
    sourceSets["main"].manifest.srcFile("src/androidMain/AndroidManifest.xml")
    defaultConfig {
        minSdk = 21
    }
    namespace = "$group.${rootProject.name}"
    publishing {
        singleVariant("release") {
            withSourcesJar()
            withJavadocJar()
        }
    }
}

ktlint {
    verbose.set(true)
    outputToConsole.set(true)
}

// Maven Publishing Configuration
val publishedGroupId: String by project
val libraryName: String by project
val libraryDescription: String by project
val siteUrl: String by project
val gitUrl: String by project
val licenseName: String by project
val licenseUrl: String by project
val developerOrg: String by project
val developerName: String by project
val developerEmail: String by project
val developerId: String by project

val signingKey = System.getenv("GPG_SECRET")
val signingPassword = System.getenv("GPG_SIGNING_PASSWORD")
val shouldSignAndPublish = isMainBranchGithub && !signingKey.isNullOrBlank() && !signingPassword.isNullOrBlank()

mavenPublishing {
    publishToMavenCentral(SonatypeHost.CENTRAL_PORTAL)

    if (shouldSignAndPublish) {
        signAllPublications()
    }

    coordinates(publishedGroupId, rootProject.name, libraryVersion)

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

// Version Management
class Version(val major: UInt, val minor: UInt, val patch: UInt, val snapshot: Boolean) {
    constructor(string: String, snapshot: Boolean) :
        this(
            string.split('.')[0].toUInt(),
            string.split('.')[1].toUInt(),
            string.split('.')[2].toUInt(),
            snapshot,
        )

    fun incrementMajor() = Version(major + 1u, 0u, 0u, snapshot)
    fun incrementMinor() = Version(major, minor + 1u, 0u, snapshot)
    fun incrementPatch() = Version(major, minor, patch + 1u, snapshot)
    fun snapshot() = Version(major, minor, patch, true)
    fun isVersionZero() = major == 0u && minor == 0u && patch == 0u

    override fun toString(): String =
        if (snapshot) "$major.$minor.$patch-SNAPSHOT" else "$major.$minor.$patch"
}

private var latestVersion: Version? = null

@Suppress("UNCHECKED_CAST")
fun getLatestVersion(): Version {
    latestVersion?.let { if (!it.isVersionZero()) return it }
    return try {
        val xml = URL("https://repo1.maven.org/maven2/com/ditchoom/${rootProject.name}/maven-metadata.xml").readText()
        val versioning = XmlParser().parseText(xml)["versioning"] as List<Node>
        val latestStringList = versioning.first()["latest"] as List<Node>
        Version((latestStringList.first().value() as List<*>).first().toString(), false)
            .also { latestVersion = it }
    } catch (e: Exception) {
        println("Warning: Could not fetch latest version: ${e.message}")
        Version(1u, 2u, 0u, true).also { latestVersion = it }
    }
}

fun getNextVersion(snapshot: Boolean): Version {
    var v = getLatestVersion()
    if (snapshot) v = v.snapshot()
    return when {
        project.hasProperty("incrementMajor") && project.property("incrementMajor") == "true" -> v.incrementMajor()
        project.hasProperty("incrementMinor") && project.property("incrementMinor") == "true" -> v.incrementMinor()
        else -> v.incrementPatch()
    }
}

tasks.register("nextVersion") {
    doLast { println(getNextVersion(!isRunningOnGithub)) }
}

tasks.register("printVersion") {
    doLast { println(libraryVersion) }
}
