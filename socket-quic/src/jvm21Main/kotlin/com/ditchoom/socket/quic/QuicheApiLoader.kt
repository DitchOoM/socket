@file:Suppress("unused") // Loaded at runtime via multi-release JAR, shadows jvmMain version

package com.ditchoom.socket.quic

/**
 * JDK 21+ shadow of [loadQuicheApi] — returns [FfmQuicheApi] using Panama FFM downcalls.
 * Zero-copy: no JNI boundary, MemorySegment passed directly to quiche.
 *
 * The multi-release JAR ensures this replaces the JNI version on JDK 21+.
 */
fun loadQuicheApi(): QuicheApi {
    val libraryPath = resolveQuicheLibraryPath()
    return FfmQuicheApi.create(libraryPath)
}

private fun resolveQuicheLibraryPath(): String {
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

    // Extract from JAR resources to temp file (FFM needs a file path)
    val resourcePath = "META-INF/native/$os-$arch/libquiche.$ext"
    val stream =
        QuicheApi::class.java.classLoader?.getResourceAsStream(resourcePath)
            ?: error(
                "quiche native library not found: $resourcePath. " +
                    "Ensure it is built for $os-$arch.",
            )

    val tempDir =
        java.nio.file.Files
            .createTempDirectory("quiche-ffm")
            .toFile()
    tempDir.deleteOnExit()
    val tempFile = java.io.File(tempDir, "libquiche.$ext")
    tempFile.deleteOnExit()

    stream.use { input ->
        tempFile.outputStream().use { output ->
            input.copyTo(output)
        }
    }

    return tempFile.absolutePath
}
