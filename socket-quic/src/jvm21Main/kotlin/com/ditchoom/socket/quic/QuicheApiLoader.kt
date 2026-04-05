@file:Suppress("unused") // Loaded at runtime via multi-release JAR, shadows jvmMain version

package com.ditchoom.socket.quic

/**
 * JDK 21+ shadow of [loadQuicheApi] — returns [FfmQuicheApi] using Panama FFM downcalls.
 * Zero-copy: no JNI boundary, MemorySegment passed directly to quiche.
 *
 * The multi-release JAR ensures this replaces the JNI version on JDK 21+.
 */
fun loadQuicheApi(): QuicheApi {
    // Only use FFM if the pure quiche shared lib is available.
    // If only the JNI shim exists (libquiche_jni.so), fall back to JNI
    // to avoid double-loading the native library.
    val resourcePath = resolveQuicheResourcePath()
    val stream = QuicheApi::class.java.classLoader?.getResourceAsStream(resourcePath)
    if (stream != null) {
        stream.close()
        return try {
            FfmQuicheApi.create(extractToTemp(resourcePath))
        } catch (_: Throwable) {
            JniQuicheApi
        }
    }
    return JniQuicheApi
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

private fun resolveQuicheResourcePath(): String {
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
    val ext =
        when (os) {
            "linux" -> "so"
            "macos" -> "dylib"
            "windows" -> "dll"
            else -> "so"
        }
    return "META-INF/native/$os-$arch/libquiche.$ext"
}
