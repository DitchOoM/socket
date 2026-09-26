package com.ditchoom.socket.quic

import java.io.File
import java.nio.file.FileSystems
import java.nio.file.Files
import java.nio.file.attribute.AclEntry
import java.nio.file.attribute.AclEntryPermission
import java.nio.file.attribute.AclEntryType
import java.nio.file.attribute.AclFileAttributeView
import java.nio.file.attribute.PosixFilePermissions

/**
 * An empty `.pem` temp file only its owner can read or write: `rw-------` set at creation on POSIX, and on
 * an ACL file system (Windows) an ACL holding one entry that grants the owner everything, replacing the
 * entries inherited from the temp directory before any byte is written.
 */
internal actual fun createOwnerOnlyTempFile(prefix: String): File {
    val views = FileSystems.getDefault().supportedFileAttributeViews()
    if ("posix" in views) {
        val ownerOnly = PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------"))
        return Files.createTempFile(prefix, ".pem", ownerOnly).toFile()
    }
    val path = Files.createTempFile(prefix, ".pem")
    val acl = Files.getFileAttributeView(path, AclFileAttributeView::class.java)
    acl.acl =
        listOf(
            AclEntry
                .newBuilder()
                .setType(AclEntryType.ALLOW)
                .setPrincipal(acl.owner)
                .setPermissions(AclEntryPermission.entries.toSet())
                .build(),
        )
    return path.toFile()
}
