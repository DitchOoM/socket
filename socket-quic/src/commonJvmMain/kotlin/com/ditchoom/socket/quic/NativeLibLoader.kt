package com.ditchoom.socket.quic

import java.io.File
import java.nio.file.Files

/**
 * Loads platform-specific native libraries bundled in JAR resources.
 * Libraries are at `META-INF/native/{os}-{arch}/lib{name}.{ext}`.
 */
internal object NativeLibLoader {
    private val loaded = mutableSetOf<String>()

    @Synchronized
    fun load(name: String) {
        if (name in loaded) return

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

        val resourcePath = "META-INF/native/$os-$arch/lib$name.$ext"
        val stream =
            NativeLibLoader::class.java.classLoader?.getResourceAsStream(resourcePath)
                ?: error(
                    "Native library not found: $resourcePath. " +
                        "Ensure the quiche native library is built for $os-$arch.",
                )

        val tempDir = Files.createTempDirectory("quiche-native").toFile()
        tempDir.deleteOnExit()
        val tempFile = File(tempDir, "lib$name.$ext")
        tempFile.deleteOnExit()

        stream.use { input ->
            tempFile.outputStream().use { output ->
                input.copyTo(output)
            }
        }

        System.load(tempFile.absolutePath)
        loaded.add(name)
    }
}
