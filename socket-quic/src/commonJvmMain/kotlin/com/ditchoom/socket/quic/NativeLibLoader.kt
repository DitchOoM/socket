package com.ditchoom.socket.quic

import java.io.File
import java.nio.file.Files

/**
 * Loads the quiche JNI native library.
 *
 * - **Android**: Uses [System.loadLibrary] which finds the `.so` in the APK's `lib/` dir
 *   (populated from `src/androidMain/jniLibs/{abi}/`).
 * - **JVM**: Extracts from `META-INF/native/{os}-{arch}/` JAR resources to a temp dir
 *   and loads via [System.load].
 */
internal object NativeLibLoader {
    private val loaded = mutableSetOf<String>()

    @Synchronized
    fun load(name: String) {
        if (name in loaded) return

        // Try Android path first (System.loadLibrary uses the APK's lib/ directory)
        try {
            System.loadLibrary(name)
            loaded.add(name)
            return
        } catch (_: UnsatisfiedLinkError) {
            // Not on Android or lib not in APK — fall through to JVM path
        }

        // JVM path: extract from JAR resources
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
