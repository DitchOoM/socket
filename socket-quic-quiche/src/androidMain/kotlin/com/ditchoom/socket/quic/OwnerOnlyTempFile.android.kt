package com.ditchoom.socket.quic

import java.io.File

/**
 * Android's `java.io.tmpdir` is the app's private cache directory, which no other app can enter, so the
 * file is private to this app from creation (`java.nio.file` needs API 26; minSdk is 24).
 */
internal actual fun createOwnerOnlyTempFile(prefix: String): File = File.createTempFile(prefix, ".pem")
