import org.apache.tools.ant.taskdefs.condition.Os

plugins {
//    id("dev.petuska.npm.publish") version "3.4.1"
    kotlin("multiplatform") version "1.9.22"
    kotlin("native.cocoapods") version "1.9.22"
    id("com.android.library")
    id("io.codearte.nexus-staging") version "0.30.0"
    `maven-publish`
    signing
    id("org.jlleitschuh.gradle.ktlint") version "11.5.1"
    id("org.jlleitschuh.gradle.ktlint-idea") version "11.5.1"
}
val isRunningOnGithub = System.getenv("GITHUB_REPOSITORY")?.isNotBlank() == true
val isMainBranchGithub = System.getenv("GITHUB_REF") == "refs/heads/main"
val isMacOS = Os.isFamily(Os.FAMILY_MAC)
val loadAllPlatforms = !isRunningOnGithub || (isMacOS && isMainBranchGithub) || !isMacOS

println(
    "isRunningOnGithub: $isRunningOnGithub isMainBranchGithub: $isMainBranchGithub OS:$isMacOS " +
        "Load All Platforms: $loadAllPlatforms"
)

val libraryVersionPrefix: String by project
group = "com.ditchoom"
version = "$libraryVersionPrefix.0-SNAPSHOT"
val libraryVersion = if (System.getenv("GITHUB_RUN_NUMBER") != null) {
    "$libraryVersionPrefix${(Integer.parseInt(System.getenv("GITHUB_RUN_NUMBER")) - 16)}"
} else {
    "${libraryVersionPrefix}0-SNAPSHOT"
}

repositories {
    google()
    mavenCentral()
}

kotlin {
    androidTarget {
        publishLibraryVariants("release")
    }
    if (loadAllPlatforms) {
        jvm {
            compilations.all {
                kotlinOptions.jvmTarget = "1.8"
            }
            testRuns["test"].executionTask.configure {
                useJUnit()
            }
        }
        js {
            browser()
            nodejs()
        }
    }
    macosArm64()
    macosX64()
    watchos()
    watchosSimulatorArm64()
    tvos()
    ios()
    iosSimulatorArm64()

    cocoapods {
        ios.deploymentTarget = "13.0"
        osx.deploymentTarget = "11.0"
        watchos.deploymentTarget = "6.0"
        tvos.deploymentTarget = "13.0"
        pod("SocketWrapper") {
            source = git("https://github.com/DitchOoM/apple-socket-wrapper.git") {
                tag = "0.1.3"
            }
            extraOpts += listOf("-compiler-option", "-fmodules")
//            source = path(project.file("./../SocketWrapper/"))
        }
    }
    sourceSets {
        val commonMain by getting {
            dependencies {
                implementation("com.ditchoom:buffer:1.3.37")
                implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.7.3")
            }
        }
        val commonTest by getting {
            dependencies {
                implementation(kotlin("test"))
            }
        }
        if (loadAllPlatforms) {
            val jvmMain by getting {
                kotlin.srcDir("src/commonJvmMain/kotlin")
            }
            val jvmTest by getting {
                kotlin.srcDir("src/commonJvmTest/kotlin")
            }
            val jsMain by getting {
                dependencies {
                    implementation("org.jetbrains.kotlin-wrappers:kotlin-js:1.0.0-pre.682")
                }
            }
            val jsTest by getting {
                dependencies {
                    implementation(kotlin("test-js"))
                    implementation(npm("tcp-port-used", "1.0.2"))
                }
            }
        }
        val macosX64Main by getting
        val macosX64Test by getting
        val macosArm64Main by getting
        val macosArm64Test by getting
        val iosMain by getting
        val iosTest by getting
        val iosSimulatorArm64Main by getting
        val iosSimulatorArm64Test by getting
        val watchosMain by getting
        val watchosTest by getting
        val watchosSimulatorArm64Main by getting
        val watchosSimulatorArm64Test by getting
        val tvosMain by getting
        val tvosTest by getting

        val appleMain by sourceSets.creating {
            dependsOn(commonMain)
            kotlin.srcDir("src/appleMain/kotlin")
            macosX64Main.dependsOn(this)
            macosArm64Main.dependsOn(this)
            iosMain.dependsOn(this)
            iosSimulatorArm64Main.dependsOn(this)
            tvosMain.dependsOn(this)
            watchosMain.dependsOn(this)
            watchosSimulatorArm64Main.dependsOn(this)
        }

        val appleTest by sourceSets.creating {
            dependsOn(commonTest)
            kotlin.srcDir("src/appleTest/kotlin")
            macosX64Test.dependsOn(this)
            macosArm64Test.dependsOn(this)
            iosTest.dependsOn(this)
            iosSimulatorArm64Test.dependsOn(this)
            tvosTest.dependsOn(this)
            watchosTest.dependsOn(this)
            watchosSimulatorArm64Test.dependsOn(this)
        }

        val androidMain by getting {
            kotlin.srcDir("src/commonJvmMain/kotlin")
            dependsOn(commonMain)
        }
        val androidUnitTest by getting {
            kotlin.srcDir("src/commonJvmTest/kotlin")
            dependsOn(commonTest)
        }
        val androidInstrumentedTest by getting {
            dependsOn(commonTest)
            kotlin.srcDir("src/commonJvmTest/kotlin")
            kotlin.srcDir("src/commonTest/kotlin")
        }

        all {
            languageSettings.optIn("kotlinx.cinterop.ExperimentalForeignApi")
            languageSettings.optIn("kotlinx.cinterop.BetaInteropApi")
            languageSettings.optIn("kotlinx.cinterop.UnsafeNumber")
        }
    }
}

android {
    compileSdk = 34
    sourceSets["main"].manifest.srcFile("src/androidMain/AndroidManifest.xml")
    defaultConfig {
        minSdk = 18
        targetSdk = 34
    }
    lint {
        abortOnError = false
    }
    namespace = "$group.${rootProject.name}"
}
dependencies {
    implementation("androidx.core:core-ktx:1.12.0")
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
                System.getenv("GPG_SIGNING_PASSWORD")
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
    disabledRules.set(setOf("no-wildcard-imports"))
    verbose.set(true)
    outputToConsole.set(true)
}

// if (System.getenv("NPM_ACCESS_TOKEN") != null) {
//    npmPublishing {
//        repositories {
//            repository("npmjs") {
//                registry = uri("https://registry.npmjs.org")
//                authToken = System.getenv("NPM_ACCESS_TOKEN")
//            }
//        }
//        readme = file("Readme.md")
//        organization = "ditchoom"
//        access = PUBLIC
//        bundleKotlinDependencies = true
//        version = libraryVersion
//        dry = !"refs/heads/main".equals(System.getenv("GITHUB_REF"), ignoreCase = true)
//        publications {
//            val js by getting {
//                moduleName = "socket-kt"
//            }
//        }
//    }
// }
