package com.ditchoom.socket.testkit.walk

import kotlin.test.Test
import kotlin.test.assertEquals

class BuildRevisionTests {
    private val sha = "859a8c75b1e0f2d3c4a5968778695a4b3c2d1e0f"

    @Test
    fun aCleanStampNamesItsCommit() {
        assertEquals("build=$sha", BuildRevision.parse(sha).line)
    }

    @Test
    fun aDirtyTreeSaysSo() {
        assertEquals(BuildRevision.Commit(sha, BuildRevision.WorkTree.Dirty), BuildRevision.parse("$sha+dirty\n"))
        assertEquals("build=$sha+dirty", BuildRevision.parse("$sha+dirty").line)
    }

    @Test
    fun aMissingStampIsAValueNotAnAbsentField() {
        assertEquals("build=unknown(NotStamped)", BuildRevision.parse("").line)
        assertEquals("build=unknown(Unreadable)", BuildRevision.parse("not-a-sha").line)
        assertEquals("build=unknown(NotPackaged)", BuildRevision.Unknown(BuildRevision.UnknownRevision.NotPackaged).line)
    }
}

class DiskFreeTests {
    @Test
    fun aReadingIsInMebibytesAndZeroIsUnknown() {
        assertEquals("diskFreeMb=2048", DiskFree.ofReading(2048L * 1024 * 1024).line)
        assertEquals("diskFreeMb=unknown", DiskFree.ofReading(0).line)
    }
}
