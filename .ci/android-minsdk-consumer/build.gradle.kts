// The minSdk regression guard: a minimal Android APPLICATION with minSdk 24 that depends on every
// library module an Android user consumes, as PUBLISHED. Assembling it runs the manifest merger over
// every AAR in the graph, our own and the transitive ones, so any dependency that declares a higher
// minSdk fails the build with "uses-sdk:minSdkVersion 24 cannot be smaller than version N declared in
// library ...".
//
// That is not hypothetical: :socket-quic-quiche declared minSdk 24 but depended on buffer-crypto, whose
// Android artifact declares minSdk 28, so every app below 28 that added the QUIC module failed to build.
// Nothing in the library builds can see this: a library's own manifest merge does not check its
// dependencies' minSdk against its own, only an application's does.
//
// Assemble only: no code, no emulator. The app has no sources on purpose; what it checks is the
// dependency graph (manifest merge, AAR metadata, dexing at minSdk 24), not an API.
//
// Parameterised like .ci/consumer-smoke: -PsocketVersion + -PmavenRepoPath point it at the merged
// maven-local repo in CI; without -PmavenRepoPath it reads mavenLocal().
plugins {
    id("com.android.application")
}

val socketVersion = findProperty("socketVersion") as String? ?: error("pass -PsocketVersion=<version under test>")
val mavenRepoPath = findProperty("mavenRepoPath") as String?

// The floor this check holds the published modules to. Raising it hides exactly the regression this
// build exists to catch; it moves only when the libraries' own minSdk moves.
val consumerMinSdk = 24

repositories {
    if (mavenRepoPath != null) {
        maven(url = uri(file(mavenRepoPath)))
    } else {
        mavenLocal()
    }
    google()
    mavenCentral()
}

android {
    namespace = "com.ditchoom.socket.minsdkconsumer"
    // Same compileSdk as the library modules, so checkDebugAarMetadata accepts their AARs.
    compileSdk = 37
    defaultConfig {
        applicationId = "com.ditchoom.socket.minsdkconsumer"
        minSdk = consumerMinSdk
        targetSdk = 37
        versionCode = 1
        versionName = "1"
    }
}

dependencies {
    for (module in listOf(
        "socket",
        "socket-quic",
        "socket-quic-quiche",
        "socket-http3",
        "socket-webtransport",
        "socket-udp",
        "network-monitor",
    )) {
        implementation("com.ditchoom:$module:$socketVersion")
    }
}
