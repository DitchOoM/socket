plugins {
    alias(libs.plugins.kotlin.multiplatform)
}

/*
 * :ios-probe — the iOS half of the real-handoff rig.
 *
 * NOT part of the published library and NOT wired into CI: it exists so a person can walk out of a
 * building with an iPhone and record what a QUIC connection does when the path underneath it dies.
 * It is a module in the repo rather than a scratch directory on purpose — the previous iOS probe
 * lived only as untracked files under /tmp and was destroyed by the system's temp cleaner, taking
 * the whole rig with it.
 *
 * Produces a dynamic framework the Xcode app in `iosApp/` links. Dynamic, not static, because the
 * quiche static archive reaches the app through this framework's link step.
 */
repositories {
    google()
    mavenCentral()
}

kotlin {
    iosArm64 {
        binaries.framework {
            baseName = "probe"
        }
    }

    sourceSets {
        iosArm64Main {
            dependencies {
                implementation(project(":socket-quic-default"))
                // The #447 pool-recovery verdict is one decision shared with the Android probe;
                // two copies of it is how the two drifted into passing a connection that never
                // recovered. :socket-testkit keeps kotlin.test out of its main source set, so it
                // links into a shipping probe app without dragging a test framework along.
                implementation(project(":socket-testkit"))
                implementation(libs.kotlinx.coroutines.core)
            }
        }
    }
}
