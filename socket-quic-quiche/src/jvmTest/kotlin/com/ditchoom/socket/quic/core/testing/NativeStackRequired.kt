package com.ditchoom.socket.quic.core.testing

import com.ditchoom.socket.quic.core.ready.NetStack
import com.ditchoom.socket.quic.core.ready.netStackOfThisRuntime
import org.junit.Assume.assumeTrue

/** Skips the calling test where this runtime has no `qnet` binding to call: Windows runs the JDK stack. */
internal fun assumeNativeStack() {
    assumeTrue("qnet has no Windows build, so Windows runs the JDK stack", netStackOfThisRuntime is NetStack.Native)
}
