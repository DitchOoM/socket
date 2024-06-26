import groovy.util.Node
import groovy.xml.XmlParser
import org.apache.tools.ant.taskdefs.condition.Os
import java.net.URL

plugins {
    kotlin("multiplatform") version "2.0.0"
    kotlin("native.cocoapods") version "2.0.0"
    id("com.android.library") version "8.4.0"
    id("io.codearte.nexus-staging") version "0.30.0"
    `maven-publish`
    signing
    id("org.jlleitschuh.gradle.ktlint") version "12.1.1"
}
val isRunningOnGithub = System.getenv("GITHUB_REPOSITORY")?.isNotBlank() == true
val isMainBranchGithub = System.getenv("GITHUB_REF") == "refs/heads/main"
val isMacOS = Os.isFamily(Os.FAMILY_MAC)
val loadAllPlatforms = !isRunningOnGithub || (isMacOS && isMainBranchGithub) || !isMacOS
val libraryVersionPrefix: String by project
group = "com.ditchoom"
val libraryVersion = getNextVersion().toString()
println(
    "Version: ${libraryVersion}\nisRunningOnGithub: $isRunningOnGithub\nisMainBranchGithub: $isMainBranchGithub\n" +
        "OS:$isMacOS\nLoad All Platforms: $loadAllPlatforms",
)

repositories {
    google()
    mavenCentral()
    maven { setUrl("https://maven.pkg.jetbrains.space/kotlin/p/kotlin/kotlin-js-wrappers/") }
}

kotlin {
    jvmToolchain(19)
    androidTarget {
        publishLibraryVariants("release")
    }
    jvm()
    js {
        browser()
        nodejs()
    }
    macosX64()
    macosArm64()
    iosArm64()
    iosX64()
    watchosArm64()
    watchosX64()
    tvosArm64()
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
            implementation("com.ditchoom:buffer:1.4.1")
            implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.8.1")
        }
        androidMain.dependencies {
            implementation("androidx.core:core-ktx:1.13.1")
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
            implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.8.1")
            implementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.8.1")
        }
        jsMain.dependencies {
            implementation("org.jetbrains.kotlin-wrappers:kotlin-js:1.0.0-pre.746")
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
            implementation("org.jetbrains.kotlinx:kotlinx-coroutines-debug:1.8.1")
        }
        androidUnitTest.dependsOn(commonJvmTest)
    }
}

android {
    compileSdk = 34
    sourceSets["main"].manifest.srcFile("src/androidMain/AndroidManifest.xml")
    defaultConfig {
        minSdk = 19
    }
    namespace = "$group.${rootProject.name}"
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

if (isRunningOnGithub) {
    if (isMainBranchGithub) {
        signing {
            useInMemoryPgpKeys(
                "56F1A973",
                System.getenv("GPG_SECRET"),
                System.getenv("GPG_SIGNING_PASSWORD"),
            )
            sign(publishing.publications)
        }
    }

    val ossUser = System.getenv("SONATYPE_NEXUS_USERNAME")
    val ossPassword = System.getenv("SONATYPE_NEXUS_PASSWORD")

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

    project.group = publishedGroupId
    project.version = libraryVersion

    publishing {
        publications.withType(MavenPublication::class) {
            groupId = publishedGroupId
            version = libraryVersion

            artifact(tasks["javadocJar"])

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

        repositories {
            val repositoryId = System.getenv("SONATYPE_REPOSITORY_ID")
            maven("https://oss.sonatype.org/service/local/staging/deployByRepositoryId/$repositoryId/") {
                name = "sonatype"
                credentials {
                    username = ossUser
                    password = ossPassword
                }
            }
        }
    }

    nexusStaging {
        username = ossUser
        password = ossPassword
        packageGroup = publishedGroupId
    }
}

ktlint {
    verbose.set(true)
    outputToConsole.set(true)
}

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
        if (snapshot) {
            "$major.$minor.$patch-SNAPSHOT"
        } else {
            "$major.$minor.$patch"
        }
}
private var latestVersion: Version? = Version(0u, 0u, 0u, true)

@Suppress("UNCHECKED_CAST")
fun getLatestVersion(): Version {
    val latestVersion = latestVersion
    if (latestVersion != null && !latestVersion.isVersionZero()) {
        return latestVersion
    }
    val xml = URL("https://repo1.maven.org/maven2/com/ditchoom/${rootProject.name}/maven-metadata.xml").readText()
    val versioning = XmlParser().parseText(xml)["versioning"] as List<Node>
    val latestStringList = versioning.first()["latest"] as List<Node>
    val result = Version((latestStringList.first().value() as List<*>).first().toString(), false)
    this.latestVersion = result
    return result
}

fun getNextVersion(snapshot: Boolean = !isRunningOnGithub): Version {
    var v = getLatestVersion()
    if (snapshot) {
        v = v.snapshot()
    }
    if (project.hasProperty("incrementMajor") && project.property("incrementMajor") == "true") {
        return v.incrementMajor()
    } else if (project.hasProperty("incrementMinor") && project.property("incrementMinor") == "true") {
        return v.incrementMinor()
    }
    return v.incrementPatch()
}

tasks.create("nextVersion") {
    println(getNextVersion())
}

val signingTasks = tasks.withType<Sign>()
tasks.withType<AbstractPublishToMaven>().configureEach {
    dependsOn(signingTasks)
}
