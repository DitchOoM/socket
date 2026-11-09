package com.ditchoom.socket

import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertIs

/**
 * [wrapJvmException] classifies a bare IOException by its message, and each OS words the same
 * errno differently. These pin the wordings CI has actually seen, so a host this suite does not
 * run on (Windows, from a Mac) still has its mapping checked on every JVM lane.
 */
class JvmExceptionMessageMappingTests {
    @Test
    fun aPosixResetIsConnectionReset() {
        assertIs<SocketClosedException.ConnectionReset>(wrapJvmException(IOException("Connection reset by peer")))
    }

    @Test
    fun aWindowsResetIsConnectionResetNotAGenericClose() {
        // WSAECONNRESET (10054), from Windows JVM Tests on PR #797. Its "closed" used to map to General.
        assertIs<SocketClosedException.ConnectionReset>(
            wrapJvmException(IOException("An existing connection was forcibly closed by the remote host")),
        )
    }

    @Test
    fun aBrokenPipeIsBrokenPipe() {
        assertIs<SocketClosedException.BrokenPipe>(wrapJvmException(IOException("Broken pipe")))
    }

    @Test
    fun aLocalCloseIsGeneral() {
        assertIs<SocketClosedException.General>(wrapJvmException(IOException("Socket closed")))
    }
}
