package com.ditchoom.socket.quic

import java.io.File
import java.nio.channels.Channels
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.StandardOpenOption

/**
 * Loads quiche JNI native library.
 *
 * Android: `System.loadLibrary` (from APK).
 * JVM: extracts from JAR to temp dir, then `System.load`.
 */
internal object NativeLibLoader {
    @Volatile
    private var loaded = false

    @Synchronized
    fun load(
        @Suppress("UNUSED_PARAMETER") name: String,
    ) {
        if (loaded) return

        // Android
        try {
            // The shim DT_NEEDEDs libquiche.so; load it first so the shim resolves against the sibling
            // copy in the APK nativeLibraryDir (older Android linkers don't auto-resolve transitive
            // NEEDED from the app lib dir). No-op on a JVM (no such lib on java.library.path).
            try {
                System.loadLibrary("quiche")
            } catch (_: UnsatisfiedLinkError) {
            }
            System.loadLibrary("quiche_jni")
            loaded = true
            return
        } catch (_: UnsatisfiedLinkError) {
        }

        val os =
            System.getProperty("os.name").lowercase().let {
                when {
                    "linux" in it -> "linux"
                    "mac" in it || "darwin" in it -> "macos"
                    "windows" in it -> "windows"
                    else -> error("Unsupported OS: $it")
                }
            }
        val arch =
            System.getProperty("os.arch").lowercase().let {
                when (it) {
                    "amd64", "x86_64" -> "x64"
                    "aarch64", "arm64" -> "arm64"
                    else -> error("Unsupported arch: $it")
                }
            }
        val dir = Files.createTempDirectory("quiche").toFile().apply { deleteOnExit() }

        // The JNI shim dynamically links the self-contained libquiche.{so,dylib} (which bundles quiche +
        // BoringSSL). Extract that base lib into the SAME dir first so the shim's DT_NEEDED / @rpath
        // resolves it via RUNPATH $ORIGIN (Linux) / @loader_path (macOS). Windows ships a single
        // self-contained quiche_jni.dll with no separate base lib.
        if (os == "linux") extract(dir, "META-INF/native/linux-$arch/libquiche.so")
        if (os == "macos") extract(dir, "META-INF/native/macos-$arch/libquiche.dylib")

        val jni =
            when (os) {
                "linux" -> extract(dir, "META-INF/native/linux-$arch/libquiche_jni.so")
                "macos" -> extract(dir, "META-INF/native/macos-$arch/libquiche_jni.dylib")
                "windows" -> extract(dir, "META-INF/native/windows-$arch/quiche_jni.dll")
                else -> error("Unsupported OS: $os")
            }
        System.load(jni.absolutePath)
        loaded = true
    }

    private fun extract(
        dir: File,
        resource: String,
    ): File {
        val name = resource.substringAfterLast('/')
        val dest = File(dir, name).apply { deleteOnExit() }
        val stream =
            NativeLibLoader::class.java.classLoader?.getResourceAsStream(resource)
                ?: error("Not found in JAR: $resource")
        Channels.newChannel(stream).use { src ->
            FileChannel.open(dest.toPath(), StandardOpenOption.CREATE, StandardOpenOption.WRITE).use { dst ->
                dst.transferFrom(src, 0, Long.MAX_VALUE)
            }
        }
        return dest
    }
}
