pluginManagement {
    repositories {
        google()
        gradlePluginPortal()
        mavenCentral()
    }
    resolutionStrategy {
        eachPlugin {
            if (requested.id.namespace == "com.android" || requested.id.name == "kotlin-android-extensions") {
                useModule("com.android.tools.build:gradle:4.1.2")
            }
        }
    }
}
rootProject.name = "socket"
plugins {
    id("com.gradle.enterprise") version("3.10.3")
}
gradleEnterprise {
    buildScan {
        termsOfServiceUrl = "https://gradle.com/terms-of-service"
        termsOfServiceAgree = "yes"
        publishOnFailureIf(!System.getenv("GITHUB_RUN_NUMBER").isNullOrEmpty())
    }
}