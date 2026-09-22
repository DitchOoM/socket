package com.ditchoom.socket.testkit.walk

/**
 * The commit a walk binary was built from, as its build stamped it — so a walk log states which
 * probe and which server it ran, and a stamp that is missing reads as a value, not as a field nobody
 * noticed was absent.
 */
public sealed interface BuildRevision {
    /** The `build=` field every START line carries. */
    public val line: String

    /** Built from [sha], with a working tree that was [tree]. */
    public data class Commit(
        val sha: String,
        val tree: WorkTree,
    ) : BuildRevision {
        override val line: String
            get() =
                when (tree) {
                    WorkTree.Clean -> "build=$sha"
                    WorkTree.Dirty -> "build=$sha+dirty"
                }
    }

    public data class Unknown(
        val reason: UnknownRevision,
    ) : BuildRevision {
        override val line: String get() = "build=unknown(${reason.name})"
    }

    /** Whether the tree a build was made from matched its commit. */
    public enum class WorkTree { Clean, Dirty }

    public enum class UnknownRevision {
        /** The build ran where git could not name a commit. */
        NotStamped,

        /** This run is not the packaged binary the stamp ships in — a test run straight from the build. */
        NotPackaged,

        /** The stamp is not a commit id. */
        Unreadable,
    }

    public companion object {
        private const val DIRTY = "+dirty"
        private val SHA = Regex("[0-9a-f]{40}")

        /** A build's stamp: `<40 hex digits>`, then `+dirty` when the tree had changes; empty when git had no answer. */
        public fun parse(stamp: String): BuildRevision {
            val text = stamp.trim()
            if (text.isEmpty()) return Unknown(UnknownRevision.NotStamped)
            val tree = if (text.endsWith(DIRTY)) WorkTree.Dirty else WorkTree.Clean
            val sha = text.removeSuffix(DIRTY)
            return if (SHA.matches(sha)) Commit(sha, tree) else Unknown(UnknownRevision.Unreadable)
        }
    }
}
