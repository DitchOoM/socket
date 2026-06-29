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
fun loadQuicheApi(): QuicheApi {
    val classLoader =
        QuicheApi::class.java.classLoader
            ?: error("Cannot load quiche FFM backend: no class loader for QuicheApi")
    // Try each candidate native lib via FFM, preferring the self-contained JNI
    // shim (libquiche_jni.*). FFM only needs quiche's C API symbols, which the
    // shim re-exports (it whole-archives libquiche.a + BoringSSL), so it loads
    // cleanly via dlopen on every platform we ship. The "pure" libquiche.* is
    // kept as a secondary candidate for platforms where it *is* self-contained
    // (e.g. macOS boringssl-vendored); on Linux it is non-self-contained and is
    // simply skipped when its load fails.
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
 * Candidate classpath resources for the FFM native lib, in load-preference order:
 * the self-contained JNI shim first (always loads), then the pure quiche lib.
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
        listOf("$dir/libquiche_jni.$ext", "$dir/libquiche.$ext")
    }
}
