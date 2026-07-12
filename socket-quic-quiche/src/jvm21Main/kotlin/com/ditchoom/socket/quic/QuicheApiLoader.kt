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
    // Prefer the self-contained libquiche.{so,dylib}: it bundles quiche + BoringSSL (Linux
    // whole-archives the external BoringSSL into the cdylib; macOS/Android via boring-crate), so it
    // dlopens cleanly on a clean box with zero ambient libs — the case that was broken. The JNI shim is
    // kept as a FALLBACK only for platforms/builds that ship a self-contained shim with no separate base
    // lib (e.g. a linux-arm64 classifier that bundles only the whole-archived shim). Where the base lib
    // is present it always wins, so a thin shim (which DT_NEEDEDs the base lib) is never reached via this
    // path. (Windows ships a single self-contained quiche_jni.dll; see quicheFfmResourceCandidates.)
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
 * Candidate classpath resources for the FFM native lib, in load-preference order. Non-Windows: the
 * self-contained libquiche.{so,dylib} first, then the JNI shim as a fallback for classifiers that ship
 * only a self-contained shim (no separate base lib). Windows: the single self-contained quiche_jni.dll.
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
        listOf("$dir/libquiche.$ext", "$dir/libquiche_jni.$ext")
    }
}
