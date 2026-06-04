plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.ktlint)
}

group = "com.ditchoom"

repositories {
    mavenLocal()
    mavenCentral()
}

// HTTP/3 layer on top of :socket-quic — the frame + QPACK codecs, the stream reader,
// and the Http3Connection client. Pure Kotlin (no platform actuals of its own); it
// rides on :socket-quic's multiplatform QuicScope/QuicByteStream.
//
// Targets are jvm + js while the layer is validated — this preserves the jvmTest +
// jsNodeTest coverage the codecs already had inside :socket-quic. Expanding to the
// full :socket-quic target matrix (linuxX64, Apple, Android, wasmJs) + publishing is
// a tracked follow-up (see HANDOFF.md). KSP-for-main (for future declarative codecs)
// is likewise deferred until a @ProtocolMessage codec actually exists; the wiring to
// copy is on origin/feature/socket-http3.
kotlin {
    jvmToolchain(21)
    jvm()
    js {
        browser()
        nodejs()
    }

    applyDefaultHierarchyTemplate()
    sourceSets {
        commonMain.dependencies {
            api(project(":socket-quic"))
            api(libs.buffer)
            api(libs.buffer.flow)
            api(libs.buffer.codec)
            implementation(libs.kotlinx.coroutines.core)
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
            implementation(libs.kotlinx.coroutines.core)
            implementation(libs.kotlinx.coroutines.test)
        }
    }
}

// The live HTTP/3 interop test (Http3PublicEndpointInteropTests) needs :socket-quic's quiche
// native lib on the JVM test classpath — on JDK 21+ FFM loads `libquiche.so` as a classloader
// resource under META-INF/native/<platform>/. :socket-quic stages those natives onto its own
// jvmTest classpath but doesn't export them to dependents, so reuse the same staged dir here and
// depend on its staging task. (Scripted unit tests don't need this — only the gated interop GET,
// which otherwise skips with "no native lib could be loaded".)
afterEvaluate {
    val quicProject = project(":socket-quic")
    val stagedNatives = quicProject.layout.buildDirectory.dir("generated-native-resources/jvmMain")
    tasks.named<org.gradle.api.tasks.testing.Test>("jvmTest").configure {
        dependsOn(quicProject.tasks.named("stageQuicheNativeResources"))
        classpath += files(stagedNatives)
    }
}
