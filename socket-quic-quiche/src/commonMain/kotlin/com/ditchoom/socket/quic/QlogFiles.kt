package com.ditchoom.socket.quic

import com.ditchoom.socket.quic.trace.QlogFile

// The file operations a budgeted qlog needs beside quiche's own writes: measure a segment, delete what
// the budget dropped, append a line to the directory's notes, and list what an earlier process left.

/** Bytes on disk at [path]; a file that is not there occupies none. */
internal expect fun qlogFileBytes(path: String): Long

/** Removes [path]; a file already gone counts as deleted, because the disk no longer holds it. */
internal expect fun deleteQlogFile(path: String): QlogDeletion

/** Appends [line] and a newline to [path], creating it. Best effort: a note never takes a connection down. */
internal expect fun appendQlogNote(
    path: String,
    line: String,
)

/** Every regular file directly in [dir] with its size, least recently modified first; none when [dir] cannot be read. */
internal expect fun listQlogFiles(dir: String): List<QlogFile>

internal enum class QlogDeletion { Deleted, Failed }
