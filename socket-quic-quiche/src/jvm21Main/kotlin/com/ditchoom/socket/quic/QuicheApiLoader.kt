@file:Suppress("unused") // Loaded at runtime via multi-release JAR, shadows jvmMain version

package com.ditchoom.socket.quic

/**
 * JDK 21+ shadow of [loadQuicheApi] — returns [FfmQuicheApi] using Panama FFM downcalls.
 * Zero-copy: no JNI boundary, MemorySegment passed directly to quiche.
 *
 * The multi-release JAR ensures this replaces the JNI version on JDK 21+, so on
 * JDK 21+ the backend is **deterministically FFM**. There is intentionally no
 * silent fallback to JNI: a native lib that can't be loaded via FFM is a
 * packaging bug, and degrading to JNI is exactly what masked FFM being broken on
 * Linux (it loaded the pure `libquiche.so`, which dlopens with unresolved
 * BoringSSL symbols, then silently dropped to JNI). We fail loudly instead.
 *
 * JNI is reached only where it's genuinely the only option — JDK < 21, served by
 * the base `commonJvmMain` loader this shadows — or when a test deliberately
 * selects it by keeping this FFM loader off the classpath (`-PquicheJvmBackend`).
 */
fun loadQuicheApi(): QuicheApi = ffmQuicheApi

/**
 * Process-wide singleton. `loadQuicheApi()` is called per connection/server; loading per call
 * mapped a fresh ~5 MB libquiche copy each time AND made every copy GC-unloadable via its
 * auto-arena — the root cause of the build-linux `:socket-http3:jvmTest` SIGSEGV (BoringSSL
 * pthread TLS destructors outliving a dlclosed copy; see QuicheApiLifecycleTest). One extraction,
 * one dlopen, held for the process lifetime.
 */
private val ffmQuicheApi: QuicheApi by lazy { loadFfmQuicheApi() }

private fun loadFfmQuicheApi(): QuicheApi {
    val classLoader =
        QuicheApi::class.java.classLoader
            ?: error("Cannot load quiche FFM backend: no class loader for QuicheApi")
    // Load the self-contained libquiche.{so,dylib}: it bundles quiche + BoringSSL (Linux whole-archives
    // the external BoringSSL into the cdylib; macOS/Android via boring-crate), so it dlopens cleanly on a
    // clean box with zero ambient libs — the case that was broken. The JNI shim is NOT a candidate here:
    // every non-Windows classifier ships this self-contained base lib, and the shim is now THIN
    // (DT_NEEDED=libquiche), so it could never load standalone via FFM anyway — it exists only for the
    // JDK < 21 JNI path. Windows is the sole exception: it ships a single self-contained quiche_jni.dll
    // (no separate base lib), which is its FFM candidate — see quicheFfmResourceCandidates.
    val failures = mutableListOf<String>()
    for (resourcePath in quicheFfmResourceCandidates()) {
        val stream = classLoader.getResourceAsStream(resourcePath)
        if (stream == null) {
            failures += "$resourcePath (not on classpath)"
            continue
        }
        stream.close()
        try {
            return maybeGuardRecvInfo(FfmQuicheApi.create(extractToTemp(resourcePath)))
        } catch (t: Throwable) {
            failures += "$resourcePath (${t.message})"
        }
    }
    error(
        "FFM is the required quiche backend on JDK 21+ but no native lib could be " +
            "loaded. This is a packaging bug — fix the bundled natives rather than " +
            "expecting a JNI fallback. Tried: ${failures.joinToString("; ")}",
    )
}

private fun extractToTemp(resourcePath: String): String {
    val stream = QuicheApi::class.java.classLoader!!.getResourceAsStream(resourcePath)!!
    val tempDir =
        java.nio.file.Files
            .createTempDirectory("quiche-ffm")
            .toFile()
    tempDir.deleteOnExit()
    val ext = resourcePath.substringAfterLast('.')
    val tempFile = java.io.File(tempDir, "libquiche.$ext")
    tempFile.deleteOnExit()
    stream.use { input -> tempFile.outputStream().use { output -> input.copyTo(output) } }
    return tempFile.absolutePath
}

/**
 * The FFM native lib for this platform. Non-Windows: the self-contained libquiche.{so,dylib}. Windows:
 * the single self-contained quiche_jni.dll (it has no separate base lib). The JNI shim is not an FFM
 * candidate on non-Windows — it's thin (DT_NEEDEDs the base lib) and serves only the JDK < 21 JNI path.
 */
private fun quicheFfmResourceCandidates(): List<String> {
    val osName = System.getProperty("os.name").lowercase()
    val archName = System.getProperty("os.arch").lowercase()
    val os =
        when {
            osName.contains("linux") -> "linux"
            osName.contains("mac") || osName.contains("darwin") -> "macos"
            osName.contains("windows") -> "windows"
            else -> error("Unsupported OS: $osName")
        }
    val arch =
        when {
            archName == "amd64" || archName == "x86_64" -> "x64"
            archName == "aarch64" || archName == "arm64" -> "arm64"
            else -> error("Unsupported architecture: $archName")
        }
    val dir = "META-INF/native/$os-$arch"
    return if (os == "windows") {
        // Windows ships only the self-contained quiche_jni.dll (no separate quiche.dll).
        listOf("$dir/quiche_jni.dll")
    } else {
        val ext = if (os == "macos") "dylib" else "so"
        listOf("$dir/libquiche.$ext")
    }
}
