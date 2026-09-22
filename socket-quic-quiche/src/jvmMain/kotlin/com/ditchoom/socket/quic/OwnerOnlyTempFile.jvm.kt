package com.ditchoom.socket.quic

import java.io.File
import java.nio.file.FileSystems
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermissions

/**
 * `Files.createTempFile` with `rw-------` set at creation on POSIX; elsewhere (Windows) the file inherits
 * the per-user temp directory's ACL.
 */
internal actual fun createOwnerOnlyTempFile(prefix: String): File =
    if ("posix" in FileSystems.getDefault().supportedFileAttributeViews()) {
        val ownerOnly = PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------"))
        Files.createTempFile(prefix, ".pem", ownerOnly).toFile()
    } else {
        Files.createTempFile(prefix, ".pem").toFile()
    }
